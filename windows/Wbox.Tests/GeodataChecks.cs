using System.Text.Json.Nodes;
using Wbox;

static class GeodataChecks {
    public static async Task Run(Action<bool,string> check) {
        string directory=Path.Combine(Store.DirectoryPath,"geodata-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(directory);
        try {
            var noRules=new JsonObject{["rules"]=new JsonArray("MATCH,DIRECT")};
            check(!Geodata.Required(noRules,"mmdb")&&!Geodata.Required(noRules,"geosite"),"Configs without geographic rules do not expand unused databases");
            check(Geodata.Required(ConfigBuilder.ParseYaml("dns: {fallback: [1.1.1.1]}"),"mmdb"),"DNS fallback keeps its implicit GEOIP dependency");
            check(Geodata.Required(ConfigBuilder.ParseYaml("rule-providers: {remote: {behavior: classical}}"),"geosite"),"Opaque classical providers retain offline databases");
            File.WriteAllText(Path.Combine(directory,"Country.mmdb"),"interrupted download");
            File.WriteAllText(Path.Combine(directory,"GeoSite.dat"),"<html>download failed</html>");
            Geodata.Prepare(directory,new JsonObject());
            check(Geodata.LooksComplete(Path.Combine(directory,"Country.mmdb"),true),"Bundled MMDB repairs interrupted download under recognized alias");
            check(Geodata.LooksComplete(Path.Combine(directory,"GeoSite.dat"),false),"Bundled GEOSITE repairs failed download response");
            check(!File.Exists(Path.Combine(directory,"GeoIP.dat")),"MMDB mode does not copy unused GeoIP.dat");
            var time=File.GetLastWriteTimeUtc(Path.Combine(directory,"Country.mmdb"));Geodata.Prepare(directory,new JsonObject());
            check(time==File.GetLastWriteTimeUtc(Path.Combine(directory,"Country.mmdb")),"Existing complete data survives reconnect without rewrite");
            Geodata.Prepare(directory,new JsonObject{["geodata-mode"]=true});
            check(Geodata.LooksComplete(Path.Combine(directory,"GeoIP.dat"),false),"Geodata mode prepares the protobuf IP database");
            var custom=Path.Combine(directory,"custom");Directory.CreateDirectory(custom);
            Geodata.Prepare(custom,new JsonObject{["geox-url"]=new JsonObject{["mmdb"]="https://example.invalid/private-mmdb",["geosite"]="https://example.invalid/private-site"}});
            check(Directory.GetFiles(custom).Length==0,"Custom database sources are preserved");
            foreach(bool dataMode in new[]{false,true}) {
                var path=Path.Combine(directory,"config.yaml");
                var config=new JsonObject{["geodata-mode"]=dataMode,["proxies"]=new JsonArray(),["rules"]=new JsonArray("GEOIP,CN,DIRECT","GEOSITE,gfw,DIRECT","MATCH,DIRECT")};
                File.WriteAllText(path,ConfigBuilder.SerializeYaml(config));
                var logs=new List<string>();using var manager=new CoreManager(()=>true);manager.Log+=line=>{lock(logs)logs.Add(line);};
                await manager.StartAsync(new Preferences{Core="mihomo",Mode="config",ConfigPath=path,SystemProxy=false});
                check(manager.Ready,"Real mihomo starts GEOIP and GEOSITE rules from bundled "+(dataMode?"DAT":"MMDB"));
                lock(logs)check(!logs.Any(l=>l.Contains("start download")||l.Contains("invalid, remove")),"Core never downloads default data during initialization");
                manager.Stop();
            }
        }finally{Directory.Delete(directory,true);}
    }
}
