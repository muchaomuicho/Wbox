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
        assertFalse(SubscriptionUpdates.due(p,now));p.data.put("subscription_auto_update",true).put("subscription_update_minutes",60).put("subscription_last_attempt",now-3_599_000);
        assertFalse(SubscriptionUpdates.due(p,now));assertTrue(SubscriptionUpdates.due(p,now+1000));p.data.put("subscription_update_minutes",0);assertEquals(15,SubscriptionUpdates.interval(p));
    }
    @Test public void mihomoUsesNativeUnifiedTimingWithoutEditingSource()throws Exception {
        Profiles p=new Profiles(folder.getRoot());String source="proxies: []\nproxy-groups: [{name: Main, type: select, proxies: [DIRECT]}]\nrules: [MATCH,DIRECT]\nunified-delay: false";
        p.importConfig(source,null,null);assertEquals(Boolean.TRUE,Profiles.yamlMap(p.compile("secret",31080,31090,true,folder.getRoot())).get("unified-delay"));assertEquals(source,p.activeConfig().getString("content"));
    }
}
