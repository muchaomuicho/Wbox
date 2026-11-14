package com.wbox.app;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class CoreApi {
    public static final class Stats { public long upload,download; public int connections; public java.util.List<ConnectionRecord> rows=java.util.List.of(); }
    public Stats stats()throws Exception { return snapshot(false,java.util.List.of(),1); }
    public Stats snapshot(boolean details,java.util.List<ConnectionRecord> previous,double seconds)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/connections").openConnection();c.setConnectTimeout(1200);c.setReadTimeout(2000);c.setRequestProperty("Authorization","Bearer "+secret);
        try {if(c.getResponseCode()!=200)throw new IOException("控制接口不可用");
            try(android.util.JsonReader reader=new android.util.JsonReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
                Stats result=new Stats();java.util.List<ConnectionRecord> rows=new java.util.ArrayList<>();java.util.Map<String,ConnectionRecord> before=new java.util.HashMap<>();if(details)for(ConnectionRecord row:previous)before.put(row.id,row);reader.beginObject();while(reader.hasNext()){String key=reader.nextName();switch(key){case "uploadTotal":result.upload=reader.nextLong();break;case "downloadTotal":result.download=reader.nextLong();break;case "connections":if(reader.peek()==android.util.JsonToken.NULL){reader.nextNull();break;}reader.beginArray();while(reader.hasNext()){if(details){ConnectionRecord row=ConnectionRecord.read(reader),old=before.get(row.id);if(old!=null){row.upRate=(long)(Math.max(0,row.upload-old.upload)/Math.max(.1,seconds));row.downRate=(long)(Math.max(0,row.download-old.download)/Math.max(.1,seconds));}rows.add(row);}else reader.skipValue();result.connections++;}reader.endArray();break;default:reader.skipValue();}}reader.endObject();result.rows=rows;return result;
            }
        }finally{c.disconnect();}
    }
    private final String secret;
    private final int port;
    private final java.util.Set<String> providerNames;
    private java.util.Map<String,String> proxyProviders=java.util.Map.of();
    public CoreApi(String secret){this(secret,19090);}
    public CoreApi(String secret,int port){this(secret,port,java.util.Set.of());}
    CoreApi(String secret,int port,java.util.Set<String> providers){this.secret=secret;this.port=port;this.providerNames=providers;}
    private boolean rulesReady;
    static java.util.List<String> requiredRules(String engine,String config)throws Exception {
        java.util.List<String> result=new java.util.ArrayList<>();
        if(!engine.equals("mihomo"))return result;
        JSONObject providers=new JSONObject(Profiles.yamlMap(config)).optJSONObject("rule-providers");
        if(providers!=null)for(java.util.Iterator<String> it=providers.keys();it.hasNext();){String name=it.next();JSONObject p=providers.optJSONObject(name);if(p!=null&&java.util.List.of("file","http").contains(p.optString("type")))result.add(name);}
        return result;
    }
    void checkReady(int timeoutMs,int mixedPort,java.util.List<String> requiredRules)throws Exception {
        checkReady(timeoutMs,mixedPort);
        if(!providerNames.isEmpty()){
            JSONObject providers=request("GET","/providers/proxies",null).getJSONObject("providers");
            for(String name:providerNames){JSONObject p=providers.optJSONObject(name);if(p==null||p.optJSONArray("proxies")==null||p.getJSONArray("proxies").length()==0)throw new IOException("代理订阅仍在初始化");}
        }
        if(requiredRules.isEmpty())return;
        JSONObject providers=request("GET","/providers/rules",null).getJSONObject("providers");boolean loaded=true;
        for(String name:requiredRules){JSONObject p=providers.optJSONObject(name);String date=p==null?"":p.optString("updatedAt");if(date.length()<10||date.startsWith("0001-")){loaded=false;break;}}
        if(!loaded||!rulesReady){rulesReady=loaded;throw new IOException("规则集仍在初始化");}
    }
    void checkReady(int timeoutMs,int mixedPort)throws Exception {
        checkReady(timeoutMs);
        // Mihomo's controller can listen before the mixed inbound is bound.
        try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",mixedPort),timeoutMs);}
    }
    void checkReady(int timeoutMs)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/version").openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(timeoutMs);c.setReadTimeout(timeoutMs);c.setRequestProperty("Authorization","Bearer "+secret);
        try {
            int status=c.getResponseCode();
            if(status==401||status==403)throw new CoreStartup.Rejected("内核控制接口认证失败（HTTP "+status+"），请检查端口是否被其他进程占用");
            if(status!=200)throw new IOException("内核控制接口 HTTP "+status);
            try(InputStream in=c.getInputStream()) {
                if(!new JSONObject(Profiles.readLimited(in)).has("version"))throw new CoreStartup.Rejected("控制端口响应不是代理内核的版本信息");
            }catch(JSONException e){throw new CoreStartup.Rejected("控制端口返回了无效的版本信息");}
        }finally{c.disconnect();}
    }
    public JSONObject request(String method,String path,JSONObject body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection(java.net.Proxy.NO_PROXY);c.setRequestMethod(method);c.setConnectTimeout(1200);c.setReadTimeout(path.contains("delay")||path.contains("healthcheck")?8000:2000);c.setRequestProperty("Authorization","Bearer "+secret);
        try{if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=c.getResponseCode();if(status>=400){String error="";if(c.getErrorStream()!=null)error=Profiles.readLimited(c.getErrorStream());throw new IOException("控制接口 HTTP "+status+" "+error);}
            if(status==204)return new JSONObject();try(InputStream in=c.getInputStream()){String s=Profiles.readLimited(in);return s.isBlank()?new JSONObject():new JSONObject(s);}
        }finally{c.disconnect();}
    }
    public JSONObject connections()throws Exception{return request("GET","/connections",null);}
    public void closeConnection(String id)throws Exception{request("DELETE","/connections/"+encode(id),null);}
    public void closeAllConnections()throws Exception{request("DELETE","/connections",null);}
    public JSONObject proxies()throws Exception{
        JSONObject root=request("GET","/proxies",null).getJSONObject("proxies");
        if(providerNames.isEmpty())return root;
        JSONObject providers=request("GET","/providers/proxies",null).getJSONObject("providers");
        java.util.Map<String,String> owners=new java.util.HashMap<>();java.util.Map<String,JSONObject> details=new java.util.HashMap<>();
        for(String provider:providerNames){JSONObject entry=providers.optJSONObject(provider);JSONArray list=entry==null?null:entry.optJSONArray("proxies");if(list==null)continue;
            for(int i=0;i<list.length();i++){JSONObject node=list.getJSONObject(i);String name=node.optString("name");
                if(owners.containsKey(name)||root.has(name))owners.put(name,null);else owners.put(name,provider);
                details.put(name,node);
            }
        }
        for(String name:owners.keySet())if(owners.get(name)!=null)root.put(name,details.get(name));
        proxyProviders=owners;return root;
    }
    public void select(String group,String node)throws Exception{request("PUT","/proxies/"+encode(group),new JSONObject().put("name",node));}
    public void setMode(String mode)throws Exception {setMode(mode,null);}
    public void setMode(String mode,String preferred)throws Exception {
        if(!java.util.List.of("rule","global","direct").contains(mode))throw new IllegalArgumentException("模式无效");
        if(mode.equals("global")) {
            JSONObject root=proxies(),global=root.optJSONObject("GLOBAL");
            if(global!=null&&java.util.List.of("","DIRECT","REJECT").contains(global.optString("now"))) {
                String target=preferred!=null&&root.has(preferred)?preferred:null;java.util.Iterator<String> keys=root.keys();
                while(target==null&&keys.hasNext()){String key=keys.next();if(!key.equals("GLOBAL")&&root.optJSONObject(key)!=null&&root.getJSONObject(key).has("all")){target=key;break;}}
                JSONArray all=global.optJSONArray("all");
                if(target==null&&all!=null)for(int i=0;i<all.length();i++){String key=all.getString(i);if(!java.util.List.of("GLOBAL","DIRECT","REJECT","COMPATIBLE").contains(key)){target=key;break;}}
                if(target!=null)select("GLOBAL",target);
            }
        }
        request("PATCH","/configs",new JSONObject().put("mode",mode));
    }
    static String globalTarget(String config)throws Exception {
        java.util.Map<String,Object> root=Profiles.yamlMap(config);String target=null;
        if(root.get("rules") instanceof java.util.List<?> rules)for(Object rule:rules){String[] parts=rule.toString().split(",");if(parts.length>=2&&parts[0].trim().equals("MATCH"))target=parts[1].trim();}
        if(target!=null&&!java.util.List.of("DIRECT","REJECT").contains(target))return target;
        if(root.get("proxy-groups") instanceof java.util.List<?> groups&&!groups.isEmpty()&&groups.get(0) instanceof java.util.Map<?,?> group)return String.valueOf(group.get("name"));
        return null;
    }
    void requireUnambiguous(String node){if(proxyProviders.containsKey(node)&&proxyProviders.get(node)==null)throw new IllegalArgumentException("配置中的多个来源使用了同名节点「"+node+"」，请为它们设置不同的名称后重试。");}
    public long delay(String node,String url)throws Exception{if(!url.startsWith("https://")&&!url.startsWith("http://"))throw new IllegalArgumentException("测试地址必须是 HTTP / HTTPS");requireUnambiguous(node);String owner=proxyProviders.get(node);String path=owner==null?"/proxies/"+encode(node)+"/delay":"/providers/proxies/"+encode(owner)+"/"+encode(node)+"/healthcheck";long value=request("GET",path+"?timeout=5000&url="+encode(url),null).getLong("delay");if(value<=0)throw new IOException("延迟测试超时或未返回有效结果");return value;}
    static String encode(String s){try{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}catch(UnsupportedEncodingException impossible){throw new AssertionError(impossible);}}
}
