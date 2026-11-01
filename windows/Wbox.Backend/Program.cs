using Wbox;
using System.Collections.Concurrent;

Console.InputEncoding = new UTF8Encoding(false);
Console.OutputEncoding = new UTF8Encoding(false);

var json = new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
var outputLock = new object();
void Write(object value) { lock (outputLock) Console.WriteLine(JsonSerializer.Serialize(value, json)); }
var core = new CoreManager();
var prefs = Store.Load();
long coreGeneration = 0;
var logs = new ConcurrentQueue<object>();
long logId = 0;
void Log(string text) { logs.Enqueue(new { id = Interlocked.Increment(ref logId), message = text, level = text.Contains("error", StringComparison.OrdinalIgnoreCase) ? 2 : 4 }); while (logs.Count > 1000) logs.TryDequeue(out _); }
core.Log += Log;
core.Exited += () => Write(new { changed = true });
try { SystemProxy.Restore(); } catch (Exception ex) { Log(ex.Message); }
if (prefs.Configs.Count == 0 && File.Exists(prefs.ConfigPath)) {
    prefs.Configs.Add(new() { Path = prefs.ConfigPath, Core = prefs.Core, Name = Path.GetFileNameWithoutExtension(prefs.ConfigPath) }); Store.Save(prefs);
}
void Save() { Store.Save(prefs); Write(new { changed = true }); }
void Idle() { if (core.Running) throw new InvalidOperationException("请先停止连接，再切换工作区、内核或运行配置。"); }
ConfigProfile Profile(string id) => prefs.Configs.FirstOrDefault(p => p.Id == id) ?? throw new ArgumentException("配置不存在。");
object State() => new {
    prefs.Core, prefs.Mode, prefs.ConfigPath, prefs.DelayUrl, prefs.Silent, prefs.AutoStart, prefs.AutoConnect, prefs.SystemProxy, prefs.NativeInbounds, prefs.SelectedNode,
    configs = prefs.Configs.Select(c => new { c.Id, c.Name, c.Core, c.Path, c.Url, c.Updated }),
    nodes = prefs.Nodes.Select((n, i) => new { index = i, n.Name, n.Protocol, n.Server, n.Port, n.SourceSubscription, tag = ConfigBuilder.Tag(n, i), n.DelayText }),
    prefs.Subscriptions, running = core.Running, startedAt = core.Running ? new DateTimeOffset(core.StartedAt).ToUnixTimeMilliseconds() : 0
};
async Task<object?> Handle(string method, JsonElement a) {
    string S(string name, string fallback = "") => a.TryGetProperty(name, out var v) ? v.GetString() ?? fallback : fallback;
    int I(string name) => a.GetProperty(name).GetInt32();
    switch (method) {
        case "state": return State();
        case "service": return new { running = core.Running, startedAt = core.Running ? new DateTimeOffset(core.StartedAt).ToUnixTimeMilliseconds() : 0 };
        case "memory": using (var self = System.Diagnostics.Process.GetCurrentProcess()) return self.WorkingSet64;
        case "start": coreGeneration++; await core.StartAsync(prefs); Save(); return null;
        case "stop": core.Stop(); coreGeneration++; Save(); return null;
        case "logs": return logs.ToArray();
        case "clearLogs": logs.Clear(); return null;
        case "stats": return core.Running ? await core.StatsAsync(false) : null;
        case "groups": return core.Running ? await core.GroupsAsync() : [];
        case "connections": return core.Running ? await core.RequestAsync("GET", "/connections") : null;
        case "mode": return core.Running ? await core.RequestAsync("GET", "/configs") : null;
        case "setMode": if (S("mode") is not ("rule" or "global" or "direct")) throw new ArgumentException("模式无效。"); await core.RequestAsync("PATCH", "/configs", new JsonObject { ["mode"] = S("mode") }); return null;
        case "select":
            await core.SelectAsync(S("group"), S("node"));
            if (prefs.Mode == "nodes" && S("group") == "Wbox") { var idx = prefs.Nodes.FindIndex(n => ConfigBuilder.Tag(n, prefs.Nodes.IndexOf(n)) == S("node")); if (idx >= 0) { prefs.SelectedNode = idx; Save(); } }
            return null;
        case "close": await core.RequestAsync("DELETE", "/connections" + (S("id").Length > 0 ? "/" + Uri.EscapeDataString(S("id")) : "")); return null;
        case "delay": return await core.DelayAsync(S("tag"), prefs.DelayUrl);
        case "delayNodes": {
            bool temporary = !core.Running;
            if (!temporary && prefs.Mode != "nodes") throw new InvalidOperationException("请先停止配置工作区的连接。");
            if (temporary) {
                var test = JsonSerializer.Deserialize<Preferences>(JsonSerializer.Serialize(prefs))!; test.Mode = "nodes"; test.Core = prefs.NodeCore.Length > 0 ? prefs.NodeCore : prefs.Core; test.SystemProxy = false;
                await core.StartAsync(test);
            }
            try { await Parallel.ForEachAsync(Enumerable.Range(0, prefs.Nodes.Count), new ParallelOptions { MaxDegreeOfParallelism = 4 }, async (i, _) => { try { prefs.Nodes[i].DelayText = (await core.DelayAsync(ConfigBuilder.Tag(prefs.Nodes[i], i), prefs.DelayUrl)) + " ms"; } catch { prefs.Nodes[i].DelayText = "超时"; } }); }
            finally { if (temporary) core.Stop(); Save(); }
            return null;
        }
        case "proxyStatus": return new { available = core.Running, enabled = core.Running && SystemProxy.Enabled };
        case "proxy": if (a.GetProperty("enabled").GetBoolean()) { if (!core.Running) throw new InvalidOperationException("请先启动内核。"); SystemProxy.Install(core.ProxyPort); } else SystemProxy.Restore(); prefs.SystemProxy = a.GetProperty("enabled").GetBoolean(); Save(); return null;
        case "settings":
            if (a.TryGetProperty("mode", out var mode)) { Idle(); prefs.Mode = mode.GetString() == "config" ? "config" : "nodes"; if (prefs.Mode == "nodes") prefs.Core = prefs.NodeCore.Length > 0 ? prefs.NodeCore : "mihomo"; else { var c = prefs.Configs.FirstOrDefault(c => c.Path == prefs.ConfigPath); if (c != null) prefs.Core = c.Core; } }
            if (a.TryGetProperty("core", out var engine)) { Idle(); if (prefs.Mode == "config") throw new InvalidOperationException("配置工作区自动匹配配置的内核。"); prefs.Core = engine.GetString() == "sing-box" ? "sing-box" : "mihomo"; prefs.NodeCore = prefs.Core; }
            if (a.TryGetProperty("delayUrl", out var delayUrl)) { if (!Uri.TryCreate(delayUrl.GetString(), UriKind.Absolute, out var uri) || uri.Scheme is not ("https" or "http")) throw new ArgumentException("测速链接需要是 HTTP / HTTPS 地址。"); prefs.DelayUrl = uri.ToString(); }
            if (a.TryGetProperty("silent", out var silent)) prefs.Silent = silent.GetBoolean();
            if (a.TryGetProperty("autoStart", out var auto)) prefs.AutoStart = auto.GetBoolean();
            if (a.TryGetProperty("autoConnect", out var connect)) prefs.AutoConnect = connect.GetBoolean();
            if (a.TryGetProperty("systemProxy", out var proxy)) { Idle(); prefs.SystemProxy = proxy.GetBoolean(); }
            if (a.TryGetProperty("nativeInbounds", out var native)) { Idle(); prefs.NativeInbounds = native.GetBoolean(); }
            Save(); return null;
        case "selectNode": { var index = I("index"); if (index < 0 || index >= prefs.Nodes.Count) throw new ArgumentException("节点不存在。"); if (core.Running) { if (prefs.Mode != "nodes") throw new InvalidOperationException("请先切换到节点工作区。"); await core.SelectAsync("Wbox", ConfigBuilder.Tag(prefs.Nodes[index], index)); } prefs.SelectedNode = index; Save(); return null; }
        case "removeNode": Idle(); prefs.Nodes.RemoveAt(I("index")); prefs.SelectedNode = Math.Clamp(prefs.SelectedNode, 0, Math.Max(0, prefs.Nodes.Count - 1)); Save(); return null;
        case "removeSubscription": Idle(); prefs.Nodes.RemoveAll(n => n.SourceSubscription == S("url")); prefs.Subscriptions.Remove(S("url")); prefs.SelectedNode = 0; Save(); return null;
        case "importNodes": {
            Idle(); var text = S("text").Trim(); var url = S("url").Trim();
            if (url.Length > 0) text = await ProfileImport.FetchAsync(url, prefs.Core == "sing-box" ? "sing-box" : "clash.meta");
            var nodes = NodeParser.ParseSubscription(text); if (nodes.Count == 0) throw new FormatException("未识别到节点。完整 JSON / YAML 请导入配置工作区。");
            if (url.Length > 0) { var selected = prefs.Nodes.ElementAtOrDefault(prefs.SelectedNode); var name = selected?.Name; prefs.Nodes.RemoveAll(n => n.SourceSubscription == url); foreach (var node in nodes) node.SourceSubscription = url; if (!prefs.Subscriptions.Contains(url)) prefs.Subscriptions.Add(url); prefs.Nodes.AddRange(nodes); var index = prefs.Nodes.FindIndex(n => n.Name == name); prefs.SelectedNode = Math.Max(0, index); }
            else prefs.Nodes.AddRange(nodes);
            Save(); return nodes.Count;
        }
        case "profiles": return new { selectedId = prefs.Mode == "nodes" ? (prefs.Nodes.Count > 0 ? "nodes" : null) : prefs.Configs.FirstOrDefault(p => p.Path == prefs.ConfigPath)?.Id, profiles = prefs.Mode == "nodes" ? new[] { new { id = "nodes", name = $"节点工作区 · {prefs.Nodes.Count} 个节点", type = "local", autoUpdate = false, autoUpdateIntervalMinutes = 0, lastUpdated = 0L } } : prefs.Configs.Select(c => new { id = c.Id, name = c.Name + " · " + c.Core, type = c.Url.Length > 0 ? "remote" : "local", autoUpdate = false, autoUpdateIntervalMinutes = 0, lastUpdated = new DateTimeOffset(c.Updated).ToUnixTimeMilliseconds() }).ToArray() };
        case "createProfile": { Idle(); var url = S("remoteUrl"); var text = url.Length > 0 ? await ProfileImport.FetchAsync(url) : S("content"); var p = ProfileImport.SaveConfig(prefs, text, S("name", "配置"), url); prefs.Mode = "config"; prefs.ConfigPath = p.Path; prefs.Core = p.Core; Save(); return null; }
        case "trayProfiles": {
            var entries = prefs.Configs.Select(c => new { id = c.Id, name = c.Name + " · " + c.Core, type = "local" }).ToList();
            if (prefs.Nodes.Count > 0) entries.Add(new { id = "nodes", name = $"节点工作区 · {prefs.Nodes.Count} 个节点", type = "local" });
            return new { selectedId = prefs.Mode == "nodes" ? "nodes" : prefs.Configs.FirstOrDefault(c => c.Path == prefs.ConfigPath)?.Id, profiles = entries };
        }
        case "traySelectProfile": {
            string id = S("id");
            var profile = id == "nodes" && prefs.Nodes.Count > 0 ? null : Profile(id);
            if (id == "nodes" && prefs.Mode == "nodes" || profile != null && prefs.Mode == "config" && prefs.ConfigPath == profile.Path) return null;
            bool restart = core.Running;
            core.Stop(); coreGeneration++;
            if (profile == null) { prefs.Mode = "nodes"; prefs.Core = prefs.NodeCore.Length > 0 ? prefs.NodeCore : "mihomo"; }
            else { prefs.Mode = "config"; prefs.ConfigPath = profile.Path; prefs.Core = profile.Core; }
            Save();
            if (restart) { await core.StartAsync(prefs); Save(); }
            return null;
        }
        case "selectProfile": { Idle(); var p = Profile(S("id")); prefs.ConfigPath = p.Path; prefs.Core = p.Core; Save(); return null; }
        case "removeProfile": Idle(); prefs.Configs.Remove(Profile(S("id"))); if (!prefs.Configs.Any(p => p.Path == prefs.ConfigPath)) { prefs.ConfigPath = prefs.Configs.FirstOrDefault()?.Path ?? ""; prefs.Core = prefs.Configs.FirstOrDefault()?.Core ?? "mihomo"; } Save(); return null;
        case "updateProfile": { Idle(); var p = Profile(S("id")); if (p.Url.Length == 0) throw new ArgumentException("这是本地配置。"); ProfileImport.SaveConfig(prefs, await ProfileImport.FetchAsync(p.Url), p.Name, p.Url, p); if (prefs.ConfigPath == p.Path) prefs.Core = p.Core; Save(); return null; }
        case "readProfile": return File.ReadAllText(Profile(S("id")).Path);
        case "writeProfile": { Idle(); var p = Profile(S("id")); ProfileImport.SaveConfig(prefs, S("content"), p.Name, p.Url, p); Save(); return null; }
        default: throw new ArgumentException("未知操作：" + method);
    }
}
string? line;
var delaySlots = new SemaphoreSlim(4);
var delayTasks = new List<Task>();
async Task ReplyDelay(long id, string tag, long generation, string url) {
    await delaySlots.WaitAsync();
    try {
        if (generation != coreGeneration || !core.Running) throw new InvalidOperationException("连接已变化，测速已取消。");
        Write(new { id, result = await core.DelayAsync(tag, url) });
    } catch (Exception ex) { Write(new { id, error = ex.Message }); }
    finally { delaySlots.Release(); }
}
while ((line = await Console.In.ReadLineAsync()) != null) {
    long id = 0;
    try {
        using var request = JsonDocument.Parse(line);
        id = request.RootElement.GetProperty("id").GetInt64();
        string method = request.RootElement.GetProperty("method").GetString()!;
        var arguments = request.RootElement.GetProperty("args");
        // Network tests must not prevent the tray or main window from stopping
        // the service. Only these read-only requests run concurrently.
        if (method == "delay") {
            delayTasks.RemoveAll(task => task.IsCompleted);
            delayTasks.Add(ReplyDelay(id, arguments.GetProperty("tag").GetString()!, coreGeneration, prefs.DelayUrl));
            continue;
        }
        var result = await Handle(method, arguments); Write(new { id, result });
    }
    catch (Exception ex) { Write(new { id, error = ex.Message }); }
}
// Parent closed normally or crashed. Retry restoration before releasing the job.
for (int attempt = 0; ; attempt++) {
    try { core.Stop(); break; } catch (Exception ex) { Log(ex.Message); await Task.Delay(Math.Min(30_000, 1000 * (attempt + 1))); }
}
coreGeneration++;
await Task.WhenAll(delayTasks);
