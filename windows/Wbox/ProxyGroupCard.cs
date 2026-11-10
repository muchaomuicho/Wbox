using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Button = System.Windows.Controls.Button;
using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using Color = System.Windows.Media.Color;
using HorizontalAlignment = System.Windows.HorizontalAlignment;
using FontFamily = System.Windows.Media.FontFamily;
using Orientation = System.Windows.Controls.Orientation;
namespace Wbox;

public sealed class ProxyGroupCard : Border {
    readonly StackPanel layout = new();
    readonly StackPanel summary = new() { Orientation = Orientation.Horizontal, Margin = new Thickness(0,18,0,0) };
    readonly WrapPanel nodes = new() { Margin = new Thickness(0,18,-12,-12) };
    readonly Button heading = new(), test = new();
    readonly TextBlock count = new() { FontSize = 13, Foreground = new SolidColorBrush(Color.FromRgb(180,180,180)) };
    readonly Button fold = new() { FontSize = 18, Background = Brushes.Transparent, Padding = new Thickness(12,4,0,4), Margin = new Thickness(8,0,0,0) };
    readonly Func<string,string,Task> select;
    readonly Func<string,Task<int>> delay;
    GroupItem group;
    bool expanded, testing;
    public string GroupName => group.Name;
    public ProxyGroupCard(GroupItem group, bool expanded, Func<string,string,Task> select, Func<string,Task<int>> delay) {
        this.group = group; this.expanded = expanded; this.select = select; this.delay = delay;
        Style = (Style)System.Windows.Application.Current.FindResource("Card"); Margin = new Thickness(0,0,0,18); Padding = new Thickness(24);
        Child = layout; var header = new DockPanel(); layout.Children.Add(header);
        DockPanel.SetDock(fold,Dock.Right);header.Children.Add(fold);fold.Click += (_,_) => { this.expanded = !this.expanded; Render(); };
        test.Content = "\uE9D9"; test.FontFamily = new FontFamily("Segoe MDL2 Assets"); test.FontSize = 20; test.Padding = new Thickness(10,4,10,4); test.ToolTip = "测试本组所有节点"; test.Margin = new Thickness(10,0,0,0); DockPanel.SetDock(test,Dock.Right); header.Children.Add(test);
        var badge = new Border { Child = count, Background = new SolidColorBrush(Color.FromRgb(58,58,58)), CornerRadius = new CornerRadius(14), Padding = new Thickness(9,5,9,5), VerticalAlignment = VerticalAlignment.Center };DockPanel.SetDock(badge,Dock.Right);header.Children.Add(badge);
        heading.HorizontalContentAlignment = HorizontalAlignment.Left; heading.Background = Brushes.Transparent; heading.Padding = new Thickness(0,6,0,6); heading.Margin = new Thickness(0); header.Children.Add(heading);
        heading.Click += (_,_) => { this.expanded = !this.expanded; Render(); };
        test.Click += async (_,_) => {
            if (testing) return; testing = true; test.IsEnabled = false;
            try {
                var names = this.group.Nodes.ToArray(); var values = new Dictionary<string,int>();
                using var limit = new SemaphoreSlim(4);
                await Task.WhenAll(names.Select(async name => { await limit.WaitAsync(); try { int value; try { value = await delay(name); } catch { value = 0; } lock(values) values[name] = value; } finally { limit.Release(); } }));
                this.group = this.group with { Details = this.group.Details.Select(n => n with { Delay = values.GetValueOrDefault(n.Name) }).ToList() }; Render();
            } finally { testing = false; test.IsEnabled = true; }
        };
        layout.Children.Add(summary); layout.Children.Add(nodes); SizeChanged += (_,_) => ResizeNodes(); Render();
    }
    public void Apply(GroupItem value) {
        if (testing) return;
        bool same = group.Selected == value.Selected && group.Type == value.Type && group.Nodes.SequenceEqual(value.Nodes) && group.Details.SequenceEqual(value.Details);
        group = value; if (!same) Render();
    }
    void Render() {
        var head = new StackPanel { Orientation = Orientation.Horizontal };
        head.Children.Add(new FlagLabel { Value = group.Name });
        head.Children.Add(new TextBlock { Text = "  " + group.Type, Foreground = (Brush)FindResource("Muted"), FontWeight = FontWeights.Normal });
        count.Text = group.Nodes.Count.ToString(); fold.Content = expanded ? "⌃" : "⌄";
        heading.Content = head; heading.FontSize = 16; heading.FontWeight = FontWeights.SemiBold;
        nodes.Children.Clear(); summary.Children.Clear(); nodes.Visibility = expanded ? Visibility.Visible : Visibility.Collapsed; summary.Visibility = expanded ? Visibility.Collapsed : Visibility.Visible;
        if (!expanded) {
            foreach (var node in group.Nodes.Take(32)) { var dot = new Border { Width = 13, Height = 13, CornerRadius = new CornerRadius(5), Margin = new Thickness(0,0,5,0), Background = new SolidColorBrush(Color.FromRgb(136,180,159)), ToolTip = node }; if (node == group.Selected) dot.Child = new System.Windows.Shapes.Ellipse { Width = 5, Height = 5, Fill = Brushes.White }; summary.Children.Add(dot); }
            return;
        }
        foreach (var name in group.Nodes) {
            var info = group.Details.FirstOrDefault(n => n.Name == name); var selected = name == group.Selected;
            var labels = new StackPanel(); labels.Children.Add(new FlagLabel { Value = name });
            var detail = new DockPanel { Margin = new Thickness(0,10,0,0) };
            var latency = new TextBlock { Text = info?.Delay is > 0 ? info.Delay + " ms" : info?.Delay == 0 ? "超时" : "—", Foreground = new SolidColorBrush(Color.FromRgb(136,180,159)), FontSize = 13, HorizontalAlignment = HorizontalAlignment.Right }; DockPanel.SetDock(latency,Dock.Right); detail.Children.Add(latency);
            detail.Children.Add(new TextBlock { Text = info?.Type ?? "—", FontSize = 13, Foreground = (Brush)FindResource("Muted") }); labels.Children.Add(detail);
            var button = new Button { Content = labels, FontSize = 16, FontWeight = selected ? FontWeights.SemiBold : FontWeights.Normal, Padding = new Thickness(16,15,16,15), Margin = new Thickness(0,0,12,12), Background = new SolidColorBrush(selected ? Color.FromRgb(65,65,65) : Color.FromRgb(31,31,31)), BorderBrush = selected ? Brushes.LightGray : (Brush)FindResource("Line"), BorderThickness = new Thickness(selected ? 1.5 : 1), ToolTip = name };
            button.Click += async (_,_) => { if (!this.group.Selectable) return; button.IsEnabled = false; try { await select(this.group.Name,name); } finally { button.IsEnabled = true; } }; nodes.Children.Add(button);
        }
        ResizeNodes();
    }
    void ResizeNodes() { var width = Math.Max(220,ActualWidth - 50); int columns = Math.Max(1,(int)(width/225)); foreach (FrameworkElement item in nodes.Children) item.Width = width/columns-12; }
}
