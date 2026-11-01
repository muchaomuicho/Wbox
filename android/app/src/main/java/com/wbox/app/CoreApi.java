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
    public CoreApi(String secret){this(secret,19090);}
    public CoreApi(String secret,int port){this.secret=secret;this.port=port;}
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
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(1200);c.setReadTimeout(path.contains("delay")?6000:2000);c.setRequestProperty("Authorization","Bearer "+secret);
        try{if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=c.getResponseCode();if(status>=400){String error="";if(c.getErrorStream()!=null)error=Profiles.readLimited(c.getErrorStream());throw new IOException("控制接口 HTTP "+status+" "+error);}
            if(status==204)return new JSONObject();try(InputStream in=c.getInputStream()){String s=Profiles.readLimited(in);return s.isBlank()?new JSONObject():new JSONObject(s);}
        }finally{c.disconnect();}
    }
    public JSONObject connections()throws Exception{return request("GET","/connections",null);}
    public void closeConnection(String id)throws Exception{request("DELETE","/connections/"+encode(id),null);}
    public void closeAllConnections()throws Exception{request("DELETE","/connections",null);}
    public JSONObject proxies()throws Exception{return request("GET","/proxies",null).getJSONObject("proxies");}
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
    public long delay(String node,String url)throws Exception{if(!url.startsWith("https://")&&!url.startsWith("http://"))throw new IllegalArgumentException("测试地址必须是 HTTP / HTTPS");return request("GET","/proxies/"+encode(node)+"/delay?timeout=4000&url="+encode(url),null).getLong("delay");}
    static String encode(String s){try{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}catch(UnsupportedEncodingException impossible){throw new AssertionError(impossible);}}
}
