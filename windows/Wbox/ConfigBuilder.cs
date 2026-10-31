using YamlDotNet.Serialization;
namespace Wbox;

public static class ConfigBuilder
{
    public const int MixedPort = 10808;
    public const int ControllerPort = 19090;
    public static JsonObject ParseYaml(string text)
    {
        var deserializer = new DeserializerBuilder().WithAttemptingUnquotedStringTypeDeserialization().Build();
        var yaml = deserializer.Deserialize<object>(text);
        var serializer = new SerializerBuilder().JsonCompatible().Build();
        return JsonNode.Parse(serializer.Serialize(yaml))?.AsObject() ?? throw new FormatException("YAML 根节点必须为对象。");
    }
    public static string SerializeYaml(JsonObject value)
    {
        // JSON is a YAML 1.2 subset accepted by mihomo and preserves scalar types without double conversion.
        return value.ToJsonString(Store.JsonOptions);
    }
    public static string Build(Preferences preferences, string secret)
    {
        JsonObject root;
        bool singbox = preferences.Core == "sing-box";
        if (preferences.Mode == "config")
        {
            if (!File.Exists(preferences.ConfigPath)) throw new FileNotFoundException("请先选择配置文件。");
            var content = File.ReadAllText(preferences.ConfigPath);
            root = singbox ? JsonNode.Parse(content, documentOptions: new() { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip })!.AsObject() : ParseYaml(content);
        }
        else
        {
            if (preferences.Nodes.Count == 0) throw new InvalidOperationException("请先导入节点或订阅。");
            root = singbox ? SingboxNodes(preferences) : MihomoNodes(preferences);
        }
        if (singbox)
        {
            var experimental = root["experimental"] as JsonObject ?? new JsonObject(); root["experimental"] = experimental.DeepClone();
            var e = root["experimental"]!.AsObject();
            var clash = e["clash_api"] as JsonObject ?? new JsonObject(); e["clash_api"] = clash.DeepClone();
            e["clash_api"]!["external_controller"] = $"127.0.0.1:{ControllerPort}";
            e["clash_api"]!["secret"] = secret;
            // Keep all user inbounds; add a dedicated loopback inbound only when our port is not occupied by an existing mixed inbound.
            var inbounds = root["inbounds"] as JsonArray ?? new JsonArray(); root["inbounds"] = inbounds.DeepClone();
            var a = root["inbounds"]!.AsArray();
            var existing = a.OfType<JsonObject>().FirstOrDefault(i => i["listen_port"]?.ToString() == MixedPort.ToString());
            if (existing != null && existing["type"]?.ToString() != "mixed") throw new InvalidOperationException("配置的 10808 端口已被非 mixed 入站使用，请修改原配置端口。");
            if (existing == null) a.Add(new JsonObject { ["type"] = "mixed", ["tag"] = "wbox-mixed", ["listen"] = "127.0.0.1", ["listen_port"] = MixedPort });
            else { existing["listen"] = "127.0.0.1"; existing.Remove("users"); }
        }
        else
        {
            root["external-controller"] = $"127.0.0.1:{ControllerPort}"; root["secret"] = secret;
            root.Remove("external-controller-tls"); root.Remove("external-controller-unix"); root.Remove("external-controller-pipe");
            root["mixed-port"] = MixedPort; root["allow-lan"] = false; root["bind-address"] = "127.0.0.1";
            root.Remove("authentication");
            root["ipv6"] ??= true;
        }
        return root.ToJsonString(Store.JsonOptions);
    }
    static JsonObject SingboxNodes(Preferences p)
    {
        var proxies = new JsonArray(); var names = new JsonArray(); var endpoints = new JsonArray();
        if (!NodeOptions.Compatible(p.Nodes[Math.Clamp(p.SelectedNode, 0, p.Nodes.Count - 1)], p.Core)) throw new InvalidOperationException("所选节点不支持此内核，请切换内核或节点。");
        for (int i = 0; i < p.Nodes.Count; i++) { if (!NodeOptions.Compatible(p.Nodes[i], p.Core)) continue; var tag = Tag(p.Nodes[i], i); names.Add(tag); var outbound = SingboxOutbound(p.Nodes[i], tag); if (p.Nodes[i].Protocol == "wireguard") endpoints.Add(outbound); else proxies.Add(outbound); }
        proxies.Insert(0, new JsonObject { ["type"] = "selector", ["tag"] = "Wbox", ["outbounds"] = names, ["default"] = Tag(p.Nodes[Math.Clamp(p.SelectedNode, 0, p.Nodes.Count - 1)], Math.Clamp(p.SelectedNode, 0, p.Nodes.Count - 1)), ["interrupt_exist_connections"] = true });
        proxies.Add(new JsonObject { ["type"] = "direct", ["tag"] = "DIRECT" });
        return new JsonObject { ["log"] = new JsonObject { ["level"] = "warn", ["timestamp"] = true }, ["dns"] = new JsonObject { ["servers"] = new JsonArray(new JsonObject { ["type"] = "local", ["tag"] = "local" }), ["final"] = "local" }, ["endpoints"] = endpoints, ["outbounds"] = proxies, ["route"] = new JsonObject { ["final"] = "Wbox", ["auto_detect_interface"] = true, ["default_domain_resolver"] = "local" } };
    }
    static JsonObject MihomoNodes(Preferences p)
    {
        var proxies = new JsonArray(); var names = new JsonArray();
        if (!NodeOptions.Compatible(p.Nodes[Math.Clamp(p.SelectedNode, 0, p.Nodes.Count - 1)], p.Core)) throw new InvalidOperationException("所选节点不支持此内核，请切换内核或节点。");
        for (int i = 0; i < p.Nodes.Count; i++) { if (!NodeOptions.Compatible(p.Nodes[i], p.Core)) continue; var name = Tag(p.Nodes[i], i); names.Add(name); proxies.Add(MihomoProxy(p.Nodes[i], name)); }
        var selected = Math.Clamp(p.SelectedNode, 0, p.Nodes.Count - 1);
        var selectedName = Tag(p.Nodes[selected], selected); var idx = names.Select(n => n!.ToString()).ToList().IndexOf(selectedName);
        if (idx > 0) { names.RemoveAt(idx); names.Insert(0, JsonValue.Create(selectedName)); }
        return new JsonObject { ["mode"] = "rule", ["log-level"] = "warning", ["ipv6"] = true, ["proxies"] = proxies, ["proxy-groups"] = new JsonArray(new JsonObject { ["name"] = "Wbox", ["type"] = "select", ["proxies"] = names }), ["rules"] = new JsonArray("MATCH,Wbox") };
    }
    public static string Tag(NodeRecord n, int i) => $"{n.Name} · {i + 1}";
    public static JsonObject SingboxOutbound(NodeRecord n, string tag)
    {
        if (n.NativeSingbox != null) { var copy = (JsonObject)n.NativeSingbox.DeepClone(); copy["tag"] = tag; return copy; }
        if (n.NativeMihomo != null) throw new InvalidOperationException("此节点来自 mihomo 原生订阅，请选择 mihomo 内核。分享链接节点可在双内核间切换。");
        var o = new JsonObject { ["type"] = n.Protocol == "ss" ? "shadowsocks" : n.Protocol, ["tag"] = tag, ["server"] = n.Server, ["server_port"] = n.Port };
        if (n.Protocol is "ss" or "trojan" or "hysteria2" or "tuic") o["password"] = n.Password;
        if (n.Protocol is "vmess" or "vless" or "tuic") o["uuid"] = n.Uuid;
        if (n.Protocol == "ss") o["method"] = n.Cipher;
        if (n.Protocol == "vmess") { o["security"] = n.Cipher; o["alter_id"] = n.AlterId; }
        if (n.Protocol == "vless" && n.Flow.Length > 0) o["flow"] = n.Flow;
        if (n.Tls)
        {
            var tls = new JsonObject { ["enabled"] = true, ["insecure"] = n.Insecure };
            if (n.Sni.Length > 0) tls["server_name"] = n.Sni;
            if (!string.IsNullOrEmpty(n.Fingerprint)) tls["utls"] = new JsonObject { ["enabled"] = true, ["fingerprint"] = n.Fingerprint };
            if (!string.IsNullOrEmpty(n.RealityKey)) tls["reality"] = new JsonObject { ["enabled"] = true, ["public_key"] = n.RealityKey, ["short_id"] = n.RealityShortId ?? "" };
            o["tls"] = tls;
        }
        if (n.Network != "tcp" && n.Protocol is "vmess" or "vless" or "trojan")
        {
            var transport = new JsonObject { ["type"] = n.Network == "h2" ? "http" : n.Network };
            if (n.Network == "grpc") transport["service_name"] = n.Path.Trim('/');
            else { transport["path"] = n.Path; if (n.Host.Length > 0) { if (n.Network == "ws") transport["headers"] = new JsonObject { ["Host"] = n.Host }; else transport["host"] = new JsonArray(n.Host); } }
            o["transport"] = transport;
        }
        NodeOptions.Apply(n, o, true); o["tag"] = tag;
        return o;
    }
    public static JsonObject MihomoProxy(NodeRecord n, string tag)
    {
        if (n.NativeMihomo != null) { var copy = (JsonObject)n.NativeMihomo.DeepClone(); copy["name"] = tag; return copy; }
        if (n.NativeSingbox != null) throw new InvalidOperationException("此节点来自 sing-box 原生订阅，请选择 sing-box 内核。分享链接节点可在双内核间切换。");
        var o = new JsonObject { ["type"] = n.Protocol, ["name"] = tag, ["server"] = n.Server, ["port"] = n.Port, ["udp"] = true };
        if (n.Protocol is "ss" or "trojan" or "hysteria2" or "tuic") o["password"] = n.Password;
        if (n.Protocol is "vmess" or "vless" or "tuic") o["uuid"] = n.Uuid;
        if (n.Protocol == "ss") o["cipher"] = n.Cipher;
        if (n.Protocol == "vmess") { o["cipher"] = n.Cipher; o["alterId"] = n.AlterId; }
        if (n.Protocol == "vless" && n.Flow.Length > 0) o["flow"] = n.Flow;
        if (n.Tls) { o["tls"] = true; o["skip-cert-verify"] = n.Insecure; if (n.Sni.Length > 0) o[n.Protocol == "vmess" || n.Protocol == "vless" ? "servername" : "sni"] = n.Sni; }
        if (!string.IsNullOrEmpty(n.Fingerprint)) o["client-fingerprint"] = n.Fingerprint;
        if (!string.IsNullOrEmpty(n.RealityKey)) o["reality-opts"] = new JsonObject { ["public-key"] = n.RealityKey, ["short-id"] = n.RealityShortId ?? "" };
        if (n.Network != "tcp" && n.Protocol is "vmess" or "vless" or "trojan")
        {
            o["network"] = n.Network == "http" ? "h2" : n.Network;
            if (n.Network == "ws") o["ws-opts"] = new JsonObject { ["path"] = n.Path, ["headers"] = new JsonObject { ["Host"] = n.Host.Length > 0 ? n.Host : n.Server } };
            else if (n.Network == "grpc") o["grpc-opts"] = new JsonObject { ["grpc-service-name"] = n.Path.Trim('/') };
            else o["h2-opts"] = new JsonObject { ["path"] = n.Path, ["host"] = new JsonArray(n.Host.Length > 0 ? n.Host : n.Server) };
        }
        NodeOptions.Apply(n, o, false);
        return o;
    }
}
