package com.wbox.app;

import org.junit.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class BundledRulesTest {
    private File dir;
    @Before public void setup()throws Exception {dir=Files.createTempDirectory("wbox-bundled-").toFile();}
    @After public void cleanup()throws Exception {remove(dir);}
    private static void remove(File file)throws Exception {File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);Files.deleteIfExists(file.toPath());}
    @Test public void templatesUseLocalRulesAndKeepNodesIsolated()throws Exception {
        Profiles p=new Profiles(dir);p.importNodes("socks5://127.0.0.1:19999#AI\nsocks5://127.0.0.1:19998#Unused",null);p.setWorkspace("nodes");p.data.put("selected","AI");
        for(String engine:List.of("sing-box","mihomo")) {
            p.data.put("engine",engine);String text=p.compile("secret");boolean sb=engine.equals("sing-box");JSONObject j=new JSONObject(Profiles.yamlMap(text));
            assertFalse(text.contains("Unused"));assertFalse(text.contains("__WBOX_SELECTED__"));
            JSONArray groups=j.getJSONArray(sb?"outbounds":"proxy-groups");assertEquals("节点 · AI",groups.getJSONObject(0).getJSONArray(sb?"outbounds":"proxies").getString(0));
            List<JSONObject> rules=new ArrayList<>();if(sb){JSONArray rs=j.getJSONObject("route").getJSONArray("rule_set");for(int i=0;i<rs.length();i++)rules.add(rs.getJSONObject(i));}else{JSONObject rs=j.getJSONObject("rule-providers");for(Iterator<String> it=rs.keys();it.hasNext();)rules.add(rs.getJSONObject(it.next()));}
            assertEquals(13,rules.size());for(JSONObject r:rules){assertEquals(sb?"local":"file",r.getString("type"));assertFalse(r.has("url"));assertTrue(new File(r.getString("path")).isFile());}
            File first=new File(rules.get(0).getString("path"));long stamp=first.lastModified();p.compile("secret");assertEquals(stamp,first.lastModified());Files.write(first.toPath(),"broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));p.compile("secret");assertTrue(first.length()>32);
        }
    }
    @Test public void customUrlsAndSavedSourceArePreserved()throws Exception {
        Profiles p=new Profiles(dir);
        String url="https://anti-ad.net/anti-ad-sing-box.srs";
        String original="{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}],\"route\":{\"rule_set\":[{\"tag\":\"known\",\"type\":\"remote\",\"format\":\"binary\",\"url\":\""+url+"\"},{\"tag\":\"anti-ad\",\"type\":\"remote\",\"format\":\"binary\",\"url\":\"https://custom.invalid/a.srs\"}]}}";
        p.importConfig(original,null,null);JSONArray rules=new JSONObject(p.compile("s")).getJSONObject("route").getJSONArray("rule_set");
        assertEquals("local",rules.getJSONObject(0).getString("type"));assertEquals("remote",rules.getJSONObject(1).getString("type"));assertEquals(original,p.data.getString("config_sing-box"));
    }
}
