namespace Wbox;

public static class ProfileImport
{
    public static string DetectCore(string text)
    {
        text = text.TrimStart('\uFEFF', ' ', '\r', '\n', '\t');
        var root = text.StartsWith('{') ? JsonNode.Parse(text, documentOptions: new() { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip })!.AsObject() : ConfigBuilder.ParseYaml(text);
        if (root.ContainsKey("outbounds") || root.ContainsKey("inbounds") || root.ContainsKey("endpoints")) return "sing-box";
        if (new[] { "proxies", "proxy-providers", "proxy-groups", "rules", "mixed-port" }.Any(root.ContainsKey)) return "mihomo";
        throw new FormatException("需要完整的 sing-box JSON 或 mihomo YAML 配置；节点订阅请在节点工作区导入。");
    }
    public static async Task<string> FetchAsync(string address, string agent = "clash.meta/1.19.33")
    {
        if (!Uri.TryCreate(address.Trim(), UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https")) throw new ArgumentException("请输入 HTTP / HTTPS 地址。");
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        using var client = new HttpClient(); client.DefaultRequestHeaders.UserAgent.ParseAdd(agent);
        using var response = await client.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
        if (!response.IsSuccessStatusCode) throw new IOException($"订阅服务器返回 HTTP {(int)response.StatusCode}。请检查地址、订阅有效期或服务器限制。");
        const int limit = 4 * 1024 * 1024;
        if (response.Content.Headers.ContentLength > limit) throw new IOException("配置超过 4 MiB。");
        using var input = await response.Content.ReadAsStreamAsync(timeout.Token); using var output = new MemoryStream(); var buffer = new byte[8192];
        int read; while ((read = await input.ReadAsync(buffer, timeout.Token)) > 0) { if (output.Length + read > limit) throw new IOException("配置超过 4 MiB。"); output.Write(buffer, 0, read); }
        return Encoding.UTF8.GetString(output.GetBuffer(), 0, (int)output.Length).TrimStart('\uFEFF');
    }
    public static ConfigProfile SaveConfig(Preferences p, string text, string name, string url = "", ConfigProfile? existing = null)
    {
        var engine = DetectCore(text);
        var profile = existing ?? new ConfigProfile();
        var directory = Path.Combine(Store.DirectoryPath, "profiles", profile.Id); Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, engine == "sing-box" ? "config.json" : "config.yaml");
        File.WriteAllText(path + ".tmp", text); File.Move(path + ".tmp", path, true);
        profile.Core = engine; profile.Path = path; profile.Name = name; profile.Url = url; profile.Updated = DateTime.Now;
        if (existing == null) p.Configs.Add(profile);
        return profile;
    }
}
