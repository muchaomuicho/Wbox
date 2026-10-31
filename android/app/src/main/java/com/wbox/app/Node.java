package com.wbox.app;

import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Node {
    public String name, protocol, server, password="", uuid="", cipher="", sni="", network="", path="", host="", flow="", realityKey="", shortId="", fingerprint="", raw;
    public int port, alterId;
    public String username="", source="";
    public Map<String,String> options=new HashMap<>();
    public JSONObject nativeSingbox, nativeMihomo;
    public static Node stored(Object value)throws Exception {
        if(value instanceof String)return parse((String)value);
        JSONObject v=(JSONObject)value;
        if(v.has("link")){Node n=parse(v.getString("link"));n.source=v.optString("source");return n;}
        boolean sb=v.has("singbox");JSONObject j=v.getJSONObject(sb?"singbox":"mihomo");Node n=new Node();n.nativeSingbox=sb?j:null;n.nativeMihomo=sb?null:j;n.name=j.optString(sb?"tag":"name",j.optString("type"));n.protocol=j.optString("type");n.server=j.optString("server");n.port=j.optInt(sb?"server_port":"port");n.source=v.optString("source");n.raw=v.toString();return n;
    }
    public Object storage()throws Exception {if(nativeSingbox!=null||nativeMihomo!=null)return new JSONObject().put(nativeSingbox!=null?"singbox":"mihomo",nativeSingbox!=null?nativeSingbox:nativeMihomo).put("source",source);return source.isEmpty()?raw:new JSONObject().put("link",raw).put("source",source);}
    public boolean compatible(String engine){if(protocol.equals("naive"))return false;return engine.equals("sing-box")?nativeMihomo==null&&!(protocol.equals("socks")&&tls):nativeSingbox==null;}
    public boolean tls, insecure;
    static String dec(String x) { try{return URLDecoder.decode(x.replace("+","%2B"),"UTF-8");}catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError(impossible);} }
    static String b64(String x) { return new String(Base64.getDecoder().decode(x.replace('-','+').replace('_','/').replaceAll("\\s", "")), StandardCharsets.UTF_8); }
    public static Node parse(String link) throws Exception {
        Node n = new Node(); n.raw=link.trim();
        if (n.raw.startsWith("vmess://")) {
            JSONObject j=new JSONObject(b64(n.raw.substring(8)));
            n.protocol="vmess"; n.server=j.getString("add"); n.port=Integer.parseInt(j.get("port").toString()); n.uuid=j.getString("id");
            n.name=j.optString("ps",n.server); n.cipher=j.optString("scy","auto"); n.network=j.optString("net"); n.path=j.optString("path"); n.host=j.optString("host"); n.sni=j.optString("sni",n.host); n.tls="tls".equals(j.optString("tls"));
            n.alterId=j.optInt("aid");n.fingerprint=j.optString("fp");if(!j.optString("alpn").isEmpty())n.options.put("alpn",j.optString("alpn"));
        } else {
            String text=n.raw;
            if(text.startsWith("ss://") && !text.substring(5).split("[#?]",2)[0].contains("@")) {
                String[] parts=text.substring(5).split("#",2); text="ss://"+b64(parts[0])+(parts.length>1?"#"+parts[1]:"");
            }
            URI u=new URI(text); n.protocol=u.getScheme().toLowerCase(Locale.ROOT); if(n.protocol.equals("hy2")) n.protocol="hysteria2";
            String scheme=n.protocol;
            if(n.protocol.equals("socks5"))n.protocol="socks";
            if(n.protocol.equals("https"))n.protocol="http";
            if(n.protocol.startsWith("naive+"))n.protocol="naive";
            if(n.protocol.equals("wg"))n.protocol="wireguard";
            n.server=u.getHost(); n.port=u.getPort(); if(n.port==-1)n.port=scheme.equals("http")?80:n.protocol.equals("socks")?1080:n.protocol.equals("wireguard")?2408:443;
            if(n.server!=null && n.server.startsWith("["))n.server=n.server.substring(1,n.server.length()-1);
            if(n.server==null || n.server.isBlank())throw new IllegalArgumentException("节点地址无效");
            n.name=u.getRawFragment()==null?n.server:dec(u.getRawFragment());
            Map<String,String> q=new HashMap<>(); if(u.getRawQuery()!=null)for(String p:u.getRawQuery().split("&")){String[] kv=p.split("=",2);q.put(dec(kv[0]),kv.length>1?dec(kv[1]):"");}
            String user=u.getRawUserInfo()==null?"":dec(u.getRawUserInfo());
            switch(n.protocol) {
                case "ss": {
                    
                    String cred=user.contains(":")?user:b64(user);String[] v=cred.split(":",2);if(v.length!=2)throw new IllegalArgumentException("SS 凭据无效");n.cipher=v[0];n.password=v[1];break;
                }
                case "vless":n.uuid=user; break;
                case "trojan":case "hysteria2":case "anytls":case "hysteria":n.password=user;n.tls=true;break;
                case "tuic": {String[] v=user.split(":",2);n.uuid=v[0];n.password=v.length>1?v[1]:"";n.tls=true;break;}
                case "socks":case "http":case "naive": {if(!user.isEmpty()&&!user.contains(":")){try{user=b64(user);}catch(Exception ignored){}}String[] v=user.split(":",2);n.username=v[0];n.password=v.length>1?v[1]:"";n.tls=scheme.equals("https")||n.protocol.equals("naive");break;}
                case "wireguard":n.password=user;break;
                default:throw new IllegalArgumentException("不支持的分享协议："+n.protocol);
            }
            String security=q.getOrDefault("security", "");
            n.realityKey=q.getOrDefault("pbk", "");n.shortId=q.getOrDefault("sid", "");n.fingerprint=q.getOrDefault("fp", security.equals("reality")?"chrome":"");
            if(security.equals("reality")&&n.realityKey.isEmpty())throw new IllegalArgumentException("Reality 节点缺少 public key");
            n.tls|=security.equals("tls")||security.equals("reality");n.insecure=q.getOrDefault("allowInsecure",q.getOrDefault("insecure","0")).equals("1");
            n.sni=q.getOrDefault("sni",q.getOrDefault("peer", ""));n.network=q.getOrDefault("type", "");n.path=q.getOrDefault("serviceName",q.getOrDefault("path", ""));n.host=q.getOrDefault("host", "");n.flow=q.getOrDefault("flow", "");
            n.options=q;if(n.protocol.equals("naive"))q.put("quic",scheme.equals("naive+quic")?"true":"false");
            if(n.protocol.equals("hysteria"))n.password=q.getOrDefault("auth",user);
            for(String option:List.of("ech","echConfig","fm","extra","packetEncoding"))if(!q.getOrDefault(option,"").isEmpty())throw new IllegalArgumentException(option+" 分享参数尚未支持，请使用完整配置");
            if(q.containsKey("plugin")&&!List.of("obfs-local","simple-obfs","v2ray-plugin").contains(q.get("plugin").split(";")[0]))throw new IllegalArgumentException("不支持的 SS 插件");
            if(n.protocol.equals("wireguard")&&(q.getOrDefault("address","").isEmpty()||q.getOrDefault("publickey","").isEmpty()))throw new IllegalArgumentException("WireGuard 缺少 address / publickey");
        }
        if(n.port<1||n.port>65535)throw new IllegalArgumentException("端口必须在 1–65535");
        if(List.of("vmess","vless","tuic").contains(n.protocol))try{UUID.fromString(n.uuid);}catch(Exception ex){throw new IllegalArgumentException("节点 UUID 无效");}
        if(!n.network.isEmpty()&&!List.of("tcp","ws","grpc","http","h2").contains(n.network))throw new IllegalArgumentException("此传输请使用完整配置："+n.network);
        if(n.name==null||n.name.isBlank())n.name=n.server;
        return n;
    }
    public JSONObject singbox() throws Exception {
        if(nativeSingbox!=null)return new JSONObject(nativeSingbox.toString()).put("tag",name);
        if(nativeMihomo!=null)throw new IllegalArgumentException("此节点需要 mihomo 内核");
        JSONObject j=new JSONObject().put("type",protocol.equals("ss")?"shadowsocks":protocol).put("tag",name).put("server",server).put("server_port",port);
        if(!password.isEmpty())j.put("password",password); if(!uuid.isEmpty())j.put("uuid",uuid);
        if(protocol.equals("ss"))j.put("method",cipher); if(protocol.equals("vmess"))j.put("security",cipher.isEmpty()?"auto":cipher).put("alter_id",alterId);
        if(!flow.isEmpty())j.put("flow",flow);
        if(tls){JSONObject t=new JSONObject().put("enabled",true).put("server_name",sni.isEmpty()?server:sni).put("insecure",insecure);if(!fingerprint.isEmpty())t.put("utls",new JSONObject().put("enabled",true).put("fingerprint",fingerprint));if(!realityKey.isEmpty())t.put("reality",new JSONObject().put("enabled",true).put("public_key",realityKey).put("short_id",shortId));j.put("tls",t);}
        if(network.equals("ws")){JSONObject t=new JSONObject().put("type","ws").put("path",path.isEmpty()?"/":path);if(!host.isEmpty())t.put("headers",new JSONObject().put("Host",host));j.put("transport",t);}
        if(network.equals("grpc"))j.put("transport",new JSONObject().put("type","grpc").put("service_name",path));
        if(network.equals("http")||network.equals("h2"))j.put("transport",new JSONObject().put("type","http").put("path",path.isEmpty()?"/":path).put("host",new JSONArray().put(host.isEmpty()?server:host)));
        if(protocol.equals("hysteria"))j.remove("password");
        NodeOptions.singbox(this,j);return j;
    }
    public Map<String,Object> mihomo() {
        if(nativeMihomo!=null){Map<String,Object> copy=Profiles.yamlMap(nativeMihomo.toString());copy.put("name",name);return copy;}
        if(nativeSingbox!=null||protocol.equals("naive"))throw new IllegalArgumentException("此节点需要 sing-box 内核");
        Map<String,Object> j=new LinkedHashMap<>();j.put("name",name);j.put("type",protocol);j.put("server",server);j.put("port",port);j.put("udp",true);
        if(protocol.equals("vmess"))j.put("alterId",alterId);
        if(!password.isEmpty())j.put("password",password);if(!uuid.isEmpty())j.put("uuid",uuid);if(!cipher.isEmpty())j.put("cipher",cipher);if(!flow.isEmpty())j.put("flow",flow);
        if(tls){if(List.of("vless","vmess","http","socks").contains(protocol))j.put("tls",true);j.put("skip-cert-verify",insecure);j.put((protocol.equals("vmess")||protocol.equals("vless"))?"servername":"sni",sni.isEmpty()?server:sni);}
        if(!fingerprint.isEmpty())j.put("client-fingerprint",fingerprint);if(!realityKey.isEmpty())j.put("reality-opts",Map.of("public-key",realityKey,"short-id",shortId));
        if(network.equals("ws")){j.put("network","ws");Map<String,Object> opts=new LinkedHashMap<>();opts.put("path",path.isEmpty()?"/":path);if(!host.isEmpty())opts.put("headers",Map.of("Host",host));j.put("ws-opts",opts);}
        if(network.equals("grpc")){j.put("network","grpc");j.put("grpc-opts",Map.of("grpc-service-name",path));}
        if(network.equals("http")||network.equals("h2")){j.put("network","h2");j.put("h2-opts",Map.of("path",path.isEmpty()?"/":path,"host",List.of(host.isEmpty()?server:host)));}
        NodeOptions.mihomo(this,j);return j;
    }
}
