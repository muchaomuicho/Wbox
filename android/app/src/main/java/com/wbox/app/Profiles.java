package com.wbox.app;

import android.content.Context;
import org.json.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Profiles {
    public static final String TEST_URL="https://www.apple.com/library/test/success.html";
    final File file;
    public JSONObject data;
    private JSONArray cachedArray;
    private List<Node> cachedNodes=List.of();
    private boolean writeThrough=true;
    public Profiles(Context c) { this(c.getFilesDir()); }
    public Profiles(File directory) { file=new File(directory,"profiles.json");load(); }
    public synchronized void load(){try{data=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}catch(Exception e){data=new JSONObject();}
        try {
            if(!data.has("node_engine"))data.put("node_engine",engine());
            if(!data.has("configs")){JSONArray a=new JSONArray();for(String core:List.of("sing-box","mihomo")){String content=data.optString("config_"+core);if(content.isEmpty())continue;JSONObject p=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",core+" 配置").put("core",core).put("content",content).put("url",data.optString("config_url_"+core)).put("updated",System.currentTimeMillis());a.put(p);if(core.equals(engine()))data.put("active_config",p.getString("id"));}data.put("configs",a);}
            if(!data.optBoolean("source_migrated")){JSONArray old=data.optJSONArray("nodes");JSONObject subs=data.optJSONObject("subscriptions");if(old!=null&&subs!=null){JSONArray rebuilt=new JSONArray();for(int i=0;i<old.length();i++){Object value=old.get(i);String source="";if(value instanceof String){Iterator<String> keys=subs.keys();while(keys.hasNext()){String key=keys.next();JSONArray links=subs.optJSONArray(key);if(links!=null)for(int j=0;j<links.length();j++)if(value.equals(links.optString(j)))source=key;}}rebuilt.put(source.isEmpty()?value:new JSONObject().put("link",value).put("source",source));}data.put("nodes",rebuilt);}data.put("source_migrated",true);}
        } catch(Exception ignored){}
    }
    public synchronized void save() throws Exception { if(!writeThrough)return;File tmp=new File(file.getParentFile(),"profiles.tmp");Files.write(tmp.toPath(),data.toString(2).getBytes(StandardCharsets.UTF_8));Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING); }
    public Profiles copy()throws Exception {Profiles copy=new Profiles(file.getParentFile());copy.data=new JSONObject(data.toString());copy.writeThrough=false;return copy;}
    public static boolean subscriptionAddress(String input) {try{URI uri=new URI(input.trim());return List.of("http","https").contains(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getFragment()==null&&!input.contains("\n");}catch(Exception e){return false;}}
    public String engine(){return data.optString("engine","sing-box");}
    public String mode(){return data.optString("mode","nodes");}
    public List<Node> nodes() throws Exception {JSONArray a=data.optJSONArray("nodes");if(a!=cachedArray){List<Node> r=new ArrayList<>();if(a!=null)for(int i=0;i<a.length();i++)r.add(Node.stored(a.get(i)));unique(r);cachedNodes=r;cachedArray=a;}return new ArrayList<>(cachedNodes);}
    static void unique(List<Node> ns){Set<String> used=new HashSet<>();for(Node n:ns){String base=n.name;int i=2;while(!used.add(n.name)||n.name.equals("Wbox")||n.name.equals("DIRECT"))n.name=base+" ("+i+++ ")";}}
    public String importText(String body,String source)throws Exception {
        // Compatibility entry point for pre-0.3 profiles. UI always uses an explicit workspace.
        String text=body.trim();
        if(text.startsWith("{")||text.contains("proxies:")||text.contains("proxy-providers:")||text.contains("mixed-port:")){String result=importConfig(body,source,null);setWorkspace("config");save();return result;}
        String result=importNodes(body,source);setWorkspace("nodes");save();return result;
    }
    public void setWorkspace(String mode)throws Exception {
        if(mode().equals("nodes"))data.put("node_engine",engine());
        data.put("mode",mode);
        if(mode.equals("nodes"))data.put("engine",data.optString("node_engine",engine()));
        else {JSONObject p=activeConfig();if(p!=null)activateConfig(p.getString("id"));}
    }
    public JSONArray configs(){JSONArray a=data.optJSONArray("configs");return a==null?new JSONArray():a;}
    public JSONObject activeConfig(){JSONArray a=configs();for(int i=0;i<a.length();i++){JSONObject p=a.optJSONObject(i);if(p!=null&&p.optString("id").equals(data.optString("active_config")))return p;}return a.optJSONObject(0);}
    public void activateConfig(String id)throws Exception {for(int i=0;i<configs().length();i++){JSONObject p=configs().getJSONObject(i);if(p.getString("id").equals(id)){data.put("active_config",id);data.put("config_"+p.getString("core"),p.getString("content"));if(mode().equals("config"))data.put("engine",p.getString("core"));return;}}}
    public String importConfig(String body,String source,String id)throws Exception {
        String text=body.trim();Map<String,Object> root=yamlMap(text);
        String core=root.containsKey("outbounds")||root.containsKey("inbounds")||root.containsKey("endpoints")?"sing-box":"mihomo";
        if(core.equals("sing-box"))new JSONObject(text);
        else if(Collections.disjoint(root.keySet(),List.of("proxies","proxy-providers","proxy-groups","rules","mixed-port")))throw new IllegalArgumentException("需要完整 JSON / YAML 配置；节点订阅请在节点工作区导入");
        JSONArray a=configs();JSONObject profile=null;for(int i=0;i<a.length();i++)if(a.getJSONObject(i).optString("id").equals(id)){profile=a.getJSONObject(i);break;}
        if(profile==null){profile=new JSONObject().put("id",UUID.randomUUID().toString());a.put(profile);}
        if(!profile.has("name"))profile.put("name",source==null?core+" 配置 "+a.length():new URL(source).getHost());
        profile.put("core",core).put("content",text).put("url",source==null?"":source).put("updated",System.currentTimeMillis());
        data.put("configs",a);activateConfig(profile.getString("id"));save();return "已保存 "+core+" 配置";
    }
    public String importNodes(String body,String source)throws Exception {
        String text=body.trim();List<Node> parsed=new ArrayList<>();
        if(text.startsWith("{")||text.contains("proxies:")||text.contains("proxy-providers:")){
            JSONObject root=new JSONObject(yamlMap(text));boolean sb=root.has("outbounds");JSONArray array=root.optJSONArray(sb?"outbounds":"proxies");
            if(array==null)throw new IllegalArgumentException("未找到节点列表；proxy-providers 请在配置工作区导入");
            for(int i=0;i<array.length();i++){JSONObject j=array.getJSONObject(i);if(!j.optString("server").isEmpty())parsed.add(Node.stored(new JSONObject().put(sb?"singbox":"mihomo",j)));}
        } else {
            if(!text.contains("://"))text=Node.b64(text);
            for(String line:text.split("\\r?\\n")){if(line.isBlank()||line.trim().startsWith("#"))continue;parsed.add(Node.parse(line));}
        }
        if(parsed.isEmpty())throw new IllegalArgumentException("内容中没有有效节点，原列表未改动");
        String owner=source==null?"":source;List<Node> previous=nodes();LinkedHashMap<String,Object> stored=new LinkedHashMap<>();
        // Each source owns its entries; identical links in a manual group are independent.
        for(Node n:previous)if(source==null||!n.source.equals(source))stored.put(n.source+"|"+n.raw,n.storage());
        for(Node n:parsed){n.source=owner;stored.put(owner+"|"+n.raw,n.storage());}
        JSONObject subscriptions=data.optJSONObject("subscriptions");if(subscriptions==null)subscriptions=new JSONObject();if(source!=null)subscriptions.put(source,new JSONArray());
        data.put("nodes",new JSONArray(stored.values())).put("subscriptions",subscriptions);
        List<Node> all=nodes();String compatible=all.stream().allMatch(n->n.nativeSingbox!=null)?"sing-box":all.stream().allMatch(n->n.nativeMihomo!=null)?"mihomo":null;
        if(compatible!=null){data.put("node_engine",compatible);if(mode().equals("nodes"))data.put("engine",compatible);}
        save();return "已导入 "+parsed.size()+" 个节点";
    }
    public static Map<String,Object> yamlMap(String text){LoaderOptions o=new LoaderOptions();o.setCodePointLimit(4*1024*1024);o.setMaxAliasesForCollections(30);Object v=new Yaml(new SafeConstructor(o)).load(text);if(!(v instanceof Map))throw new IllegalArgumentException("配置必须是 YAML 对象");return (Map<String,Object>)v;}
    public static String fetch(String address)throws Exception{return fetch(address,false);}
    public static String fetch(String address,boolean nodes) throws Exception {URL url=new URL(address);if(!List.of("https","http").contains(url.getProtocol()))throw new IllegalArgumentException("订阅必须是 HTTP / HTTPS 地址");HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("User-Agent",nodes?"v2rayN/7.24 Wbox/0.3.0":"Wbox/0.3.0");try{if(c.getResponseCode()!=200)throw new IOException("订阅 HTTP "+c.getResponseCode());try(InputStream in=c.getInputStream()){return readLimited(in);}}finally{c.disconnect();}}
    public static String readLimited(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>4*1024*1024)throw new IOException("配置超过 4 MB");}return out.toString("UTF-8");}
    public String compile(String secret) throws Exception {
        boolean sb=engine().equals("sing-box");List<Node> ns=mode().equals("nodes")?nodes():List.of();
        if(mode().equals("nodes")){if(ns.isEmpty())throw new IllegalArgumentException("请先导入节点或订阅");String selected=data.optString("selected");for(Node n:ns)if(n.name.equals(selected)&&!n.compatible(engine()))throw new IllegalArgumentException("所选节点不兼容当前内核");ns.removeIf(n->!n.compatible(engine()));}if(mode().equals("nodes")&&ns.isEmpty())throw new IllegalArgumentException("没有兼容节点；Android 内核未包含 NaiveProxy");
        if(sb) {
            JSONObject j;
            if(mode().equals("config")){String cfg=data.optString("config_sing-box");if(cfg.isBlank())throw new IllegalArgumentException("请导入 sing-box JSON 配置");j=new JSONObject(cfg);}
            else {JSONArray outs=new JSONArray(),names=new JSONArray(),endpoints=new JSONArray();for(Node n:ns){if(n.protocol.equals("wireguard"))endpoints.put(n.singbox());else outs.put(n.singbox());names.put(n.name);}names.put("DIRECT");outs.put(new JSONObject().put("type","direct").put("tag","DIRECT"));outs.put(new JSONObject().put("type","selector").put("tag","Wbox").put("outbounds",names).put("default",ns.get(0).name));
                String selected=data.optString("selected");for(Node n:ns)if(n.name.equals(selected))outs.getJSONObject(outs.length()-1).put("default",selected);
                j=new JSONObject().put("endpoints",endpoints).put("log",new JSONObject().put("level","warn")).put("outbounds",outs).put("route",new JSONObject().put("final","Wbox").put("default_domain_resolver","dns")).put("dns",new JSONObject().put("servers",new JSONArray().put(new JSONObject().put("type","udp").put("tag","dns").put("server","1.1.1.1"))).put("strategy",data.optBoolean("ipv6",true)?"prefer_ipv6":"ipv4_only"));}
            // Android owns the VPN. Preserve user rules/outbounds, replace only OS-facing TUN and reserved mixed listener.
            JSONArray in=j.optJSONArray("inbounds"),kept=new JSONArray();if(in!=null)for(int i=0;i<in.length();i++){JSONObject v=in.getJSONObject(i);if(!v.optString("type").equals("tun")&&v.optInt("listen_port")!=10808)kept.put(v);}
            kept.put(new JSONObject().put("type","mixed").put("tag","wbox-in").put("listen","127.0.0.1").put("listen_port",10808));j.put("inbounds",kept);
            JSONObject ex=j.optJSONObject("experimental");if(ex==null)ex=new JSONObject();ex.put("clash_api",new JSONObject().put("external_controller","127.0.0.1:19090").put("secret",secret));j.put("experimental",ex);
            JSONObject cache=ex.optJSONObject("cache_file");if(cache==null)cache=new JSONObject();cache.put("enabled",true).put("store_fakeip",true);
            String cacheId=mode().equals("config")&&activeConfig()!=null?activeConfig().optString("id").replaceAll("[^a-zA-Z0-9-]",""):"nodes";
            cache.put("path",new File(file.getParentFile(),"cache-"+cacheId+".db").getAbsolutePath());ex.put("cache_file",cache);
            return j.toString(2);
        }
        Map<String,Object> m;
        if(mode().equals("config")){String cfg=data.optString("config_mihomo");if(cfg.isBlank())throw new IllegalArgumentException("请导入 mihomo YAML 配置");m=yamlMap(cfg);}
        else{m=new LinkedHashMap<>();m.put("log-level","warning");List<Object> proxies=new ArrayList<>();List<String> names=new ArrayList<>();for(Node n:ns){proxies.add(n.mihomo());names.add(n.name);}String selected=data.optString("selected");if(names.remove(selected))names.add(0,selected);names.add("DIRECT");m.put("proxies",proxies);m.put("proxy-groups",List.of(Map.of("name","Wbox","type","select","proxies",names)));m.put("rules",List.of("MATCH,Wbox"));m.put("ipv6",data.optBoolean("ipv6",true));m.put("dns",Map.of("enable",true,"ipv6",data.optBoolean("ipv6",true),"enhanced-mode","redir-host","nameserver",List.of("https://1.1.1.1/dns-query")));}
        m.put("mixed-port",10808);m.put("allow-lan",false);m.put("bind-address","127.0.0.1");m.put("external-controller","127.0.0.1:19090");m.put("secret",secret);m.put("tun",Map.of("enable",false));m.putIfAbsent("ipv6",true);
        return new Yaml().dump(m);
    }
}
