using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Controls;

class Program
{
    [STAThread]
    static int Main(string[] args)
    {
        try
        {
            Environment.SetEnvironmentVariable("WBOX_DATA_DIR", Path.GetFullPath("windows/test-runtime"));
            var app = new Wbox.App(); app.InitializeComponent();
            var window = new Wbox.MainWindow(new Wbox.Preferences());
            var root = (FrameworkElement)window.Content;
            root.Measure(new Size(1120, 800)); root.Arrange(new Rect(0, 0, 1120, 800)); root.UpdateLayout();
            var path = Path.GetFullPath(args.FirstOrDefault() ?? "windows/ui-preview.png");
            var tabs = (TabControl)window.FindName("MainTabs");
            for (int i = 0; i < tabs.Items.Count; i++)
            {
                tabs.SelectedIndex = i; root.Measure(new Size(1120, 800)); root.Arrange(new Rect(0, 0, 1120, 800)); root.UpdateLayout();
                var target = new RenderTargetBitmap(1120, 800, 96, 96, PixelFormats.Pbgra32); target.Render(root);
                var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(target));
                var tabPath = i == 0 ? path : Path.Combine(Path.GetDirectoryName(path)!, Path.GetFileNameWithoutExtension(path) + "-" + i + ".png");
                using var output = File.Create(tabPath); encoder.Save(output);
            }
            window.Dispose(); app.Shutdown();
            Console.WriteLine("PASS WPF XAML constructed and rendered: " + path); return 0;
        }
        catch (Exception ex) { Console.Error.WriteLine(ex); return 1; }
    }
}
