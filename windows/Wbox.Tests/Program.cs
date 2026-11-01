using System.Text;
using System.Text.Json.Nodes;
using Wbox;

try
{
Environment.SetEnvironmentVariable("WBOX_DATA_DIR", Path.GetFullPath("windows/test-runtime"));
if (args.Length == 2 && args[0] == "--subscription") {
    var imported = NodeParser.ParseSubscription(File.ReadAllText(args[1]));
    Console.WriteLine("Imported " + imported.Count + " nodes: " + string.Join(", ", imported.GroupBy(n => n.Protocol).Select(g => g.Key + "=" + g.Count())));
    foreach (var engine in new[] { "sing-box", "mihomo" }) {
        if (!imported.Any(n => NodeOptions.Compatible(n,engine))) { Console.WriteLine(engine + " requires switching to the native subscription core"); continue; }
        var options = new Preferences { Core = engine, Nodes = imported, SystemProxy = false };
        var generated = ConfigBuilder.Build(options, "test");
        var checkPath = Path.Combine(Store.DirectoryPath, "subscription-check.json"); File.WriteAllText(checkPath,generated);
        await ImportChecks.Validate(engine,checkPath,(ok,label) => { if(!ok) throw new Exception("Native subscription config check failed"); });
        File.Delete(checkPath);
        Console.WriteLine(engine + " configuration generated successfully");
    }
    var content = File.ReadAllText(args[1]); var fullCore=ProfileImport.DetectCore(content);
    var fullPath=Path.Combine(Store.DirectoryPath,"remote-check.json"); File.WriteAllText(fullPath,content);
    await ImportChecks.Validate(fullCore,fullPath,(ok,label) => {if(!ok)throw new Exception("Remote config check failed");});File.Delete(fullPath);
    Console.WriteLine("Full remote configuration passed native check"); return;
}
int checks = 0;
void Check(bool condition, string label) { if (!condition) throw new Exception("FAILED: " + label); checks++; Console.WriteLine("PASS " + label); }
if (args.Contains("--proxy-native")) { await ProxyNativeChecks.Run(Check); Console.WriteLine($"{checks} native proxy checks passed."); return; }
await ImportChecks.Run(Check);
NodeEditingChecks.Run(Check);
var ss = NodeParser.Parse("ss://" + Convert.ToBase64String(Encoding.UTF8.GetBytes("aes-128-gcm:p:a@ss")) + "@[2001:db8::1]:8388#IPv6");
Check(ss.Password == "p:a@ss" && ss.Server == "2001:db8::1" && ss.Port == 8388, "SS URL credentials and IPv6");
ss.Name = "🇯🇵 日本 IPv6";
var unicode = new JsonObject { ["name"] = ss.Name, ["literal"] = @"\uD83C\uDDEF", ["escape"] = "a\\\"b" };
var serializedUnicode = ConfigBuilder.SerializeYaml(unicode);
Check(serializedUnicode.Contains("🇯🇵") && JsonNode.DeepEquals(unicode, ConfigBuilder.ParseYaml(serializedUnicode)), "YAML-safe flags preserve literal escapes and credentials");
var vm = NodeParser.Parse("vmess://" + Convert.ToBase64String(Encoding.UTF8.GetBytes("{\"ps\":\"Test\",\"add\":\"example.org\",\"port\":\"443\",\"id\":\"99b91b8e-d4f1-459f-89d5-819553d4a9c3\",\"net\":\"ws\",\"tls\":\"tls\",\"path\":\"/path\",\"host\":\"cdn.example.org\"}")));
Check(vm.Network == "ws" && vm.Tls && vm.Host == "cdn.example.org", "VMess TLS WebSocket");
var vl = NodeParser.Parse("vless://99b91b8e-d4f1-459f-89d5-819553d4a9c3@example.org:443?security=reality&pbk=Q2FgNiSGAlK14obBNanOfIlu71vuoTxaJMqpWWdTEFg&sid=dafe&fp=chrome&flow=xtls-rprx-vision#Reality");
Check(vl.Tls && vl.RealityKey!.Length == 43 && vl.Flow == "xtls-rprx-vision", "VLESS Reality");
var subscription = NodeParser.ParseSubscription(Convert.ToBase64String(Encoding.UTF8.GetBytes("trojan://secret@example.org:443#test\nss://" + Convert.ToBase64String(Encoding.UTF8.GetBytes("aes-128-gcm:pass")) + "@example.org:8388#ss")));
Check(subscription.Count == 2, "Base64 multiline subscription");
var native = NodeParser.ParseSubscription("proxies:\n  - name: A\n    type: ss\n    server: example.org\n    port: 8388\n    cipher: aes-128-gcm\n    password: secret\n");
Check(native.Count == 1 && native[0].NativeMihomo != null, "Native YAML subscription");
var p = new Preferences { Nodes = [ss, vm, vl], Core = "sing-box" };
var sing = JsonNode.Parse(ConfigBuilder.Build(p, "secret"))!;
Check(sing["outbounds"]![0]!["outbounds"]!.AsArray().Count == 3 && sing["experimental"]!["clash_api"]!["secret"]!.ToString() == "secret", "Sing-box selector and protected controller");
p.Core = "mihomo";
var mihomo = JsonNode.Parse(ConfigBuilder.Build(p, "secret"))!;
Check(mihomo["ipv6"]!.GetValue<bool>() && mihomo["mixed-port"]!.GetValue<int>() == 10808 && mihomo["proxies"]!.AsArray().Count == 3, "Mihomo IPv6 node config");
var before = new Dictionary<string, ProxyValue> { ["ProxyEnable"] = new() { Exists = true, Dword = true, Value = "0" } };
var owned = new Dictionary<string, ProxyValue> { ["ProxyEnable"] = new() { Exists = true, Dword = true, Value = "1" }, ["ProxyServer"] = new() { Exists = true, Value = "127.0.0.1:10808" } };
var snapshot = new ProxySnapshot { Before = before, Owned = owned };
Check(SystemProxy.CanRestore(snapshot, owned), "Proxy owned values recover");
Check(!SystemProxy.CanRestore(snapshot, before), "Proxy user edits preserved");
var drift = new Dictionary<string, ProxyValue>(owned) { ["ProxyOverride"] = new() { Exists = true, Value = ";localhost.*;;<local>;localhost;127.*;[::1]" }, ["AutoDetect"] = new() };
Check(SystemProxy.CanRestore(snapshot, drift), "Proxy bypass/autodetect drift does not strand the owned listener");
drift["ProxyServer"] = new() { Exists = true, Value = "http=127.0.0.1:10808;https=127.0.0.1:10808" };
Check(SystemProxy.CanRestore(snapshot, drift), "Per-protocol WinINet endpoints match owned listener");
drift["ProxyServer"] = new() { Exists = true, Value = "127.0.0.1:7890" };
Check(!SystemProxy.CanRestore(snapshot, drift), "Another application's proxy endpoint is preserved");
var oldLocal = new ProxySnapshot { Owned = owned, Before = new(owned) };
Check(SystemProxy.RestorePlan(oldLocal, _ => true)["ProxyEnable"].Value == "0", "Never restore own stopped proxy as the previous proxy");
oldLocal.Before["ProxyServer"] = new() { Exists = true, Value = "127.0.0.1:7890" };
Check(SystemProxy.RestorePlan(oldLocal, _ => false)["ProxyEnable"].Value == "0", "Dead previous loopback proxy disabled");
Check(SystemProxy.RestorePlan(oldLocal, _ => true)["ProxyEnable"].Value == "1", "Live previous loopback proxy restored");
oldLocal.Before["ProxyServer"] = new() { Exists = true, Value = "proxy.example.org:8080" };
Check(SystemProxy.RestorePlan(oldLocal, _ => false)["ProxyEnable"].Value == "1", "Remote prior proxy does not depend on a local reachability test");
var configPath = Path.GetTempFileName();
try
{
    File.WriteAllText(configPath, "mode: rule\nipv6: false\nrules:\n  - DOMAIN,example.org,DIRECT\n  - MATCH,Proxy\nproxy-groups:\n  - name: Proxy\n    type: select\n    proxies: [DIRECT]\ndns:\n  enable: true\n  nameserver: [1.1.1.1]\n");
    p.Mode = "config"; p.ConfigPath = configPath;
    var config = JsonNode.Parse(ConfigBuilder.Build(p, "secret"))!;
    Check(config["dns"]!["nameserver"]![0]!.ToString() == "1.1.1.1" && !config["ipv6"]!.GetValue<bool>() && config["rules"]!.AsArray().Count == 2, "User DNS routes and IPv6 choice preserved");
    Check(File.ReadAllText(configPath).StartsWith("mode: rule"), "Original configuration remains unchanged");
}
finally { File.Delete(configPath); }
if (args.Contains("--integration"))
{
    await StartupChecks.Run(Check);
    var testPath = Path.GetFullPath(Path.Combine("windows", "test-runtime"));
    Environment.SetEnvironmentVariable("WBOX_DATA_DIR", testPath);
    // Store was initialized above; use node mode and avoid altering the real system proxy.
    p.Mode = "nodes"; p.SystemProxy = false;
    p.Nodes.Add(NodeParser.Parse("trojan://password@example.org:443?sni=example.org#Trojan"));
    p.Nodes.Add(NodeParser.Parse("hysteria2://password@example.org:443?sni=example.org#HY2"));
    p.Nodes.Add(NodeParser.Parse("tuic://99b91b8e-d4f1-459f-89d5-819553d4a9c3:password@example.org:443?sni=example.org#TUIC"));
    foreach (var kernel in new[] { "mihomo", "sing-box" })
    {
        p.Core = kernel;
        using var manager = new CoreManager(); manager.Log += Console.WriteLine;
        await manager.StartAsync(p);
        Check(manager.Running, kernel + " official core launch");
        var groups = await manager.GroupsAsync();
        Check(groups.Any(g => g.Name == "Wbox" && g.Nodes.Count == 6), kernel + " proxy selector API");
        await manager.SelectAsync("Wbox", ConfigBuilder.Tag(p.Nodes[1], 1));
        groups = await manager.GroupsAsync();
        Check(groups.First(g => g.Name == "Wbox").Selected == ConfigBuilder.Tag(p.Nodes[1], 1), kernel + " node switch API");
        var stats = await manager.StatsAsync();
        Check(stats.Memory > 0 && stats.Inbound == 0 && stats.Uploaded == 0, kernel + " connection and actual memory API");
        using var unauthorized = new System.Net.Http.HttpClient(new System.Net.Http.SocketsHttpHandler { UseProxy = false });
        var denied = await unauthorized.GetAsync("http://127.0.0.1:19090/proxies");
        Check((int)denied.StatusCode == 401, kernel + " controller denies missing secret");
        manager.Stop(); Check(!manager.Running, kernel + " core stopped");
    }
    // Local HTTP traffic verifies the packaged proxy path and counters without depending on a user's remote account.
    foreach (var kernel in new[] { "mihomo", "sing-box" })
    {
        var directPath = Path.Combine(Store.DirectoryPath, "direct-" + kernel + "." + (kernel == "mihomo" ? "yaml" : "json"));
        Directory.CreateDirectory(Store.DirectoryPath);
        File.WriteAllText(directPath, kernel == "mihomo" ? "mode: rule\nrules: [MATCH,DIRECT]\n" : "{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"DIRECT\"}],\"route\":{\"final\":\"DIRECT\"}}");
        // YAML flow sequence MATCH,DIRECT must be a single string rule.
        if (kernel == "mihomo") File.WriteAllText(directPath, "mode: rule\nrules:\n  - MATCH,DIRECT\n");
        p.Core = kernel; p.Mode = "config"; p.ConfigPath = directPath;
        using var manager = new CoreManager();
        await manager.StartAsync(p);
        var server = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 18080); server.Start();
        var ready = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var serving = Task.Run(async () =>
        {
            using var socket = await server.AcceptTcpClientAsync(); using var stream = socket.GetStream();
            var request = new byte[4096]; await stream.ReadAsync(request);
            var first = Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Length: 4096\r\nConnection: close\r\n\r\n" + new string('W', 1024));
            await stream.WriteAsync(first); await stream.FlushAsync(); ready.SetResult(true);
            await release.Task; await stream.WriteAsync(Encoding.ASCII.GetBytes(new string('W', 3072))); await stream.FlushAsync();
        });
        try
        {
            using var handler = new System.Net.Http.SocketsHttpHandler { Proxy = new System.Net.WebProxy("http://127.0.0.1:10808"), UseProxy = true };
            using var client = new System.Net.Http.HttpClient(handler) { Timeout = TimeSpan.FromSeconds(8) };
            var transfer = client.GetStringAsync("http://127.0.0.1:18080/");
            await ready.Task.WaitAsync(TimeSpan.FromSeconds(6)); await Task.Delay(80);
            var active = await manager.StatsAsync(); Check(active.Inbound > 0 && active.Outbound > 0, kernel + " active inbound/outbound connection counters");
            release.SetResult(true); var body = await transfer; await serving;
            Check(body.Length == 4096 && body.All(c => c == 'W'), kernel + " mixed HTTP proxy transfers bytes");
            var stats = await manager.StatsAsync(); Check(stats.Downloaded >= 4096 && stats.Uploaded > 0, kernel + " session upload/download byte counters");
            using var delayCancellation = new CancellationTokenSource();
            var delayServing = Task.Run(async () =>
            {
                try
                {
                    while (!delayCancellation.IsCancellationRequested)
                    {
                        using var socket = await server.AcceptTcpClientAsync(delayCancellation.Token); using var stream = socket.GetStream();
                        var request = new byte[4096]; await stream.ReadAsync(request, delayCancellation.Token);
                        await stream.WriteAsync(Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: close\r\n\r\nW"));
                    }
                }
                catch (OperationCanceledException) { }
            });
            int delay = 0;
            try
            {
                if (kernel == "mihomo") delay = await manager.DelayAsync("DIRECT", "http://127.0.0.1:18080/");
                else
                {
                    bool rejected = false;
                    try { await manager.DelayAsync("DIRECT", "http://127.0.0.1:18080/"); }
                    catch (ArgumentException) { rejected = true; }
                    Check(rejected, "sing-box HTTP delay URL rejected without silent upstream fallback");
                }
            }
            finally { delayCancellation.Cancel(); await delayServing; }
            if (kernel == "mihomo") Check(delay >= 0 && delay < 8000, kernel + " custom URL delay API");
            var ipv6Server = new System.Net.Sockets.TcpListener(System.Net.IPAddress.IPv6Loopback, 18081); ipv6Server.Start();
            try
            {
                var ipv6Serving = Task.Run(async () =>
                {
                    using var socket = await ipv6Server.AcceptTcpClientAsync(); using var stream = socket.GetStream();
                    var request = new byte[4096]; await stream.ReadAsync(request);
                    await stream.WriteAsync(Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nIPv6"));
                });
                var ipv6Body = await client.GetStringAsync("http://[::1]:18081/"); await ipv6Serving;
                Check(ipv6Body == "IPv6", kernel + " IPv6 destination through mixed proxy");
            }
            finally { ipv6Server.Stop(); }
        }
        finally { release.TrySetResult(true); server.Stop(); manager.Stop(); File.Delete(directPath); }
    }
}
Console.WriteLine($"{checks} checks passed.");
}
catch (Exception error)
{
    Console.Error.WriteLine(error);
    Environment.ExitCode = 1;
}
