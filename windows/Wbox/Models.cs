namespace Wbox;

public sealed class Preferences
{
    public string Core { get; set; } = "mihomo";
    public string Mode { get; set; } = "config";
    public string ConfigPath { get; set; } = "";
    public string DelayUrl { get; set; } = "https://www.apple.com/library/test/success.html";
    public bool Silent { get; set; }
    public bool AutoConnect { get; set; }
    public bool AutoStart { get; set; }
    public bool SystemProxy { get; set; } = true;
    public bool NativeInbounds { get; set; }
    public int SelectedNode { get; set; }
    public List<NodeRecord> Nodes { get; set; } = [];
    public List<string> Subscriptions { get; set; } = [];
    public Dictionary<string, SubscriptionOptions> SubscriptionSettings { get; set; } = [];
    public bool SubscriptionSettingsMigrated { get; set; }
    public List<ConfigProfile> Configs { get; set; } = [];
    public string NodeCore { get; set; } = "";
    public Dictionary<string, string> NodeGroupSelections { get; set; } = [];
    public string LibrarySort { get; set; } = "default";
    public string SubscriptionFilter { get; set; } = "";
    public bool SubscriptionAutoUpdate { get; set; }
    public int SubscriptionUpdateMinutes { get; set; } = 360;
    public DateTime SubscriptionLastAttempt { get; set; }
    public DateTime SubscriptionLastSuccess { get; set; }
    public string SubscriptionUpdateError { get; set; } = "";
}

public sealed class ConfigProfile
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = "配置";
    public string Core { get; set; } = "sing-box";
    public string Path { get; set; } = "";
    public string Url { get; set; } = "";
    public SubscriptionOptions? Subscription { get; set; }
    public DateTime Updated { get; set; } = DateTime.Now;
    public Dictionary<string, string> GroupSelections { get; set; } = [];
    public string Detail => $"{Core} · {(Url.Length == 0 ? "本地文件" : "远程配置")} · {Updated:MM-dd HH:mm}";
}

public sealed class SubscriptionOptions
{
    public SubscriptionUsage? Usage { get; set; }
    public string Name { get; set; } = "";
    public bool AutoUpdate { get; set; }
    public int UpdateMinutes { get; set; } = 1440;
    public DateTime LastAttempt { get; set; }
    public DateTime LastSuccess { get; set; }
    public string Error { get; set; } = "";
}

public sealed class NodeRecord
{
    public string Name { get; set; } = "Node";
    public string Protocol { get; set; } = "";
    public string Server { get; set; } = "";
    public int Port { get; set; }
    public string Password { get; set; } = "";
    public string Uuid { get; set; } = "";
    public string Cipher { get; set; } = "aes-128-gcm";
    public bool Tls { get; set; }
    public string Sni { get; set; } = "";
    public bool Insecure { get; set; }
    public string Network { get; set; } = "tcp";
    public string Path { get; set; } = "/";
    public string Host { get; set; } = "";
    public string Flow { get; set; } = "";
    public int AlterId { get; set; }
    public string? RealityKey { get; set; }
    public string? RealityShortId { get; set; }
    public string? Fingerprint { get; set; }
    public JsonObject? NativeSingbox { get; set; }
    public JsonObject? NativeMihomo { get; set; }
    public string SourceSubscription { get; set; } = "";
    public string Username { get; set; } = "";
    public Dictionary<string, string> Options { get; set; } = [];
    public string TransportText => Network + (Tls ? " · TLS" : "");
    public string SourceName => SourceSubscription.Length == 0 ? "手动导入" : new Uri(SourceSubscription).Host;
    [System.Text.Json.Serialization.JsonIgnore]
    public string DelayText { get; set; } = "未测试";
    public string Display => $"{Name}  ·  {Protocol.ToUpperInvariant()}  ·  {Server}:{Port}";
}

public static class Store
{
    public static readonly string DirectoryPath = Environment.GetEnvironmentVariable("WBOX_DATA_DIR") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Wbox");
    public static readonly string PreferencesPath = Path.Combine(DirectoryPath, "settings.json");
    public static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerOptions.Default) { WriteIndented = true };
    public static Preferences Load()
    {
        Directory.CreateDirectory(DirectoryPath);
        try { var p = JsonSerializer.Deserialize<Preferences>(File.ReadAllText(PreferencesPath)) ?? new(); SubscriptionRefresh.Normalize(p); return p; }
        catch { return new(); }
    }
    public static void Save(Preferences value)
    {
        Directory.CreateDirectory(DirectoryPath);
        var temporary = PreferencesPath + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(value, JsonOptions));
        File.Move(temporary, PreferencesPath, true);
    }
}
