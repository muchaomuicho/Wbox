package com.wbox.app;

import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Node {
    public String name, protocol, server, password="", uuid="", cipher="", sni="", network="", path="", host="", flow="", realityKey="", shortId="", fingerprint="", raw;
    public int port;
    public boolean tls, insecure;
    static String dec(String x) { try{return URLDecoder.decode(x,"UTF-8");}catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError(impossible);} }
    static String b64(String x) { return new String(Base64.getDecoder().decode(x.replace('-','+').replace('_','/').replaceAll("\\s", "")), StandardCharsets.UTF_8); }
    public static Node parse(String link) throws Exception {
        Node n = new Node(); n.raw=link.trim();
        if (n.raw.startsWith("vmess://")) {
            JSONObject j=new JSONObject(b64(n.raw.substring(8)));
            n.protocol="vmess"; n.server=j.getString("add"); n.port=Integer.parseInt(j.get("port").toString()); n.uuid=j.getString("id");
            n.name=j.optString("ps",n.server); n.cipher=j.optString("scy","auto"); n.network=j.optString("net"); n.path=j.optString("path"); n.host=j.optString("host"); n.sni=j.optString("sni",n.host); n.tls="tls".equals(j.optString("tls"));
            if(j.optInt("aid")!=0) throw new IllegalArgumentException("不支持旧版 VMess alterId；请导入完整配置");
        } else {
            String text=n.raw;
            if(text.startsWith("ss://") && !text.substring(5).split("[#?]",2)[0].contains("@")) {
                String[] parts=text.substring(5).split("#",2); text="ss://"+b64(parts[0])+(parts.length>1?"#"+parts[1]:"");
            }
            URI u=new URI(text); n.protocol=u.getScheme().toLowerCase(Locale.ROOT); if(n.protocol.equals("hy2")) n.protocol="hysteria2";
            n.server=u.getHost(); n.port=u.getPort(); if(n.port==-1)n.port=443;
            if(n.server!=null && n.server.startsWith("["))n.server=n.server.substring(1,n.server.length()-1);
            if(n.server==null || n.server.isBlank())throw new IllegalArgumentException("节点地址无效");
            n.name=u.getRawFragment()==null?n.server:dec(u.getRawFragment());
            Map<String,String> q=new HashMap<>(); if(u.getRawQuery()!=null)for(String p:u.getRawQuery().split("&")){String[] kv=p.split("=",2);q.put(dec(kv[0]),kv.length>1?dec(kv[1]):"");}
            String user=u.getRawUserInfo()==null?"":dec(u.getRawUserInfo());
            switch(n.protocol) {
                case "ss": {
                    if(q.containsKey("plugin"))throw new IllegalArgumentException("SS 插件请使用完整配置");
                    String cred=user.contains(":")?user:b64(user);String[] v=cred.split(":",2);if(v.length!=2)throw new IllegalArgumentException("SS 凭据无效");n.cipher=v[0];n.password=v[1];break;
                }
                case "vless":n.uuid=user; break;
                case "trojan":case "hysteria2":n.password=user;n.tls=true;break;
                case "tuic": {String[] v=user.split(":",2);n.uuid=v[0];n.password=v.length>1?v[1]:"";n.tls=true;break;}
                default:throw new IllegalArgumentException("不支持的分享协议："+n.protocol);
            }
            String security=q.getOrDefault("security", "");
            n.realityKey=q.getOrDefault("pbk", "");n.shortId=q.getOrDefault("sid", "");n.fingerprint=q.getOrDefault("fp", security.equals("reality")?"chrome":"");
            if(security.equals("reality")&&n.realityKey.isEmpty())throw new IllegalArgumentException("Reality 节点缺少 public key");
            n.tls|=security.equals("tls")||security.equals("reality");n.insecure=q.getOrDefault("allowInsecure",q.getOrDefault("insecure","0")).equals("1");
            n.sni=q.getOrDefault("sni",q.getOrDefault("peer", ""));n.network=q.getOrDefault("type", "");n.path=q.getOrDefault("path", "");n.host=q.getOrDefault("host", "");n.flow=q.getOrDefault("flow", "");
            for(String option:List.of("obfs","alpn","congestion_control","udp_relay_mode"))if(q.containsKey(option))throw new IllegalArgumentException(option+" 参数请使用完整配置，避免丢失设置");
        }
        if(n.port<1||n.port>65535)throw new IllegalArgumentException("端口必须在 1–65535");
        if(List.of("vmess","vless","tuic").contains(n.protocol))try{UUID.fromString(n.uuid);}catch(Exception ex){throw new IllegalArgumentException("节点 UUID 无效");}
        if(!n.network.isEmpty()&&!List.of("tcp","ws","grpc").contains(n.network))throw new IllegalArgumentException("此传输请使用完整配置："+n.network);
        if(n.name==null||n.name.isBlank())n.name=n.server;
        return n;
    }
    public JSONObject singbox() throws Exception {
        JSONObject j=new JSONObject().put("type",protocol.equals("ss")?"shadowsocks":protocol).put("tag",name).put("server",server).put("server_port",port);
        if(!password.isEmpty())j.put("password",password); if(!uuid.isEmpty())j.put("uuid",uuid);
        if(protocol.equals("ss"))j.put("method",cipher); if(protocol.equals("vmess"))j.put("security",cipher.isEmpty()?"auto":cipher);
        if(!flow.isEmpty())j.put("flow",flow);
        if(tls){JSONObject t=new JSONObject().put("enabled",true).put("server_name",sni.isEmpty()?server:sni).put("insecure",insecure);if(!fingerprint.isEmpty())t.put("utls",new JSONObject().put("enabled",true).put("fingerprint",fingerprint));if(!realityKey.isEmpty())t.put("reality",new JSONObject().put("enabled",true).put("public_key",realityKey).put("short_id",shortId));j.put("tls",t);}
        if(network.equals("ws")){JSONObject t=new JSONObject().put("type","ws").put("path",path.isEmpty()?"/":path);if(!host.isEmpty())t.put("headers",new JSONObject().put("Host",host));j.put("transport",t);}
        if(network.equals("grpc"))j.put("transport",new JSONObject().put("type","grpc").put("service_name",path));
        if(protocol.equals("tuic"))j.put("congestion_control","cubic");return j;
    }
    public Map<String,Object> mihomo() {
        Map<String,Object> j=new LinkedHashMap<>();j.put("name",name);j.put("type",protocol);j.put("server",server);j.put("port",port);j.put("udp",true);
        if(protocol.equals("vmess"))j.put("alterId",0);
        if(!password.isEmpty())j.put("password",password);if(!uuid.isEmpty())j.put("uuid",uuid);if(!cipher.isEmpty())j.put("cipher",cipher);if(!flow.isEmpty())j.put("flow",flow);
        if(tls){if(protocol.equals("vless")||protocol.equals("vmess"))j.put("tls",true);j.put("skip-cert-verify",insecure);j.put(protocol.equals("vmess")?"servername":"sni",sni.isEmpty()?server:sni);}
        if(!fingerprint.isEmpty())j.put("client-fingerprint",fingerprint);if(!realityKey.isEmpty())j.put("reality-opts",Map.of("public-key",realityKey,"short-id",shortId));
        if(network.equals("ws")){j.put("network","ws");Map<String,Object> opts=new LinkedHashMap<>();opts.put("path",path.isEmpty()?"/":path);if(!host.isEmpty())opts.put("headers",Map.of("Host",host));j.put("ws-opts",opts);}
        if(network.equals("grpc")){j.put("network","grpc");j.put("grpc-opts",Map.of("grpc-service-name",path));}return j;
    }
}
