using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;
using Wbox;

static class ForwardingChecks {
    public static async Task Run(Action<bool,string> check) {
        Directory.CreateDirectory(Store.DirectoryPath);
        var source = Path.Combine(Store.DirectoryPath,"fakeip-regression.json");
        var cache = Path.Combine(Store.DirectoryPath,"fakeip-regression.db");
        if(File.Exists(cache))File.Delete(cache);
        var server = new TcpListener(IPAddress.Loopback,0); server.Start();
        var port = ((IPEndPoint)server.LocalEndpoint).Port;
        using var cancel = new CancellationTokenSource();
        var serving = Task.Run(async()=> {try{while(!cancel.IsCancellationRequested){using var socket=await server.AcceptTcpClientAsync(cancel.Token);using var stream=socket.GetStream();var buffer=new byte[4096];await stream.ReadAsync(buffer,cancel.Token);await stream.WriteAsync(Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nWbox"),cancel.Token);}}catch(OperationCanceledException){} });
        var logs = new List<string>();
        try {
            var config = new JsonObject {
                ["dns"]=new JsonObject{["servers"]=new JsonArray(new JsonObject{["type"]="fakeip",["tag"]="fake",["inet4_range"]="198.18.0.0/15",["inet6_range"]="fc00::/18"},new JsonObject{["type"]="local",["tag"]="local"}),["final"]="local",["rules"]=new JsonArray(new JsonObject{["query_type"]=new JsonArray("A","AAAA"),["server"]="fake"})},
                ["outbounds"]=new JsonArray(new JsonObject{["type"]="direct",["tag"]="direct"}),
                ["route"]=new JsonObject{["default_domain_resolver"]="local",["rules"]=new JsonArray(new JsonObject{["action"]="route-options",["override_address"]="127.0.0.1"}),["final"]="direct"},
                ["experimental"]=new JsonObject{["cache_file"]=new JsonObject{["enabled"]=true,["store_fakeip"]=true,["path"]=cache}}
            };
            File.WriteAllText(source,config.ToJsonString());
            var prefs = new Preferences{Core="sing-box",Mode="config",ConfigPath=source,SystemProxy=false};
            using var core = new CoreManager(()=>true);core.Log+=line=>{lock(logs)logs.Add(line);};
            await core.StartAsync(prefs);
            var dns=await core.RequestAsync("GET","/dns/query?name=wbox-regression.test&type=A");
            var fake=dns!["Answer"]![0]!["data"]!.ToString();
            check(fake.StartsWith("198.18."),"sing-box allocates real FakeIP record");
            core.Stop();
            check(!logs.Any(l=>l.Contains("timed out")||l.Contains("attach failed")||l.Contains("强制")),"sing-box exits through signal without forced termination");
            await core.StartAsync(prefs);
            using var client = new HttpClient(new SocketsHttpHandler{Proxy=new WebProxy($"http://127.0.0.1:{core.ProxyPort}"),UseProxy=true}){Timeout=TimeSpan.FromSeconds(5)};
            check(await client.GetStringAsync($"http://{fake}:{port}/")=="Wbox","FakeIP traffic survives stop/restart before metadata timer fires");
            core.Stop();
            // A second packaged core is a SOCKS upstream: verify a real two-hop path.
            File.WriteAllText(source,"{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}]}");
            await core.StartAsync(prefs);
            foreach(var engine in new[]{"sing-box","mihomo"}) {
                using var downstream=new CoreManager(()=>true);
                var downstreamLogs = new List<string>();downstream.Log+=line=>downstreamLogs.Add(line);
                await downstream.StartAsync(new Preferences{Core=engine,SystemProxy=false,Nodes=[NodeParser.Parse($"socks5://127.0.0.1:{core.ProxyPort}#upstream")]});
                using var proxy=new HttpClient(new SocketsHttpHandler{Proxy=new WebProxy($"http://127.0.0.1:{downstream.ProxyPort}"),UseProxy=true}){Timeout=TimeSpan.FromSeconds(5)};
                check(await proxy.GetStringAsync($"http://127.0.0.1:{port}/")=="Wbox",engine+" forwards through selected SOCKS upstream");
                downstream.Stop();
                check(!downstreamLogs.Any(l=>l.Contains("timed out")||l.Contains("attach failed")||l.Contains("强制")),engine+" normal stop flushes cache without killing the core");
            }
            core.Stop();
        } finally {cancel.Cancel();server.Stop();await serving;File.Delete(source);if(File.Exists(cache))File.Delete(cache);}
    }
}
