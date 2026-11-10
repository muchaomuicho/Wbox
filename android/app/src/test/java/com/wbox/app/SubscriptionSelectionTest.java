package com.wbox.app;

import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SubscriptionSelectionTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private static final String CONFIG="{\"outbounds\":[{\"type\":\"selector\",\"tag\":\"Main\",\"outbounds\":[\"A\",\"B\"]},{\"type\":\"direct\",\"tag\":\"A\"},{\"type\":\"direct\",\"tag\":\"B\"}]}";
    @Test public void offlineSelectionIsSavedAndIsolatedPerProfile()throws Exception {
        Profiles p=new Profiles(folder.getRoot());p.importConfig(CONFIG,null,null);
        p.activeConfig().put("group_selections",new JSONObject().put("Main","B"));p.save();
        Profiles reloaded=new Profiles(folder.getRoot());assertEquals("B",ProxyCatalog.offline(reloaded).getJSONObject("Main").getString("now"));
        reloaded.importConfig(CONFIG,null,null);assertEquals("A",ProxyCatalog.offline(reloaded).getJSONObject("Main").getString("now"));
        reloaded.activateConfig(p.activeConfig().getString("id"));reloaded.activeConfig().getJSONObject("group_selections").put("Main","removed");
        assertEquals("A",ProxyCatalog.offline(reloaded).getJSONObject("Main").getString("now"));
    }
    @Test public void concurrentUpdatePreservesThemeAndSelector()throws Exception {
        Profiles original=new Profiles(folder.getRoot());original.importConfig(CONFIG,"https://example.org/sub",null);
        Profiles download=new Profiles(folder.getRoot()),ui=new Profiles(folder.getRoot());
        ui.data.put("theme","dark");ui.activeConfig().put("group_selections",new JSONObject().put("Main","B"));ui.save();
        download.importConfig(CONFIG.replace("\"type\":\"direct\",\"tag\":\"A\"","\"type\":\"direct\",\"tag\":\"A\",\"domain_strategy\":\"ipv4_only\""),"https://example.org/sub",download.activeConfig().getString("id"),false);
        Profiles result=new Profiles(folder.getRoot());assertEquals("dark",result.data.getString("theme"));assertEquals("B",ProxyCatalog.offline(result).getJSONObject("Main").getString("now"));assertTrue(result.activeConfig().getString("content").contains("ipv4_only"));
    }
    @Test public void staleEditorDoesNotResurrectRemovedProfile()throws Exception {
        Profiles original=new Profiles(folder.getRoot());original.importConfig(CONFIG,null,null);
        Profiles editor=new Profiles(folder.getRoot()),remove=new Profiles(folder.getRoot());
        remove.data.put("configs",new JSONArray());remove.save();
        editor.activeConfig().put("name","renamed");editor.save();assertEquals(0,new Profiles(folder.getRoot()).configs().length());
    }
    @Test public void scheduleUsesPersistedAttemptsAndBounds()throws Exception {
        Profiles p=new Profiles(folder.getRoot());long now=10_000_000L;
        assertFalse(SubscriptionUpdates.due(p,now));p.importConfig(CONFIG,"https://example.org/one",null);
        JSONObject settings=SubscriptionUpdates.options(p,p.activeConfig().getString("id"),"https://example.org/one");
        settings.put("auto_update",true).put("update_minutes",60).put("last_attempt",now-3_599_000);
        assertFalse(SubscriptionUpdates.due(p,now));assertTrue(SubscriptionUpdates.due(p,now+1000));settings.put("update_minutes",0);assertEquals(15,SubscriptionUpdates.interval(settings));
    }
    @Test public void schedulesAndMetadataAreIndependent()throws Exception {
        Profiles p=new Profiles(folder.getRoot());p.importConfig(CONFIG,"https://example.org/one",null);String first=p.activeConfig().getString("id");
        p.importConfig(CONFIG,"https://example.org/two",null);String second=p.activeConfig().getString("id");
        SubscriptionUpdates.edit(p,first,"https://example.org/one","Renamed","https://example.org/new",true,30);
        assertTrue(SubscriptionUpdates.options(p,first,"https://example.org/new").getBoolean("auto_update"));
        assertFalse(SubscriptionUpdates.options(p,second,"https://example.org/two").getBoolean("auto_update"));
        assertNull(SubscriptionUpdates.options(p,first,"https://example.org/one"));
        p.activateConfig(first);assertEquals(CONFIG,p.activeConfig().getString("content"));assertEquals("Renamed",p.activeConfig().getString("name"));
    }
    @Test public void nodeSubscriptionEditingPreservesNodesAndMigratesOldSchedule()throws Exception {
        Profiles p=new Profiles(folder.getRoot());p.importNodes("http://user:pass@127.0.0.1:8888#Node","https://example.org/one");
        p.data.remove("subscription_settings_migrated");p.data.remove("subscription_settings");p.data.put("subscription_auto_update",true).put("subscription_update_minutes",60);
        SubscriptionUpdates.migrate(p.data);assertTrue(SubscriptionUpdates.options(p,"","https://example.org/one").getBoolean("auto_update"));
        SubscriptionUpdates.edit(p,"","https://example.org/one","Nodes","https://example.org/new",false,1440);
        assertEquals(1,p.nodes().size());assertEquals("https://example.org/new",p.nodes().get(0).source);
        assertFalse(SubscriptionUpdates.due(p,System.currentTimeMillis()));assertEquals("Nodes",SubscriptionUpdates.name(p,"https://example.org/new"));
    }
    @Test public void mihomoUsesNativeUnifiedTimingWithoutEditingSource()throws Exception {
        Profiles p=new Profiles(folder.getRoot());String source="proxies: []\nproxy-groups: [{name: Main, type: select, proxies: [DIRECT]}]\nrules: [MATCH,DIRECT]\nunified-delay: false";
        p.importConfig(source,null,null);assertEquals(Boolean.TRUE,Profiles.yamlMap(p.compile("secret",31080,31090,true,folder.getRoot())).get("unified-delay"));assertEquals(source,p.activeConfig().getString("content"));
    }
}
