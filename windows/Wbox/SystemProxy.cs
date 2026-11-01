using Microsoft.Win32;
using System.Runtime.InteropServices;
using System.ComponentModel;
using System.Net.Sockets;
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
    static readonly object Gate = new();
    [DllImport("wininet.dll", EntryPoint = "InternetSetOptionW", SetLastError = true)] static extern bool InternetSetOption(IntPtr hInternet, int option, IntPtr buffer, int length);
    [DllImport("wininet.dll", EntryPoint = "InternetQueryOptionW", SetLastError = true)] static extern bool InternetQueryOption(IntPtr handle, int option, IntPtr buffer, ref int length);
    [DllImport("kernel32.dll")] static extern IntPtr GlobalFree(IntPtr pointer);
    [StructLayout(LayoutKind.Explicit)] struct OptionValue { [FieldOffset(0)] public int Number; [FieldOffset(0)] public IntPtr Text; }
    [StructLayout(LayoutKind.Sequential)] struct Option { public int Id; public OptionValue Value; }
    [StructLayout(LayoutKind.Sequential)] struct OptionList { public int Size; public IntPtr Connection; public int Count; public int Error; public IntPtr Options; }
    static void Notify() {
        if (!InternetSetOption(IntPtr.Zero, 39, IntPtr.Zero, 0) || !InternetSetOption(IntPtr.Zero, 37, IntPtr.Zero, 0)) throw new Win32Exception(Marshal.GetLastWin32Error());
    }
    // The same WinINet per-connection API used by sing/common/wininet. Registry-only
    // writes do not reliably update the active connection's proxy flags.
    static Dictionary<string, ProxyValue> ConnectionOptions(Dictionary<string, ProxyValue>? settings = null) {
        int stride = Marshal.SizeOf<Option>();
        var options = Marshal.AllocHGlobal(stride * 4);
        var listPointer = Marshal.AllocHGlobal(Marshal.SizeOf<OptionList>());
        var strings = new List<IntPtr>();
        try {
            string Value(string name) => settings!.GetValueOrDefault(name)?.Value ?? "";
            var flags = settings == null ? 0 : 1 | (Value("ProxyEnable") == "1" ? 2 : 0) | (Value("AutoConfigURL").Length > 0 ? 4 : 0) | (Value("AutoDetect") == "1" ? 8 : 0);
            string[] names = ["", "ProxyServer", "ProxyOverride", "AutoConfigURL"];
            for (int i = 0; i < 4; i++) {
                var option = new Option { Id = i + 1 };
                if (i == 0) option.Value.Number = flags;
                else if (settings != null) { option.Value.Text = Marshal.StringToHGlobalUni(Value(names[i])); strings.Add(option.Value.Text); }
                Marshal.StructureToPtr(option, options + i * stride, false);
            }
            var list = new OptionList { Size = Marshal.SizeOf<OptionList>(), Count = 4, Options = options };
            Marshal.StructureToPtr(list, listPointer, false);
            var length = list.Size;
            bool success = settings == null ? InternetQueryOption(IntPtr.Zero, 75, listPointer, ref length) : InternetSetOption(IntPtr.Zero, 75, listPointer, length);
            if (!success) throw new Win32Exception(Marshal.GetLastWin32Error(), "无法更新 Windows 系统代理，内核保持运行。请重试停止。");
            if (settings != null) { Notify(); return settings; }
            flags = Marshal.PtrToStructure<Option>(options).Value.Number;
            var result = new Dictionary<string, ProxyValue> {
                ["ProxyEnable"] = new() { Exists = true, Dword = true, Value = (flags & 2) != 0 ? "1" : "0" },
                ["AutoDetect"] = new() { Exists = true, Dword = true, Value = (flags & 8) != 0 ? "1" : "0" }
            };
            for (int i = 1; i < 4; i++) {
                var pointer = Marshal.PtrToStructure<Option>(options + i * stride).Value.Text;
                var value = Marshal.PtrToStringUni(pointer) ?? "";
                if (pointer != IntPtr.Zero) GlobalFree(pointer);
                if (i == 3 && (flags & 4) == 0) value = "";
                result[names[i]] = new() { Exists = value.Length > 0, Value = value };
            }
            return result;
        } finally { foreach (var pointer in strings) Marshal.FreeHGlobal(pointer); Marshal.FreeHGlobal(options); Marshal.FreeHGlobal(listPointer); }
    }
    static ProxyValue Read(RegistryKey key, string name)
    {
        var raw = key.GetValue(name, null); return raw == null ? new() : new() { Exists = true, Value = Convert.ToString(raw) ?? "", Dword = key.GetValueKind(name) == RegistryValueKind.DWord };
    }
    static void Write(RegistryKey key, string name, ProxyValue value)
    {
        if (!value.Exists) key.DeleteValue(name, false);
        else key.SetValue(name, value.Dword ? int.Parse(value.Value) : value.Value, value.Dword ? RegistryValueKind.DWord : RegistryValueKind.String);
    }
    static string[] Endpoints(string text) => text.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).Select(v => v[(v.IndexOf('=') + 1)..].Replace("http://", "", StringComparison.OrdinalIgnoreCase).TrimEnd('/').ToLowerInvariant()).Distinct().Order().ToArray();
    public static bool CanRestore(ProxySnapshot snapshot, Dictionary<string, ProxyValue> current) {
        var expected = snapshot.Owned.GetValueOrDefault("ProxyServer")?.Value ?? "";
        var actual = current.GetValueOrDefault("ProxyServer")?.Value ?? "";
        return current.GetValueOrDefault("ProxyEnable")?.Value == "1" && expected.Length > 0 && Endpoints(expected).SequenceEqual(Endpoints(actual));
    }
    public static Dictionary<string, ProxyValue> RestorePlan(ProxySnapshot snapshot, Func<string, bool> reachable) {
        var result = new Dictionary<string, ProxyValue>(snapshot.Before);
        var server = result.GetValueOrDefault("ProxyServer")?.Value ?? "";
        if (result.GetValueOrDefault("ProxyEnable")?.Value == "1" && Endpoints(server).Any(endpoint => {
            if (!Uri.TryCreate("http://" + endpoint, UriKind.Absolute, out var uri) || !uri.IsLoopback) return false;
            return Endpoints(snapshot.Owned.GetValueOrDefault("ProxyServer")?.Value ?? "").Contains(endpoint) || !reachable(endpoint);
        })) result["ProxyEnable"] = new() { Exists = true, Dword = true, Value = "0" };
        return result;
    }
    static bool Reachable(string endpoint) {
        try { var uri = new Uri("http://" + endpoint); using var socket = new TcpClient(); socket.ConnectAsync(uri.Host, uri.Port).WaitAsync(TimeSpan.FromMilliseconds(250)).GetAwaiter().GetResult(); return true; } catch { return false; }
    }
    public static bool Enabled => ConnectionOptions()["ProxyEnable"].Value == "1";
    public static void Install(int port = ConfigBuilder.MixedPort)
    {
        lock (Gate) InstallLocked(port);
    }
    static void InstallLocked(int port)
    {
        Restore();
        Directory.CreateDirectory(Store.DirectoryPath);
        using var key = Registry.CurrentUser.CreateSubKey(KeyPath);
        var snapshot = new ProxySnapshot { Before = ConnectionOptions(), Owned = new() { ["ProxyEnable"] = new() { Exists = true, Dword = true, Value = "1" }, ["ProxyServer"] = new() { Exists = true, Value = $"127.0.0.1:{port}" }, ["ProxyOverride"] = new() { Exists = true, Value = "<local>;localhost;127.*;[::1]" }, ["AutoConfigURL"] = new() { Exists = false }, ["AutoDetect"] = new() { Exists = true, Dword = true, Value = "0" } } };
        // Durable backup precedes registry changes, enabling safe recovery after an OS/application crash.
        using (var backup = new FileStream(SnapshotPath, FileMode.Create, FileAccess.Write, FileShare.None)) { JsonSerializer.Serialize(backup, snapshot, Store.JsonOptions); backup.Flush(true); }
        ConnectionOptions(snapshot.Owned);
        if (!CanRestore(snapshot, ConnectionOptions()) || !Enabled) throw new IOException("系统代理未成功启用。");
    }
    public static bool Restore()
    {
        lock (Gate) return RestoreLocked();
    }
    static bool RestoreLocked()
    {
        if (!File.Exists(SnapshotPath)) return false;
        var snapshot = JsonSerializer.Deserialize<ProxySnapshot>(File.ReadAllText(SnapshotPath)) ?? throw new IOException("系统代理恢复记录损坏，请手动检查 Windows 代理设置。");
        using var key = Registry.CurrentUser.CreateSubKey(KeyPath);
        var current = ConnectionOptions();
        var owned = CanRestore(snapshot, current);
        if (owned) {
            var plan = RestorePlan(snapshot, Reachable);
            ConnectionOptions(plan);
            var actual = ConnectionOptions();
            if (actual["ProxyEnable"].Value != plan.GetValueOrDefault("ProxyEnable")?.Value || (actual["ProxyEnable"].Value == "1" && !Endpoints(actual["ProxyServer"].Value).SequenceEqual(Endpoints(plan["ProxyServer"].Value)))) throw new IOException("系统代理恢复尚未生效，保留恢复记录和运行中的内核。");
        }
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
