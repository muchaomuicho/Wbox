using System.Diagnostics;
using System.Net.Http.Headers;
namespace Wbox;

public sealed record ConnectionItem(string Network, string Source, string Destination, string Rule, string Outbound, long Upload, long Download) {
    public string Id { get; init; } = "";
    public string Inbound { get; init; } = "";
    public long UploadRate { get; init; }
    public long DownloadRate { get; init; }
    public string UpText => "↑ " + DisplayFormat.Bytes(UploadRate) + "/s       ↑ " + DisplayFormat.Bytes(Upload);
    public string DownText => "↓ " + DisplayFormat.Bytes(DownloadRate) + "/s       ↓ " + DisplayFormat.Bytes(Download);
}
public sealed record ProxyNode(string Name, string Type, int? Delay);
public sealed record GroupItem(string Name, string Selected, List<string> Nodes, bool Selectable) {
    public string Type { get; init; } = "Selector";
    public List<ProxyNode> Details { get; init; } = [];
}
public sealed record CoreStats(int Inbound, int Outbound, long Uploaded, long Downloaded, long UploadRate, long DownloadRate, long Memory, List<ConnectionItem> Connections) { public long AppMemory { get; init; } public long CoreMemory { get; init; } }

public sealed class CoreManager : IDisposable
{
    readonly Func<bool> restoreProxy;
    public CoreManager(Func<bool>? restoreProxy = null) { this.restoreProxy = restoreProxy ?? SystemProxy.Restore; }
    Process? process;
    Process? coreProcess;
    CancellationTokenSource sessionCancellation = new();
    ProcessJob? job;
    HttpClient? api;
    bool stopping;
    bool starting;
    readonly System.Collections.Concurrent.ConcurrentQueue<string> startupLog = new();
    Dictionary<string, (long Up, long Down)> previousConnections = [];
    DateTime connectionSample;
    public DateTime StartedAt { get; private set; }
    public int ProxyPort { get; private set; } = ConfigBuilder.MixedPort;
    public int ApiPort { get; private set; } = ConfigBuilder.ControllerPort;
    long initialUpload = -1, initialDownload = -1, lastUpload, lastDownload;
    DateTime lastSample;
    string? runtimePath;
    string? transientDirectory;
    string activeCore = "";
    string? globalTarget;
    long cachedAppMemory, cachedCoreMemory;
    DateTime memorySample;
    public bool Running => process is { HasExited: false };
    public bool Starting => starting;
    public bool Ready => Running && !starting;
    public void CancelStartup() { if(starting) sessionCancellation.Cancel(); }
    public event Action<string>? Log;
    public event Action? Exited;
    public async Task StartAsync(Preferences p, bool transient = false)
    {
        if (Running || starting) return;
        var startupWatch=Stopwatch.StartNew();
        stopping = false; starting = true; startupLog.Clear();
        sessionCancellation.Dispose(); sessionCancellation = new();
        try
        {
        activeCore = p.Core;
        var executable = Path.Combine(AppContext.BaseDirectory, "cores", p.Core + ".exe");
        if (!File.Exists(executable)) throw new FileNotFoundException("安装包缺少代理内核：" + executable);
        var secret = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32));
        ProxyPort = StartupPolicy.AvailablePort(ConfigBuilder.MixedPort);
        ApiPort = StartupPolicy.AvailablePort(ConfigBuilder.ControllerPort, ProxyPort);
        var config = ConfigBuilder.Build(p, secret, ProxyPort, ApiPort, transient);
        globalTarget = p.Core == "mihomo" ? GlobalTarget(ConfigBuilder.ParseYaml(config)) : null;
        StartupPolicy.Check(config, p.Core);
        // Resolve relative providers, certificates, rule sets and included files against the imported config directory.
        var working = p.Mode == "config" ? Path.GetDirectoryName(Path.GetFullPath(p.ConfigPath))! : Path.Combine(Store.DirectoryPath, p.Core);
        if (transient) working = transientDirectory = Path.Combine(Store.DirectoryPath, "latency", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(working);
        if(p.Core=="mihomo" && p.Mode=="config")Geodata.Prepare(working,ConfigBuilder.ParseYaml(config),message=>Log?.Invoke(message));
        runtimePath = Path.Combine(working, $".wbox-runtime-{Guid.NewGuid():N}.{(p.Core == "sing-box" ? "json" : "yaml")}");
        File.WriteAllText(runtimePath, config);
            // run performs the same validation and reports startup errors. Avoid
            // spawning a second core and loading remote resources twice.
            var host = Path.Combine(AppContext.BaseDirectory, "cores", "wbox-core-host.exe");
            if (!File.Exists(host)) throw new FileNotFoundException("安装包缺少内核进程管理器。", host);
            var start = new ProcessStartInfo(host) { WorkingDirectory = working, CreateNoWindow = true, UseShellExecute = false, RedirectStandardError = true, RedirectStandardOutput = true, RedirectStandardInput = true };
            start.ArgumentList.Add(executable);
            if (p.Core == "sing-box") { start.ArgumentList.Add("run"); start.ArgumentList.Add("-c"); start.ArgumentList.Add(runtimePath); }
            else { start.ArgumentList.Add("-d"); start.ArgumentList.Add(working); start.ArgumentList.Add("-f"); start.ArgumentList.Add(runtimePath); }
            start.Environment["GOMEMLIMIT"] = "96MiB";
            start.Environment["GOGC"] = "75";
            process = new Process { StartInfo = start, EnableRaisingEvents = true };
            process.OutputDataReceived += (_, e) => { if (e.Data != null) CaptureLog(e.Data); };
            process.ErrorDataReceived += (_, e) => { if (e.Data != null) CaptureLog(e.Data); };
            process.Exited += (_, _) => { if (!stopping && !starting) { try { restoreProxy(); } catch (Exception ex) { Log?.Invoke(ex.Message); } Exited?.Invoke(); } };
            if (!process.Start()) throw new InvalidOperationException("内核启动失败。");
            job = new ProcessJob(); job.Add(process);
            process.BeginOutputReadLine(); process.BeginErrorReadLine();
            api = new HttpClient(new SocketsHttpHandler { UseProxy = false }) { BaseAddress = new Uri($"http://127.0.0.1:{ApiPort}"), Timeout = TimeSpan.FromSeconds(2) };
            api.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", secret);
            var ready = false;
            string lastProbe = "控制接口尚未监听";
            var waitWatch=Stopwatch.StartNew();bool explained=false;
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(sessionCancellation.Token);
            deadline.CancelAfter(TimeSpan.FromSeconds(120));
            try {
                while (Running && !deadline.IsCancellationRequested) {
                    if(!explained && waitWatch.Elapsed.TotalSeconds>=5) {explained=true;Log?.Invoke("INFO Wbox: 内核仍在初始化，等待远程规则集及服务就绪；可随时停止连接。");}
                    try {
                        using var response = await api.GetAsync("/version", deadline.Token);
                        if(response.StatusCode is System.Net.HttpStatusCode.Unauthorized or System.Net.HttpStatusCode.Forbidden)throw new InvalidOperationException($"控制接口认证失败（HTTP {(int)response.StatusCode}），请检查端口占用。");
                        if (response.IsSuccessStatusCode) {
                            using var listener=new System.Net.Sockets.TcpClient();
                            await listener.ConnectAsync("127.0.0.1",ProxyPort,deadline.Token);
                            ready = true; break;
                        }
                        lastProbe=$"控制接口返回 HTTP {(int)response.StatusCode}";
                    } catch (HttpRequestException ex) { lastProbe=ex.Message; } catch (System.Net.Sockets.SocketException ex) { lastProbe=ex.Message; } catch (TaskCanceledException) { }
                    await Task.Delay(200,deadline.Token);
                }
            } catch(OperationCanceledException) when(!sessionCancellation.IsCancellationRequested) { }
            sessionCancellation.Token.ThrowIfCancellationRequested();
            if (!ready || !Running) {
                if (process.HasExited) process.WaitForExit(1000);
                var reason = process.HasExited ? $"内核退出（代码 {process.ExitCode}）" : $"内核初始化超过 120 秒，控制接口仍未就绪。{lastProbe}";
                throw new InvalidOperationException(reason + "。\n" + string.Join("\n", startupLog.TakeLast(8)));
            }
            if (!transient) {
                if (p.Core == "sing-box") await RequestAsync("PATCH", "/configs", new JsonObject { ["mode"] = "rule" });
                else if ((await RequestAsync("GET", "/configs"))?["mode"]?.ToString() == "global") await SetModeAsync("global");
            }
            initialUpload = initialDownload = lastUpload = lastDownload = 0; lastSample = DateTime.UtcNow;
            sessionCancellation.Token.ThrowIfCancellationRequested();
            if (p.SystemProxy) SystemProxy.Install(ProxyPort);
            StartedAt = DateTime.UtcNow; previousConnections.Clear(); connectionSample = DateTime.UtcNow;
            starting = false;
            Log?.Invoke($"Wbox · {p.Core} 已启动（{startupWatch.ElapsedMilliseconds} ms） · 本地代理 127.0.0.1:{ProxyPort}");
        }
        catch { Stop(); throw; }
        finally { starting=false; }
    }
    void CaptureLog(string line) {
        if (line.StartsWith("__WBOX_CORE_PID__") && int.TryParse(line[17..], out var pid)) {
            try { coreProcess = Process.GetProcessById(pid); } catch (ArgumentException) { }
            return;
        }
        line = System.Text.RegularExpressions.Regex.Replace(line, "\u001b\\[[0-9;]*m", "");
        if (line.Length > 4096) line = line[..4096];
        if (starting) { startupLog.Enqueue(line); while (startupLog.Count > 40) startupLog.TryDequeue(out _); }
        Log?.Invoke(line);
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
        var connectionSeconds = Math.Max(.1, (DateTime.UtcNow - connectionSample).TotalSeconds);
        var nextConnections = new Dictionary<string, (long Up, long Down)>();
        if (root.TryGetProperty("connections", out var rows) && rows.ValueKind == JsonValueKind.Array) {
            count = rows.GetArrayLength();
            if (details) foreach (var c in rows.EnumerateArray()) {
                if (!c.TryGetProperty("metadata", out var m)) continue;
                string Value(string key) => m.TryGetProperty(key, out var value) ? value.ToString() : "";
                var target = Value("host"); if (target.Length == 0) target = Value("destinationIP");
                var id = c.TryGetProperty("id", out var ci) ? ci.ToString() : "";
                long cup = c.TryGetProperty("upload", out var cu) ? cu.GetInt64() : 0, cdown = c.TryGetProperty("download", out var cd) ? cd.GetInt64() : 0;
                var hadPrevious = previousConnections.TryGetValue(id, out var before); nextConnections[id] = (cup, cdown);
                var inbound = Value("inboundName"); if (inbound.Length == 0) inbound = Value("type");
                connections.Add(new(Value("network").ToUpperInvariant(), $"{Value("sourceIP")}:{Value("sourcePort")}", $"{target}:{Value("destinationPort")}", c.TryGetProperty("rule", out var rule) ? rule.ToString() : "", c.TryGetProperty("chains", out var chains) ? string.Join(" → ", chains.EnumerateArray().Select(n => n.ToString())) : "", cup, cdown) { Id = id, Inbound = inbound, UploadRate = hadPrevious ? (long)(Math.Max(0,cup-before.Up)/connectionSeconds) : 0, DownloadRate = hadPrevious ? (long)(Math.Max(0,cdown-before.Down)/connectionSeconds) : 0 });
            }
        }
        previousConnections = nextConnections; connectionSample = DateTime.UtcNow;
        if ((DateTime.UtcNow - memorySample).TotalSeconds >= 5) {
            try { coreProcess?.Refresh(); cachedCoreMemory = coreProcess?.WorkingSet64 ?? 0; } catch { }
            using var self = Process.GetCurrentProcess(); cachedAppMemory = self.WorkingSet64; memorySample = DateTime.UtcNow;
        }
        return new(count, count, Math.Max(0, upload - initialUpload), Math.Max(0, download - initialDownload), upRate, downRate, cachedAppMemory + cachedCoreMemory, connections) { AppMemory = cachedAppMemory, CoreMemory = cachedCoreMemory };
    }
    public async Task<List<GroupItem>> GroupsAsync()
    {
        var root = JsonNode.Parse(await api!.GetStringAsync("/proxies"))!["proxies"]!.AsObject();
        return root.Where(kv => kv.Value?["all"] is JsonArray && kv.Value?["type"]?.ToString() is "Selector" or "URLTest" or "Fallback" or "LoadBalance")
            .Where(kv => kv.Key != "GLOBAL")
            .Select(kv => new GroupItem(kv.Key, kv.Value!["now"]?.ToString() ?? "", kv.Value["all"]!.AsArray().Select(n => n!.ToString()).ToList(), kv.Value["type"]!.ToString() == "Selector") { Type = kv.Value["type"]!.ToString(), Details = kv.Value["all"]!.AsArray().Select(n => { var name = n!.ToString(); var detail = root[name]; var delay = (detail?["history"] as JsonArray)?.LastOrDefault()?["delay"]?.GetValue<int>(); return new ProxyNode(name, detail?["type"]?.ToString() ?? "—", delay); }).ToList() }).ToList();
    }
    public async Task SelectAsync(string group, string node)
    {
        using var content = new StringContent(new JsonObject { ["name"] = node }.ToJsonString(), Encoding.UTF8, "application/json");
        using var response = await api!.PutAsync("/proxies/" + Uri.EscapeDataString(group), content); response.EnsureSuccessStatusCode();
    }
    public async Task SetModeAsync(string mode) {
        if (activeCore != "mihomo" || mode is not ("rule" or "global" or "direct")) throw new ArgumentException("此内核不支持指定的代理模式。");
        if (mode == "global") {
            var root = (await RequestAsync("GET", "/proxies"))?["proxies"]?.AsObject();
            if (root?["GLOBAL"] is JsonObject global && global["now"]?.ToString() is null or "DIRECT" or "REJECT") {
                var target = globalTarget != null && root.ContainsKey(globalTarget) ? globalTarget : root.FirstOrDefault(kv => kv.Key != "GLOBAL" && kv.Value?["all"] is JsonArray).Key;
                target ??= (global["all"] as JsonArray)?.Select(n => n?.ToString()).FirstOrDefault(n => n is not (null or "GLOBAL" or "DIRECT" or "REJECT" or "COMPATIBLE"));
                if (target != null) await SelectAsync("GLOBAL", target);
            }
        }
        await RequestAsync("PATCH", "/configs", new JsonObject { ["mode"] = mode });
    }
    public static string? GlobalTarget(JsonObject config) {
        var final = (config["rules"] as JsonArray)?.Select(n => n?.ToString().Split(',')).LastOrDefault(r => r is { Length: >= 2 } && r[0].Trim() == "MATCH")?[1].Trim();
        return final is not (null or "DIRECT" or "REJECT") ? final : (config["proxy-groups"] as JsonArray)?.FirstOrDefault()?["name"]?.ToString();
    }
    public async Task<JsonNode?> RequestAsync(string method, string path, JsonNode? body = null) {
        using var request = new HttpRequestMessage(new HttpMethod(method), path);
        if (body != null) request.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
        using var response = await api!.SendAsync(request); response.EnsureSuccessStatusCode();
        var text = await response.Content.ReadAsStringAsync(); return text.Length == 0 ? null : JsonNode.Parse(text);
    }
    public async Task<int> DelayAsync(string node, string url, CancellationToken cancellation = default)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("https" or "http")) throw new ArgumentException("延迟测试地址需要是 HTTP 或 HTTPS URL。");
        if (activeCore == "sing-box" && uri.Scheme != "https") throw new ArgumentException("sing-box 的测速 API 仅接受 HTTPS 链接，请填写 HTTPS 测试地址。");
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation, sessionCancellation.Token);
        timeout.CancelAfter(TimeSpan.FromSeconds(6));
        using var request = new HttpRequestMessage(HttpMethod.Get, $"/proxies/{Uri.EscapeDataString(node)}/delay?timeout=4000&url={Uri.EscapeDataString(url)}");
        // Delay checks can exceed the normal controller request timeout.
        using var client = new HttpClient(new SocketsHttpHandler { UseProxy = false }) { BaseAddress = api!.BaseAddress, Timeout = TimeSpan.FromSeconds(6) };
        client.DefaultRequestHeaders.Authorization = api.DefaultRequestHeaders.Authorization;
        using var response = await client.SendAsync(request, timeout.Token); response.EnsureSuccessStatusCode();
        return JsonNode.Parse(await response.Content.ReadAsStringAsync())!["delay"]!.GetValue<int>();
    }
    public void Stop()
    {
        // Follow boxdd: reset platform options first. A failed reset must not
        // close the listener which Windows may still be using.
        restoreProxy();
        sessionCancellation.Cancel();
        stopping = true;
        if (Running) {
            try { process!.StandardInput.WriteLine("stop"); process.StandardInput.Flush(); } catch (IOException) { }
            if (!process!.WaitForExit(6500)) { Log?.Invoke("WARN Wbox: 内核未正常退出，已强制结束。"); process.Kill(true); process.WaitForExit(3000); }
        }
        coreProcess?.Dispose(); coreProcess = null;
        job?.Dispose(); job = null; process?.Dispose(); process = null; api?.Dispose(); api = null;
        if (runtimePath != null) { try { File.Delete(runtimePath); } catch { } runtimePath = null; }
        if (transientDirectory != null) { try { Directory.Delete(transientDirectory, true); } catch (IOException) { } transientDirectory = null; }
    }
    public void Dispose() => Stop();
}
