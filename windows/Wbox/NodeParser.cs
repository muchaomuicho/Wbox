using YamlDotNet.Serialization;
namespace Wbox;

public static class NodeParser
{
    static string Decode64(string value)
    {
        value = value.Trim().Replace('-', '+').Replace('_', '/');
        return Encoding.UTF8.GetString(Convert.FromBase64String(value.PadRight((value.Length + 3) / 4 * 4, '=')));
    }
    static Dictionary<string, string> Query(string query) => query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries)
        .Select(s => s.Split('=', 2)).GroupBy(p => Uri.UnescapeDataString(p[0])).ToDictionary(g => g.Key, g => g.Last().Length == 2 ? Uri.UnescapeDataString(g.Last()[1]) : "");
    public static NodeRecord Parse(string input)
    {
        var text = input.Trim();
        if (text.StartsWith("vmess://", StringComparison.OrdinalIgnoreCase))
        {
            var j = JsonNode.Parse(Decode64(text[8..]))!.AsObject();
            string Read(string key, string fallback = "") => j[key]?.ToString() ?? fallback;
            var n = new NodeRecord { Protocol = "vmess", Name = Read("ps", "VMess"), Server = Read("add"), Port = int.Parse(Read("port")), Uuid = Read("id"), Cipher = Read("scy", "auto"), Network = Read("net", "tcp"), Path = Read("path", "/"), Host = Read("host"), Tls = Read("tls") == "tls", Sni = Read("sni"), Fingerprint = Read("fp") };
            int.TryParse(Read("aid"), out var aid); n.AlterId = aid;
            Validate(n); return n;
        }
        if (text.StartsWith("ss://", StringComparison.OrdinalIgnoreCase))
        {
            var body = text[5..];
            var hash = body.IndexOf('#'); var name = hash >= 0 ? Uri.UnescapeDataString(body[(hash + 1)..]) : "Shadowsocks";
            if (hash >= 0) body = body[..hash];
            var q = body.IndexOf('?');
            if (q >= 0 && Query(body[(q + 1)..]).TryGetValue("plugin", out var plugin) && plugin.Length > 0)
                throw new FormatException("SS plugin 分享链接请通过完整 JSON/YAML 配置导入。");
            if (q >= 0) body = body[..q];
            body = body.TrimEnd('/');
            if (!body.Contains('@')) body = Decode64(body);
            var at = body.LastIndexOf('@'); if (at < 0) throw new FormatException("SS 链接缺少服务器地址。");
            var credential = body[..at]; if (!credential.Contains(':')) credential = Decode64(credential);
            var split = credential.IndexOf(':'); if (split < 0) throw new FormatException("SS 链接缺少加密方式。");
            var endpoint = new Uri("http://" + body[(at + 1)..]);
            var node = new NodeRecord { Protocol = "ss", Name = name, Cipher = Uri.UnescapeDataString(credential[..split]), Password = Uri.UnescapeDataString(credential[(split + 1)..]), Server = endpoint.Host.Trim('[', ']'), Port = endpoint.Port };
            Validate(node); return node;
        }
        if (!Uri.TryCreate(text, UriKind.Absolute, out var uri)) throw new FormatException("无法识别节点链接。");
        var protocol = uri.Scheme.ToLowerInvariant();
        if (protocol == "hy2") protocol = "hysteria2";
        if (protocol is not ("vless" or "trojan" or "hysteria2" or "tuic")) throw new FormatException($"暂不支持 {protocol} 分享链接，可导入内核原生配置。");
        var options = Query(uri.Query);
        string Opt(string k, string fallback = "") => options.GetValueOrDefault(k, fallback);
        var userinfo = Uri.UnescapeDataString(uri.UserInfo);
        var result = new NodeRecord { Protocol = protocol, Name = uri.Fragment.Length > 1 ? Uri.UnescapeDataString(uri.Fragment[1..]) : protocol, Server = uri.Host.Trim('[', ']'), Port = uri.IsDefaultPort || uri.Port < 1 ? 443 : uri.Port, Uuid = userinfo.Split(':', 2)[0], Password = protocol == "tuic" ? userinfo.Split(':', 2).ElementAtOrDefault(1) ?? "" : userinfo, Tls = protocol != "vless" || Opt("security") is "tls" or "reality", Sni = Opt("sni", Opt("peer")), Network = Opt("type", "tcp"), Path = Opt("path", "/"), Host = Opt("host"), Flow = Opt("flow"), Insecure = Opt("allowInsecure", Opt("insecure")) == "1", RealityKey = Opt("pbk"), RealityShortId = Opt("sid"), Fingerprint = Opt("fp") };
        if (protocol == "hysteria2" && options.TryGetValue("obfs", out var obfs) && obfs.Length > 0)
            throw new FormatException("带 obfs 的 Hysteria2 分享链接请通过原生配置导入，避免丢失混淆设置。");
        if (protocol == "tuic" && (!string.IsNullOrEmpty(Opt("congestion_control")) || !string.IsNullOrEmpty(Opt("udp_relay_mode"))))
            throw new FormatException("带自定义 TUIC 参数的分享链接请通过原生配置导入。");
        Validate(result); return result;
    }
    public static void Validate(NodeRecord node)
    {
        if (string.IsNullOrWhiteSpace(node.Server) || node.Port is < 1 or > 65535) throw new FormatException("节点地址或端口无效。");
        if (node.Protocol is "vmess" or "vless" or "tuic" && !Guid.TryParse(node.Uuid, out _)) throw new FormatException("节点 UUID 无效。");
        if (node.Network is not ("tcp" or "ws" or "grpc" or "http" or "h2")) throw new FormatException($"暂不支持 {node.Network} 分享传输，请导入完整配置。");
    }
    public static List<NodeRecord> ParseSubscription(string text)
    {
        text = text.Trim();
        if (text.StartsWith('{') || text.StartsWith('['))
        {
            var json = JsonNode.Parse(text);
            if (json is JsonObject obj && obj["outbounds"] is JsonArray outbounds) return NativeNodes(outbounds, true);
            if (json is JsonObject m && m["proxies"] is JsonArray proxies) return NativeNodes(proxies, false);
            throw new FormatException("JSON 订阅需要包含 sing-box outbounds 或 mihomo proxies。");
        }
        if (text.Contains("proxies:") || text.Contains("proxy-providers:"))
        {
            var yaml = ConfigBuilder.ParseYaml(text);
            if (yaml["proxies"] is not JsonArray proxies || proxies.Count == 0) throw new FormatException("此 YAML 使用远程 provider，请作为完整配置文件导入。");
            return NativeNodes(proxies, false);
        }
        if (!text.Contains("://")) text = Decode64(text);
        var lines = text.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries).Where(l => !l.TrimStart().StartsWith('#')).ToArray();
        var nodes = new List<NodeRecord>();
        foreach (var line in lines) nodes.Add(Parse(line));
        if (nodes.Count == 0) throw new FormatException("订阅中没有有效节点。");
        return nodes;
    }
    static List<NodeRecord> NativeNodes(JsonArray array, bool singbox)
    {
        var list = new List<NodeRecord>();
        foreach (var o in array.OfType<JsonObject>())
        {
            var type = o["type"]?.ToString() ?? "";
            if (type is "direct" or "block" or "dns" or "selector" or "urltest") continue;
            var server = o["server"]?.ToString(); if (string.IsNullOrEmpty(server)) continue;
            var n = new NodeRecord { Name = o[singbox ? "tag" : "name"]?.ToString() ?? type, Protocol = type == "shadowsocks" ? "ss" : type, Server = server, Port = int.Parse(o[singbox ? "server_port" : "port"]!.ToString()), Password = o["password"]?.ToString() ?? "", Uuid = o["uuid"]?.ToString() ?? "", Cipher = o[singbox ? "method" : "cipher"]?.ToString() ?? "auto" };
            if (singbox) n.NativeSingbox = (JsonObject)o.DeepClone(); else n.NativeMihomo = (JsonObject)o.DeepClone();
            list.Add(n);
        }
        if (list.Count == 0) throw new FormatException("配置中没有可导入的代理节点。");
        return list;
    }
}
