using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
namespace Wbox;

// Windows does not compose regional-indicator pairs into flags. Use small bundled assets.
public sealed class FlagLabel : DockPanel {
    public static readonly DependencyProperty ValueProperty = DependencyProperty.Register(nameof(Value), typeof(string), typeof(FlagLabel), new PropertyMetadata("", (d, _) => ((FlagLabel)d).Update()));
    public string Value { get => (string)GetValue(ValueProperty); set => SetValue(ValueProperty, value); }
    static readonly Dictionary<string, ImageSource> cache = [];
    readonly System.Windows.Controls.Image flag = new() { Width = 22, Height = 22, Margin = new Thickness(0,0,6,0), VerticalAlignment = VerticalAlignment.Center };
    readonly TextBlock label = new() { VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis };
    public FlagLabel() { ClipToBounds = true; SetDock(flag,Dock.Left); Children.Add(flag); Children.Add(label); }
    public static (string Code, string Text) Split(string value) {
        for (int i = 0; i + 3 < value.Length; i++) {
            if (!char.IsHighSurrogate(value[i]) || !char.IsLowSurrogate(value[i+1]) || !char.IsHighSurrogate(value[i+2]) || !char.IsLowSurrogate(value[i+3])) continue;
            var a = char.ConvertToUtf32(value,i); var b = char.ConvertToUtf32(value,i+2);
            if (a is >= 0x1f1e6 and <= 0x1f1ff && b is >= 0x1f1e6 and <= 0x1f1ff)
                return ($"{(char)('A'+a-0x1f1e6)}{(char)('A'+b-0x1f1e6)}", value.Remove(i,4).Trim());
        }
        return ("",value);
    }
    void Update() {
        var (code,text) = Split(Value ?? ""); label.Text = text; ToolTip = Value;
        flag.Visibility = Visibility.Collapsed;
        if (code.Length == 0) return;
        if (!cache.TryGetValue(code,out var source)) try {
            var bitmap = new BitmapImage(); bitmap.BeginInit(); bitmap.UriSource = new Uri($"pack://application:,,,/Wbox;component/flags/{code}.png"); bitmap.DecodePixelWidth = 32; bitmap.CacheOption = BitmapCacheOption.OnLoad; bitmap.EndInit(); bitmap.Freeze(); cache[code] = source = bitmap;
        } catch { label.Text = Value; return; }
        flag.Source = source; flag.Visibility = Visibility.Visible;
    }
}
