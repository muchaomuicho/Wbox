namespace Wbox;

public static class Geodata
{
    public static bool Required(JsonObject config,string key)
    {
        if(config["geo-auto-update"]?.ToString().Equals("true",StringComparison.OrdinalIgnoreCase)==true)return true;
        if(config["rule-providers"] is JsonObject providers&&providers.Any(p=>p.Value?["behavior"]?.ToString()=="classical"))return true;
        bool site=key=="geosite";string text=config.ToJsonString().ToLowerInvariant();
        if(text.Contains(site?"geosite,":"geoip,")||text.Contains(site?"geosite:":"geoip:"))return true;
        if(config["dns"] is JsonObject dns&&dns["fallback"] is JsonArray { Count: >0 }) {
            var filter=dns["fallback-filter"] as JsonObject;
            if(!site&&filter?["geoip"]?.ToString().Equals("false",StringComparison.OrdinalIgnoreCase)!=true)return true;
            if(site&&filter?["geosite"] is JsonArray { Count: >0 })return true;
        }
        return false;
    }
    public static void Prepare(string directory, JsonObject config, Action<string>? log=null, string? assets=null,bool onlyRequired=false)
    {
        assets ??= Path.Combine(AppContext.BaseDirectory,"geodata");
        var manifest=JsonNode.Parse(File.ReadAllText(Path.Combine(assets,"manifest.json")))!.AsArray();
        var custom=config["geox-url"] as JsonObject;
        foreach(var entry in manifest.OfType<JsonObject>()) {
            string name=entry["name"]!.ToString(), key=entry["key"]!.ToString();
            bool dataMode=config["geodata-mode"]?.ToString().Equals("true",StringComparison.OrdinalIgnoreCase)==true;
            if(key=="geoip"&&!dataMode || key=="mmdb"&&dataMode)continue;
            var url=custom?[key]?.ToString();
            if(!string.IsNullOrWhiteSpace(url)&&!url.Equals(entry["url"]!.ToString(),StringComparison.OrdinalIgnoreCase))continue;
            if(onlyRequired&&!Required(config,key))continue;
            var aliases=entry["aliases"]!.AsArray().Select(a=>a!.ToString()).ToArray();
            var target=Directory.GetFiles(directory).OrderBy(Path.GetFileName,StringComparer.Ordinal).FirstOrDefault(f=>aliases.Contains(Path.GetFileName(f),StringComparer.OrdinalIgnoreCase)) ?? Path.Combine(directory,name);
            if(LooksComplete(target,name.EndsWith(".metadb")))continue;
            var temp=target+".wbox-tmp";
            try {
                using(var input=File.OpenRead(Path.Combine(assets,name)))
                using(var output=File.Create(temp))input.CopyTo(output,65536);
                using(var input=File.OpenRead(temp)) {
                    var hash=Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(input));
                    if(!hash.Equals(entry["sha256"]!.ToString(),StringComparison.OrdinalIgnoreCase))throw new IOException("随附地理数据库校验失败："+name);
                }
                File.Move(temp,target,true);
                log?.Invoke("INFO Wbox: 已准备离线规则数据库 "+name);
            } finally {if(File.Exists(temp))File.Delete(temp);}
        }
    }
    // Detect missing/empty/truncated downloads without loading a database into
    // the UI heap. The core remains responsible for semantic validation.
    public static bool LooksComplete(string path,bool mmdb)
    {
        if(!File.Exists(path))return false;
        using var input=File.OpenRead(path);if(input.Length<32)return false;
        if(mmdb) {
            var tail=new byte[(int)Math.Min(input.Length,131072)];input.Seek(-tail.Length,SeekOrigin.End);input.ReadExactly(tail);
            ReadOnlySpan<byte> marker=[0xab,0xcd,0xef,77,97,120,77,105,110,100,46,99,111,109];
            int at=tail.AsSpan().LastIndexOf(marker);return at>=0 && at+marker.Length<tail.Length-16;
        }
        while(input.Position<input.Length) {
            if(input.ReadByte()!=10)return false;
            long length=0;int shift=0,b;
            do {b=input.ReadByte();if(b<0||shift>28)return false;length|=(long)(b&127)<<shift;shift+=7;}while((b&128)!=0);
            if(length<=0||length>input.Length-input.Position)return false;
            input.Seek(length,SeekOrigin.Current);
        }
        return true;
    }
}
