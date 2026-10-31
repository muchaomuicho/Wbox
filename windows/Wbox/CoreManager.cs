using System.Diagnostics;
using System.Net.Http.Headers;
namespace Wbox;

public sealed record ConnectionItem(string Network, string Source, string Destination, string Rule, string Outbound, long Upload, long Download);
public sealed record GroupItem(string Name, string Selected, List<string> Nodes, bool Selectable);
public sealed record CoreStats(int Inbound, int Outbound, long Uploaded, long Downloaded, long UploadRate, long DownloadRate, long Memory, List<ConnectionItem> Connections) { public long AppMemory { get; init; } public long CoreMemory { get; init; } }

public sealed class CoreManager : IDisposable
{
    Process? process;
    ProcessJob? job;
    HttpClient? api;
    bool stopping;
    long initialUpload = -1, initialDownload = -1, lastUpload, lastDownload;
    DateTime lastSample;
    string? runtimePath;
    string activeCore = "";
    long cachedAppMemory, cachedCoreMemory;
    DateTime memorySample;
    public bool Running => process is { HasExited: false };
    public event Action<string>? Log;
    public event Action? Exited;
    public async Task StartAsync(Preferences p)
    {
        if (Running) return;
        stopping = false;
        activeCore = p.Core;
        var executable = Path.Combine(AppContext.BaseDirectory, "cores", p.Core + ".exe");
        if (!File.Exists(executable)) throw new FileNotFoundException("安装包缺少代理内核：" + executable);
        var secret = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32));
        var config = ConfigBuilder.Build(p, secret);
        // Resolve relative providers, certificates, rule sets and included files against the imported config directory.
        var working = p.Mode == "config" ? Path.GetDirectoryName(Path.GetFullPath(p.ConfigPath))! : Store.DirectoryPath;
        Directory.CreateDirectory(working);
        runtimePath = Path.Combine(working, $".wbox-runtime-{Guid.NewGuid():N}.{(p.Core == "sing-box" ? "json" : "yaml")}");
        File.WriteAllText(runtimePath, config);
        try
        {
            var validation = new ProcessStartInfo(executable) { WorkingDirectory = working, CreateNoWindow = true, UseShellExecute = false, RedirectStandardError = true, RedirectStandardOutput = true };
            if (p.Core == "sing-box") { validation.ArgumentList.Add("check"); validation.ArgumentList.Add("-c"); validation.ArgumentList.Add(runtimePath); }
            else { validation.ArgumentList.Add("-t"); validation.ArgumentList.Add("-d"); validation.ArgumentList.Add(working); validation.ArgumentList.Add("-f"); validation.ArgumentList.Add(runtimePath); }
            using (var check = Process.Start(validation)!)
            {
                var stdout = check.StandardOutput.ReadToEndAsync(); var stderr = check.StandardError.ReadToEndAsync();
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(25));
                try { await check.WaitForExitAsync(timeout.Token); }
                catch { check.Kill(true); throw new InvalidOperationException("配置检查超时，请检查配置中的远程资源。"); }
                var output = await stdout + await stderr;
                if (check.ExitCode != 0) throw new InvalidOperationException("配置检查未通过：\n" + output);
            }
            var start = new ProcessStartInfo(executable) { WorkingDirectory = working, CreateNoWindow = true, UseShellExecute = false, RedirectStandardError = true, RedirectStandardOutput = true };
            if (p.Core == "sing-box") { start.ArgumentList.Add("run"); start.ArgumentList.Add("-c"); start.ArgumentList.Add(runtimePath); }
            else { start.ArgumentList.Add("-d"); start.ArgumentList.Add(working); start.ArgumentList.Add("-f"); start.ArgumentList.Add(runtimePath); }
            start.Environment["GOMEMLIMIT"] = "96MiB";
            start.Environment["GOGC"] = "75";
            process = new Process { StartInfo = start, EnableRaisingEvents = true };
            process.OutputDataReceived += (_, e) => { if (e.Data != null) Log?.Invoke(e.Data); };
            process.ErrorDataReceived += (_, e) => { if (e.Data != null) Log?.Invoke(e.Data); };
            process.Exited += (_, _) => { if (!stopping) { try { SystemProxy.Restore(); } catch (Exception ex) { Log?.Invoke(ex.Message); } Exited?.Invoke(); } };
            if (!process.Start()) throw new InvalidOperationException("内核启动失败。");
            job = new ProcessJob(); job.Add(process);
            process.BeginOutputReadLine(); process.BeginErrorReadLine();
            api = new HttpClient(new SocketsHttpHandler { UseProxy = false }) { BaseAddress = new Uri($"http://127.0.0.1:{ConfigBuilder.ControllerPort}"), Timeout = TimeSpan.FromSeconds(4) };
            api.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", secret);
            var ready = false;
            for (int i = 0; i < 80 && Running; i++)
            {
                try { using var response = await api.GetAsync("/version"); if (response.IsSuccessStatusCode) { ready = true; break; } } catch (HttpRequestException) { } catch (TaskCanceledException) { }
                await Task.Delay(150);
            }
            if (!ready) throw new InvalidOperationException("内核控制接口未就绪，请查看日志；10808 或 19090 端口可能被占用。");
            initialUpload = initialDownload = lastUpload = lastDownload = 0; lastSample = DateTime.UtcNow;
            if (p.SystemProxy) SystemProxy.Install();
            Log?.Invoke($"Wbox · {p.Core} 已启动 · 本地代理 127.0.0.1:{ConfigBuilder.MixedPort}");
        }
        catch { Stop(); throw; }
    }
    public async Task<CoreStats> StatsAsync(bool details = true)
    {
        using var stream = await api!.GetStreamAsync("/connections");
        using var document = await JsonDocument.ParseAsync(stream); var root = document.RootElement;
        long upload = root.TryGetProperty("uploadTotal", out var u) ? u.GetInt64() : 0, download = root.TryGetProperty("downloadTotal", out var d) ? d.GetInt64() : 0;
        if (initialUpload < 0) { initialUpload = upload; initialDownload = download; lastUpload = upload; lastDownload = download; }
        var seconds = Math.Max((DateTime.UtcNow - lastSample).TotalSeconds, 0.1);
        var upRate = (long)(Math.Max(0, upload - lastUpload) / seconds); var downRate = (long)(Math.Max(0, download - lastDownload) / seconds);
        lastUpload = upload; lastDownload = download; lastSample = DateTime.UtcNow;
        var connections = new List<ConnectionItem>(); int count = 0;
        if (root.TryGetProperty("connections", out var rows) && rows.ValueKind == JsonValueKind.Array) {
            count = rows.GetArrayLength();
            if (details) foreach (var c in rows.EnumerateArray()) {
                if (!c.TryGetProperty("metadata", out var m)) continue;
                string Value(string key) => m.TryGetProperty(key, out var value) ? value.ToString() : "";
                var target = Value("host"); if (target.Length == 0) target = Value("destinationIP");
                connections.Add(new(Value("network"), $"{Value("sourceIP")}:{Value("sourcePort")}", $"{target}:{Value("destinationPort")}", c.TryGetProperty("rule", out var rule) ? rule.ToString() : "", c.TryGetProperty("chains", out var chains) ? string.Join(" → ", chains.EnumerateArray().Select(n => n.ToString())) : "", c.TryGetProperty("upload", out var cu) ? cu.GetInt64() : 0, c.TryGetProperty("download", out var cd) ? cd.GetInt64() : 0));
            }
        }
        if ((DateTime.UtcNow - memorySample).TotalSeconds >= 5) {
            try { process!.Refresh(); cachedCoreMemory = process.WorkingSet64; } catch { }
            using var self = Process.GetCurrentProcess(); cachedAppMemory = self.WorkingSet64; memorySample = DateTime.UtcNow;
        }
        return new(count, count, Math.Max(0, upload - initialUpload), Math.Max(0, download - initialDownload), upRate, downRate, cachedAppMemory + cachedCoreMemory, connections) { AppMemory = cachedAppMemory, CoreMemory = cachedCoreMemory };
    }
    public async Task<List<GroupItem>> GroupsAsync()
    {
        var root = JsonNode.Parse(await api!.GetStringAsync("/proxies"))!["proxies"]!.AsObject();
        return root.Where(kv => kv.Value?["all"] is JsonArray && kv.Value?["type"]?.ToString() is "Selector" or "URLTest" or "Fallback" or "LoadBalance")
            .Where(kv => !(activeCore == "sing-box" && kv.Key == "GLOBAL"))
            .Select(kv => new GroupItem(kv.Key, kv.Value!["now"]?.ToString() ?? "", kv.Value["all"]!.AsArray().Select(n => n!.ToString()).ToList(), kv.Value["type"]!.ToString() == "Selector")).ToList();
    }
    public async Task SelectAsync(string group, string node)
    {
        using var content = new StringContent(new JsonObject { ["name"] = node }.ToJsonString(), Encoding.UTF8, "application/json");
        using var response = await api!.PutAsync("/proxies/" + Uri.EscapeDataString(group), content); response.EnsureSuccessStatusCode();
    }
    public async Task<int> DelayAsync(string node, string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("https" or "http")) throw new ArgumentException("延迟测试地址需要是 HTTP 或 HTTPS URL。");
        if (activeCore == "sing-box" && uri.Scheme != "https") throw new ArgumentException("sing-box 的测速 API 仅接受 HTTPS 链接，请填写 HTTPS 测试地址。");
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(12));
        using var request = new HttpRequestMessage(HttpMethod.Get, $"/proxies/{Uri.EscapeDataString(node)}/delay?timeout=8000&url={Uri.EscapeDataString(url)}");
        // Delay checks can exceed the normal controller request timeout.
        using var client = new HttpClient(new SocketsHttpHandler { UseProxy = false }) { BaseAddress = api!.BaseAddress, Timeout = TimeSpan.FromSeconds(12) };
        client.DefaultRequestHeaders.Authorization = api.DefaultRequestHeaders.Authorization;
        using var response = await client.SendAsync(request, timeout.Token); response.EnsureSuccessStatusCode();
        return JsonNode.Parse(await response.Content.ReadAsStringAsync())!["delay"]!.GetValue<int>();
    }
    public void Stop()
    {
        stopping = true;
        try { SystemProxy.Restore(); } catch (Exception ex) { Log?.Invoke("系统代理恢复失败：" + ex.Message); }
        try { if (Running) { process!.Kill(true); process.WaitForExit(3000); } } catch { }
        job?.Dispose(); job = null; process?.Dispose(); process = null; api?.Dispose(); api = null;
        if (runtimePath != null) { try { File.Delete(runtimePath); } catch { } runtimePath = null; }
    }
    public void Dispose() => Stop();
}
