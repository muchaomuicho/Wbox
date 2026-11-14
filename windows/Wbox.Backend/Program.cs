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
bool subscriptionApplying = false;
// UI progress follows downloads through the queued application transaction.
var subscriptionDownloads = new ConcurrentDictionary<long,string>();
string[] subscriptionBatchSources = [];
string SubscriptionSource(string id,string url) => id.Length>0 ? "config:"+id : "subscription:"+url;
long coreGeneration = 0;
long stopEpoch = 0;
int stopRequested = 0;
bool CanStart(long epoch) => Volatile.Read(ref stopRequested) == 0 && Interlocked.Read(ref stopEpoch) == epoch;
void RequestStop() { Volatile.Write(ref stopRequested,1); Interlocked.Increment(ref stopEpoch); core.CancelStartup(); }
async Task StartConnection(Preferences target,long epoch) {
    if(!CanStart(epoch))throw new OperationCanceledException();
    var startup=core.StartAsync(target);
    // A stop can arrive between the check and StartAsync creating its new CTS.
    if(!CanStart(epoch))core.CancelStartup();
    await startup;
    if(!CanStart(epoch))throw new OperationCanceledException();
}
bool delayTesting = false;
CancellationTokenSource nodeTestCancellation = new();
Task nodeTests = Task.CompletedTask;
var groupMeasurements = new ConcurrentDictionary<string,GroupMeasurements>();
var nodeMeasurementsLock = new object();
LatencySnapshot? activeProbe = null;
string groupTestError = "";
string GroupContext() => LatencySnapshot.Identity(prefs);
string CheckGroupContext(Preferences? source=null) {
    var key=LatencySnapshot.Identity(source??prefs);
    if(!groupMeasurements.ContainsKey(key)&&groupMeasurements.Count>=16)
        foreach(var old in groupMeasurements.Keys.Where(value=>value!=activeProbe?.Context).Take(groupMeasurements.Count-15))groupMeasurements.TryRemove(old,out _);
    groupMeasurements.TryAdd(key,new());
    return key;
}
async Task<List<GroupItem>> Groups(bool tray=false) {
    while(true) {
    var source=prefs;var generation=coreGeneration;var context=CheckGroupContext(source);
    var measured=groupMeasurements[context];List<GroupItem> groups;
    try {groups=tray&&core.Ready?await core.GroupsAsync():source.Mode=="nodes"?ProxyCatalog.Offline(source):core.Ready?await core.GroupsAsync():measured.Catalog??ProxyCatalog.Offline(source);}
    catch(Exception)when(generation!=coreGeneration||context!=GroupContext()){continue;}
    if(generation!=coreGeneration||context!=GroupContext())continue;
    if(!core.Ready&&source.Mode=="config")groups=CurrentSelections(groups,source);
    var active=source.Mode=="nodes"?source.Nodes.ElementAtOrDefault(source.SelectedNode):null;
    var activeTag=active==null?null:ConfigBuilder.Tag(active,source.SelectedNode);
    var probe=activeProbe;
    return groups.Select(g=>g with {Details=g.Details.Select(n=>{
        string leaf;try{leaf=ProxyCatalog.Resolve(groups,n.Name);}catch{leaf=n.Name;}
        var testing=probe?.Context==context&&probe.IsPending(leaf);
        if(active!=null&&leaf==activeTag)return n with {Testing=testing||active.DelayText=="测试中",Delay=int.TryParse(active.DelayText.Split(' ')[0],out var ms)?ms:active.DelayText is "timeout" or "Timeout" or "失败"?0:null};
        return measured.Delays.TryGetValue(leaf,out var value)?n with {Testing=testing,Delay=value}:n with {Testing=testing,Delay=source.Mode=="nodes"?n.Delay:null};
    }).ToList()}).ToList();
    }
}
List<GroupItem> CurrentSelections(List<GroupItem> groups,Preferences source) {
    var selections=source.Configs.FirstOrDefault(c=>c.Path==source.ConfigPath)?.GroupSelections;
    return groups.Select(g=>selections?.TryGetValue(g.Name,out var selected)==true&&g.Nodes.Contains(selected)?g with {Selected=selected}:g).ToList();
}
string[] GroupTargets(List<GroupItem> groups,string tag,bool expand,bool all) {
    var group=groups.FirstOrDefault(g=>g.Name==tag);
    IEnumerable<string> requested=all?groups.Where(g=>g.Selectable).SelectMany(g=>g.Nodes):expand?group?.Nodes??[tag]:[tag];
    return requested.Select(name=>ProxyCatalog.Resolve(groups,name)).Distinct().ToArray();
}
async Task BeginGroupTest(string tag,bool expand=true,bool all=false) {
    var source=CopyPreferences();var context=CheckGroupContext(source);
    var catalog=core.Ready?await core.GroupsAsync():CurrentSelections(groupMeasurements[context].Catalog??ProxyCatalog.Offline(source),source);
    var probe=new LatencySnapshot(source,catalog);
    // Publish every known target before the isolated core starts, including
    // cached provider leaves. Its actual catalog can refine this set later.
    probe.SetPending(GroupTargets(catalog,tag,expand,all));
    groupTestError="";nodeTestCancellation.Dispose();nodeTestCancellation=new();activeProbe=probe;delayTesting=true;
    nodeTests=TestGroup(probe,tag,nodeTestCancellation.Token,expand,all);
}
async Task TestGroup(LatencySnapshot probe,string tag,CancellationToken token,bool expand=true,bool all=false) {
    var snapshot=probe.Preferences;
    var measured=groupMeasurements.GetOrAdd(probe.Context,_=>new());
    using var tester=new CoreManager(()=>true);
    try {
        snapshot.SystemProxy=false;snapshot.NativeInbounds=false;
        token.ThrowIfCancellationRequested();var starting=tester.StartAsync(snapshot,true,probe.ProbeContent,probe.Resources);
        using var cancel=token.Register(tester.CancelStartup);
        await starting;token.ThrowIfCancellationRequested();
        var groups=await tester.GroupsAsync();
        foreach(var selection in probe.Catalog?.Where(g=>g.Selectable)??[]) {
            var available=groups.FirstOrDefault(g=>g.Name==selection.Name);
            if(available?.Nodes.Contains(selection.Selected)==true)await tester.SelectAsync(selection.Name,selection.Selected);
        }
        if(probe.Catalog!=null)groups=await tester.GroupsAsync();
        measured.Catalog=groups;
        var names=GroupTargets(groups,tag,expand,all);
        probe.SetPending(names);
        Write(new{changed=true});
        await Parallel.ForEachAsync(names,new ParallelOptions{MaxDegreeOfParallelism=4,CancellationToken=token},async(name,ct)=>{
            try {
                int value;
                try {value=await tester.DelayAsync(name,snapshot.DelayUrl,ct);}
                catch(OperationCanceledException) when(ct.IsCancellationRequested){return;}
                catch(InvalidOperationException error){Log("WARN 代理组测速："+error.Message);groupTestError=error.Message;return;}
                catch{value=0;}
                if(probe.IsValid(prefs)){
                    measured.Delays[name]=value;
                    if(snapshot.Mode=="nodes") {
                        var testedIndex=snapshot.Nodes.FindIndex(n=>ConfigBuilder.Tag(n,snapshot.Nodes.IndexOf(n))==name);
                        if(testedIndex>=0)SetNodeMeasurement(snapshot.Nodes[testedIndex],value>0?$"{value} ms":"timeout");
                    }
                }
            }finally {probe.Complete(name);Write(new{changed=true});}
        });
    }catch(OperationCanceledException) when(token.IsCancellationRequested){}
    catch(Exception error){Log("ERROR 代理组测速失败："+error.Message);groupTestError=error.Message;}
    finally {try{tester.Stop(token.IsCancellationRequested);}finally{probe.ClearPending();activeProbe=null;delayTesting=false;Write(new{changed=true});}}
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
async Task CancelTests() {
    nodeTestCancellation.Cancel();activeProbe?.ClearPending();await nodeTests;
}
void SetNodeMeasurement(NodeRecord tested,string value) {
    var revision=NodeEditing.Revision(tested);
    lock(nodeMeasurementsLock)
        foreach(var current in prefs.Nodes.Where(node=>NodeEditing.Revision(node)==revision))current.DelayText=value;
}
void PreserveNodeMeasurements(Preferences next) {
    foreach(var node in next.Nodes) {
        var revision=NodeEditing.Revision(node);
        var previous=prefs.Nodes.FirstOrDefault(current=>NodeEditing.Revision(current)==revision);
        if(previous!=null)node.DelayText=previous.DelayText;
    }
}
void SwapPreferences(Preferences next) {
    lock(nodeMeasurementsLock){PreserveNodeMeasurements(next);prefs=next;}
}
string RuntimeInput(Preferences p) {
    if(p.Mode=="config" && !File.Exists(p.ConfigPath) || p.Mode=="nodes" && p.Nodes.Count==0)return "";
    var input=p.Mode+"|"+p.Core+"|"+p.ConfigPath+"|"+p.NativeInbounds+"|";
    return input+(core.Running?ConfigBuilder.Build(p,"validation"):p.Mode=="config"?File.ReadAllText(p.ConfigPath):JsonSerializer.Serialize(p.Nodes)+"|"+p.SelectedNode);
}
async Task CommitPreferences(Preferences next,string? previousInput=null,Action? rollbackFiles=null) {
    var previous=prefs;
    var before=previousInput??RuntimeInput(previous);
    var epoch=Interlocked.Read(ref stopEpoch);
    bool restart=false;
    try {
        var after=RuntimeInput(next);
        restart=core.Running && before!=after;
        if(activeProbe?.IsValid(next)==false)await CancelTests();
        if(restart){core.Stop();coreGeneration++;if(after.Length>0)await StartConnection(next,epoch);}
        else if(core.Ready && previous.SystemProxy!=next.SystemProxy){if(next.SystemProxy)SystemProxy.Install(core.ProxyPort);else SystemProxy.Restore();}
        SwapPreferences(next);Save();
    } catch(Exception changeError) {
        rollbackFiles?.Invoke();SwapPreferences(previous);
        if(restart){
            core.Stop();coreGeneration++;
            if(CanStart(epoch))try{await StartConnection(previous,epoch);}
            catch(OperationCanceledException) when(!CanStart(epoch)){}
            catch(Exception restoreError){Save();throw new InvalidOperationException("修改未保存，恢复连接失败："+restoreError.Message,changeError);}
        }
        Save();throw;
    }
}
async Task ApplyChange(Action<Preferences> change) {
    var next=CopyPreferences();var before=RuntimeInput(prefs);
    var files=prefs.Configs.Where(c=>File.Exists(c.Path)).Select(c=>c.Path).Distinct().ToDictionary(path=>path,File.ReadAllBytes);
    void RestoreFiles(){
        foreach(var item in files)File.WriteAllBytes(item.Key,item.Value);
        foreach(var path in next.Configs.Select(c=>c.Path).Distinct().Where(path=>!files.ContainsKey(path)))
            if(path.StartsWith(Path.Combine(Store.DirectoryPath,"profiles")+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)&&File.Exists(path))File.Delete(path);
    }
    try{change(next);await CommitPreferences(next,before,RestoreFiles);}catch{RestoreFiles();throw;}
}
Task CommitNodes(Preferences next)=>CommitPreferences(next);
ConfigProfile Profile(string id) => prefs.Configs.FirstOrDefault(p => p.Id == id) ?? throw new ArgumentException("配置不存在。");
object State() => new {
    delayTesting,
    groupTestError,
    prefs.LibrarySort, prefs.Core, prefs.Mode, prefs.ConfigPath, prefs.DelayUrl, prefs.Silent, prefs.AutoStart, prefs.AutoConnect, prefs.SystemProxy, prefs.NativeInbounds, prefs.SelectedNode,
    configs = LibraryOrder.Configs(prefs).Select(c => new { c.Id, c.Name, c.Core, c.Path, c.Url, c.Updated, c.Subscription }),
    nodes = prefs.Nodes.Select((n, i) => new { index = i, n.Name, n.Protocol, n.Server, n.Port, n.SourceSubscription, tag = ConfigBuilder.Tag(n, i), n.DelayText }),
    subscriptions = LibraryOrder.Subscriptions(prefs), running = core.Ready, starting = core.Starting, startedAt = core.Ready ? new DateTimeOffset(core.StartedAt).ToUnixTimeMilliseconds() : 0
    ,prefs.SubscriptionSettings,prefs.SubscriptionAutoUpdate,prefs.SubscriptionUpdateMinutes,prefs.SubscriptionLastAttempt,prefs.SubscriptionLastSuccess,prefs.SubscriptionUpdateError,subscriptionUpdating=subscriptionTask!=null||subscriptionApplying||!subscriptionDownloads.IsEmpty,
    subscriptionUpdatingSources=subscriptionBatchSources.Concat(subscriptionDownloads.Values).Distinct().ToArray()
};
async Task TestNodes(Preferences test, NodeRecord[] records, CancellationToken cancellation, int[]? indexes=null) {
    using var starts = new SemaphoreSlim(1);
    try {
        await Parallel.ForEachAsync(indexes ?? Enumerable.Range(0, records.Length).ToArray(), new ParallelOptions { MaxDegreeOfParallelism = 2, CancellationToken = cancellation }, async (i, token) => {
            string result;
            using var tester = new CoreManager(() => true);
            using var cancelStartup = token.Register(tester.CancelStartup);
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
            catch { result="timeout"; }
            finally {if(token.IsCancellationRequested)tester.Stop(true);}
            if(!cancellation.IsCancellationRequested) {SetNodeMeasurement(records[i],result);Write(new {changed=true});}
        });
    } catch(OperationCanceledException) { }
    catch(Exception error) {Log("ERROR Wbox: 测速失败："+error.Message);}
    finally {lock(nodeMeasurementsLock){foreach(var node in prefs.Nodes)if(node.DelayText=="测试中")node.DelayText="未测试";}activeProbe=null;delayTesting=false;Write(new {changed=true});}
}
async Task<object?> Handle(string method, JsonElement a,long? intentEpoch=null) {
    string S(string name, string fallback = "") => a.TryGetProperty(name, out var v) ? v.GetString() ?? fallback : fallback;
    int I(string name) => a.GetProperty(name).GetInt32();
    Task<SubscriptionDownload> Download(string url) {
        if(!a.TryGetProperty("downloadContent",out var content))return ProfileImport.DownloadAsync(url);
        if(S("downloadedUrl")!=url)throw new InvalidOperationException("订阅地址已变化，请重新更新。");
        return Task.FromResult(new SubscriptionDownload(content.GetString()!,a.GetProperty("downloadUsage").Deserialize<SubscriptionUsage>()));
    }
    switch (method) {
        case "state": return State();
        case "applySubscriptions": {
            var results=a.Deserialize<List<SubscriptionResult>>()!;
            try{await ApplyChange(next=>{
                next.SubscriptionUpdateError=SubscriptionRefresh.Apply(next,results,false);
                if(next.SubscriptionUpdateError.Length==0)next.SubscriptionLastSuccess=DateTime.UtcNow;
                else Log("WARN 订阅更新："+next.SubscriptionUpdateError);
            });}catch(Exception error){prefs.SubscriptionUpdateError=error.Message;Log("ERROR 订阅更新已恢复原配置："+error.Message);Save();}finally{subscriptionApplying=false;subscriptionBatchSources=[];Write(new{changed=true});}
            return null;
        }
        case "autoUpdate":
        case "refreshSubscriptions": {
            bool manual=method=="refreshSubscriptions";
            if(subscriptionTask!=null||subscriptionApplying||(!manual&&!SubscriptionRefresh.Due(prefs,DateTime.UtcNow)))return null;
            if(prefs.Subscriptions.Count==0&&!prefs.Configs.Any(c=>c.Url.Length>0))return null;
            var snapshot=CopyPreferences();
            subscriptionBatchSources=SubscriptionRefresh.Targets(snapshot).Where(t=>manual||SubscriptionRefresh.Due(t.Options,DateTime.UtcNow)).Select(t=>SubscriptionSource(t.Id,t.Url)).ToArray();
            SubscriptionRefresh.MarkAttempts(prefs,manual,DateTime.UtcNow);
            prefs.SubscriptionLastAttempt=DateTime.UtcNow;subscriptionManual=manual;
            subscriptionTask=SubscriptionRefresh.FetchAsync(snapshot,manual);
            _=subscriptionTask.ContinueWith(_=>Write(new{changed=true}),TaskScheduler.Default);
            Save();return null;
        }
        case "startup": return StartupRegistration.Configure(S("executable"), a.GetProperty("enabled").GetBoolean(), a.GetProperty("silent").GetBoolean());
        case "editSubscription": {
            var changed=S("oldUrl")!=S("url").Trim();
            var download=changed?await Download(S("url").Trim()):null;
            await ApplyChange(next=>{
                SubscriptionRefresh.Edit(next,S("id"),S("oldUrl"),S("name"),S("url"),a.GetProperty("autoUpdate").GetBoolean(),I("updateMinutes"));
                if(download!=null){
                    var target=SubscriptionRefresh.Targets(next).First(t=>t.Id==S("id")&&t.Url==S("url").Trim());
                    var error=SubscriptionRefresh.Apply(next,[new(target.Id,target.Url,target.Revision,download.Content,null,true,download.Usage)],false);
                    if(error.Length>0)throw new FormatException(error);
                }
            });return null;
        }
        case "service": return new { running = core.Ready, starting = core.Starting, startedAt = core.Ready ? new DateTimeOffset(core.StartedAt).ToUnixTimeMilliseconds() : 0 };
        case "memory": using (var self = System.Diagnostics.Process.GetCurrentProcess()) return self.PrivateMemorySize64;
        case "start": {
            var epoch=intentEpoch??Interlocked.Read(ref stopEpoch);
            if(epoch!=Interlocked.Read(ref stopEpoch))return null;
            Volatile.Write(ref stopRequested,0);
            coreGeneration++;
            if(prefs.Mode=="config"){var selected=prefs.Configs.FirstOrDefault(p=>p.Path==prefs.ConfigPath);if(selected!=null)prefs.Core=selected.Core;}
            await StartConnection(CopyPreferences(),epoch);Save();return null;
        }
        case "stop": await CancelTests();core.Stop();coreGeneration++;Save();return null;
        case "logs": return logs.ToArray();
        case "clearLogs": logs.Clear(); return null;
        case "stats": return core.Ready ? await core.StatsAsync(false) : null;
        case "groups": return await Groups();
        case "trayGroups": return await Groups(true);
        case "cancelTest": nodeTestCancellation.Cancel();activeProbe?.ClearPending();return null;
        case "testOne": {
            if(delayTesting)throw new InvalidOperationException("正在测速，请稍候。");
            if(prefs.Mode=="nodes") {
                var index=I("index");
                if(index<0||index>=prefs.Nodes.Count)throw new ArgumentException("节点不存在。");
                var test=CopyPreferences();test.SystemProxy=false;activeProbe=new(test);
                groupTestError="";nodeTestCancellation.Dispose();nodeTestCancellation=new();delayTesting=true;
                var records=prefs.Nodes.ToArray();records[index].DelayText="测试中";
                nodeTests=TestNodes(test,records,nodeTestCancellation.Token,[index]);
            } else {
                await BeginGroupTest(S("tag"),false);
            }
            Write(new{changed=true});return null;
        }
        case "trayTestGroup":
        case "testGroup": {
            if(delayTesting)return null;
            if(method=="trayTestGroup"&&!core.Ready)throw new InvalidOperationException("请先连接，再测试托盘中的运行组。");
            if(method=="testGroup"&&prefs.Mode=="nodes")return await Handle("delayNodes",a);
            await BeginGroupTest(S("tag"),all:method=="trayTestGroup"&&S("tag")=="");
            Write(new{changed=true});return null;
        }
        case "connections": return core.Ready ? await core.RequestAsync("GET", "/connections") : null;
        case "mode": return core.Ready && prefs.Core=="mihomo" ? await core.RequestAsync("GET", "/configs") : null;
        case "setMode": await core.SetModeAsync(S("mode")); return null;
        case "traySelect":
        case "select": {
            if(prefs.Mode=="nodes"&&method=="select") {
                var index=prefs.Nodes.FindIndex(n=>ConfigBuilder.Tag(n,prefs.Nodes.IndexOf(n))==S("node"));
                if(index<0)throw new ArgumentException("节点不存在。");
                var next=CopyPreferences();next.SelectedNode=index;await CommitNodes(next);return null;
            }
            if(method=="traySelect"&&!core.Ready)throw new InvalidOperationException("运行组已关闭，请重新连接。");
            var groups=await Groups(method=="traySelect");
            var group=groups.FirstOrDefault(g=>g.Name==S("group"));
            if(group==null||!group.Selectable)throw new ArgumentException("该代理组不支持手动选择。");
            if(!group.Nodes.Contains(S("node")))throw new ArgumentException("节点不属于该代理组。");
            var candidate=groups.Select(g=>g.Name==group.Name?g with {Selected=S("node")}:g).ToList();
            ProxyCatalog.Resolve(candidate,group.Name);
            if(core.Ready)await core.SelectAsync(group.Name,S("node"));
            if(prefs.Mode=="nodes") {
                var active=ConfigBuilder.Tag(prefs.Nodes[prefs.SelectedNode],prefs.SelectedNode);
                prefs.NodeGroupSelections[$"{prefs.Core}:{group.Name}"]=S("node")==active?NodeTemplate.Selected:S("node");
            } else prefs.Configs.First(c=>c.Path==prefs.ConfigPath).GroupSelections[group.Name]=S("node");
            Save();return null;
        }
        case "close": await core.RequestAsync("DELETE", "/connections" + (S("id").Length > 0 ? "/" + Uri.EscapeDataString(S("id")) : "")); return null;
        case "delay": return await core.DelayAsync(S("tag"), prefs.DelayUrl);
        case "delayNodes": {
            if (delayTesting) return null;
            if (core.Running && prefs.Mode != "nodes") throw new InvalidOperationException("请先停止配置工作区的连接。");
            var test = CopyPreferences(); test.Mode = "nodes"; test.Core = prefs.NodeCore.Length > 0 ? prefs.NodeCore : prefs.Core; test.SystemProxy = false;
            if (test.Core == "sing-box" && !test.DelayUrl.StartsWith("https://")) throw new ArgumentException("sing-box 测速需要 HTTPS 地址。");
            groupTestError="";nodeTestCancellation.Dispose(); nodeTestCancellation = new(); delayTesting = true;
            activeProbe=new(test);
            var records = prefs.Nodes.ToArray(); foreach (var n in records) n.DelayText = "测试中";
            nodeTests = TestNodes(test, records, nodeTestCancellation.Token); Write(new { changed = true });
            return null;
        }
        case "proxyStatus": return new { available = core.Running, enabled = core.Running && SystemProxy.Enabled };
        case "proxy": if (a.GetProperty("enabled").GetBoolean()) { if (!core.Running) throw new InvalidOperationException("请先启动内核。"); SystemProxy.Install(core.ProxyPort); } else SystemProxy.Restore(); prefs.SystemProxy = a.GetProperty("enabled").GetBoolean(); Save(); return null;
        case "settings": {
            await ApplyChange(next => {
            if(a.TryGetProperty("subscriptionUpdateMinutes",out var interval)) {
                int minutes=interval.GetInt32();if(minutes<15||minutes>10080)throw new ArgumentException("更新间隔需为 15–10080 分钟。");
                next.SubscriptionUpdateMinutes=minutes;
            }
            if(a.TryGetProperty("subscriptionAutoUpdate",out var updates))next.SubscriptionAutoUpdate=updates.GetBoolean();
            if (a.TryGetProperty("mode", out var mode)) { next.Mode = mode.GetString() == "config" ? "config" : "nodes"; if (next.Mode == "nodes") next.Core = next.NodeCore.Length > 0 ? next.NodeCore : "mihomo"; else { var c = next.Configs.FirstOrDefault(c => c.Path == next.ConfigPath); if (c != null) next.Core = c.Core; } }
            if (a.TryGetProperty("core", out var engine)) { if (next.Mode == "config") throw new InvalidOperationException("配置工作区自动匹配配置的内核。"); next.Core = engine.GetString() == "sing-box" ? "sing-box" : "mihomo"; next.NodeCore = next.Core; }
            if (a.TryGetProperty("delayUrl", out var delayUrl)) { if (!Uri.TryCreate(delayUrl.GetString(), UriKind.Absolute, out var uri) || uri.Scheme is not ("https" or "http")) throw new ArgumentException("测速链接需要是 HTTP / HTTPS 地址。"); next.DelayUrl = uri.ToString(); }
            if (a.TryGetProperty("silent", out var silent)) next.Silent = silent.GetBoolean();
            if (a.TryGetProperty("autoStart", out var auto)) next.AutoStart = auto.GetBoolean();
            if (a.TryGetProperty("autoConnect", out var connect)) next.AutoConnect = connect.GetBoolean();
            if (a.TryGetProperty("systemProxy", out var proxy)) { next.SystemProxy = proxy.GetBoolean(); }
            if (a.TryGetProperty("nativeInbounds", out var native)) { next.NativeInbounds = native.GetBoolean(); }
            }); return null;
        }
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
            if(S("downloadedRevision").Length>0 && (!prefs.Subscriptions.Contains(url)||SubscriptionRefresh.NodeRevision(prefs,url)!=S("downloadedRevision")))throw new InvalidOperationException("订阅已编辑或移除，请重新更新。");
            SubscriptionDownload? download=null;
            if (url.Length > 0) {download=await Download(url);text=download.Content;}
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
        case "profiles": return new { selectedId = prefs.Mode == "nodes" ? (prefs.Nodes.Count > 0 ? "nodes" : null) : prefs.Configs.FirstOrDefault(p => p.Path == prefs.ConfigPath)?.Id, profiles = prefs.Mode == "nodes" ? new[] { new { id = "nodes", name = $"节点工作区 · {prefs.Nodes.Count} 个节点", type = "local", autoUpdate = false, autoUpdateIntervalMinutes = 0, lastUpdated = 0L } } : LibraryOrder.Configs(prefs).Select(c => new { id = c.Id, name = c.Name + " · " + c.Core, type = c.Url.Length > 0 ? "remote" : "local", autoUpdate = false, autoUpdateIntervalMinutes = 0, lastUpdated = new DateTimeOffset(c.Updated).ToUnixTimeMilliseconds() }).ToArray() };
        case "createProfile": { var url = S("remoteUrl"); var download = url.Length > 0 ? await Download(url) : null; var p = ProfileImport.SaveConfig(prefs, download?.Content ?? S("content"), S("name", "配置"), url); if(p.Subscription!=null)p.Subscription.Usage=download?.Usage; if(!core.Running&&!core.Starting){prefs.Mode = "config"; prefs.ConfigPath = p.Path; prefs.Core = p.Core;} Save(); return null; }
        case "trayProfiles": {
            var entries = LibraryOrder.Configs(prefs).Select(c => new { id = c.Id, name = c.Name + " · " + c.Core, type = "local" }).ToList();
            if (prefs.Nodes.Count > 0) entries.Add(new { id = "nodes", name = $"节点工作区 · {prefs.Nodes.Count} 个节点", type = "local" });
            return new { selectedId = prefs.Mode == "nodes" ? "nodes" : prefs.Configs.FirstOrDefault(c => c.Path == prefs.ConfigPath)?.Id, profiles = entries };
        }
        case "traySelectProfile":
        case "selectProfile": {
            var id=S("id");
            await ApplyChange(next=>{
                if(id=="nodes"&&next.Nodes.Count>0){next.Mode="nodes";next.Core=next.NodeCore.Length>0?next.NodeCore:"mihomo";}
                else{var p=next.Configs.FirstOrDefault(c=>c.Id==id)??throw new ArgumentException("配置不存在。");next.Mode="config";next.ConfigPath=p.Path;next.Core=p.Core;}
            });return null;
        }
        case "removeProfile": {
            await ApplyChange(next=>{next.Configs.RemoveAll(c=>c.Id==S("id"));if(!next.Configs.Any(c=>c.Path==next.ConfigPath)){next.ConfigPath=next.Configs.FirstOrDefault()?.Path??"";if(next.Mode=="config")next.Core=next.Configs.FirstOrDefault()?.Core??"mihomo";}});return null;
        }
        case "updateProfile": {
            var p=Profile(S("id"));if(p.Url.Length==0)throw new ArgumentException("这是本地配置。");
            if(S("downloadedRevision").Length>0&&p.Updated.Ticks.ToString()!=S("downloadedRevision"))throw new InvalidOperationException("配置已编辑，请重新更新。");
            var download=await Download(p.Url);
            await ApplyChange(next=>{var target=next.Configs.First(c=>c.Id==p.Id);var selected=next.ConfigPath==target.Path;ProfileImport.SaveConfig(next,download.Content,target.Name,target.Url,target);target.Subscription!.Usage=download.Usage;if(selected){next.ConfigPath=target.Path;if(next.Mode=="config")next.Core=target.Core;}});return null;
        }
        case "readProfile": return File.ReadAllText(Profile(S("id")).Path);
        case "renameProfile": { var name = S("name").Trim(); if (name.Length == 0 || name.Length > 100) throw new ArgumentException("名称需要为 1–100 个字符。"); Profile(S("id")).Name = name; Save(); return null; }
        case "writeProfile": {
            await ApplyChange(next=>{var p=next.Configs.FirstOrDefault(c=>c.Id==S("id"))??throw new ArgumentException("配置不存在。");var selected=next.ConfigPath==p.Path;ProfileImport.SaveConfig(next,S("content"),p.Name,p.Url,p);if(selected){next.ConfigPath=p.Path;if(next.Mode=="config")next.Core=p.Core;}});return null;
        }
        case "sortLibrary": {
            var order=S("order");if(order is not ("default" or "name" or "updated"))throw new ArgumentException("未知排序方式。");
            prefs.LibrarySort=order;Save();return null;
        }
        case "reorderLibrary": {
            if(a.TryGetProperty("ids",out var ids)) {
                if(S("kind")!="config")throw new ArgumentException("未知配置列表。");
                LibraryOrder.MergeConfigs(prefs,ids.Deserialize<string[]>()??[]);Save();
            } else if(LibraryOrder.Move(prefs,S("kind"),S("id"),S("anchor"),a.GetProperty("after").GetBoolean()))Save();
            return null;
        }
        default: throw new ArgumentException("未知操作：" + method);
    }
}
string? line;
Task operationTask=Task.CompletedTask;
var operationLock=new object();
var downloads=new List<Task>();
void QueueOperation(long id,string method,JsonElement arguments){lock(operationLock)operationTask=ReplyOperation(operationTask,id,method,arguments,Interlocked.Read(ref stopEpoch));}
async Task PrepareDownload(long id,string method,JsonElement arguments,string url){
    try{
        var source=method=="updateProfile"?SubscriptionSource(arguments.GetProperty("id").GetString()!,url):method=="importNodes"&&prefs.Subscriptions.Contains(url)?SubscriptionSource("",url):"";
        if(source.Length>0){subscriptionDownloads[id]=source;Write(new{changed=true});}
        var revision=method=="updateProfile"?Profile(arguments.GetProperty("id").GetString()!).Updated.Ticks.ToString():method=="importNodes"&&prefs.Subscriptions.Contains(url)?SubscriptionRefresh.NodeRevision(prefs,url):"";
        var download=await ProfileImport.DownloadAsync(url);
        var prepared=JsonNode.Parse(arguments.GetRawText())!.AsObject();
        prepared["downloadContent"]=download.Content;prepared["downloadUsage"]=JsonSerializer.SerializeToNode(download.Usage);prepared["downloadedUrl"]=url;
        prepared["downloadedRevision"]=revision;
        QueueOperation(id,method,JsonSerializer.SerializeToElement(prepared));
    }catch(Exception error){subscriptionDownloads.TryRemove(id,out _);Write(new{id,error=error.Message});Write(new{changed=true});}
}
async Task ReplyOperation(Task previous,long id,string method,JsonElement arguments,long intentEpoch) {
    await previous;
    try{var result=await Handle(method,arguments,intentEpoch);Write(new{id,result});}
    catch(OperationCanceledException){Write(new{id,result=(object?)null});}
    catch(Exception ex){Write(new{id,error=ex.Message});}
    finally{subscriptionDownloads.TryRemove(id,out _);Write(new{changed=true});}
}
bool ReadOnly(string method)=>method is "state" or "service" or "memory" or "logs" or "stats" or "groups" or "trayGroups" or "connections" or "mode" or "profiles" or "trayProfiles" or "readProfile" or "readNode" or "proxyStatus";
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
        if(subscriptionTask is {IsCompleted:true}) {
            var completed=subscriptionTask;subscriptionTask=null;
            subscriptionApplying=true;
            var results=await completed;
            QueueOperation(0,"applySubscriptions",JsonSerializer.SerializeToElement(results));
        }
        using var request = JsonDocument.Parse(line);
        id = request.RootElement.GetProperty("id").GetInt64();
        string method = request.RootElement.GetProperty("method").GetString()!;
        var arguments = request.RootElement.GetProperty("args");
        string downloadUrl="";
        if(method=="updateProfile")downloadUrl=Profile(arguments.GetProperty("id").GetString()!).Url;
        else if(method is "importNodes" or "createProfile" or "editSubscription"){
            var key=method=="createProfile"?"remoteUrl":"url";
            if(arguments.TryGetProperty(key,out var address))downloadUrl=address.GetString()?.Trim()??"";
            if(method=="editSubscription"&&arguments.GetProperty("oldUrl").GetString()==downloadUrl)downloadUrl="";
        }
        else if(method=="importInput"){
            var input=arguments.GetProperty("text").GetString()?.Trim()??"";
            if(!input.Contains('\n')&&Uri.TryCreate(input,UriKind.Absolute,out var uri)&&uri.Scheme is "http" or "https"&&uri.UserInfo.Length==0&&uri.Fragment.Length==0){method="importNodes";downloadUrl=input;arguments=JsonSerializer.SerializeToElement(new{url=input,text=""});}
        }
        if(downloadUrl.Length>0){downloads.RemoveAll(task=>task.IsCompleted);downloads.Add(PrepareDownload(id,method,arguments.Clone(),downloadUrl));continue;}
        if(method=="stop")RequestStop();
        if(method=="stop" || method=="cancelTest")nodeTestCancellation.Cancel();
        if(!ReadOnly(method) && method!="delay") {
            QueueOperation(id,method,arguments.Clone());continue;
        }
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
RequestStop();await Task.WhenAll(downloads);await operationTask;
for (int attempt = 0; ; attempt++) {
    try { core.Stop(); break; } catch (Exception ex) { Log(ex.Message); await Task.Delay(Math.Min(30_000, 1000 * (attempt + 1))); }
}
coreGeneration++;
nodeTestCancellation.Cancel();
await nodeTests;
await Task.WhenAll(delayTasks);
