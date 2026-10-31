using System.Windows;
using System.Threading;
namespace Wbox;

public partial class App : System.Windows.Application
{
    Mutex? mutex;
    EventWaitHandle? activation;
    RegisteredWaitHandle? waiter;
    MainWindow? window;
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        // This static 2D interface only redraws traffic once per second. Avoid a
        // dedicated GPU rendering context and its driver allocations.
        System.Windows.Media.RenderOptions.ProcessRenderMode = System.Windows.Interop.RenderMode.SoftwareOnly;
        var sid = System.Security.Principal.WindowsIdentity.GetCurrent().User!.Value;
        var isolated = Environment.GetEnvironmentVariable("WBOX_DATA_DIR");
        if (isolated != null) sid += "-" + Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(Path.GetFullPath(isolated))))[..12];
        mutex = new Mutex(true, "Local\\Wbox-" + sid, out var first);
        activation = new EventWaitHandle(false, EventResetMode.AutoReset, "Local\\Wbox-Activate-" + sid);
        if (!first) { activation.Set(); Shutdown(); return; }
        try { SystemProxy.Restore(); }
        catch (Exception ex) { System.Windows.MessageBox.Show(ex.Message, "Wbox · 代理恢复", MessageBoxButton.OK, MessageBoxImage.Warning); }
        var preferences = Store.Load();
        window = new MainWindow(preferences); MainWindow = window;
        waiter = ThreadPool.RegisterWaitForSingleObject(activation, (_, _) => Dispatcher.BeginInvoke(() => window.ShowFromTray()), null, -1, false);
        DispatcherUnhandledException += (_, args) => { args.Handled = true; System.Windows.MessageBox.Show(args.Exception.Message, "Wbox"); window.ExitApp(); };
        if (!(e.Args.Contains("--silent") || preferences.Silent)) window.Show();
        if (preferences.AutoConnect) Dispatcher.BeginInvoke(async () => await window.ConnectAsync());
    }
    protected override void OnExit(ExitEventArgs e)
    {
        window?.Dispose(); waiter?.Unregister(null); activation?.Dispose();
        try { mutex?.ReleaseMutex(); } catch { } mutex?.Dispose(); base.OnExit(e);
    }
}
