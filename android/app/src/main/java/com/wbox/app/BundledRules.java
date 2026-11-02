package com.wbox.app;

import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Shared templates and pinned rule snapshots; only exact known remote sources are localized. */
final class BundledRules {
    static String read(Geodata.Source source,String path)throws Exception {
        try(InputStream in=source.open(path)){return Profiles.readLimited(in);}
    }
    static void apply(JSONObject root,boolean sb,File working,Geodata.Source source)throws Exception {
        List<JSONObject> rules=new ArrayList<>();
        if(sb){JSONObject route=root.optJSONObject("route");JSONArray list=route==null?null:route.optJSONArray("rule_set");if(list!=null)for(int i=0;i<list.length();i++)if(list.optJSONObject(i)!=null)rules.add(list.getJSONObject(i));}
        else {JSONObject providers=root.optJSONObject("rule-providers");if(providers!=null)for(Iterator<String> it=providers.keys();it.hasNext();){JSONObject rule=providers.optJSONObject(it.next());if(rule!=null)rules.add(rule);}}
        if(rules.isEmpty())return;
        JSONArray entries=new JSONArray(read(source,"rules/manifest.json"));
        for(JSONObject rule:rules) {
            if(!rule.optString("type").equals(sb?"remote":"http"))continue;
            JSONObject entry=null;
            for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);if(e.getString("engine").equals(sb?"sing-box":"mihomo")&&e.getString("url").equals(rule.optString("url"))&&e.getString("format").equals(rule.optString("format",sb?"source":"yaml"))&&(sb||e.getString("behavior").equals(rule.optString("behavior")))){entry=e;break;}}
            if(entry==null)continue;
            File directory=new File(working,".wbox-rules");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建规则目录");
            String hash=entry.getString("sha256"),name=entry.getString("file");File target=new File(directory,hash.substring(0,12)+"-"+name);
            if(!matches(target,hash)) {
                File temp=File.createTempFile("rule-",".tmp",directory);
                try {try(InputStream in=source.open("rules/"+name);OutputStream out=new FileOutputStream(temp)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
                    if(!matches(temp,hash))throw new IOException("内置规则集校验失败："+name);
                    Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                }finally{Files.deleteIfExists(temp.toPath());}
            }
            rule.put("type",sb?"local":"file").put("path",target.getAbsolutePath());
            for(String key:List.of("url","download_detour","update_interval","http_client","interval","proxy","header","size-limit"))rule.remove(key);
        }
    }
    private static boolean matches(File file,String expected)throws Exception {
        if(!file.isFile())return false;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString().equals(expected);
    }
    static String nodeTag(String name){return Set.of("节点选择","Microsoft","AI","Steam","Steam Download","国内","直连","DIRECT","REJECT","GLOBAL").contains(name)||name.startsWith("节点 · ")?"节点 · "+name:name;}
    static JSONObject nodes(boolean sb,LinkedHashMap<Node,JSONObject> active,Geodata.Source source)throws Exception {
        String content=read(source,"templates/"+(sb?"sing-box.json":"mihomo.yaml"));
        JSONObject root=sb?new JSONObject(content):new JSONObject(Profiles.yamlMap(content));
        String selected=nodeTag(active.keySet().iterator().next().name);
        JSONArray groups=root.getJSONArray(sb?"outbounds":"proxy-groups");
        for(int i=0;i<groups.length();i++){JSONObject g=groups.getJSONObject(i);JSONArray names=g.optJSONArray(sb?"outbounds":"proxies");if(names!=null)for(int n=0;n<names.length();n++)if(names.optString(n).equals("__WBOX_SELECTED__"))names.put(n,selected);if(g.optString("default").equals("__WBOX_SELECTED__"))g.put("default",selected);}
        JSONArray nodes=sb?groups:new JSONArray(),endpoints=new JSONArray();
        for(var entry:active.entrySet()){
            JSONObject value=new JSONObject(entry.getValue().toString());value.put(sb?"tag":"name",nodeTag(entry.getKey().name));
            String chain=sb?"detour":"dialer-proxy";if(value.has(chain))value.put(chain,nodeTag(value.getString(chain)));
            if(sb&&entry.getKey().protocol.equals("wireguard"))endpoints.put(value);else nodes.put(value);
        }
        if(sb)root.put("endpoints",endpoints);else root.put("proxies",nodes);
        return root;
    }
}
