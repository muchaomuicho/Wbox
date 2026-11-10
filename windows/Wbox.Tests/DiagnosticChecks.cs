using System.Net;
using System.Text.Json.Nodes;
using Wbox;
static class DiagnosticChecks {
    public static async Task Run(string path) {
        Directory.CreateDirectory(Store.DirectoryPath);
        var local=Path.Combine(Store.DirectoryPath,"diagnostic-private.json");
        var config=JsonNode.Parse(File.ReadAllText(path))!.AsObject();
        config["experimental"]!["cache_file"]!["path"]=Path.Combine(Store.DirectoryPath,"diagnostic-cache.db");
        File.WriteAllText(local,config.ToJsonString());
        using var manager=new CoreManager(()=>true);
        try {
            await manager.StartAsync(new Preferences{Core="sing-box",Mode="config",ConfigPath=local,SystemProxy=false,NativeInbounds=false});
            Console.WriteLine("Original profile (managed mixed inbound): started");
            using var client=new HttpClient(new SocketsHttpHandler{Proxy=new WebProxy($"http://127.0.0.1:{manager.ProxyPort}"),UseProxy=true}){Timeout=TimeSpan.FromSeconds(12)};
            foreach(var url in new[]{"https://www.apple.com/library/test/success.html","https://www.google.com/generate_204"}) {
                try{using var response=await client.GetAsync(url);Console.WriteLine(new Uri(url).Host+": HTTP "+(int)response.StatusCode);}
                catch(Exception e){Console.WriteLine(new Uri(url).Host+": "+e.GetType().Name);}
            }
        } catch(Exception e){Console.WriteLine("Diagnostic: "+e.GetType().Name);}
        finally{manager.Stop();File.Delete(local);var cache=Path.Combine(Store.DirectoryPath,"diagnostic-cache.db");if(File.Exists(cache))File.Delete(cache);}
    }
}
