using System.Reflection;
using Wbox;

static class ProxyNativeChecks {
    public static async Task Run(Action<bool,string> check) {
        var method = typeof(SystemProxy).GetMethod("ConnectionOptions", BindingFlags.NonPublic | BindingFlags.Static)!;
        Dictionary<string,ProxyValue> Options(Dictionary<string,ProxyValue>? value = null) => (Dictionary<string,ProxyValue>)method.Invoke(null,[value])!;
        var original = Options();
        if (original["ProxyEnable"].Value == "1") throw new InvalidOperationException("An external system proxy is active; native mutation test intentionally not run.");
        Directory.CreateDirectory(Store.DirectoryPath);
        try {
            foreach (var engine in new[] { "sing-box", "mihomo" }) {
                var config = Path.Combine(Store.DirectoryPath, "native-proxy-" + engine + ".json");
                File.WriteAllText(config, engine == "sing-box" ? "{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"DIRECT\"}],\"route\":{\"final\":\"DIRECT\"}}" : "{\"rules\":[\"MATCH,DIRECT\"]}");
                bool failRestore = false;
                using var manager = new CoreManager(() => failRestore ? throw new IOException("Injected WinINet failure") : SystemProxy.Restore());
                try {
                    await manager.StartAsync(new() { Mode = "config", Core = engine, ConfigPath = config, SystemProxy = true });
                    var owned = Options();
                    check(owned["ProxyEnable"].Value == "1" && owned["ProxyServer"].Value.Contains(":" + manager.ProxyPort), engine + " WinINet proxy enabled after listener ready");
                    owned["ProxyOverride"] = new() { Exists = true, Value = ";localhost.*;;<local>;localhost;127.*;[::1]" };
                    Options(owned);
                    failRestore = true; bool rejected = false;
                    try { manager.Stop(); } catch (IOException) { rejected = true; }
                    check(rejected && manager.Running && File.Exists(Path.Combine(Store.DirectoryPath,"proxy-backup.json")), engine + " failed proxy reset preserves live core and durable backup");
                    failRestore = false; manager.Stop();
                    var restored = Options();
                    check(!manager.Running && restored["ProxyEnable"].Value == "0", engine + " stop clears proxy despite bypass drift before core exit");
                    check(!File.Exists(Path.Combine(Store.DirectoryPath,"proxy-backup.json")), engine + " successful restore removes backup");
                    check(restored["AutoConfigURL"].Value == original["AutoConfigURL"].Value && restored["AutoDetect"].Value == original["AutoDetect"].Value, engine + " preserves previous PAC and detection policy");
                } finally { failRestore = false; manager.Stop(); File.Delete(config); }
            }
        } finally { Options(original); }
        check(Options().All(kv => kv.Value.Equals(original[kv.Key])), "WinINet connection options exactly restored after test");
    }
}
