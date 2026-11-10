using System.Text.Json.Nodes;
using Wbox;

static class IsolationChecks
{
    public static async Task Run(Action<bool,string> check)
    {
        foreach(var engine in new[]{"sing-box","mihomo"}) {
            string json=engine=="sing-box" ? """{"outbounds":[{"type":"http","tag":"Parent","server":"127.0.0.1","server_port":9000},{"type":"http","tag":"Chain","server":"127.0.0.1","server_port":9001,"detour":"Parent"},{"type":"http","tag":"Broken","server":"127.0.0.1","server_port":9002,"detour":"Missing"}]}""" : """{"proxies":[{"type":"http","name":"Parent","server":"127.0.0.1","port":9000},{"type":"http","name":"Chain","server":"127.0.0.1","port":9001,"dialer-proxy":"Parent"},{"type":"http","name":"Broken","server":"127.0.0.1","port":9002,"dialer-proxy":"Missing"}]}""";
            var p=new Preferences{Core=engine,Mode="nodes",SystemProxy=false,Nodes=NodeParser.ParseSubscription(json)};
            var original=p.Nodes[1].NativeSingbox?.ToJsonString()??p.Nodes[1].NativeMihomo!.ToJsonString();
            p.SelectedNode=0;var active=ConfigBuilder.ActiveNodes(p);
            check(active.Count==1,engine+" unrelated broken nodes are excluded");
            p.SelectedNode=1;active=ConfigBuilder.ActiveNodes(p);
            check(active.Count==2&&active[1][engine=="sing-box"?"detour":"dialer-proxy"]!.ToString()==ConfigBuilder.Tag(p.Nodes[0],0),engine+" explicit dependency is renamed consistently");
            check(original==(p.Nodes[1].NativeSingbox?.ToJsonString()??p.Nodes[1].NativeMihomo!.ToJsonString()),engine+" dependency resolution preserves source data");
            var path=Path.Combine(Store.DirectoryPath,"chain-"+engine+".json");File.WriteAllText(path,ConfigBuilder.Build(p,"test"));await ImportChecks.Validate(engine,path,check);
            p.SelectedNode=2;bool rejected=false;try{ConfigBuilder.Build(p,"test");}catch(InvalidOperationException e){rejected=e.Message.Contains("Missing");}check(rejected,engine+" selected broken node reports its missing dependency");
            p.SelectedNode=1;p.Nodes[0].SourceSubscription="https://other.invalid/sub";rejected=false;try{ConfigBuilder.Build(p,"test");}catch(InvalidOperationException){rejected=true;}check(rejected,engine+" dependencies never cross subscription ownership");
        }
    }
}
