using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;
using System.Xml.Linq;
using Wbox;

static class ReliabilityChecks
{
    public static async Task Run(Action<bool,string> check)
    {
        string path=@"C:\应用 & test\Wbox.exe",sid="S-1-5-21-123";
        XNamespace ns="http://schemas.microsoft.com/windows/2004/02/mit/task";
        var xml=XDocument.Parse(StartupRegistration.TaskXml(path,sid,true,true));
        check(xml.Descendants(ns+"Command").Single().Value==path && xml.Descendants(ns+"UserId").All(x=>x.Value==sid),"Login task preserves executable path and restricts trigger to the current user");
        check(xml.Descendants(ns+"RunLevel").Single().Value=="HighestAvailable" && xml.Descendants(ns+"Arguments").Single().Value=="--autostart --silent", "Elevated silent login uses Task Scheduler with an interactive token");
        check(xml.Descendants(ns+"DisallowStartIfOnBatteries").Single().Value=="false" && xml.Descendants(ns+"ExecutionTimeLimit").Single().Value=="PT0S", "Login task runs on battery without a time limit");
        foreach(string engine in new[]{"sing-box","mihomo"}) {
            var p=new Preferences{Mode="nodes",Core=engine,SystemProxy=false,Nodes=[NodeParser.Parse("socks://127.0.0.1:1080#probe")]};
            var text=ConfigBuilder.Build(p,"test",transient:true,workingDirectory:Path.Combine(Store.DirectoryPath,"probe"));
            var root=JsonNode.Parse(text)!;
            check(engine=="sing-box"?root["route"]?["rule_set"]==null:root["rule-providers"]==null,engine+" latency probe omits rule databases while retaining the selected node");
            var probePath=Path.Combine(Store.DirectoryPath,"probe-"+engine+".json");File.WriteAllText(probePath,text);await ImportChecks.Validate(engine,probePath,check);
        }
        using var server=new TcpListener(IPAddress.Loopback,0);server.Start();
        string url=$"http://127.0.0.1:{((IPEndPoint)server.LocalEndpoint).Port}/subscribe?token=synthetic&clash";
        var requests=new List<string>();
        async Task Respond(int status,string body) {
            using var peer=await server.AcceptTcpClientAsync();using var stream=peer.GetStream();using var reader=new StreamReader(stream,Encoding.ASCII,leaveOpen:true);
            var headers=new StringBuilder();string? line;while(!string.IsNullOrEmpty(line=await reader.ReadLineAsync()))headers.AppendLine(line);requests.Add(headers.ToString());
            byte[] content=Encoding.UTF8.GetBytes(body);byte[] head=Encoding.ASCII.GetBytes($"HTTP/1.1 {status} Response\r\nContent-Length: {content.Length}\r\nConnection: close\r\n\r\n");await stream.WriteAsync(head);await stream.WriteAsync(content);
        }
        var responder=Task.Run(async()=>{await Respond(502,"");await Respond(200,"proxies: []");});
        check(await ProfileImport.FetchAsync(url)=="proxies: []","Subscription retries a transient 502 and returns the next response");await responder;
        check(requests.Count==2 && requests.All(r=>r.Contains("User-Agent: clash.meta/1.19.33")&&r.Contains("?token=synthetic&clash")),"Mihomo UA and subscription query flags survive retry");
        responder=Respond(401,"");bool rejected=false;try{await ProfileImport.FetchAsync(url);}catch(IOException e){rejected=e.Message.Contains("401");}await responder;
        check(rejected&&requests.Count==3,"Authentication failures are not retried");
        using var cancellation=new CancellationTokenSource(100);bool cancelled=false;
        try{await ProfileImport.FetchAsync(url,cancellation:cancellation.Token);}catch(OperationCanceledException){cancelled=true;}
        check(cancelled,"Caller cancellation is preserved, not shown as a network timeout");
    }
}
