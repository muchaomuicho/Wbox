namespace Wbox;

public sealed record SubscriptionResult(string Id, string Url, string Revision, string? Content, string? Error, bool Manual = true, SubscriptionUsage? Usage = null);

public static class SubscriptionRefresh
{
    public static void Normalize(Preferences p)
    {
        SubscriptionOptions Create(string url) => new() { Name = new Uri(url).Host,
            AutoUpdate = !p.SubscriptionSettingsMigrated && p.SubscriptionAutoUpdate,
            UpdateMinutes = !p.SubscriptionSettingsMigrated ? Math.Clamp(p.SubscriptionUpdateMinutes,15,10080) : 1440,
            LastAttempt = !p.SubscriptionSettingsMigrated ? p.SubscriptionLastAttempt : default };
        foreach(var c in p.Configs.Where(c=>c.Url.Length>0)) c.Subscription ??= Create(c.Url);
        foreach(var url in p.Subscriptions) if(!p.SubscriptionSettings.ContainsKey(url))p.SubscriptionSettings[url]=Create(url);
        p.SubscriptionSettingsMigrated=true;
    }
    public static bool Due(SubscriptionOptions s,DateTime now) => s.AutoUpdate && now-s.LastAttempt.ToUniversalTime() >= TimeSpan.FromMinutes(Math.Clamp(s.UpdateMinutes,15,10080));
    public static bool Due(Preferences p,DateTime now) { Normalize(p);return Targets(p).Any(t=>Due(t.Options,now)); }
    public static IEnumerable<(string Id,string Url,string Revision,SubscriptionOptions Options)> Targets(Preferences p) =>
        p.Configs.Where(c=>c.Url.Length>0).Select(c=>(c.Id,c.Url,c.Updated.Ticks.ToString(),c.Subscription!))
            .Concat(p.Subscriptions.Select(url=>("",url,NodeRevision(p,url),p.SubscriptionSettings[url])));
    public static string NodeRevision(Preferences p,string url) => JsonSerializer.Serialize(p.Nodes.Where(n=>n.SourceSubscription==url));
    public static void MarkAttempts(Preferences p,bool manual,DateTime now) { Normalize(p);foreach(var t in Targets(p).Where(t=>manual||Due(t.Options,now)))t.Options.LastAttempt=now; }
    public static async Task<List<SubscriptionResult>> FetchAsync(Preferences snapshot,bool manual=true)
    {
        Normalize(snapshot);var now=DateTime.UtcNow;
        var targets=Targets(snapshot).Where(t=>manual||Due(t.Options,now)).ToArray();
        var results=new List<SubscriptionResult>();
        foreach(var t in targets) {
            try {var download=await ProfileImport.DownloadAsync(t.Url);results.Add(new(t.Id,t.Url,t.Revision,download.Content,null,manual,download.Usage));}
            catch {results.Add(new(t.Id,t.Url,t.Revision,null,"下载失败，请检查网络或订阅地址",manual));}
        }
        return results;
    }
    public static string Apply(Preferences p,IEnumerable<SubscriptionResult> results,bool running)
    {
        Normalize(p);var errors=new List<string>();
        foreach(var r in results) {
            var profile=r.Id.Length>0?p.Configs.FirstOrDefault(c=>c.Id==r.Id&&c.Url==r.Url):null;
            if(r.Id.Length>0&&profile==null || r.Id.Length==0&&!p.Subscriptions.Contains(r.Url))continue;
            var settings=profile?.Subscription??p.SubscriptionSettings[r.Url];
            if(!r.Manual&&!settings.AutoUpdate)continue;
            if((profile!=null?profile.Updated.Ticks.ToString():NodeRevision(p,r.Url))!=r.Revision)continue;
            if(r.Error!=null){settings.Error=r.Error;errors.Add(r.Error);continue;}
            try {
                if(profile!=null) {
                    var selected=p.ConfigPath==profile.Path;
                    ProfileImport.SaveConfig(p,r.Content!,profile.Name,profile.Url,profile);
                    if(selected){p.ConfigPath=profile.Path;if(!running)p.Core=profile.Core;}
                } else {
                    var nodes=NodeParser.ParseSubscription(r.Content!);
                    if(nodes.Count==0)throw new FormatException();
                    var selected=p.Nodes.ElementAtOrDefault(p.SelectedNode);
                    p.Nodes.RemoveAll(n=>n.SourceSubscription==r.Url);
                    foreach(var node in nodes)node.SourceSubscription=r.Url;
                    p.Nodes.AddRange(nodes);
                    var index=selected==null?0:p.Nodes.IndexOf(selected);
                    if(index<0)index=p.Nodes.FindIndex(n=>n.SourceSubscription==selected!.SourceSubscription&&n.Name==selected.Name);
                    p.SelectedNode=Math.Max(0,index);
                }
                settings.Usage=r.Usage;settings.LastSuccess=DateTime.UtcNow;settings.Error="";
            } catch {settings.Error="订阅内容无效，已保留原数据";errors.Add(settings.Error);}
        }
        return string.Join("；",errors.Distinct());
    }
    public static void Edit(Preferences p,string id,string oldUrl,string name,string url,bool enabled,int minutes)
    {
        name=name.Trim();url=url.Trim();
        if(name.Length==0||name.Length>100)throw new ArgumentException("名称需要为 1–100 个字符。");
        if(!Uri.TryCreate(url,UriKind.Absolute,out var uri)||uri.Scheme is not ("https" or "http")||uri.UserInfo.Length>0||uri.Fragment.Length>0)throw new ArgumentException("订阅地址需要是有效的 HTTP / HTTPS 地址。");
        if(minutes<15||minutes>10080)throw new ArgumentException("更新间隔需为 15–10080 分钟。");
        Normalize(p);SubscriptionOptions options;
        if(id.Length>0) {
            var profile=p.Configs.FirstOrDefault(c=>c.Id==id&&c.Url==oldUrl)??throw new ArgumentException("配置已变化，请重新打开编辑窗口。");
            options=profile.Subscription!;profile.Name=name;profile.Url=url;
        }else {
            if(!p.Subscriptions.Contains(oldUrl))throw new ArgumentException("订阅已移除。");
            if(oldUrl!=url&&p.Subscriptions.Contains(url))throw new ArgumentException("该订阅地址已存在。");
            options=p.SubscriptionSettings[oldUrl];p.SubscriptionSettings.Remove(oldUrl);p.SubscriptionSettings[url]=options;
            p.Subscriptions[p.Subscriptions.IndexOf(oldUrl)]=url;
            foreach(var node in p.Nodes.Where(n=>n.SourceSubscription==oldUrl))node.SourceSubscription=url;
        }
        options.Name=name;options.AutoUpdate=enabled;options.UpdateMinutes=minutes;
        if(url!=oldUrl){options.Usage=null;options.LastAttempt=default;options.LastSuccess=default;options.Error="";}
    }
}
