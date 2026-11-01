using System.Text.Json;
using System.Text.Json.Nodes;
using Wbox;

static class NodeEditingChecks
{
    public static void Run(Action<bool, string> check)
    {
        static NodeRecord Copy(NodeRecord n) => JsonSerializer.Deserialize<NodeRecord>(JsonSerializer.Serialize(n))!;
        static bool Rejected(NodeRecord original, NodeRecord edited) {
            try { NodeEditing.Prepare(original, edited); return false; } catch (FormatException) { return true; }
        }
        var original = NodeParser.Parse("vless://99b91b8e-d4f1-459f-89d5-819553d4a9c3@example.org:443?security=reality&pbk=Q2FgNiSGAlK14obBNanOfIlu71vuoTxaJMqpWWdTEFg&sid=dafe&fp=chrome&flow=xtls-rprx-vision#Reality");
        original.SourceSubscription = "https://example.org/subscription";
        original.Options["alpn"] = "h2,http/1.1";
        var revision = NodeEditing.Revision(original);
        var edited = Copy(original); edited.Name = "Renamed"; edited.Server = "new.example.org"; edited.SourceSubscription = "";
        var next = NodeEditing.Prepare(original, edited);
        check(next.Name == "Renamed" && next.Server == "new.example.org" && next.RealityKey == original.RealityKey && next.Options["alpn"] == original.Options["alpn"] && next.SourceSubscription == original.SourceSubscription, "Node editor preserves advanced parameters and subscription ownership");
        check(NodeEditing.Revision(next) != revision && NodeEditing.Revision(original) == revision, "Node revisions detect modifications without mutating original");
        edited.Port = 65536;
        check(Rejected(original, edited) && original.Port == 443, "Invalid edited port leaves original unchanged");
        edited = Copy(original); edited.Uuid = "invalid";
        check(Rejected(original, edited), "Node editor rejects invalid UUID");
        edited = Copy(original); edited.Protocol = "trojan";
        check(Rejected(original, edited), "Node editor rejects protocol substitution");
        edited = Copy(original); edited.NativeSingbox = new JsonObject();
        check(Rejected(original, edited), "Node editor rejects hidden native override");
        foreach (bool sb in new[] { true, false }) {
            var native = new JsonObject { ["type"] = sb ? "shadowsocks" : "ss", [sb ? "tag" : "name"] = "Native", ["server"] = "example.org", [sb ? "server_port" : "port"] = 8388, [sb ? "method" : "cipher"] = "aes-128-gcm", ["password"] = "synthetic", [sb ? "udp_over_tcp" : "udp"] = true };
            var node = NodeParser.ParseSubscription(new JsonObject { [sb ? "outbounds" : "proxies"] = new JsonArray(native) }.ToJsonString())[0];
            edited = Copy(node); (sb ? edited.NativeSingbox! : edited.NativeMihomo!)[sb ? "tag" : "name"] = "Edited native";
            next = NodeEditing.Prepare(node, edited);
            check(next.Name == "Edited native" && (sb ? next.NativeSingbox! : next.NativeMihomo!)[sb ? "udp_over_tcp" : "udp"]!.GetValue<bool>(), (sb ? "sing-box" : "mihomo") + " native editor preserves protocol-specific fields");
        }
    }
}
