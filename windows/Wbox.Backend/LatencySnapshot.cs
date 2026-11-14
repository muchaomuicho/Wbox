using Wbox;

// A latency request owns its input, independently of the active connection.
internal sealed class LatencySnapshot
{
    public Preferences Preferences { get; }
    public string? Content { get; }
    public string? ProbeContent { get; }
    public IReadOnlyDictionary<string,byte[]> Resources { get; }
    public string Context { get; }
    public List<GroupItem>? Catalog { get; }

    public LatencySnapshot(Preferences preferences, List<GroupItem>? catalog = null)
    {
        Preferences = preferences;
        Content = preferences.Mode == "config" ? File.ReadAllText(preferences.ConfigPath) : null;
        Resources = Content != null ? ProviderResources(preferences,Content) : new Dictionary<string,byte[]>();
        ProbeContent = Content != null ? FixedProviders(preferences,Content,Resources) : null;
        Context = Identity(preferences, Content,Resources);
        Catalog = catalog;
    }

    public static string Identity(Preferences preferences, string? content = null,IReadOnlyDictionary<string,byte[]>? resources = null)
    {
        if(preferences.Mode=="config")content??=File.Exists(preferences.ConfigPath)?File.ReadAllText(preferences.ConfigPath):"";
        var input = preferences.Mode == "config"
            ? preferences.ConfigPath + "|" + content + "|" + string.Join('|',(resources??ProviderResources(preferences,content!)).OrderBy(entry=>entry.Key).Select(entry=>entry.Key+":"+Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(entry.Value))))
            : JsonSerializer.Serialize(preferences.Nodes) + "|" + preferences.SelectedNode;
        var hash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(input)));
        return preferences.Mode + "|" + preferences.Core + "|" + preferences.DelayUrl + "|" + hash;
    }

    public bool IsValid(Preferences next)
    {
        if (next.DelayUrl != Preferences.DelayUrl) return false;
        if (Content != null)
            return next.Configs.Any(profile => profile.Path == Preferences.ConfigPath && profile.Core == Preferences.Core)
                && File.Exists(Preferences.ConfigPath) && File.ReadAllText(Preferences.ConfigPath) == Content
                && Identity(Preferences,Content)==Context;
        var engine = next.NodeCore.Length > 0 ? next.NodeCore : next.Mode == "nodes" ? next.Core : Preferences.Core;
        return engine == Preferences.Core && JsonSerializer.Serialize(next.Nodes) == JsonSerializer.Serialize(Preferences.Nodes);
    }

    static JsonObject Parse(Preferences preferences,string content)=>preferences.Core=="mihomo"?ConfigBuilder.ParseYaml(content):JsonNode.Parse(content,documentOptions:new(){AllowTrailingCommas=true,CommentHandling=JsonCommentHandling.Skip})!.AsObject();
    static IEnumerable<JsonObject> Providers(Preferences preferences,JsonObject root)=>preferences.Core=="mihomo"
        ? new[]{"proxy-providers","rule-providers"}.SelectMany(key=>(root[key] as JsonObject)?.Select(entry=>entry.Value).OfType<JsonObject>()??[])
        : (root["route"]?["rule_set"] as JsonArray)?.OfType<JsonObject>()??[];
    static string? ProviderPath(Preferences preferences,JsonObject root,JsonObject provider) {
        var source=Path.GetDirectoryName(Path.GetFullPath(preferences.ConfigPath))!;
        if(provider["path"]?.ToString() is string path && path.Length>0)return Path.GetFullPath(path,source);
        if(preferences.Core!="mihomo"||provider["type"]?.ToString()!="http"||provider["url"]?.ToString() is not string url)return null;
        var proxies=(root["proxy-providers"] as JsonObject)?.Any(entry=>ReferenceEquals(entry.Value,provider))==true;
        // The pinned mihomo core uses this same URL hash for implicit caches.
        var hash=Convert.ToHexString(System.Security.Cryptography.MD5.HashData(Encoding.UTF8.GetBytes(url))).ToLowerInvariant();
        return Path.Combine(source,proxies?"proxies":"rules",hash);
    }
    static Dictionary<string,byte[]> ProviderResources(Preferences preferences,string content) {
        var files=new Dictionary<string,byte[]>(StringComparer.OrdinalIgnoreCase);
        if(content.Length==0)return files;
        var root=Parse(preferences,content);
        foreach(var provider in Providers(preferences,root)) {
            var path=ProviderPath(preferences,root,provider);
            if(path!=null&&File.Exists(path))files[path]=File.ReadAllBytes(path);
        }
        return files;
    }
    static string FixedProviders(Preferences preferences,string content,IReadOnlyDictionary<string,byte[]> resources) {
        var root=Parse(preferences,content);
        foreach(var provider in Providers(preferences,root)) {
            var path=ProviderPath(preferences,root,provider);
            if(path==null||!resources.ContainsKey(path))continue;
            provider["type"]=preferences.Core=="mihomo"?"file":"local";provider["path"]=path;
            provider.Remove("url");provider.Remove("interval");provider.Remove("update_interval");
            if(provider["health-check"] is JsonObject health)health["enable"]=false;
        }
        return preferences.Core=="mihomo"?ConfigBuilder.SerializeYaml(root):root.ToJsonString(Store.JsonOptions);
    }
}

internal sealed class GroupMeasurements
{
    public System.Collections.Concurrent.ConcurrentDictionary<string, int> Delays { get; } = new();
    public List<GroupItem>? Catalog { get; set; }
}
