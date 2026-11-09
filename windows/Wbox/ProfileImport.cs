namespace Wbox;

public static class ProfileImport
{
    public const string MihomoUserAgent = "clash.meta/1.19.33";
    public static string DetectCore(string text)
    {
        text = text.TrimStart('\uFEFF', ' ', '\r', '\n', '\t');
        var root = text.StartsWith('{') ? JsonNode.Parse(text, documentOptions: new() { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip })!.AsObject() : ConfigBuilder.ParseYaml(text);
        if (root.ContainsKey("outbounds") || root.ContainsKey("inbounds") || root.ContainsKey("endpoints")) return "sing-box";
        if (new[] { "proxies", "proxy-providers", "proxy-groups", "rules", "mixed-port" }.Any(root.ContainsKey)) return "mihomo";
        throw new FormatException("需要完整的 sing-box JSON 或 mihomo YAML 配置；节点订阅请在节点工作区导入。");
    }
    public static async Task<string> FetchAsync(string address, string agent = MihomoUserAgent, CancellationToken cancellation = default)
    {
        if (!Uri.TryCreate(address.Trim(), UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https")) throw new ArgumentException("请输入 HTTP / HTTPS 地址。");
        using var client = new HttpClient(new SocketsHttpHandler { AutomaticDecompression = System.Net.DecompressionMethods.All, ConnectCallback = ConnectAsync }) { Timeout = Timeout.InfiniteTimeSpan };
        client.DefaultRequestHeaders.UserAgent.ParseAdd(agent);
        for (int attempt = 0; ; attempt++) {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            timeout.CancelAfter(TimeSpan.FromSeconds(30));
            try {
                using var response = await client.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
                int status = (int)response.StatusCode;
                if (status is 502 or 503 or 504 && attempt == 0) { await Task.Delay(750, cancellation); continue; }
                if (!response.IsSuccessStatusCode) throw new IOException(status is 502 or 503 or 504
                    ? $"订阅请求返回 HTTP {status}，重试后仍失败。可能是订阅服务或当前代理的上游故障，请稍后重试或更换网络。"
                    : $"订阅服务器返回 HTTP {status}。请检查订阅地址、有效期或访问限制。");
                return await ReadAsync(response, timeout.Token);
            } catch (OperationCanceledException) when (!cancellation.IsCancellationRequested) {
                if (attempt > 0) throw new IOException("订阅下载超时（每次最多 30 秒，已重试一次）。请检查当前网络、代理与订阅服务。");
            } catch (HttpRequestException) {
                if (attempt > 0) throw new IOException("无法连接订阅服务，已重试一次。请检查 DNS、TLS、当前网络与系统代理。");
            }
            await Task.Delay(750, cancellation);
        }
    }
    // Try both address families with a short stagger. An unreachable IPv6 route
    // must not consume the full request deadline before IPv4 gets a chance.
    static async ValueTask<Stream> ConnectAsync(SocketsHttpConnectionContext context, CancellationToken cancellation)
    {
        var endpoint=context.DnsEndPoint;
        var addresses=await System.Net.Dns.GetHostAddressesAsync(endpoint.Host,cancellation);
        var v6=new Queue<System.Net.IPAddress>(addresses.Where(a=>a.AddressFamily==System.Net.Sockets.AddressFamily.InterNetworkV6));
        var v4=new Queue<System.Net.IPAddress>(addresses.Where(a=>a.AddressFamily==System.Net.Sockets.AddressFamily.InterNetwork));
        var ordered=new List<System.Net.IPAddress>();
        while(v6.Count+v4.Count>0){if(v6.TryDequeue(out var a))ordered.Add(a);if(v4.TryDequeue(out var b))ordered.Add(b);}
        using var race=CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        async Task<Stream> Connect(System.Net.IPAddress address,int index) {
            await Task.Delay(index*250,race.Token);
            var socket=new System.Net.Sockets.Socket(address.AddressFamily,System.Net.Sockets.SocketType.Stream,System.Net.Sockets.ProtocolType.Tcp){NoDelay=true};
            try{await socket.ConnectAsync(address,endpoint.Port,race.Token);return new System.Net.Sockets.NetworkStream(socket,ownsSocket:true);}
            catch{socket.Dispose();throw;}
        }
        var attempts=ordered.Select((a,i)=>Connect(a,i)).ToList();Exception? last=null;
        try {
            while(attempts.Count>0){var done=await Task.WhenAny(attempts);attempts.Remove(done);try{return await done;}catch(Exception error){last=error;cancellation.ThrowIfCancellationRequested();}}
            throw new HttpRequestException("连接订阅服务器失败。",last);
        } finally {
            race.Cancel();foreach(var attempt in attempts)try{(await attempt).Dispose();}catch{ }
        }
    }
    static async Task<string> ReadAsync(HttpResponseMessage response, CancellationToken cancellation)
    {
        const int limit = 4 * 1024 * 1024;
        if (response.Content.Headers.ContentLength > limit) throw new IOException("配置超过 4 MiB。");
        using var input = await response.Content.ReadAsStreamAsync(cancellation); using var output = new MemoryStream(); var buffer = new byte[8192];
        int read; while ((read = await input.ReadAsync(buffer, cancellation)) > 0) { if (output.Length + read > limit) throw new IOException("配置超过 4 MiB。"); output.Write(buffer, 0, read); }
        return Encoding.UTF8.GetString(output.GetBuffer(), 0, (int)output.Length).TrimStart('\uFEFF');
    }
    public static ConfigProfile SaveConfig(Preferences p, string text, string name, string url = "", ConfigProfile? existing = null)
    {
        var engine = DetectCore(text);
        var profile = existing ?? new ConfigProfile();
        var directory = Path.Combine(Store.DirectoryPath, "profiles", profile.Id); Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, engine == "sing-box" ? "config.json" : "config.yaml");
        File.WriteAllText(path + ".tmp", text); File.Move(path + ".tmp", path, true);
        profile.Core = engine; profile.Path = path; profile.Name = name; profile.Url = url; profile.Updated = DateTime.Now;
        if (existing == null) p.Configs.Add(profile);
        SubscriptionRefresh.Normalize(p);
        if(profile.Subscription!=null){profile.Subscription.LastAttempt=DateTime.UtcNow;profile.Subscription.LastSuccess=DateTime.UtcNow;profile.Subscription.Error="";}
        return profile;
    }
}
