using System.Net;
using System.Net.Sockets;
using System.Security.Principal;
namespace Wbox;

public static class StartupPolicy
{
    public static bool Elevated => new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator);
    public static int AvailablePort(int preferred, int exclude = -1) {
        var listener = new TcpListener(IPAddress.Loopback, preferred);
        try { listener.Server.ExclusiveAddressUse = true; listener.Start(); return preferred; }
        catch (SocketException) { }
        finally { listener.Stop(); }
        do { listener = new TcpListener(IPAddress.Loopback, 0); try { listener.Start(); preferred = ((IPEndPoint)listener.LocalEndpoint).Port; } finally { listener.Stop(); } } while (preferred == exclude);
        return preferred;
    }
    public static void Check(string config, string core) {
        var root = JsonNode.Parse(config)!;
        var tun = core == "sing-box" ? (root["inbounds"] as JsonArray)?.Any(i => i?["type"]?.ToString() == "tun") == true : root["tun"]?["enable"]?.GetValue<bool>() == true;
        if (tun && !Elevated) throw new InvalidOperationException("配置包含 TUN，需要管理员权限。请在设置中以管理员身份重启，或在概览选择「系统代理」后连接。");
        var listeners = System.Net.NetworkInformation.IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners();
        if (core == "sing-box" && root["inbounds"] is JsonArray inbounds) foreach (var inbound in inbounds) {
            if (int.TryParse(inbound?["listen_port"]?.ToString(), out var port) && listeners.Any(l => l.Port == port))
                throw new InvalidOperationException($"配置入站端口 {port} 已被占用。请修改该入站端口，或在概览选择「系统代理」。");
        }
    }
}
