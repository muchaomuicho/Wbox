using System.Net;
using System.Net.Sockets;
using System.Text.Json.Nodes;
using Wbox;

static class StartupChecks {
    public static async Task Run(Action<bool,string> check) {
        Directory.CreateDirectory(Store.DirectoryPath);
        var path = Path.Combine(Store.DirectoryPath,"startup-regression.json");
        var source = """{"inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"],"auto_route":true},{"type":"mixed","tag":"mixed-in","listen":"::","listen_port":7890}],"outbounds":[{"type":"direct","tag":"DIRECT"}],"route":{"final":"DIRECT"}}""";
        File.WriteAllText(path,source);
        var options = new Preferences { Core="sing-box", Mode="config", ConfigPath=path, SystemProxy=false };
        var generated = JsonNode.Parse(ConfigBuilder.Build(options,"secret"))!;
        check(generated["inbounds"]!.AsArray().Count == 1 && generated["inbounds"]![0]!["tag"]!.ToString()=="mixed-in" && generated["inbounds"]![0]!["listen_port"]!.GetValue<int>()==10808, "Managed inbound avoids native TUN and 7890 collision, retains routing tag");
        options.NativeInbounds = true;
        var native = ConfigBuilder.Build(options,"secret");
        check(JsonNode.Parse(native)!["inbounds"]!.AsArray().Count==3 && File.ReadAllText(path)==source, "Native inbound mode and source file preserved");
        if (!StartupPolicy.Elevated) {
            bool explained=false;try { StartupPolicy.Check(native,"sing-box"); } catch(InvalidOperationException ex) { explained=ex.Message.Contains("TUN")&&ex.Message.Contains("管理员"); }
            check(explained,"TUN privilege failure explains remedy before starting core");
        }
        using var busyProxy = new TcpListener(IPAddress.Loopback,ConfigBuilder.MixedPort);
        using var busyApi = new TcpListener(IPAddress.Loopback,ConfigBuilder.ControllerPort);
        busyProxy.Start();busyApi.Start(); options.NativeInbounds=false;
        using var manager=new CoreManager(); int exits=0;manager.Exited+=()=>exits++;
        await manager.StartAsync(options);
        check(manager.ProxyPort!=10808 && manager.ApiPort!=19090 && manager.ProxyPort!=manager.ApiPort,"Occupied Wbox ports choose independent available ports");
        var stats=await manager.StatsAsync();check(manager.Running && stats.Inbound==0,"TUN-containing import starts in managed mode without system changes");
        manager.Stop();check(exits==0,"Expected stop does not report an unexpected exit");
        File.Delete(path);
    }
}
