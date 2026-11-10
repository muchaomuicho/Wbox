package com.wbox.app;

import org.json.*;
import java.util.*;

final class NodeOptions {
    static String get(Node n,String key,String fallback){return n.options.getOrDefault(key,fallback);}
    static int number(Node n,String key,int fallback){try{return Integer.parseInt(get(n,key,""));}catch(Exception e){return fallback;}}
    static List<String> list(String s){List<String> out=new ArrayList<>();for(String v:s.split(","))if(!v.trim().isEmpty())out.add(v.trim());return out;}
    static List<Integer> reserved(Node n){List<Integer> r=new ArrayList<>();for(String v:list(get(n,"reserved",""))){int x=Integer.parseInt(v);if(x<0||x>255)throw new IllegalArgumentException("WireGuard reserved 必须是字节");r.add(x);}return r;}
    static void singbox(Node n,JSONObject j)throws Exception {
        String p=n.protocol;
        if(List.of("socks","http","naive").contains(p)){j.put("username",n.username).put("password",n.password);if(p.equals("socks"))j.put("version","5");}
        if(p.equals("naive"))j.put("quic",get(n,"quic","false").equals("true"));
        if(!get(n,"alpn","").isEmpty()&&j.has("tls"))j.getJSONObject("tls").put("alpn",new JSONArray(list(get(n,"alpn",""))));
        if(p.equals("hysteria2")&&!get(n,"obfs","").isEmpty()){checkObfs(n);j.put("obfs",new JSONObject().put("type","salamander").put("password",get(n,"obfs-password","")));}
        if(p.equals("hysteria")){j.put("auth_str",n.password).put("up_mbps",number(n,"upmbps",number(n,"up",100))).put("down_mbps",number(n,"downmbps",number(n,"down",100)));if(!get(n,"obfsParam",get(n,"obfs","")).isEmpty())j.put("obfs",get(n,"obfsParam",get(n,"obfs","")));}
        if(p.equals("tuic"))j.put("congestion_control",get(n,"congestion_control",get(n,"congestion-controller","cubic"))).put("udp_relay_mode",get(n,"udp_relay_mode",get(n,"udp-relay-mode","native")));
        if(p.equals("ss")&&!get(n,"plugin","").isEmpty()){String[] parts=get(n,"plugin","").split(";",2);j.put("plugin",parts[0].equals("simple-obfs")?"obfs-local":parts[0]).put("plugin_opts",parts.length==2?parts[1]:"");}
        if(!p.equals("wireguard"))return;
        if(!get(n,"dns","").isEmpty())throw new IllegalArgumentException("WireGuard dns 参数请使用 mihomo 或完整 sing-box 配置");
        List<String> keys=new ArrayList<>();j.keys().forEachRemaining(keys::add);for(String key:keys)j.remove(key);
        j.put("type","wireguard").put("tag",n.name).put("system",false).put("mtu",number(n,"mtu",1408)).put("address",new JSONArray(list(get(n,"address","")))).put("private_key",n.password);
        JSONObject peer=new JSONObject().put("address",n.server).put("port",n.port).put("public_key",get(n,"publickey","")).put("allowed_ips",new JSONArray().put("0.0.0.0/0").put("::/0"));
        if(!get(n,"presharedkey","").isEmpty())peer.put("pre_shared_key",get(n,"presharedkey",""));if(!reserved(n).isEmpty())peer.put("reserved",new JSONArray(reserved(n)));j.put("peers",new JSONArray().put(peer));
    }
    static void checkObfs(Node n){if(!get(n,"obfs","").equals("salamander")||get(n,"obfs-password","").isEmpty())throw new IllegalArgumentException("Hysteria2 需要 salamander 与 obfs-password");}
    static void mihomo(Node n,Map<String,Object> j){
        String p=n.protocol;
        if(List.of("http","socks").contains(p)){j.put("username",n.username);j.put("password",n.password);if(p.equals("socks"))j.put("type","socks5");}
        if(!get(n,"alpn","").isEmpty())j.put("alpn",list(get(n,"alpn","")));
        if(p.equals("hysteria2")&&!get(n,"obfs","").isEmpty()){checkObfs(n);j.put("obfs","salamander");j.put("obfs-password",get(n,"obfs-password",""));}
        if(p.equals("hysteria")){j.remove("password");j.put("auth-str",n.password);j.put("up",number(n,"upmbps",number(n,"up",100)));j.put("down",number(n,"downmbps",number(n,"down",100)));if(!get(n,"obfsParam",get(n,"obfs","")).isEmpty())j.put("obfs",get(n,"obfsParam",get(n,"obfs","")));}
        if(p.equals("tuic")){j.put("congestion-controller",get(n,"congestion_control",get(n,"congestion-controller","cubic")));j.put("udp-relay-mode",get(n,"udp_relay_mode",get(n,"udp-relay-mode","native")));}
        if(p.equals("ss")&&!get(n,"plugin","").isEmpty()){
            String[] parts=get(n,"plugin","").split(";");boolean obfs=parts[0].equals("obfs-local")||parts[0].equals("simple-obfs");j.put("plugin",obfs?"obfs":parts[0]);Map<String,Object> opts=new LinkedHashMap<>();
            for(int i=1;i<parts.length;i++){String[] kv=parts[i].split("=",2);String key=kv[0];if(key.equals("obfs"))key="mode";if(key.equals("obfs-host"))key="host";if(key.equals("obfs-uri"))key="path";opts.put(key,kv.length==2?kv[1]:true);}if(!obfs)opts.putIfAbsent("mode","websocket");j.put("plugin-opts",opts);
        }
        if(!p.equals("wireguard"))return;
        j.remove("password");j.put("private-key",n.password);j.put("public-key",get(n,"publickey",""));j.put("mtu",number(n,"mtu",1408));
        for(String a:list(get(n,"address","")))j.put(a.contains(":")?"ipv6":"ip",a.split("/")[0]);
        if(!get(n,"presharedkey","").isEmpty())j.put("pre-shared-key",get(n,"presharedkey",""));if(!reserved(n).isEmpty())j.put("reserved",reserved(n));
        if(!get(n,"dns","").isEmpty()){j.put("dns",list(get(n,"dns","")));j.put("remote-dns-resolve",true);}
    }
}
