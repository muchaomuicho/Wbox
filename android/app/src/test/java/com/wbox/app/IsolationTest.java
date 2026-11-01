package com.wbox.app;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;

public class IsolationTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temp=new org.junit.rules.TemporaryFolder();
    @Test public void nativeDependenciesAreScopedAndUnrelatedErrorsDoNotBlock()throws Exception {
        for(String engine:new String[]{"sing-box","mihomo"}) {
            Profiles p=new Profiles(temp.newFolder());p.data=new JSONObject().put("mode","nodes").put("engine",engine);
            boolean sb=engine.equals("sing-box");JSONArray array=new JSONArray();
            for(int i=0;i<3;i++){JSONObject node=new JSONObject().put("type","http").put(sb?"tag":"name",new String[]{"Parent","Chain","Broken"}[i]).put("server","127.0.0.1").put(sb?"server_port":"port",9000+i);if(i>0)node.put(sb?"detour":"dialer-proxy",i==1?"Parent":"Missing");array.put(node);}
            p.importNodes(new JSONObject().put(sb?"outbounds":"proxies",array).toString(),"https://example.org/sub");
            p.data.put("selected","Parent");assertFalse(p.compile("test").contains("Broken"));
            p.data.put("selected","Chain");String compiled=p.compile("test");assertTrue(compiled.contains("Parent"));assertFalse(compiled.contains("Broken"));
            p.data.put("selected","Broken");try{p.compile("test");fail();}catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("Missing"));}
            String library=p.data.getJSONArray("nodes").toString();p.setWorkspace("config");p.importConfig(new JSONObject().put(sb?"outbounds":"proxies",array).toString(),"https://config.invalid/sub",null);assertEquals(library,p.data.getJSONArray("nodes").toString());
        }
    }
}
