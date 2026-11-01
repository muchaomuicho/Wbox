package com.wbox.app;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class NodeEditing {
    static String revision(Node node)throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(node.storage().toString().getBytes(StandardCharsets.UTF_8)));
    }
    static Node prepare(Node original,JSONObject fields)throws Exception {
        Node next;
        if(original.nativeSingbox!=null||original.nativeMihomo!=null)next=Node.stored(new JSONObject().put(original.nativeSingbox!=null?"singbox":"mihomo",new JSONObject(fields.toString())));
        else {next=Node.fromFields(fields);if(!next.protocol.equals(original.protocol))throw new IllegalArgumentException("编辑时不能更换协议，请另行添加节点。");}
        next.name=next.name.trim();next.server=next.server.trim();next.source=original.source;
        if(next.name.isEmpty()||next.server.isEmpty()||next.port<1||next.port>65535)throw new IllegalArgumentException("请填写名称、服务器地址和有效端口。");
        if(next.nativeSingbox==null&&next.nativeMihomo==null){
            if(List.of("vmess","vless","tuic").contains(next.protocol))try{UUID.fromString(next.uuid);}catch(Exception e){throw new IllegalArgumentException("节点 UUID 无效。");}
            if(!List.of("tcp","ws","grpc","http","h2").contains(next.network))throw new IllegalArgumentException("不支持此传输协议，请使用完整配置。");
            for(String key:List.of("ech","echConfig","fm","extra","packetEncoding"))if(!next.options.getOrDefault(key,"").isEmpty())throw new IllegalArgumentException(key+" 请通过完整配置保留。");
            if(next.options.containsKey("plugin")&&!List.of("obfs-local","simple-obfs","v2ray-plugin").contains(next.options.get("plugin").split(";")[0]))throw new IllegalArgumentException("不支持此 SS 插件。");
            if(next.protocol.equals("wireguard")&&(next.options.getOrDefault("address","").isEmpty()||next.options.getOrDefault("publickey","").isEmpty()))throw new IllegalArgumentException("WireGuard 缺少地址或公钥。");
        }
        if(next.nativeMihomo!=null||next.protocol.equals("socks")&&next.tls||next.protocol.equals("wireguard")&&next.options.containsKey("dns"))next.mihomo();else next.singbox();
        return next;
    }
    static void replace(Profiles profiles,int index,String revision,JSONObject fields)throws Exception {
        List<Node> nodes=profiles.nodes();if(index<0||index>=nodes.size()||!revision(nodes.get(index)).equals(revision))throw new IllegalArgumentException("节点已更新，请重新打开编辑窗口。");
        Node original=nodes.get(index),next=prepare(original,fields);boolean selected=profiles.data.optString("selected").equals(original.name);nodes.set(index,next);Profiles.unique(nodes);
        JSONArray stored=new JSONArray();for(Node node:nodes)stored.put(node.storage());profiles.data.put("nodes",stored);if(selected)profiles.data.put("selected",next.name);
    }
    static void cloneNode(Profiles profiles,int index)throws Exception {
        List<Node> nodes=profiles.nodes();Node original=nodes.get(index);JSONObject fields=original.nativeSingbox!=null?new JSONObject(original.nativeSingbox.toString()):original.nativeMihomo!=null?new JSONObject(original.nativeMihomo.toString()):original.fields();
        fields.put(original.nativeSingbox!=null?"tag":"name",original.name+" 副本");Node copy=prepare(original,fields);copy.source="";nodes.add(copy);Profiles.unique(nodes);JSONArray stored=new JSONArray();for(Node node:nodes)stored.put(node.storage());profiles.data.put("nodes",stored);
    }
}
