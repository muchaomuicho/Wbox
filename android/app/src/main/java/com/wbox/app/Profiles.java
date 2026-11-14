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
    static final Object STORE_LOCK=new Object();
    private JSONObject baseline=new JSONObject();
    public static final String TEST_URL="https://www.apple.com/library/test/success.html";
    final File file;
    private final Geodata.Source bundled;
    public JSONObject data;
    private JSONArray cachedArray;
    private List<Node> cachedNodes=List.of();
    private boolean writeThrough=true;
    Map<String,byte[]> probeResources;
    String probeResourceIdentity;
    public Profiles(Context c) { this(c.getFilesDir(),name->c.getAssets().open("bundled/"+name)); }
    public Profiles(File directory) { this(directory,name->{InputStream in=Profiles.class.getResourceAsStream("/bundled/"+name);if(in==null)throw new FileNotFoundException(name);return in;}); }
    private Profiles(File directory,Geodata.Source source) { file=new File(directory,"profiles.json");bundled=source;load(); }
    public synchronized void load(){try{data=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}catch(Exception e){data=new JSONObject();}
        try{baseline=new JSONObject(data.toString());}catch(JSONException ignored){}
        try {
            if(!data.has("node_engine"))data.put("node_engine",engine());
            if(!data.has("configs")){JSONArray a=new JSONArray();for(String core:List.of("sing-box","mihomo")){String content=data.optString("config_"+core);if(content.isEmpty())continue;JSONObject p=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",core+" 配置").put("core",core).put("content",content).put("url",data.optString("config_url_"+core)).put("updated",System.currentTimeMillis());a.put(p);if(core.equals(engine()))data.put("active_config",p.getString("id"));}data.put("configs",a);}
            if(!data.optBoolean("source_migrated")){JSONArray old=data.optJSONArray("nodes");JSONObject subs=data.optJSONObject("subscriptions");if(old!=null&&subs!=null){JSONArray rebuilt=new JSONArray();for(int i=0;i<old.length();i++){Object value=old.get(i);String source="";if(value instanceof String){Iterator<String> keys=subs.keys();while(keys.hasNext()){String key=keys.next();JSONArray links=subs.optJSONArray(key);if(links!=null)for(int j=0;j<links.length();j++)if(value.equals(links.optString(j)))source=key;}}rebuilt.put(source.isEmpty()?value:new JSONObject().put("link",value).put("source",source));}data.put("nodes",rebuilt);}data.put("source_migrated",true);}
            SubscriptionUpdates.migrate(data);data.remove("ipv6");
        } catch(Exception ignored){}
    }
    public synchronized void save() throws Exception {
        if(!writeThrough)return;
        synchronized(STORE_LOCK){
            JSONObject current;try{current=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}catch(Exception e){current=new JSONObject();}
            data=merge(baseline,data,current);
            File tmp=new File(file.getParentFile(),"profiles.tmp");Files.write(tmp.toPath(),data.toString(2).getBytes(StandardCharsets.UTF_8));Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);
            baseline=new JSONObject(data.toString());
        }
    }
    // Merge only the edited fields so a background subscription download cannot
    // overwrite a simultaneous theme, selector, or unrelated profile change.
    static JSONObject merge(JSONObject before,JSONObject next,JSONObject current)throws JSONException {
        JSONObject result=new JSONObject(current.toString());Set<String> keys=new HashSet<>();before.keys().forEachRemaining(keys::add);next.keys().forEachRemaining(keys::add);
        for(String key:keys){
            Object a=before.opt(key),b=next.opt(key),c=current.opt(key);
            if(Objects.equals(String.valueOf(a),String.valueOf(b)))continue;
            if(!next.has(key)){result.remove(key);continue;}
            if(a instanceof JSONObject ao && b instanceof JSONObject bo && c instanceof JSONObject co)result.put(key,merge(ao,bo,co));
            else if(key.equals("configs")&&a instanceof JSONArray aa&&b instanceof JSONArray ba&&c instanceof JSONArray ca){
                Map<String,JSONObject> originals=new LinkedHashMap<>(),updates=new LinkedHashMap<>(),latest=new LinkedHashMap<>();
                for(int i=0;i<aa.length();i++)originals.put(aa.getJSONObject(i).optString("id"),aa.getJSONObject(i));
                for(int i=0;i<ba.length();i++)updates.put(ba.getJSONObject(i).optString("id"),ba.getJSONObject(i));
                for(int i=0;i<ca.length();i++)latest.put(ca.getJSONObject(i).optString("id"),ca.getJSONObject(i));
                for(String id:originals.keySet())if(!updates.containsKey(id))latest.remove(id);
                for(String id:updates.keySet()){
                    if(!originals.containsKey(id))latest.put(id,updates.get(id));
                    else if(latest.containsKey(id))latest.put(id,merge(originals.get(id),updates.get(id),latest.get(id)));
                }
                result.put(key,new JSONArray(latest.values()));
            }else result.put(key,b);
        }
        return result;
    }
    public Profiles copy()throws Exception {Profiles copy=new Profiles(file.getParentFile(),bundled);copy.data=new JSONObject(data.toString());copy.writeThrough=false;return copy;}
    public static boolean subscriptionAddress(String input) {try{URI uri=new URI(input.trim());return List.of("http","https").contains(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getFragment()==null&&!input.contains("\n");}catch(Exception e){return false;}}
    public String engine(){return data.optString("engine","sing-box");}
    public String mode(){return data.optString("mode","config");}
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
    /** Keep ordering separate from profile content so downloads and removals merge independently. */
    public List<JSONObject> orderedConfigs() {
        Map<String,JSONObject> remaining=new LinkedHashMap<>();JSONArray configs=configs();
        for(int i=0;i<configs.length();i++){JSONObject p=configs.optJSONObject(i);if(p!=null)remaining.put(p.optString("id"),p);}
        List<JSONObject> result=new ArrayList<>();JSONArray order=data.optJSONArray("config_order");
        if(order!=null)for(int i=0;i<order.length();i++){JSONObject p=remaining.remove(order.optString(i));if(p!=null)result.add(p);}
        result.addAll(remaining.values());
        switch(data.optString("config_sort")) {
            case "name" -> result.sort(Comparator.comparing(p->p.optString("name").toLowerCase(Locale.ROOT)));
            case "updated" -> result.sort(Comparator.comparingLong((JSONObject p)->p.optLong("updated")).reversed());
        }
        return result;
    }
    public void reorderConfigs(List<String> ids)throws JSONException {
        Set<String> remaining=new LinkedHashSet<>();for(JSONObject p:orderedConfigs())remaining.add(p.optString("id"));
        JSONArray order=new JSONArray();for(String id:ids)if(remaining.remove(id))order.put(id);
        for(String id:remaining)order.put(id);
        data.put("config_order",order).put("config_sort","default");
    }
    public JSONObject activeConfig(){JSONArray a=configs();for(int i=0;i<a.length();i++){JSONObject p=a.optJSONObject(i);if(p!=null&&p.optString("id").equals(data.optString("active_config")))return p;}return a.optJSONObject(0);}
    public void activateConfig(String id)throws Exception {for(int i=0;i<configs().length();i++){JSONObject p=configs().getJSONObject(i);if(p.getString("id").equals(id)){data.put("active_config",id);data.put("config_"+p.getString("core"),p.getString("content"));if(mode().equals("config"))data.put("engine",p.getString("core"));return;}}throw new IllegalArgumentException("配置已移除，请重新选择");}
    public String importConfig(String body,String source,String id)throws Exception {
        return importConfig(body,source,id,true);
    }
    public String importConfig(String body,String source,String id,boolean select)throws Exception {
        String text=body.trim();Map<String,Object> root=yamlMap(text);
        String core=root.containsKey("outbounds")||root.containsKey("inbounds")||root.containsKey("endpoints")?"sing-box":"mihomo";
        if(core.equals("sing-box"))new JSONObject(text);
        else if(Collections.disjoint(root.keySet(),List.of("proxies","proxy-providers","proxy-groups","rules","mixed-port")))throw new IllegalArgumentException("需要完整 JSON / YAML 配置；节点订阅请在节点工作区导入");
        JSONArray a=configs();JSONObject profile=null;for(int i=0;i<a.length();i++)if(a.getJSONObject(i).optString("id").equals(id)){profile=a.getJSONObject(i);break;}
        if(profile==null){profile=new JSONObject().put("id",UUID.randomUUID().toString());a.put(profile);}
        if(!profile.has("name"))profile.put("name",source==null?core+" 配置 "+a.length():new URL(source).getHost());
        profile.put("core",core).put("content",text).put("url",source==null?"":source).put("updated",System.currentTimeMillis());
        data.put("configs",a);SubscriptionUpdates.migrate(data);
        if(source!=null&&!source.isEmpty())profile.getJSONObject("subscription").put("last_attempt",System.currentTimeMillis()).put("last_success",System.currentTimeMillis()).put("error","");
        if(select||data.optString("active_config").isEmpty()||profile.getString("id").equals(data.optString("active_config")))activateConfig(profile.getString("id"));
        save();return "已保存 "+core+" 配置";
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
        Node selected=previous.stream().filter(n->n.name.equals(data.optString("selected"))).findFirst().orElse(null);
        // Each source owns its entries; identical links in a manual group are independent.
        for(Node n:previous)if(source==null||!n.source.equals(source))stored.put(n.source+"|"+n.raw,n.storage());
        for(Node n:parsed){n.source=owner;stored.put(owner+"|"+n.raw,n.storage());}
        JSONObject subscriptions=data.optJSONObject("subscriptions");if(subscriptions==null)subscriptions=new JSONObject();if(source!=null)subscriptions.put(source,new JSONArray());
        data.put("nodes",new JSONArray(stored.values())).put("subscriptions",subscriptions);SubscriptionUpdates.migrate(data);
        if(source!=null)SubscriptionUpdates.options(this,"",source).put("last_attempt",System.currentTimeMillis()).put("last_success",System.currentTimeMillis()).put("error","");
        List<Node> all=nodes();
        Node current=selected==null?null:all.stream().filter(n->n.source.equals(selected.source)&&n.raw.equals(selected.raw)).findFirst().orElse(null);
        if(current==null&&selected!=null)current=all.stream().filter(n->n.source.equals(selected.source)&&n.name.equals(selected.name)).findFirst().orElse(null);
        data.put("selected",(current==null?all.get(0):current).name);
        String compatible=all.stream().allMatch(n->n.nativeSingbox!=null)?"sing-box":all.stream().allMatch(n->n.nativeMihomo!=null)?"mihomo":null;
        if(compatible!=null){data.put("node_engine",compatible);if(mode().equals("nodes"))data.put("engine",compatible);}
        save();return "已导入 "+parsed.size()+" 个节点";
    }
    public static Map<String,Object> yamlMap(String text){
        text=text.trim();if(text.startsWith("\uFEFF"))text=text.substring(1).trim();
        // JSON permits escapes such as \/ which a YAML 1.1 parser rejects.
        // Parse JSON with its own grammar, including Android JSONObject output.
        if(text.startsWith("{"))try{return (Map<String,Object>)jsonValue(new JSONObject(text));}catch(JSONException e){throw new IllegalArgumentException("JSON 配置格式无效："+e.getMessage(),e);}
        LoaderOptions o=new LoaderOptions();o.setCodePointLimit(4*1024*1024);o.setMaxAliasesForCollections(30);Object v=new Yaml(new SafeConstructor(o)).load(text);if(!(v instanceof Map))throw new IllegalArgumentException("配置必须是 YAML 对象");return (Map<String,Object>)v;
    }
    private static Object jsonValue(Object value)throws JSONException {
        if(value==JSONObject.NULL)return null;
        if(value instanceof JSONObject object){Map<String,Object> map=new LinkedHashMap<>();Iterator<String> keys=object.keys();while(keys.hasNext()){String key=keys.next();map.put(key,jsonValue(object.get(key)));}return map;}
        if(value instanceof JSONArray array){List<Object> list=new ArrayList<>();for(int i=0;i<array.length();i++)list.add(jsonValue(array.get(i)));return list;}
        return value;
    }
    public static String fetch(String address)throws Exception{return fetch(address,false);}
    public static String fetch(String address,boolean nodes) throws Exception {return SubscriptionFetch.fetch(address);}
    public static String readLimited(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>4*1024*1024)throw new IOException("配置超过 4 MB");}return out.toString("UTF-8");}
    public String compile(String secret) throws Exception { return compile(secret,10808,19090,false); }
    public String compile(String secret,int mixedPort,int controllerPort,boolean transientConfig) throws Exception {
        String profile=mode().equals("config")&&activeConfig()!=null?activeConfig().optString("id").replaceAll("[^a-zA-Z0-9-]",""):"nodes";
        return compile(secret,mixedPort,controllerPort,transientConfig,new File(file.getParentFile(),"runtime/"+engine()+"/"+profile));
    }
    public String compile(String secret,int mixedPort,int controllerPort,boolean transientConfig,File working) throws Exception {
        boolean sb=engine().equals("sing-box");List<Node> ns=mode().equals("nodes")?nodes():List.of();
        LinkedHashMap<Node,JSONObject> active=new LinkedHashMap<>();
        if(mode().equals("nodes")) {
            if(ns.isEmpty())throw new IllegalArgumentException("请先导入节点或订阅");
            active=ActiveNodeConfig.resolve(ns,data.optString("selected"),engine());ns=new ArrayList<>(active.keySet());
        }
        if(sb) {
            JSONObject j;
            if(mode().equals("config")){String cfg=data.optString("config_sing-box");if(cfg.isBlank())throw new IllegalArgumentException("请导入 sing-box JSON 配置");j=new JSONObject(cfg);}
            else {j=BundledRules.nodes(true,active,bundled);j.getJSONObject("dns").put("strategy","prefer_ipv6");if(transientConfig)prepareProbe(j,true);}
            // Android owns the VPN. Preserve user rules/outbounds, replace only OS-facing TUN and reserved mixed listener.
            JSONArray in=j.optJSONArray("inbounds"),kept=new JSONArray();if(!transientConfig&&in!=null)for(int i=0;i<in.length();i++){JSONObject v=in.getJSONObject(i);if(!v.optString("type").equals("tun")&&v.optInt("listen_port")!=10808)kept.put(v);}
            kept.put(new JSONObject().put("type","mixed").put("tag","wbox-in").put("listen","127.0.0.1").put("listen_port",mixedPort));j.put("inbounds",kept);
            JSONObject ex=j.optJSONObject("experimental");if(ex==null)ex=new JSONObject();ex.put("clash_api",new JSONObject().put("external_controller","127.0.0.1:"+controllerPort).put("secret",secret));j.put("experimental",ex);
            JSONObject cache=ex.optJSONObject("cache_file");if(cache==null)cache=new JSONObject();cache.put("enabled",!transientConfig).put("store_fakeip",true);
            String cacheId=mode().equals("config")&&activeConfig()!=null?activeConfig().optString("id").replaceAll("[^a-zA-Z0-9-]",""):"nodes";
            cache.put("path",new File(file.getParentFile(),"cache-"+cacheId+".db").getAbsolutePath());ex.put("cache_file",cache);
            AndroidConfig.adaptSingBox(j,in);
            if(transientConfig&&mode().equals("config"))relocateResources(j,working);
            BundledRules.apply(j,true,working,bundled,new File(file.getParentFile(),"bundled-cache"));
            return j.toString(2);
        }
        Map<String,Object> m;
        if(mode().equals("config")){String cfg=data.optString("config_mihomo");if(cfg.isBlank())throw new IllegalArgumentException("请导入 mihomo YAML 配置");m=yamlMap(cfg);}
        else {JSONObject j=BundledRules.nodes(false,active,bundled);j.put("ipv6",true);j.getJSONObject("dns").put("ipv6",true);if(transientConfig)prepareProbe(j,false);m=yamlMap(j.toString());}
        m.put("mixed-port",mixedPort);m.put("allow-lan",false);m.put("bind-address","127.0.0.1");m.put("external-controller","127.0.0.1:"+controllerPort);m.put("secret",secret);m.put("tun",Map.of("enable",false));m.putIfAbsent("ipv6",true);
        m.put("unified-delay",true);
        AndroidConfig.adaptMihomo(m);
        if(transientConfig){for(String key:List.of("listeners","port","socks-port","redir-port","tproxy-port","external-controller-tls","external-controller-unix"))m.remove(key);Object dns=m.get("dns");if(dns instanceof Map)((Map<?,?>)dns).remove("listen");m.put("profile",Map.of("store-selected",false,"store-fake-ip",false));}
        JSONObject localized=new JSONObject(m);if(transientConfig&&mode().equals("config"))relocateResources(localized,working);BundledRules.apply(localized,false,working,bundled,new File(file.getParentFile(),"bundled-cache"));
        return new Yaml().dump(yamlMap(localized.toString()));
    }
    private void relocateResources(JSONObject root,File working)throws Exception {
        String profile=activeConfig()==null?"":activeConfig().optString("id").replaceAll("[^a-zA-Z0-9-]","");
        relocate(root,new File(file.getParentFile(),"runtime/"+engine()+"/"+profile),working);
    }
    private static void relocate(Object node,File source,File target)throws Exception {
        if(node instanceof JSONArray a){for(int i=0;i<a.length();i++)relocate(a.get(i),source,target);return;}
        if(!(node instanceof JSONObject obj))return;
        List<String> keys=new ArrayList<>();obj.keys().forEachRemaining(keys::add);
        for(String key:keys){
            Object value=obj.get(key);
            if(key.equals("path")&&(obj.has("url")||obj.has("behavior")||List.of("file","local","remote").contains(obj.optString("type")))&&value instanceof String path&&!path.isEmpty()){
                File original=new File(path);if(!original.isAbsolute())original=new File(source,path);
                String hash=String.format("%064x",new java.math.BigInteger(1,java.security.MessageDigest.getInstance("SHA-256").digest(original.getAbsolutePath().getBytes(StandardCharsets.UTF_8)))).substring(0,16);
                File destination=new File(target,"resource-"+hash+"-"+original.getName());
                if(original.isFile())Files.copy(original.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);
                obj.put(key,destination.getAbsolutePath());
            }else relocate(value,source,target);
        }
    }
    static void prepareProbe(JSONObject root,boolean sb)throws JSONException {
        if(sb){
            root.put("log",new JSONObject().put("level","error"));
            JSONObject route=root.getJSONObject("route");route.remove("rules");route.remove("rule_set");route.put("find_process",false);
            JSONObject dns=root.getJSONObject("dns");dns.remove("rules");dns.put("final","ali");
            JSONArray servers=dns.getJSONArray("servers");for(int i=servers.length()-1;i>=0;i--)if(servers.getJSONObject(i).optString("type").equals("fakeip"))servers.remove(i);
        }else{
            root.remove("rule-providers");root.remove("sniffer");root.put("rules",new JSONArray().put("MATCH,节点选择"));
            root.put("log-level","error").put("find-process-mode","off");root.put("profile",new JSONObject().put("store-selected",false).put("store-fake-ip",false));
            JSONObject dns=root.getJSONObject("dns");dns.remove("nameserver-policy");dns.put("enhanced-mode","redir-host");
        }
    }
}
