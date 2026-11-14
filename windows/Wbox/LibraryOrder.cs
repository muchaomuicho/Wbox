using System.Globalization;

namespace Wbox;

public static class LibraryOrder
{
    static readonly StringComparer Names = StringComparer.Create(CultureInfo.GetCultureInfo("zh-CN"), false);

    public static IEnumerable<ConfigProfile> Configs(Preferences preferences) => preferences.LibrarySort switch {
        "name" => preferences.Configs.OrderBy(profile => profile.Name, Names),
        "updated" => preferences.Configs.OrderByDescending(profile => profile.Updated),
        _ => preferences.Configs
    };

    public static IEnumerable<string> Subscriptions(Preferences preferences) => preferences.LibrarySort switch {
        "name" => preferences.Subscriptions.OrderBy(url => preferences.SubscriptionSettings.GetValueOrDefault(url)?.Name ?? url, Names),
        "updated" => preferences.Subscriptions.OrderByDescending(url => preferences.SubscriptionSettings.GetValueOrDefault(url)?.LastSuccess ?? default),
        _ => preferences.Subscriptions
    };

    public static bool Move(Preferences preferences, string kind, string id, string anchor, bool after)
    {
        if (kind == "config") {
            var ordered = Configs(preferences).ToList();
            if (!MoveItem(ordered, profile => profile.Id, id, anchor, after)) return false;
            preferences.Configs = ordered;
        } else if (kind == "subscription") {
            var ordered = Subscriptions(preferences).ToList();
            if (!MoveItem(ordered, url => url, id, anchor, after)) return false;
            preferences.Subscriptions = ordered;
        } else throw new ArgumentException("未知配置列表。");
        preferences.LibrarySort = "default";
        return true;
    }

    static bool MoveItem<T>(List<T> ordered, Func<T,string> identify, string id, string anchor, bool after)
    {
        if (id == anchor) return false;
        var from = ordered.FindIndex(item => identify(item) == id);
        if (from < 0 || !ordered.Any(item => identify(item) == anchor)) return false;
        var moved = ordered[from];
        ordered.RemoveAt(from);
        var to = ordered.FindIndex(item => identify(item) == anchor) + (after ? 1 : 0);
        ordered.Insert(to, moved);
        return true;
    }

    // The overview's existing reorder contract supplies IDs. Merge those IDs
    // with the current list so an older picker cannot remove a new profile.
    public static void MergeConfigs(Preferences preferences, IEnumerable<string> ids)
    {
        var current = Configs(preferences).ToList();
        var byId = current.ToDictionary(profile => profile.Id);
        var seen = new HashSet<string>();
        var ordered = new List<ConfigProfile>();
        foreach (var id in ids)
            if (seen.Add(id) && byId.TryGetValue(id, out var profile)) ordered.Add(profile);
        ordered.AddRange(current.Where(profile => !seen.Contains(profile.Id)));
        preferences.Configs = ordered;
        preferences.LibrarySort = "default";
    }
}
