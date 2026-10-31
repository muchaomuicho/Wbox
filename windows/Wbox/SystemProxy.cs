using Microsoft.Win32;
using System.Runtime.InteropServices;
namespace Wbox;

public sealed class ProxySnapshot
{
    public Dictionary<string, ProxyValue> Before { get; set; } = [];
    public Dictionary<string, ProxyValue> Owned { get; set; } = [];
}
public sealed class ProxyValue
{
    public bool Exists { get; set; }
    public string Value { get; set; } = "";
    public bool Dword { get; set; }
    public override bool Equals(object? other) => other is ProxyValue value && Exists == value.Exists && Value == value.Value && Dword == value.Dword;
    public override int GetHashCode() => HashCode.Combine(Exists, Value, Dword);
}
public static class SystemProxy
{
    const string KeyPath = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
    static readonly string SnapshotPath = Path.Combine(Store.DirectoryPath, "proxy-backup.json");
    static readonly string[] Names = ["ProxyEnable", "ProxyServer", "ProxyOverride", "AutoConfigURL", "AutoDetect"];
    [DllImport("wininet.dll", SetLastError = true)] static extern bool InternetSetOption(IntPtr hInternet, int option, IntPtr buffer, int length);
    static void Notify() { InternetSetOption(IntPtr.Zero, 39, IntPtr.Zero, 0); InternetSetOption(IntPtr.Zero, 37, IntPtr.Zero, 0); }
    static ProxyValue Read(RegistryKey key, string name)
    {
        var raw = key.GetValue(name, null); return raw == null ? new() : new() { Exists = true, Value = Convert.ToString(raw) ?? "", Dword = key.GetValueKind(name) == RegistryValueKind.DWord };
    }
    static void Write(RegistryKey key, string name, ProxyValue value)
    {
        if (!value.Exists) key.DeleteValue(name, false);
        else key.SetValue(name, value.Dword ? int.Parse(value.Value) : value.Value, value.Dword ? RegistryValueKind.DWord : RegistryValueKind.String);
    }
    public static bool CanRestore(ProxySnapshot snapshot, Dictionary<string, ProxyValue> current) => snapshot.Owned.All(kv => current.TryGetValue(kv.Key, out var actual) && actual.Equals(kv.Value));
    public static void Install(int port = ConfigBuilder.MixedPort)
    {
        Restore();
        Directory.CreateDirectory(Store.DirectoryPath);
        using var key = Registry.CurrentUser.CreateSubKey(KeyPath);
        var snapshot = new ProxySnapshot { Before = Names.ToDictionary(n => n, n => Read(key, n)), Owned = new() { ["ProxyEnable"] = new() { Exists = true, Dword = true, Value = "1" }, ["ProxyServer"] = new() { Exists = true, Value = $"127.0.0.1:{port}" }, ["ProxyOverride"] = new() { Exists = true, Value = "<local>;localhost;127.*;[::1]" }, ["AutoConfigURL"] = new() { Exists = false }, ["AutoDetect"] = new() { Exists = true, Dword = true, Value = "0" } } };
        // Durable backup precedes registry changes, enabling safe recovery after an OS/application crash.
        File.WriteAllText(SnapshotPath, JsonSerializer.Serialize(snapshot, Store.JsonOptions));
        try { foreach (var entry in snapshot.Owned) Write(key, entry.Key, entry.Value); Notify(); }
        catch { foreach (var entry in snapshot.Before) Write(key, entry.Key, entry.Value); Notify(); File.Delete(SnapshotPath); throw; }
    }
    public static bool Restore()
    {
        if (!File.Exists(SnapshotPath)) return false;
        var snapshot = JsonSerializer.Deserialize<ProxySnapshot>(File.ReadAllText(SnapshotPath)) ?? throw new IOException("系统代理恢复记录损坏，请手动检查 Windows 代理设置。");
        using var key = Registry.CurrentUser.CreateSubKey(KeyPath);
        var current = Names.ToDictionary(n => n, n => Read(key, n));
        var owned = CanRestore(snapshot, current);
        if (owned) { foreach (var entry in snapshot.Before) Write(key, entry.Key, entry.Value); Notify(); }
        // If another program/user changed the proxy, relinquish ownership without overwriting those edits.
        File.Delete(SnapshotPath);
        return owned;
    }
    public static void SetAutoStart(bool enable, bool silent)
    {
        using var run = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
        if (enable) run.SetValue("Wbox", $"\"{Environment.ProcessPath}\"{(silent ? " --silent" : "")}"); else run.DeleteValue("Wbox", false);
    }
}
