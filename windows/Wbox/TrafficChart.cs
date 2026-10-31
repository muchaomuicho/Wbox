using System.Globalization;
using System.Windows;
using System.Windows.Media;
using Point = System.Windows.Point;
using Pen = System.Windows.Media.Pen;
using Color = System.Windows.Media.Color;
namespace Wbox;

public sealed class TrafficChart : FrameworkElement
{
    public string Series { get; set; } = "Download";
    readonly Queue<(DateTime Time, long Up, long Down)> samples = new();
    public void Add(long up, long down)
    {
        var now = DateTime.UtcNow;
        samples.Enqueue((now, Math.Max(0, up), Math.Max(0, down)));
        while (samples.Count > 0 && (now - samples.Peek().Time).TotalSeconds > 60) samples.Dequeue();
        InvalidateVisual();
    }
    public void Clear() { samples.Clear(); InvalidateVisual(); }
    protected override void OnRender(DrawingContext dc)
    {
        base.OnRender(dc);
        if (ActualWidth < 1 || ActualHeight < 1) return;
        var width = ActualWidth; var top = 2d; var bottom = ActualHeight - 3;
        var data = samples.ToArray();
        var max = Math.Max(1024, data.Length == 0 ? 0 : data.Max(s => Series == "Upload" ? s.Up : s.Down) * 1.15);
        if (data.Length < 2) { dc.DrawLine(new Pen(new SolidColorBrush(Color.FromRgb(90, 90, 90)), 1.5), new Point(0, bottom), new Point(width, bottom)); return; }
        Draw(Series == "Upload", Color.FromRgb(222, 222, 222));
        void Draw(bool up, Color color)
        {
            Point PointAt(int i) => new(width * (1 - (data[^1].Time - data[i].Time).TotalSeconds / 60), bottom - (up ? data[i].Up : data[i].Down) / max * (bottom - top));
            var line = new StreamGeometry(); var area = new StreamGeometry();
            using (var path = line.Open()) using (var fill = area.Open())
            {
                path.BeginFigure(PointAt(0), false, false);
                fill.BeginFigure(new Point(PointAt(0).X, bottom), true, true); fill.LineTo(PointAt(0), true, false);
                for (int i = 1; i < data.Length; i++)
                {
                    var prev = PointAt(i - 1); var next = PointAt(i); var x = (prev.X + next.X) / 2;
                    path.LineTo(next, true, false);
                    fill.LineTo(next, true, false);
                }
                fill.LineTo(new Point(PointAt(data.Length - 1).X, bottom), true, false);
            }
            line.Freeze(); area.Freeze();
            dc.DrawGeometry(new LinearGradientBrush(Color.FromArgb(24, 220, 220, 220), Color.FromArgb(2, 220, 220, 220), 90), null, area);
            var pen = new Pen(new SolidColorBrush(color), 2);
            
            dc.DrawGeometry(null, pen, line);
        }
    }
}
