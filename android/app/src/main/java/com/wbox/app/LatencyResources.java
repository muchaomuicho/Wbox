package com.wbox.app;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.yaml.snakeyaml.Yaml;

/** Keep provider caches owned by the tested configuration out of the probe's writes. */
final class LatencyResources {
    private record ProviderId(String namespace,String name) {}
    private static final class Provider {
        final ProviderId id;final JSONObject value;final File file;final boolean proxy;
        Provider(ProviderId id,JSONObject value,File file,boolean proxy){this.id=id;this.value=value;this.file=file;this.proxy=proxy;}
    }
    static Set<String> keys(JSONObject value){Set<String> keys=new HashSet<>();value.keys().forEachRemaining(keys::add);return keys;}
    static JSONObject root(Profiles p)throws Exception{return new JSONObject(Profiles.yamlMap(p.data.optString("config_"+p.engine())));}
    static List<Provider> providers(Profiles p,JSONObject root)throws Exception {
        List<Provider> result=new ArrayList<>();
        String id=p.activeConfig()==null?"":p.activeConfig().optString("id").replaceAll("[^a-zA-Z0-9-]","");
        File directory=new File(p.file.getParentFile(),"runtime/"+p.engine()+"/"+id);
        if(p.engine().equals("mihomo")){
            for(String key:List.of("proxy-providers","rule-providers")){
                JSONObject entries=root.optJSONObject(key);if(entries==null)continue;
                for(String name:keys(entries)){
                    JSONObject value=entries.getJSONObject(name);String path=value.optString("path");
                    if(path.isEmpty()&&value.optString("type").equals("http")&&!value.optString("url").isEmpty()){
                        String hash=String.format("%032x",new java.math.BigInteger(1,MessageDigest.getInstance("MD5").digest(value.getString("url").getBytes(StandardCharsets.UTF_8))));
                        path=(key.equals("proxy-providers")?"proxies/":"rules/")+hash;
                    }
                    if(!path.isEmpty()){File file=new File(path);if(!file.isAbsolute())file=new File(directory,path);result.add(new Provider(new ProviderId(key,name),value,file,key.equals("proxy-providers")));}
                }
            }
        }else{
            JSONObject route=root.optJSONObject("route");JSONArray entries=route==null?null:route.optJSONArray("rule_set");
            if(entries!=null)for(int i=0;i<entries.length();i++){
                JSONObject value=entries.getJSONObject(i);String path=value.optString("path");
                if(!path.isEmpty()){File file=new File(path);if(!file.isAbsolute())file=new File(directory,path);result.add(new Provider(new ProviderId("rule_set",value.getString("tag")),value,file,false));}
            }
        }
        return result;
    }
    static String identity(Profiles p)throws Exception {
        if(!p.mode().equals("config"))return "";
        List<String> parts=new ArrayList<>();
        for(Provider item:providers(p,root(p)))if(item.proxy)parts.add(item.file.getAbsolutePath()+":"+item.file.lastModified()+":"+item.file.length());
        Collections.sort(parts);return String.join("|",parts);
    }
    static void capture(Profiles p)throws Exception {
        p.probeResources=new HashMap<>();
        if(p.mode().equals("config"))for(Provider item:providers(p,root(p)))if(item.file.isFile())p.probeResources.put(item.file.getAbsolutePath(),Files.readAllBytes(item.file.toPath()));
        p.probeResourceIdentity=identity(p);
    }
    static String fix(Profiles p,String compiled,File directory)throws Exception {
        if(!p.mode().equals("config"))return compiled;
        JSONObject original=root(p),next=new JSONObject(Profiles.yamlMap(compiled));
        boolean mihomo=p.engine().equals("mihomo");String remoteType=mihomo?"http":"remote",localType=mihomo?"file":"local";
        // Compilation can add localized rule paths; declaration names stay stable.
        Map<ProviderId,Provider> outputProviders=new HashMap<>();
        for(Provider item:providers(p,next))outputProviders.put(item.id,item);
        int index=0;
        for(Provider before:providers(p,original)){
            Provider after=outputProviders.get(before.id);if(after==null)continue;
            // BundledRules has already pinned these rules and may have changed their format.
            if(!before.proxy&&before.value.optString("type").equals(remoteType)&&after.value.optString("type").equals(localType))continue;
            byte[] bytes=p.probeResources==null?null:p.probeResources.get(before.file.getAbsolutePath());
            if(bytes==null)continue;
            File target=new File(directory,"provider-"+(index++)+"-"+before.file.getName());Files.write(target.toPath(),bytes);
            after.value.put("type",localType).put("path",target.getAbsolutePath());
            after.value.remove("url");after.value.remove("interval");after.value.remove("update_interval");
            JSONObject health=after.value.optJSONObject("health-check");if(health!=null)health.put("enable",false);
        }
        return mihomo?new Yaml().dump(Profiles.yamlMap(next.toString())):next.toString(2);
    }
    static Set<String> proxyNames(Profiles p,String compiled)throws Exception {
        JSONObject entries=p.engine().equals("mihomo")?new JSONObject(Profiles.yamlMap(compiled)).optJSONObject("proxy-providers"):null;
        return entries==null?Set.of():keys(entries);
    }
}
