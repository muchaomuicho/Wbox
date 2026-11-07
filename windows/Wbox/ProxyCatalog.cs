namespace Wbox;

public static class ProxyCatalog
{
    public static JsonObject Read(Preferences p) => p.Core == "sing-box"
        ? JsonNode.Parse(File.ReadAllText(p.ConfigPath), documentOptions: new() { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip })!.AsObject()
        : ConfigBuilder.ParseYaml(File.ReadAllText(p.ConfigPath));

    public static List<string> Order(JsonObject root, bool singbox) =>
        (root[singbox ? "outbounds" : "proxy-groups"] as JsonArray)?.OfType<JsonObject>()
        .Where(g => !singbox || g["type"]?.ToString() is "selector" or "urltest")
        .Select(g => g[singbox ? "tag" : "name"]?.ToString() ?? "").Where(n => n.Length > 0 && n != "GLOBAL").ToList() ?? [];

    public static List<GroupItem> Offline(Preferences p)
    {
        if (p.Mode == "nodes") {
            if (p.Nodes.Count == 0) return [];
            var details = p.Nodes.Select((n,i) => new ProxyNode(ConfigBuilder.Tag(n,i), n.Protocol, int.TryParse(n.DelayText.Split(' ')[0],out var ms)?ms:n.DelayText=="失败"?0:null)).ToList();
            return [new("节点", ConfigBuilder.Tag(p.Nodes[Math.Clamp(p.SelectedNode,0,p.Nodes.Count-1)],Math.Clamp(p.SelectedNode,0,p.Nodes.Count-1)), details.Select(n=>n.Name).ToList(), false) { Details=details }];
        }
        if (!File.Exists(p.ConfigPath)) return [];
        var root=Read(p);bool sb=p.Core=="sing-box";
        var entries=(root[sb?"outbounds":"proxy-groups"] as JsonArray)?.OfType<JsonObject>().ToList() ?? [];
        var types=new Dictionary<string,string>();
        foreach(var entry in entries.Concat((root[sb?"endpoints":"proxies"] as JsonArray)?.OfType<JsonObject>() ?? []))
            if(entry[sb?"tag":"name"]?.ToString() is string tag)types[tag]=entry["type"]?.ToString() ?? "";
        types.TryAdd("DIRECT","Direct");types.TryAdd("REJECT","Reject");
        return Order(root,sb).Select(tag=>{
            var g=entries.First(e=>e[sb?"tag":"name"]?.ToString()==tag);
            var names=(g[sb?"outbounds":"proxies"] as JsonArray)?.Select(n=>n!.ToString()).ToList() ?? [];
            var type=g["type"]?.ToString() ?? "select";
            return new GroupItem(tag,g["default"]?.ToString() ?? names.FirstOrDefault() ?? "",names,false) {
                Type=type switch {"select" or "selector"=>"Selector","url-test" or "urltest"=>"URLTest","fallback"=>"Fallback","load-balance"=>"LoadBalance",_=>type},
                Details=names.Select(n=>new ProxyNode(n,types.GetValueOrDefault(n,"—"),null)).ToList()
            };
        }).ToList();
    }
    public static string Resolve(IReadOnlyList<GroupItem> groups,string name)
    {
        var seen=new HashSet<string>();
        while(seen.Add(name)) {
            var group=groups.FirstOrDefault(g=>g.Name==name);
            if(group==null || group.Selected.Length==0)return name;
            name=group.Selected;
        }
        throw new InvalidOperationException("代理组存在循环选择。");
    }
}
