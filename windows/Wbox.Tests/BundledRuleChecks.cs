using System.Text.Json.Nodes;
using Wbox;

static class BundledRuleChecks
{
    public static void Run(Action<bool,string> check)
    {
        foreach(var engine in new[]{"sing-box","mihomo"}) {
            bool sb=engine=="sing-box";
            var p=new Preferences{Mode="nodes",Core=engine,SystemProxy=false,Nodes=[NodeParser.Parse("socks5://127.0.0.1:19999#Selected"),NodeParser.Parse("socks5://127.0.0.1:19998#Unused")]};
            string text=ConfigBuilder.Build(p,"test");var root=sb?JsonNode.Parse(text)!.AsObject():ConfigBuilder.ParseYaml(text);
            check(!text.Contains("Unused")&&!text.Contains(NodeTemplate.Selected),engine+" template injects only the active node");
            var groups=root[sb?"outbounds":"proxy-groups"]!.AsArray();
            check(groups.OfType<JsonObject>().Any(g=>g[sb?"tag":"name"]?.ToString()=="AI")&&groups.OfType<JsonObject>().Any(g=>g[sb?"tag":"name"]?.ToString()=="Steam Download"),engine+" template retains application routing groups");
            var rules=sb?root["route"]!["rule_set"]!.AsArray().OfType<JsonObject>().ToArray():root["rule-providers"]!.AsObject().Select(p=>p.Value!.AsObject()).ToArray();
            check(rules.Length==13&&rules.All(r=>r["type"]!.ToString()==(sb?"local":"file")&&!r.ContainsKey("url")&&File.Exists(r["path"]!.ToString())),engine+" thirteen rule sets load entirely from disk");
            var path=rules[0]["path"]!.ToString();var stamp=File.GetLastWriteTimeUtc(path);ConfigBuilder.Build(p,"test");
            check(File.GetLastWriteTimeUtc(path)==stamp,engine+" reconnect reuses verified rule files");
            File.WriteAllText(path,"interrupted download");ConfigBuilder.Build(p,"test");check(new FileInfo(path).Length>32,engine+" damaged local rule copy is repaired");
            var source=NodeTemplate.Load(sb,"sample");var remote=sb?source["route"]!["rule_set"]![0]!.AsObject():source["rule-providers"]!["anti-ad"]!.AsObject();
            remote["url"]="https://custom.invalid/rules";string original=source.ToJsonString();var copy=source.DeepClone().AsObject();BundledRules.Apply(copy,sb,Path.Combine(Store.DirectoryPath,"custom-rules"));
            check(source.ToJsonString()==original,engine+" source configuration remains untouched");
            var custom=sb?copy["route"]!["rule_set"]![0]!:copy["rule-providers"]!["anti-ad"]!;
            check(custom["url"]!.ToString()=="https://custom.invalid/rules"&&custom["type"]!.ToString()==(sb?"remote":"http"),engine+" custom URL with a known tag stays remote");
        }
    }
}
