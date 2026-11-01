namespace Wbox;

public static class NodeEditing
{
    public static string Revision(NodeRecord node) => Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(node))));

    public static NodeRecord Prepare(NodeRecord original, NodeRecord edited)
    {
        NodeRecord next;
        if (original.NativeSingbox != null || original.NativeMihomo != null) {
            var native = original.NativeSingbox != null ? edited.NativeSingbox : edited.NativeMihomo;
            if (native == null) throw new FormatException("原生节点内容不能为空。");
            var wrapper = new JsonObject { [original.NativeSingbox != null ? "outbounds" : "proxies"] = new JsonArray(native.DeepClone()) };
            var parsed = NodeParser.ParseSubscription(wrapper.ToJsonString());
            if (parsed.Count != 1) throw new FormatException("请保留一个包含服务器地址的原生节点。");
            next = parsed[0];
        } else {
            next = JsonSerializer.Deserialize<NodeRecord>(JsonSerializer.Serialize(edited))!;
            if (next.NativeSingbox != null || next.NativeMihomo != null || next.Protocol != original.Protocol) throw new FormatException("编辑时不能更换协议，请另行添加节点。");
            NodeParser.Validate(next);
        }
        next.Name = next.Name.Trim(); next.Server = next.Server.Trim();
        if (next.Name.Length == 0 || next.Server.Length == 0 || next.Port is < 1 or > 65535) throw new FormatException("请填写名称、服务器地址和有效端口。");
        next.SourceSubscription = original.SourceSubscription;
        var engine = next.NativeMihomo != null || next.Protocol == "socks" && next.Tls || next.Protocol == "wireguard" && next.Options.GetValueOrDefault("dns", "").Length > 0 ? "mihomo" : "sing-box";
        ConfigBuilder.Build(new Preferences { Mode = "nodes", Core = engine, Nodes = [next] }, "validation");
        return next;
    }
}
