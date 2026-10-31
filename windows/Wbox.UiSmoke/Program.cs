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
            var preferences = new Wbox.Preferences();
            var window = new Wbox.MainWindow(preferences);
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
            var configMode = (RadioButton)window.FindName("ConfigMode"); configMode.IsChecked = true;
            for(int i=0;i<3;i++) {
                tabs.SelectedIndex=i;root.Measure(new Size(1120,800));root.Arrange(new Rect(0,0,1120,800));root.UpdateLayout();
                var bitmap=new RenderTargetBitmap(1120,800,96,96,PixelFormats.Pbgra32);bitmap.Render(root);var png=new PngBitmapEncoder();png.Frames.Add(BitmapFrame.Create(bitmap));using var file=File.Create(Path.Combine(Path.GetDirectoryName(path)!,"config-"+i+".png"));png.Save(file);
            }
            var state=(TextBlock)window.FindName("StateLabel");if(state.Foreground is SolidColorBrush brush&&brush.Color.R<180)throw new Exception("Dark theme text contrast failed");
            ((RadioButton)window.FindName("NodeMode")).IsChecked=true;
            string[] sampleNames=["日本 · Tokyo 01","新加坡 · Singapore 02","香港 · Hong Kong 03","美国 · Los Angeles 04"];
            for(int i=0;i<400;i++) preferences.Nodes.Add(Wbox.NodeParser.Parse("vless://11111111-1111-4111-8111-111111111111@node"+i+".example:443?security=tls#"+Uri.EscapeDataString(i<4?sampleNames[i]:"Node "+i)));
            typeof(Wbox.MainWindow).GetMethod("RefreshNodes",System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic)!.Invoke(window,null);
            tabs.SelectedIndex=1;root.Measure(new Size(1120,800));root.Arrange(new Rect(0,0,1120,800));root.UpdateLayout();
            var grid=(DataGrid)window.FindName("NodesList");grid.SelectedIndex=3;
            if(preferences.SelectedNode!=3)throw new Exception("Node selection mapping failed");
            if(grid.ItemContainerGenerator.ContainerFromIndex(399)!=null)throw new Exception("Node list is not virtualized");
            root.UpdateLayout();
            var populated=new RenderTargetBitmap(1120,800,96,96,PixelFormats.Pbgra32);populated.Render(root);var populatedPng=new PngBitmapEncoder();populatedPng.Frames.Add(BitmapFrame.Create(populated));using(var output=File.Create(Path.Combine(Path.GetDirectoryName(path)!,"ui-nodes-populated.png")))populatedPng.Save(output);
            ((TextBox)window.FindName("NodeSearch")).Text="node17.example";grid.SelectedIndex=0;
            if(preferences.SelectedNode!=17)throw new Exception("Filtered node selection mapping failed");
            window.Dispose(); app.Shutdown();
            Console.WriteLine("PASS WPF XAML constructed and rendered: " + path); return 0;
        }
        catch (Exception ex) { Console.Error.WriteLine(ex); return 1; }
    }
}
