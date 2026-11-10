namespace Wbox;

public static class NodeOptions
{
    public static bool Compatible(NodeRecord n, string core) => core == "sing-box" ? n.NativeMihomo == null && !(n.Protocol == "socks" && n.Tls) : n.NativeSingbox == null && n.Protocol != "naive";
    static JsonArray Strings(string value) => new(value.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).Select(s => (JsonNode?)JsonValue.Create(s)).ToArray());
    public static void Apply(NodeRecord n, JsonObject o, bool sb)
    {
        string Get(string k, string d = "") => n.Options.GetValueOrDefault(k, d);
        int Number(string k, int d) => int.TryParse(Get(k), out var v) ? v : d;
        if (n.Protocol is "http" or "socks" or "naive") { o["username"] = n.Username; o["password"] = n.Password; if (n.Protocol == "socks") o[sb ? "version" : "type"] = sb ? "5" : "socks5"; }
        if (n.Protocol == "naive") { if (!sb) throw new InvalidOperationException("NaiveProxy 请使用 sing-box 内核。"); o["quic"] = Get("quic") == "true"; }
        if (n.Protocol == "anytls") o["password"] = n.Password;
        if (Get("alpn").Length > 0) { if (sb && o["tls"] is JsonObject tls) tls["alpn"] = Strings(Get("alpn")); else if (!sb) o["alpn"] = Strings(Get("alpn")); }
        if (n.Protocol == "hysteria2" && Get("obfs").Length > 0) { if (Get("obfs") != "salamander" || Get("obfs-password").Length == 0) throw new FormatException("Hysteria2 混淆需要 salamander 和 obfs-password。"); if (sb) o["obfs"] = new JsonObject { ["type"] = "salamander", ["password"] = Get("obfs-password") }; else { o["obfs"] = "salamander"; o["obfs-password"] = Get("obfs-password"); } }
        if (n.Protocol == "hysteria") { o[sb ? "auth_str" : "auth-str"] = n.Password; o[sb ? "up_mbps" : "up"] = Number("upmbps", Number("up", 100)); o[sb ? "down_mbps" : "down"] = Number("downmbps", Number("down", 100)); if (Get("obfsParam", Get("obfs")).Length > 0) o["obfs"] = Get("obfsParam", Get("obfs")); }
        if (n.Protocol == "tuic") { o[sb ? "congestion_control" : "congestion-controller"] = Get("congestion_control", Get("congestion-controller", "cubic")); o[sb ? "udp_relay_mode" : "udp-relay-mode"] = Get("udp_relay_mode", Get("udp-relay-mode", "native")); }
        if (n.Protocol == "ss" && Get("plugin").Length > 0)
        {
            var parts = Get("plugin").Split(';'); var name = parts[0] == "simple-obfs" ? "obfs-local" : parts[0];
            if (sb) { o["plugin"] = name; o["plugin_opts"] = string.Join(';', parts.Skip(1)); }
            else { o["plugin"] = name == "obfs-local" ? "obfs" : name; var opts = new JsonObject(); foreach (var part in parts.Skip(1)) { var kv = part.Split('=', 2); var key = kv[0] switch { "obfs" => "mode", "obfs-host" => "host", "obfs-uri" => "path", _ => kv[0] }; opts[key] = kv.Length == 2 ? JsonValue.Create(kv[1]) : JsonValue.Create(true); } if (name == "v2ray-plugin") opts["mode"] ??= "websocket"; o["plugin-opts"] = opts; }
        }
        if (n.Protocol != "wireguard") return;
        if (sb && Get("dns").Length > 0) throw new InvalidOperationException("WireGuard 分享链接的 dns 参数请使用 mihomo，或导入完整 sing-box 配置。");
        var addresses = Strings(Get("address"));
        JsonArray? reserved = null; if (Get("reserved").Length > 0) reserved = new JsonArray(Get("reserved").Split(',').Select(s => (JsonNode?)JsonValue.Create(byte.Parse(s))).ToArray());
        if (sb)
        {
            o.Clear(); o["type"] = "wireguard"; o["system"] = false; o["mtu"] = Number("mtu", 1408); o["address"] = addresses; o["private_key"] = n.Password;
            var peer = new JsonObject { ["address"] = n.Server, ["port"] = n.Port, ["public_key"] = Get("publickey"), ["allowed_ips"] = new JsonArray("0.0.0.0/0", "::/0") };
            if (Get("presharedkey").Length > 0) peer["pre_shared_key"] = Get("presharedkey"); if (reserved != null) peer["reserved"] = reserved;
            o["peers"] = new JsonArray(peer);
        }
        else
        {
            o["private-key"] = n.Password; o["public-key"] = Get("publickey"); o["mtu"] = Number("mtu", 1408);
            foreach (var a in addresses) { var value = a!.ToString(); o[value.Contains(':') ? "ipv6" : "ip"] = value.Split('/')[0]; }
            if (Get("presharedkey").Length > 0) o["pre-shared-key"] = Get("presharedkey"); if (reserved != null) o["reserved"] = reserved;
            if (Get("dns").Length > 0) { o["dns"] = Strings(Get("dns")); o["remote-dns-resolve"] = true; }
        }
    }
}
