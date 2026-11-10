using System.Text.RegularExpressions;
namespace Wbox;

public static class LogFormatting
{
    static readonly Regex Ansi = new("\u001b\\[[0-9;]*m", RegexOptions.Compiled);
    static readonly Regex Header = new(@"^(?:\+\d{4}\s+)?(?:\d{4}-\d{2}-\d{2}\s+)?(?:\d{2}:\d{2}:\d{2}(?:\.\d+)?\s+)?(?<level>TRACE|DEBUG|INFO|WARN(?:ING)?|ERROR|FATAL|PANIC)\b|^time=.*?\slevel=(?<level>\w+)\b", RegexOptions.Compiled | RegexOptions.IgnoreCase);
    static readonly Regex Connection = new(@"\[(\d+) [^\]]+\]", RegexOptions.Compiled);
    public static (int Level, string Message) Format(string input)
    {
        var plain = Ansi.Replace(input, "");
        var level = Header.Match(plain).Groups["level"];
        int priority = level.Value.ToUpperInvariant() switch { "PANIC" => 0, "FATAL" => 1, "ERROR" => 2, "WARN" or "WARNING" => 3, "DEBUG" => 5, "TRACE" => 6, _ => 4 };
        var colored = Connection.Replace(plain, m => {
            uint.TryParse(m.Groups[1].Value, out var id);
            return $"\u001b[{new[] { 36, 35, 34, 32, 33 }[id % 5]}m{m.Value}\u001b[0m";
        }, 1);
        if (level.Success) {
            var color = priority <= 2 ? 31 : priority == 3 ? 33 : priority == 4 ? 34 : 36;
            colored = colored.Insert(level.Index + level.Length, "\u001b[0m").Insert(level.Index, $"\u001b[{color}m");
        }
        return (priority, colored);
    }
}
