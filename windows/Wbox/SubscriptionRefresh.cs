namespace Wbox;

public sealed record SubscriptionResult(string Id, string Url, string Revision, string? Content, string? Error);

public static class SubscriptionRefresh
{
    public static bool Due(Preferences p, DateTime now) => p.SubscriptionAutoUpdate
        && now >= p.SubscriptionLastAttempt.ToUniversalTime().AddMinutes(Math.Clamp(p.SubscriptionUpdateMinutes,15,10080));

    public static string NodeRevision(Preferences p,string url) => JsonSerializer.Serialize(p.Nodes.Where(n=>n.SourceSubscription==url));

    public static async Task<List<SubscriptionResult>> FetchAsync(Preferences snapshot)
    {
        var targets=snapshot.Configs.Where(c=>c.Url.Length>0)
            .Select(c=>(Id:c.Id,Url:c.Url,Revision:c.Updated.Ticks.ToString()))
            .Concat(snapshot.Subscriptions.Select(url=>(Id:"",Url:url,Revision:NodeRevision(snapshot,url))));
        var results=new List<SubscriptionResult>();
        foreach(var t in targets) {
            try {results.Add(new(t.Id,t.Url,t.Revision,await ProfileImport.FetchAsync(t.Url),null));}
            catch {results.Add(new(t.Id,t.Url,t.Revision,null,"下载失败，请检查网络或订阅地址"));}
        }
        return results;
    }

    // Apply to the latest preferences on the backend command loop. Downloads
    // never hold up Stop, and an intervening edit/removal wins over this batch.
    public static string Apply(Preferences p,IEnumerable<SubscriptionResult> results,bool running)
    {
        var errors=new List<string>();
        foreach(var r in results) {
            var profile=r.Id.Length>0?p.Configs.FirstOrDefault(c=>c.Id==r.Id&&c.Url==r.Url):null;
            if(r.Id.Length>0&&profile==null || r.Id.Length==0&&!p.Subscriptions.Contains(r.Url))continue;
            if((profile!=null?profile.Updated.Ticks.ToString():NodeRevision(p,r.Url))!=r.Revision)continue;
            if(r.Error!=null){errors.Add(r.Error);continue;}
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
            } catch {errors.Add("订阅内容无效，已保留原数据");}
        }
        return string.Join("；",errors.Distinct());
    }
}
