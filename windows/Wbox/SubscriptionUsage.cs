namespace Wbox;

public sealed record SubscriptionUsage(long? Upload, long? Download, long? Total, long? Expire)
{
    public static SubscriptionUsage? Parse(string? header)
    {
        var values = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
        foreach (var field in (header ?? "").Split(';', ',')) {
            var pair = field.Split('=', 2, StringSplitOptions.TrimEntries);
            if (pair.Length == 2 && long.TryParse(pair[1], System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture, out var value) && value >= 0)
                values[pair[0]] = value;
        }
        long? Get(string key) => values.TryGetValue(key, out var value) ? value : null;
        var expiry = Get("expire");
        if (expiry > 253402300799) expiry = null;
        var result = new SubscriptionUsage(Get("upload"), Get("download"), Get("total"), expiry);
        return result is { Upload: null, Download: null, Total: null, Expire: null } ? null : result;
    }
}

public sealed record SubscriptionDownload(string Content, SubscriptionUsage? Usage);
