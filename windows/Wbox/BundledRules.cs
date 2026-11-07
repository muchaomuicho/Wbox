using System.Security.Cryptography;
namespace Wbox;

public static class BundledRules
{
    public static string Assets => Path.Combine(AppContext.BaseDirectory,"bundled");
    public static void Apply(JsonObject root,bool singbox,string workingDirectory)
    {
        var rules=singbox ? (root["route"]?["rule_set"] as JsonArray)?.OfType<JsonObject>() : (root["rule-providers"] as JsonObject)?.Select(e=>e.Value).OfType<JsonObject>();
        if(rules==null)return;
        var manifest=JsonNode.Parse(File.ReadAllText(Path.Combine(Assets,"rules","manifest.json")))!.AsArray();
        foreach(var rule in rules) {
            if(rule["type"]?.ToString()!=(singbox?"remote":"http"))continue;
            var entry=manifest.OfType<JsonObject>().FirstOrDefault(e=>e["engine"]!.ToString()==(singbox?"sing-box":"mihomo")&&e["url"]!.ToString()==rule["url"]?.ToString()&&(e["sourceFormat"]??e["format"])!.ToString()==(rule["format"]?.ToString()??(singbox?"source":"yaml"))&&(singbox||e["behavior"]!.ToString()==rule["behavior"]?.ToString()));
            if(entry==null)continue;
            var directory=Path.Combine(workingDirectory,".wbox-rules");Directory.CreateDirectory(directory);
            string hash=entry["sha256"]!.ToString(),file=entry["file"]!.ToString();
            var target=Path.Combine(directory,hash[..12]+"-"+file);
            bool Matches(string path){if(!File.Exists(path))return false;using var input=File.OpenRead(path);return Convert.ToHexString(SHA256.HashData(input)).Equals(hash,StringComparison.OrdinalIgnoreCase);}
            if(!Matches(target)) {
                var temp=target+"."+Guid.NewGuid().ToString("N")+".tmp";
                try {File.Copy(Path.Combine(Assets,"rules",file),temp);if(!Matches(temp))throw new IOException("内置规则集校验失败："+file);File.Move(temp,target,true);}
                finally {if(File.Exists(temp))File.Delete(temp);}
            }
            rule["type"]=singbox?"local":"file";rule["path"]=Path.GetFullPath(target);rule["format"]=entry["format"]!.ToString();
            foreach(var key in new[]{"url","download_detour","update_interval","http_client","interval","proxy","header","size-limit"})rule.Remove(key);
        }
    }
}

public static class NodeTemplate
{
    public const string Selected="__WBOX_SELECTED__";
    public static JsonObject Load(bool singbox,string selected)
    {
        var text=File.ReadAllText(Path.Combine(BundledRules.Assets,"templates",singbox?"sing-box.json":"mihomo.yaml"));
        var root=singbox?JsonNode.Parse(text)!.AsObject():ConfigBuilder.ParseYaml(text);
        var groups=root[singbox?"outbounds":"proxy-groups"]!.AsArray();
        foreach(var g in groups.OfType<JsonObject>()) {
            if(g[singbox?"outbounds":"proxies"] is JsonArray names)for(int i=0;i<names.Count;i++)if(names[i]?.ToString()==Selected)names[i]=selected;
            if(g["default"]?.ToString()==Selected)g["default"]=selected;
        }
        return root;
    }
}
