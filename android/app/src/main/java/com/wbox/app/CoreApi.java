package com.wbox.app;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class CoreApi {
    public static final class Stats { public long upload,download; public int connections; }
    public Stats stats()throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:19090/connections").openConnection();c.setConnectTimeout(1200);c.setReadTimeout(2000);c.setRequestProperty("Authorization","Bearer "+secret);
        try {if(c.getResponseCode()!=200)throw new IOException("控制接口不可用");
            try(android.util.JsonReader reader=new android.util.JsonReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
                Stats result=new Stats();reader.beginObject();while(reader.hasNext()){String key=reader.nextName();switch(key){case "uploadTotal":result.upload=reader.nextLong();break;case "downloadTotal":result.download=reader.nextLong();break;case "connections":if(reader.peek()==android.util.JsonToken.NULL){reader.nextNull();break;}reader.beginArray();while(reader.hasNext()){reader.skipValue();result.connections++;}reader.endArray();break;default:reader.skipValue();}}reader.endObject();return result;
            }
        }finally{c.disconnect();}
    }
    private final String secret;
    public CoreApi(String secret){this.secret=secret;}
    public JSONObject request(String method,String path,JSONObject body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:19090"+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(1200);c.setReadTimeout(path.contains("delay")?11000:2000);c.setRequestProperty("Authorization","Bearer "+secret);
        try{if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=c.getResponseCode();if(status>=400){String error="";if(c.getErrorStream()!=null)error=Profiles.readLimited(c.getErrorStream());throw new IOException("控制接口 HTTP "+status+" "+error);}
            if(status==204)return new JSONObject();try(InputStream in=c.getInputStream()){String s=Profiles.readLimited(in);return s.isBlank()?new JSONObject():new JSONObject(s);}
        }finally{c.disconnect();}
    }
    public JSONObject connections()throws Exception{return request("GET","/connections",null);}
    public JSONObject proxies()throws Exception{return request("GET","/proxies",null).getJSONObject("proxies");}
    public void select(String group,String node)throws Exception{request("PUT","/proxies/"+encode(group),new JSONObject().put("name",node));}
    public long delay(String node,String url)throws Exception{if(!url.startsWith("https://")&&!url.startsWith("http://"))throw new IllegalArgumentException("测试地址必须是 HTTP / HTTPS");return request("GET","/proxies/"+encode(node)+"/delay?timeout=8000&url="+encode(url),null).getLong("delay");}
    static String encode(String s){try{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}catch(UnsupportedEncodingException impossible){throw new AssertionError(impossible);}}
}
