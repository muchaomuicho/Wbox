using Wbox;
using System.Collections.Concurrent;

if(args.Length==2&&args[0]=="--remove-startup") {try{StartupRegistration.Configure(args[1],false,false);}catch{Environment.ExitCode=1;}return;}

Console.InputEncoding = new UTF8Encoding(false);
Console.OutputEncoding = new UTF8Encoding(false);

var json = new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
var outputLock = new object();
void Write(object value) { lock (outputLock) Console.WriteLine(JsonSerializer.Serialize(value, json)); }
var core = new CoreManager();
var prefs = Store.Load();
Task<List<SubscriptionResult>>? subscriptionTask = null;
bool subscriptionManual = false;
long coreGeneration = 0;
bool delayTesting = false;
CancellationTokenSource nodeTestCancellation = new();
Task nodeTests = Task.CompletedTask;
var groupDelays = new ConcurrentDictionary<string,int>();
List<GroupItem>? probeGroups = null;
string groupContext = "";
string groupTestError = "";
string GroupContext() => $"{coreGeneration}|{prefs.Mode}|{prefs.Core}|{prefs.ConfigPath}|{(File.Exists(prefs.ConfigPath)?File.GetLastWriteTimeUtc(prefs.ConfigPath).Ticks:0)}|{prefs.DelayUrl}";
void CheckGroupContext() {var key=GroupContext();if(key!=groupContext){groupContext=key;groupDelays.Clear();probeGroups=null;}}
async Task<List<GroupItem>> Groups() {
    CheckGroupContext();
    var groups=prefs.Mode=="nodes"?ProxyCatalog.Offline(prefs):core.Ready?await core.GroupsAsync():probeGroups??ProxyCatalog.Offline(prefs);
    return groups.Select(g=>g with {Details=g.Details.Select(n=>{
        string leaf;try{leaf=ProxyCatalog.Resolve(groups,n.Name);}catch{leaf=n.Name;}
        return groupDelays.TryGetValue(leaf,out var value)?n with {Delay=value}:n with {Delay=prefs.Mode=="nodes"?n.Delay:null};
    }).ToList()}).ToList();
}
async Task TestGroup(Preferences snapshot,string tag,string context,CancellationToken token,bool expand=true) {
    using var tester=new CoreManager(()=>true);
    try {
        var target=core;
        if(!core.Ready) {
            snapshot.SystemProxy=false;snapshot.NativeInbounds=false;
            token.ThrowIfCancellationRequested();var starting=tester.StartAsync(snapshot,true);
            using var cancel=token.Register(tester.CancelStartup);
            await starting;token.ThrowIfCancellationRequested();target=tester;
        }
        var groups=await target.GroupsAsync();
        if(context==GroupContext() && target==tester)probeGroups=groups;
        var group=groups.FirstOrDefault(g=>g.Name==tag);
        var names=(expand ? group?.Nodes ?? [tag] : [tag]).Select(n=>ProxyCatalog.Resolve(groups,n)).Distinct().ToArray();
        Write(new{changed=true});
        await Parallel.ForEachAsync(names,new ParallelOptions{MaxDegreeOfParallelism=4,CancellationToken=token},async(name,ct)=>{
            int value;
            try {value=await target.DelayAsync(name,snapshot.DelayUrl,ct);}
            catch(OperationCanceledException) when(ct.IsCancellationRequested){return;}
            catch{value=0;}
            if(context==GroupContext()){groupDelays[name]=value;Write(new{changed=true});}
        });
    }catch(OperationCanceledException) when(token.IsCancellationRequested){}
    catch(Exception error){Log("ERROR 代理组测速失败："+error.Message);groupTestError=error.Message;}
    finally {tester.Stop();delayTesting=false;Write(new{changed=true});}
}
var logs = new ConcurrentQueue<object>();
long logId = 0;
void Log(string text) { var entry = LogFormatting.Format(text); logs.Enqueue(new { id = Interlocked.Increment(ref logId), message = entry.Message, level = entry.Level }); while (logs.Count > 1000) logs.TryDequeue(out _); }
core.Log += Log;
core.Exited += () => Write(new { changed = true });
try { SystemProxy.Restore(); } catch (Exception ex) { Log(ex.Message); }
if (prefs.Configs.Count == 0 && File.Exists(prefs.ConfigPath)) {
    prefs.Configs.Add(new() { Path = prefs.ConfigPath, Core = prefs.Core, Name = Path.GetFileNameWithoutExtension(prefs.ConfigPath) }); Store.Save(prefs);
}
void Save() { SubscriptionRefresh.Normalize(prefs);Store.Save(prefs); Write(new { changed = true }); }
Preferences CopyPreferences() {
    var copy = JsonSerializer.Deserialize<Preferences>(JsonSerializer.Serialize(prefs))!;
    for (int i = 0; i < copy.Nodes.Count; i++) copy.Nodes[i].DelayText = prefs.Nodes[i].DelayText;
    return copy;
}
async Task CommitNodes(Preferences next) {
    if (delayTesting) { nodeTestCancellation.Cancel(); await nodeTests; }
    var previous = prefs;
    bool restart = core.Running && prefs.Mode == "nodes";
    if (restart && next.Nodes.Count > 0) restart = ConfigBuilder.Build(next, "validation") != ConfigBuilder.Build(previous, "validation");
    if (restart) { core.Stop(); coreGeneration++; }
    try {
        if (restart && next.Nodes.Count > 0) await core.StartAsync(next);
        prefs = next; Save();
    } catch (Exception changeError) {
        prefs = previous;
        if (restart) {
            core.Stop(); coreGeneration++;
            try { await core.StartAsync(previous); }
            catch (Exception restoreError) { Save(); throw new InvalidOperationException("修改未保存，恢复连接失败：" + restoreError.Message, changeError); }
        }
        Save(); throw;
    }
}
void Idle() { if (core.Running || core.Starting) throw new InvalidOperationException("请先停止连接，再切换工作区、内核或运行配置。"); }
ConfigProfile Profile(string id) => prefs.Configs.FirstOrDefault(p => p.Id == id) ?? throw new ArgumentException("配置不存在。");
object State() => new {
    delayTesting,
    groupTestError,
    prefs.Core, prefs.Mode, prefs.ConfigPath, prefs.DelayUrl, prefs.Silent, prefs.AutoStart, prefs.AutoConnect, prefs.SystemProxy, prefs.NativeInbounds, prefs.SelectedNode,
    configs = prefs.Configs.Select(c => new { c.Id, c.Name, c.Core, c.Path, c.Url, c.Updated, c.Subscription }),
    nodes = prefs.Nodes.Select((n, i) => new { index = i, n.Name, n.Protocol, n.Server, n.Port, n.SourceSubscription, tag = ConfigBuilder.Tag(n, i), n.DelayText }),
    prefs.Subscriptions, running = core.Ready, starting = core.Starting, startedAt = core.Ready ? new DateTimeOffset(core.StartedAt).ToUnixTimeMilliseconds() : 0
    ,prefs.SubscriptionSettings,prefs.SubscriptionAutoUpdate,prefs.SubscriptionUpdateMinutes,prefs.SubscriptionLastAttempt,prefs.SubscriptionLastSuccess,prefs.SubscriptionUpdateError,subscriptionUpdating=subscriptionTask!=null
};
async Task TestNodes(Preferences test, NodeRecord[] records, long generation, CancellationToken cancellation, int[]? indexes=null) {
    using var starts = new SemaphoreSlim(1);
    try {
        await Parallel.ForEachAsync(indexes ?? Enumerable.Range(0, records.Length).ToArray(), new ParallelOptions { MaxDegreeOfParallelism = 2, CancellationToken = cancellation }, async (i, token) => {
            string result;
            using var tester = new CoreManager(() => true);
            try {
                var single = JsonSerializer.Deserialize<Preferences>(JsonSerializer.Serialize(test))!; single.SelectedNode=i;
                if (!NodeOptions.Compatible(records[i],test.Core)) result="不兼容";
                else {
                    // Serialize process startup/port allocation, then measure concurrently.
                    await starts.WaitAsync(token);
                    try { token.ThrowIfCancellationRequested(); await tester.StartAsync(single,transient:true); }
                    finally { starts.Release(); }
                    result=(await tester.DelayAsync(ConfigBuilder.Tag(records[i],i),test.DelayUrl,token))+" ms";
                }
            } catch (OperationCanceledException) when (token.IsCancellationRequested) { return; }
            catch { result="失败"; }
            if(generation==coreGeneration && prefs.Nodes.Contains(records[i])) {records[i].DelayText=result;Write(new {changed=true});}
        });
    } catch(OperationCanceledException) { }
    catch(Exception error) {Log("ERROR Wbox: 测速失败："+error.Message);}
    finally {foreach(var node in records)if(node.DelayText=="测试中")node.DelayText="未测试";delayTesting=false;Write(new {changed=true});}
}
async Task<object?> Handle(string method, JsonElement a) {
    string S(string name, string fallback = "") => a.TryGetProperty(name, out var v) ? v.GetString() ?? fallback : fallback;
    int I(string name) => a.GetProperty(name).GetInt32();
    switch (method) {
        case "state": return State();
        case "autoUpdate":
        case "refreshSubscriptions": {
            bool manual=method=="refreshSubscriptions";
            if(subscriptionTask!=null||core.Starting||delayTesting||(!manual&&!SubscriptionRefresh.Due(prefs,DateTime.UtcNow)))return null;
            if(prefs.Subscriptions.Count==0&&!prefs.Configs.Any(c=>c.Url.Length>0))return null;
            var snapshot=CopyPreferences();SubscriptionRefresh.MarkAttempts(prefs,manual,DateTime.UtcNow);
            prefs.SubscriptionLastAttempt=DateTime.UtcNow;subscriptionManual=manual;
            subscriptionTask=SubscriptionRefresh.FetchAsync(snapshot,manual);
            _=subscriptionTask.ContinueWith(_=>Write(new{changed=true}),TaskScheduler.Default);
            Save();return null;
        }
        case "startup": return StartupRegistration.Configure(S("executable"), a.GetProperty("enabled").GetBoolean(), a.GetProperty("silent").GetBoolean());
        case "editSubscription": {
            SubscriptionRefresh.Edit(prefs,S("id"),S("oldUrl"),S("name"),S("url"),a.GetProperty("autoUpdate").GetBoolean(),I("updateMinutes"));
            Save();return null;
        }
        case "service": return new { running = core.Ready, starting = core.Starting, startedAt = core.Ready ? new DateTimeOffset(core.StartedAt).ToUnixTimeMilliseconds() : 0 };
        case "memory": using (var self = System.Diagnostics.Process.GetCurrentProcess()) return self.PrivateMemorySize64;
        case "start": nodeTestCancellation.Cancel();await nodeTests;coreGeneration++; if(prefs.Mode=="config"){var selected=prefs.Configs.FirstOrDefault(p=>p.Path==prefs.ConfigPath);if(selected!=null)prefs.Core=selected.Core;}await core.StartAsync(CopyPreferences()); Save(); return null;
        case "stop": nodeTestCancellation.Cancel();await nodeTests;core.Stop(); coreGeneration++; Save(); return null;
        case "logs": return logs.ToArray();
        case "clearLogs": logs.Clear(); return null;
        case "stats": return core.Ready ? await core.StatsAsync(false) : null;
        case "groups": return await Groups();
        case "cancelTest": nodeTestCancellation.Cancel();return null;
        case "testOne": {
            if(delayTesting)throw new InvalidOperationException("正在测速，请稍候。");
            if(prefs.Mode=="nodes") {
                var index=I("index");
                if(index<0||index>=prefs.Nodes.Count)throw new ArgumentException("节点不存在。");
                var test=CopyPreferences();test.SystemProxy=false;
                groupTestError="";nodeTestCancellation.Dispose();nodeTestCancellation=new();delayTesting=true;
                var records=prefs.Nodes.ToArray();records[index].DelayText="测试中";
                nodeTests=TestNodes(test,records,coreGeneration,nodeTestCancellation.Token,[index]);
            } else {
                CheckGroupContext();groupTestError="";
                nodeTestCancellation.Dispose();nodeTestCancellation=new();delayTesting=true;
                nodeTests=TestGroup(CopyPreferences(),S("tag"),GroupContext(),nodeTestCancellation.Token,false);
            }
            Write(new{changed=true});return null;
        }
        case "testGroup": {
            if(delayTesting)return null;
            if(prefs.Mode=="nodes")return await Handle("delayNodes",a);
            CheckGroupContext();groupTestError="";
            nodeTestCancellation.Dispose();nodeTestCancellation=new();delayTesting=true;
            nodeTests=TestGroup(CopyPreferences(),S("tag"),GroupContext(),nodeTestCancellation.Token);
            Write(new{changed=true});return null;
        }
        case "connections": return core.Ready ? await core.RequestAsync("GET", "/connections") : null;
        case "mode": return core.Ready && prefs.Core=="mihomo" ? await core.RequestAsync("GET", "/configs") : null;
        case "setMode": await core.SetModeAsync(S("mode")); return null;
        case "select": {
            if(delayTesting) {nodeTestCancellation.Cancel();await nodeTests;}
            if(prefs.Mode=="nodes") {
                var index=prefs.Nodes.FindIndex(n=>ConfigBuilder.Tag(n,prefs.Nodes.IndexOf(n))==S("node"));
                if(index<0)throw new ArgumentException("节点不存在。");
                var next=CopyPreferences();next.SelectedNode=index;await CommitNodes(next);return null;
            }
            var group=(await Groups()).FirstOrDefault(g=>g.Name==S("group"));
            if(group==null||!group.Selectable)throw new ArgumentException("该代理组不支持手动选择。");
            if(!group.Nodes.Contains(S("node")))throw new ArgumentException("节点不属于该代理组。");
            var candidate=(await Groups()).Select(g=>g.Name==group.Name?g with {Selected=S("node")}:g).ToList();
            ProxyCatalog.Resolve(candidate,group.Name);
            if(core.Ready)await core.SelectAsync(group.Name,S("node"));
            var profile=prefs.Configs.First(c=>c.Path==prefs.ConfigPath);
            profile.GroupSelections[group.Name]=S("node");
            if(probeGroups!=null)probeGroups=probeGroups.Select(g=>g.Name==group.Name?g with {Selected=S("node")}:g).ToList();
            Save();return null;
        }
        case "close": await core.RequestAsync("DELETE", "/connections" + (S("id").Length > 0 ? "/" + Uri.EscapeDataString(S("id")) : "")); return null;
        case "delay": return await core.DelayAsync(S("tag"), prefs.DelayUrl);
        case "delayNodes": {
            if (delayTesting) return null;
            if (core.Running && prefs.Mode != "nodes") throw new InvalidOperationException("请先停止配置工作区的连接。");
            var test = CopyPreferences(); test.Mode = "nodes"; test.Core = prefs.NodeCore.Length > 0 ? prefs.NodeCore : prefs.Core; test.SystemProxy = false;
            if (test.Core == "sing-box" && !test.DelayUrl.StartsWith("https://")) throw new ArgumentException("sing-box 测速需要 HTTPS 地址。");
            nodeTestCancellation.Dispose(); nodeTestCancellation = new(); delayTesting = true;
            var records = prefs.Nodes.ToArray(); foreach (var n in records) n.DelayText = "测试中";
            nodeTests = TestNodes(test, records, coreGeneration, nodeTestCancellation.Token); Write(new { changed = true });
            return null;
        }
        case "proxyStatus": return new { available = core.Running, enabled = core.Running && SystemProxy.Enabled };
        case "proxy": if (a.GetProperty("enabled").GetBoolean()) { if (!core.Running) throw new InvalidOperationException("请先启动内核。"); SystemProxy.Install(core.ProxyPort); } else SystemProxy.Restore(); prefs.SystemProxy = a.GetProperty("enabled").GetBoolean(); Save(); return null;
        case "settings":
            if(a.TryGetProperty("subscriptionUpdateMinutes",out var interval)) {
                int minutes=interval.GetInt32();if(minutes<15||minutes>10080)throw new ArgumentException("更新间隔需为 15–10080 分钟。");
                prefs.SubscriptionUpdateMinutes=minutes;
            }
            if(a.TryGetProperty("subscriptionAutoUpdate",out var updates))prefs.SubscriptionAutoUpdate=updates.GetBoolean();
            if (a.TryGetProperty("mode", out var mode)) { Idle(); prefs.Mode = mode.GetString() == "config" ? "config" : "nodes"; if (prefs.Mode == "nodes") prefs.Core = prefs.NodeCore.Length > 0 ? prefs.NodeCore : "mihomo"; else { var c = prefs.Configs.FirstOrDefault(c => c.Path == prefs.ConfigPath); if (c != null) prefs.Core = c.Core; } }
            if (a.TryGetProperty("core", out var engine)) { Idle(); if (prefs.Mode == "config") throw new InvalidOperationException("配置工作区自动匹配配置的内核。"); prefs.Core = engine.GetString() == "sing-box" ? "sing-box" : "mihomo"; prefs.NodeCore = prefs.Core; }
            if (a.TryGetProperty("delayUrl", out var delayUrl)) { if (!Uri.TryCreate(delayUrl.GetString(), UriKind.Absolute, out var uri) || uri.Scheme is not ("https" or "http")) throw new ArgumentException("测速链接需要是 HTTP / HTTPS 地址。"); prefs.DelayUrl = uri.ToString(); }
            if (a.TryGetProperty("silent", out var silent)) prefs.Silent = silent.GetBoolean();
            if (a.TryGetProperty("autoStart", out var auto)) prefs.AutoStart = auto.GetBoolean();
            if (a.TryGetProperty("autoConnect", out var connect)) prefs.AutoConnect = connect.GetBoolean();
            if (a.TryGetProperty("systemProxy", out var proxy)) { Idle(); prefs.SystemProxy = proxy.GetBoolean(); }
            if (a.TryGetProperty("nativeInbounds", out var native)) { Idle(); prefs.NativeInbounds = native.GetBoolean(); }
            Save(); return null;
        case "selectNode": { var index = I("index"); if (index < 0 || index >= prefs.Nodes.Count) throw new ArgumentException("节点不存在。"); if (prefs.Mode != "nodes") throw new InvalidOperationException("请先切换到节点工作区。"); var next=CopyPreferences(); next.SelectedNode=index; await CommitNodes(next); return null; }
        case "removeNode": {
            var next = CopyPreferences(); int index = I("index"); next.Nodes.RemoveAt(index);
            if (index < next.SelectedNode) next.SelectedNode--;
            next.SelectedNode = Math.Clamp(next.SelectedNode, 0, Math.Max(0, next.Nodes.Count - 1));
            await CommitNodes(next); return null;
        }
        case "readNode": { var node = prefs.Nodes.ElementAtOrDefault(I("index")) ?? throw new ArgumentException("节点不存在。"); return new { node, revision = NodeEditing.Revision(node) }; }
        case "writeNode": {
            int index = I("index"); var original = prefs.Nodes.ElementAtOrDefault(index) ?? throw new ArgumentException("节点不存在。");
            if (NodeEditing.Revision(original) != S("revision")) throw new InvalidOperationException("节点已更新，请重新打开编辑窗口。");
            var edited = JsonSerializer.Deserialize<NodeRecord>(a.GetProperty("node"), new JsonSerializerOptions { PropertyNameCaseInsensitive = true }) ?? throw new ArgumentException("节点内容无效。");
            var next = CopyPreferences(); next.Nodes[index] = NodeEditing.Prepare(original, edited);
            await CommitNodes(next);
            return null;
        }
        case "cloneNode": {
            var node = prefs.Nodes.ElementAtOrDefault(I("index")) ?? throw new ArgumentException("节点不存在。");
            var copy = JsonSerializer.Deserialize<NodeRecord>(JsonSerializer.Serialize(node))!;
            copy.Name += " 副本"; copy.SourceSubscription = "";
            if (copy.NativeSingbox != null) copy.NativeSingbox["tag"] = copy.Name;
            if (copy.NativeMihomo != null) copy.NativeMihomo["name"] = copy.Name;
            var next = CopyPreferences(); next.Nodes.Add(copy); await CommitNodes(next); return null;
        }
        case "removeSubscription": {
            var next = CopyPreferences(); var selected = next.Nodes.ElementAtOrDefault(next.SelectedNode);
            next.Nodes.RemoveAll(n => n.SourceSubscription == S("url")); next.Subscriptions.Remove(S("url"));next.SubscriptionSettings.Remove(S("url"));
            next.SelectedNode = Math.Max(0, selected == null ? 0 : next.Nodes.IndexOf(selected));
            await CommitNodes(next); return null;
        }
        case "importInput": {
            var input = S("text").Trim();
            bool remote = !input.Contains('\n') && Uri.TryCreate(input, UriKind.Absolute, out var location) && location.Scheme is "http" or "https" && location.UserInfo.Length == 0 && location.Fragment.Length == 0;
            return await Handle("importNodes", JsonSerializer.SerializeToElement(remote ? new { url = input, text = "" } : new { url = "", text = input }));
        }
        case "importNodes": {
            var text = S("text").Trim(); var url = S("url").Trim();
            SubscriptionDownload? download=null;
            if (url.Length > 0) {download=await ProfileImport.DownloadAsync(url);text=download.Content;}
            var nodes = NodeParser.ParseSubscription(text); if (nodes.Count == 0) throw new FormatException("未识别到节点。完整 JSON / YAML 请导入配置工作区。");
            var next = CopyPreferences();
            if (url.Length > 0) {
                var selected = next.Nodes.ElementAtOrDefault(next.SelectedNode);
                next.Nodes.RemoveAll(n => n.SourceSubscription == url); foreach (var node in nodes) node.SourceSubscription = url;
                if (!next.Subscriptions.Contains(url)) next.Subscriptions.Add(url); next.Nodes.AddRange(nodes);
                var index = selected == null ? 0 : next.Nodes.IndexOf(selected);
                if (index < 0) index = next.Nodes.FindIndex(n => n.SourceSubscription == selected!.SourceSubscription && n.Name == selected.Name);
                next.SelectedNode = Math.Max(0, index);
                SubscriptionRefresh.Normalize(next);next.SubscriptionSettings[url].LastAttempt=DateTime.UtcNow;next.SubscriptionSettings[url].LastSuccess=DateTime.UtcNow;next.SubscriptionSettings[url].Error="";next.SubscriptionSettings[url].Usage=download?.Usage;
            } else next.Nodes.AddRange(nodes);
            await CommitNodes(next); return nodes.Count;
        }
        case "profiles": return new { selectedId = prefs.Mode == "nodes" ? (prefs.Nodes.Count > 0 ? "nodes" : null) : prefs.Configs.FirstOrDefault(p => p.Path == prefs.ConfigPath)?.Id, profiles = prefs.Mode == "nodes" ? new[] { new { id = "nodes", name = $"节点工作区 · {prefs.Nodes.Count} 个节点", type = "local", autoUpdate = false, autoUpdateIntervalMinutes = 0, lastUpdated = 0L } } : prefs.Configs.Select(c => new { id = c.Id, name = c.Name + " · " + c.Core, type = c.Url.Length > 0 ? "remote" : "local", autoUpdate = false, autoUpdateIntervalMinutes = 0, lastUpdated = new DateTimeOffset(c.Updated).ToUnixTimeMilliseconds() }).ToArray() };
        case "createProfile": { var url = S("remoteUrl"); var download = url.Length > 0 ? await ProfileImport.DownloadAsync(url) : null; var p = ProfileImport.SaveConfig(prefs, download?.Content ?? S("content"), S("name", "配置"), url); if(p.Subscription!=null)p.Subscription.Usage=download?.Usage; if(!core.Running&&!core.Starting){prefs.Mode = "config"; prefs.ConfigPath = p.Path; prefs.Core = p.Core;} Save(); return null; }
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
        case "updateProfile": { var p = Profile(S("id")); if (p.Url.Length == 0) throw new ArgumentException("这是本地配置。"); var selected=prefs.ConfigPath==p.Path;var download=await ProfileImport.DownloadAsync(p.Url);ProfileImport.SaveConfig(prefs, download.Content, p.Name, p.Url, p);p.Subscription!.Usage=download.Usage; if(selected){prefs.ConfigPath=p.Path;if(!core.Running&&!core.Starting)prefs.Core=p.Core;} Save(); return null; }
        case "readProfile": return File.ReadAllText(Profile(S("id")).Path);
        case "renameProfile": { var name = S("name").Trim(); if (name.Length == 0 || name.Length > 100) throw new ArgumentException("名称需要为 1–100 个字符。"); Profile(S("id")).Name = name; Save(); return null; }
        case "writeProfile": { var p = Profile(S("id"));var selected=prefs.ConfigPath==p.Path; ProfileImport.SaveConfig(prefs, S("content"), p.Name, p.Url, p);if(selected){prefs.ConfigPath=p.Path;if(!core.Running&&!core.Starting)prefs.Core=p.Core;} Save(); return null; }
        default: throw new ArgumentException("未知操作：" + method);
    }
}
string? line;
Task startTask=Task.CompletedTask;
async Task ReplyStart(long id,JsonElement arguments) {
    try { var result=await Handle("start",arguments);Write(new {id,result}); }
    catch(OperationCanceledException) { Write(new {id,result=(object?)null}); }
    catch(Exception ex) {Write(new {id,error=ex.Message});}
    finally {Write(new {changed=true});}
}
var delaySlots = new SemaphoreSlim(8);
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
        if(subscriptionTask is {IsCompleted:true}&&!core.Starting&&!delayTesting) {
            var completed=subscriptionTask;subscriptionTask=null;
            {
                prefs.SubscriptionUpdateError=SubscriptionRefresh.Apply(prefs,await completed,core.Running);
                if(prefs.SubscriptionUpdateError.Length==0)prefs.SubscriptionLastSuccess=DateTime.UtcNow;
                else Log("WARN 订阅自动更新："+prefs.SubscriptionUpdateError);
                Save();
            }
        }
        using var request = JsonDocument.Parse(line);
        id = request.RootElement.GetProperty("id").GetInt64();
        string method = request.RootElement.GetProperty("method").GetString()!;
        var arguments = request.RootElement.GetProperty("args");
        // Only the startup wait runs in the background. Stop cancels that wait
        // and joins it before disposing the core; settings never race a start.
        if(method=="start") {
            if(!startTask.IsCompleted)throw new InvalidOperationException("内核正在启动，可点击停止取消。");
            startTask=ReplyStart(id,arguments.Clone());continue;
        }
        if(method=="stop") {core.CancelStartup();await startTask;}
        if(!startTask.IsCompleted && method is not ("state" or "service" or "memory" or "logs" or "clearLogs" or "stats" or "groups" or "connections" or "mode" or "profiles" or "trayProfiles" or "readProfile" or "createProfile" or "writeProfile" or "updateProfile" or "renameProfile"))
            throw new InvalidOperationException("内核正在启动，请稍候或点击停止取消。");
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
core.CancelStartup();await startTask;
for (int attempt = 0; ; attempt++) {
    try { core.Stop(); break; } catch (Exception ex) { Log(ex.Message); await Task.Delay(Math.Min(30_000, 1000 * (attempt + 1))); }
}
coreGeneration++;
nodeTestCancellation.Cancel();
await nodeTests;
await Task.WhenAll(delayTasks);
