using System.Diagnostics;
using System.Text;
using System.Text.Json.Nodes;
using Wbox;

static class ImportChecks
{
    public static async Task Run(Action<bool,string> check)
    {
        var key = Convert.ToBase64String(Enumerable.Range(1,32).Select(i => (byte)i).ToArray());
        var links = new[] {
            "anytls://secret@127.0.0.1:443?sni=example.org&alpn=h2#AnyTLS",
            "socks://user:p%2Bass@127.0.0.1:1080#SOCKS",
            "http://user:password@127.0.0.1:8080#HTTP",
            "https://user:password@127.0.0.1:8443#HTTPS",
            "hysteria://127.0.0.1:443?auth=secret&upmbps=50&downmbps=100&alpn=h3#HY1",
            "hy2://secret@127.0.0.1:443?obfs=salamander&obfs-password=secret&alpn=h3#HY2",
            "tuic://11111111-1111-4111-8111-111111111111:password@127.0.0.1:443?congestion_control=bbr&udp_relay_mode=quic&alpn=h3#TUIC",
            "ss://"+Convert.ToBase64String(Encoding.UTF8.GetBytes("2022-blake3-aes-128-gcm:"+Convert.ToBase64String(new byte[16])+":"+Convert.ToBase64String(Enumerable.Repeat((byte)1,16).ToArray())))+"@127.0.0.1:8388?#SS2022",
            "ss://YWVzLTEyOC1nY206cGFzc3dvcmQ@127.0.0.1:8388?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dexample.org#Obfs",
            "ss://YWVzLTEyOC1nY206cGFzc3dvcmQ@127.0.0.1:8388?plugin=v2ray-plugin%3Btls%3Bhost%3Dexample.org#Plugin",
            "wireguard://"+Uri.EscapeDataString(key)+"@127.0.0.1:2408?publickey="+Uri.EscapeDataString(key)+"&address=172.16.0.2%2F32%2Cfd00%3A%3A2%2F128&reserved=1,2,3#WG"
        };
        var nodes = links.Select(NodeParser.Parse).ToList();
        check(nodes[1].Password=="p+ass", "SOCKS preserves literal plus in credentials");
        check(NodeParser.Parse("http://example.org#HTTP").Port==80 && NodeParser.Parse("socks://example.org#SOCKS").Port==1080, "HTTP and SOCKS default ports");
        check(nodes[7].Password.Count(c => c == ':')==1, "SS2022 multi-user key preserved");
        check(ConfigBuilder.SingboxOutbound(nodes[5],"test")["obfs"]!["password"]!.ToString()=="secret", "Hysteria2 obfs retained");
        check(ConfigBuilder.MihomoProxy(nodes[6],"test")["congestion-controller"]!.ToString()=="bbr", "TUIC settings retained");
        var https=NodeParser.Parse("https://user:password@127.0.0.1:443?sni=example.org#TLS");
        check(ConfigBuilder.MihomoProxy(https,"tls")["sni"]!.ToString()=="example.org" && ConfigBuilder.MihomoProxy(https,"tls")["tls"]!.GetValue<bool>(), "HTTPS proxy preserves TLS and SNI");
        check(ProfileImport.DetectCore("{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"DIRECT\"}]}")=="sing-box", "Config format detected by content");
        check(ProfileImport.DetectCore("proxy-providers:\n  test:\n    type: http\n    url: https://example.org/sub\n")=="mihomo", "Remote-provider YAML accepted as complete config");
        var preferences = new Preferences { Nodes=nodes, Mode="nodes", SystemProxy=false };
        var config = ProfileImport.SaveConfig(preferences, "{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"DIRECT\"}]}", "Remote", "https://example.org/no-extension");
        check(preferences.Mode=="nodes" && preferences.Nodes.Count==nodes.Count, "Config import leaves node workspace intact");
        string before=File.ReadAllText(config.Path); bool rejected=false;
        try { ProfileImport.SaveConfig(preferences,"<html>error</html>","Remote",config.Url,config); } catch { rejected=true; }
        check(rejected&&File.ReadAllText(config.Path)==before,"Bad remote update retains saved config");
        foreach (var core in new[]{"sing-box","mihomo"}) {
            preferences.Core=core;
            var path=Path.Combine(Store.DirectoryPath,"expanded-"+core+".json");for(int index=0;index<nodes.Count;index++) {
                if(!NodeOptions.Compatible(nodes[index],core))continue;
                preferences.SelectedNode=index;File.WriteAllText(path,ConfigBuilder.Build(preferences,"test"));
                await Validate(core,path,check);
            }
        }
        var naive=NodeParser.Parse("naive+https://user:password@127.0.0.1:443#Naive");
        check(NodeOptions.Compatible(naive,"sing-box")&&!NodeOptions.Compatible(naive,"mihomo"),"NaiveProxy core capability explicit");
        preferences.Core="sing-box";preferences.Nodes=[naive];
        var naivePath=Path.Combine(Store.DirectoryPath,"naive.json");File.WriteAllText(naivePath,ConfigBuilder.Build(preferences,"test"));await Validate("sing-box",naivePath,check);
    }
    public static async Task Validate(string core,string path,Action<bool,string> check) {
        var start=new ProcessStartInfo(Path.Combine(AppContext.BaseDirectory,"cores",core+".exe")){UseShellExecute=false,CreateNoWindow=true,RedirectStandardError=true,RedirectStandardOutput=true};
        foreach(var arg in core=="sing-box"?new[]{"check","-c",path}:new[]{"-t","-f",path,"-d",Store.DirectoryPath})start.ArgumentList.Add(arg);
        using var p=Process.Start(start)!;var stdout=p.StandardOutput.ReadToEndAsync();var stderr=p.StandardError.ReadToEndAsync();
        try{await p.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(25));}catch{p.Kill(true);throw;}
        var output=await stdout+await stderr;check(p.ExitCode==0,core+" expanded protocols core validation: "+(p.ExitCode==0?"OK":output));
    }
}
