namespace Wbox;

public sealed class Preferences
{
    public string Core { get; set; } = "mihomo";
    public string Mode { get; set; } = "nodes";
    public string ConfigPath { get; set; } = "";
    public string DelayUrl { get; set; } = "https://www.apple.com/library/test/success.html";
    public bool Silent { get; set; }
    public bool AutoConnect { get; set; }
    public bool AutoStart { get; set; }
    public bool SystemProxy { get; set; } = true;
    public int SelectedNode { get; set; }
    public List<NodeRecord> Nodes { get; set; } = [];
    public List<string> Subscriptions { get; set; } = [];
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
        try { return JsonSerializer.Deserialize<Preferences>(File.ReadAllText(PreferencesPath)) ?? new(); }
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
