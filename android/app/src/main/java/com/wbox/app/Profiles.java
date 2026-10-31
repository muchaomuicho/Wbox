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
    public Profiles(Context c) { this(c.getFilesDir()); }
    public Profiles(File directory) { file=new File(directory,"profiles.json");load(); }
    public synchronized void load(){try{data=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}catch(Exception e){data=new JSONObject();}}
    public synchronized void save() throws Exception { File tmp=new File(file.getParentFile(),"profiles.tmp");Files.write(tmp.toPath(),data.toString(2).getBytes(StandardCharsets.UTF_8));Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING); }
    public String engine(){return data.optString("engine","sing-box");}
    public String mode(){return data.optString("mode","nodes");}
    public List<Node> nodes() throws Exception {List<Node> r=new ArrayList<>();JSONArray a=data.optJSONArray("nodes");if(a!=null)for(int i=0;i<a.length();i++)r.add(Node.parse(a.getString(i)));unique(r);return r;}
    static void unique(List<Node> ns){Set<String> used=new HashSet<>();for(Node n:ns){String base=n.name;int i=2;while(!used.add(n.name)||n.name.equals("Wbox")||n.name.equals("DIRECT"))n.name=base+" ("+i+++ ")";}}
    public String importText(String body,String source) throws Exception {
        String text=body.trim();
        if(text.startsWith("{")){JSONObject j=new JSONObject(text);if(j.has("outbounds")||j.has("inbounds")){data.put("config_sing-box",text).put("engine","sing-box").put("mode","config"); if(source!=null)data.put("config_url_sing-box",source);save();return "已导入 sing-box JSON 配置";}}
        if(text.contains("proxies:")||text.contains("proxy-providers:")||text.contains("mixed-port:")) {
            Map<String,Object> map=yamlMap(text);if(!map.containsKey("proxies")&&!map.containsKey("proxy-providers"))throw new IllegalArgumentException("YAML 中没有节点或代理提供者");
            data.put("config_mihomo",text).put("engine","mihomo").put("mode","config");if(source!=null)data.put("config_url_mihomo",source);save();return "已导入 mihomo YAML 配置";
        }
        if(!text.contains("://"))text=Node.b64(text);
        List<Node> parsed=new ArrayList<>();List<String> errors=new ArrayList<>();for(String line:text.split("\\r?\\n")){if(line.isBlank())continue;try{parsed.add(Node.parse(line));}catch(Exception ex){errors.add(ex.getMessage());}}
        if(parsed.isEmpty())throw new IllegalArgumentException(errors.isEmpty()?"内容中没有有效节点":String.join("；",errors));
        JSONArray stored=data.optJSONArray("nodes");if(stored==null)stored=new JSONArray();
        // Subscription refresh replaces only that subscription's previous nodes.
        JSONObject subscriptions=data.optJSONObject("subscriptions");if(subscriptions==null)subscriptions=new JSONObject();Set<String> remove=new HashSet<>();
        if(source!=null){JSONArray prev=subscriptions.optJSONArray(source);if(prev!=null)for(int i=0;i<prev.length();i++)remove.add(prev.getString(i));}
        LinkedHashSet<String> links=new LinkedHashSet<>();for(int i=0;i<stored.length();i++){String l=stored.getString(i);if(!remove.contains(l))links.add(l);}
        JSONArray imported=new JSONArray();for(Node n:parsed){links.add(n.raw);imported.put(n.raw);}if(source!=null)subscriptions.put(source,imported);
        data.put("nodes",new JSONArray(links)).put("subscriptions",subscriptions).put("mode","nodes");save();
        return "已导入 "+parsed.size()+" 个节点"+(errors.isEmpty()?"":"，跳过 "+errors.size()+" 个："+errors.get(0));
    }
    public static Map<String,Object> yamlMap(String text){LoaderOptions o=new LoaderOptions();o.setCodePointLimit(4*1024*1024);o.setMaxAliasesForCollections(30);Object v=new Yaml(new SafeConstructor(o)).load(text);if(!(v instanceof Map))throw new IllegalArgumentException("配置必须是 YAML 对象");return (Map<String,Object>)v;}
    public static String fetch(String address) throws Exception {URL url=new URL(address);if(!List.of("https","http").contains(url.getProtocol()))throw new IllegalArgumentException("订阅必须是 HTTP / HTTPS 地址");HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("User-Agent","Wbox/0.1.0");try{if(c.getResponseCode()!=200)throw new IOException("订阅 HTTP "+c.getResponseCode());try(InputStream in=c.getInputStream()){return readLimited(in);}}finally{c.disconnect();}}
    public static String readLimited(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>4*1024*1024)throw new IOException("配置超过 4 MB");}return out.toString("UTF-8");}
    public String compile(String secret) throws Exception {
        boolean sb=engine().equals("sing-box");List<Node> ns=mode().equals("nodes")?nodes():List.of();if(mode().equals("nodes")&&ns.isEmpty())throw new IllegalArgumentException("请先导入节点或配置");
        if(sb) {
            JSONObject j;
            if(mode().equals("config")){String cfg=data.optString("config_sing-box");if(cfg.isBlank())throw new IllegalArgumentException("请导入 sing-box JSON 配置");j=new JSONObject(cfg);}
            else {JSONArray outs=new JSONArray(),names=new JSONArray();for(Node n:ns){outs.put(n.singbox());names.put(n.name);}names.put("DIRECT");outs.put(new JSONObject().put("type","direct").put("tag","DIRECT"));outs.put(new JSONObject().put("type","selector").put("tag","Wbox").put("outbounds",names).put("default",ns.get(0).name));
                String selected=data.optString("selected");for(Node n:ns)if(n.name.equals(selected))outs.getJSONObject(outs.length()-1).put("default",selected);
                j=new JSONObject().put("outbounds",outs).put("route",new JSONObject().put("final","Wbox").put("default_domain_resolver","dns")).put("dns",new JSONObject().put("servers",new JSONArray().put(new JSONObject().put("type","udp").put("tag","dns").put("server","1.1.1.1"))).put("strategy",data.optBoolean("ipv6",true)?"prefer_ipv6":"ipv4_only"));}
            // Android owns the VPN. Preserve user rules/outbounds, replace only OS-facing TUN and reserved mixed listener.
            JSONArray in=j.optJSONArray("inbounds"),kept=new JSONArray();if(in!=null)for(int i=0;i<in.length();i++){JSONObject v=in.getJSONObject(i);if(!v.optString("type").equals("tun")&&v.optInt("listen_port")!=10808)kept.put(v);}
            kept.put(new JSONObject().put("type","mixed").put("tag","wbox-in").put("listen","127.0.0.1").put("listen_port",10808));j.put("inbounds",kept);
            JSONObject ex=j.optJSONObject("experimental");if(ex==null)ex=new JSONObject();ex.put("clash_api",new JSONObject().put("external_controller","127.0.0.1:19090").put("secret",secret));j.put("experimental",ex);
            return j.toString(2);
        }
        Map<String,Object> m;
        if(mode().equals("config")){String cfg=data.optString("config_mihomo");if(cfg.isBlank())throw new IllegalArgumentException("请导入 mihomo YAML 配置");m=yamlMap(cfg);}
        else{m=new LinkedHashMap<>();List<Object> proxies=new ArrayList<>();List<String> names=new ArrayList<>();for(Node n:ns){proxies.add(n.mihomo());names.add(n.name);}String selected=data.optString("selected");if(names.remove(selected))names.add(0,selected);names.add("DIRECT");m.put("proxies",proxies);m.put("proxy-groups",List.of(Map.of("name","Wbox","type","select","proxies",names)));m.put("rules",List.of("MATCH,Wbox"));m.put("ipv6",data.optBoolean("ipv6",true));m.put("dns",Map.of("enable",true,"ipv6",data.optBoolean("ipv6",true),"enhanced-mode","redir-host","nameserver",List.of("https://1.1.1.1/dns-query")));}
        m.put("mixed-port",10808);m.put("allow-lan",false);m.put("bind-address","127.0.0.1");m.put("external-controller","127.0.0.1:19090");m.put("secret",secret);m.put("tun",Map.of("enable",false));m.putIfAbsent("ipv6",true);
        return new Yaml().dump(m);
    }
}
