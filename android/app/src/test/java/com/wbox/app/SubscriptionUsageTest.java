package com.wbox.app;

import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SubscriptionUsageTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void parsesPartialAndMalformedHeaders()throws Exception {
        JSONObject usage=SubscriptionUsage.parse("UPLOAD=1024; download=2048; total=4294967296; expire=1799999999");
        assertEquals(1024,usage.getLong("upload"));assertEquals(4294967296L,usage.getLong("total"));
        assertNull(SubscriptionUsage.parse(null));assertNull(SubscriptionUsage.parse("upload=-1;total=NaN;expire=999999999999999999999"));
        usage=SubscriptionUsage.parse("download=0; total=0; expire=0");
        assertFalse(usage.has("upload"));assertEquals(0,usage.getLong("expire"));
    }
    @Test public void metadataStaysWithSourceAndClearsAfterUrlChange()throws Exception {
        Profiles p=new Profiles(temp.getRoot());String first="https://example.org/one",second="https://example.org/two";
        p.importNodes("http://user:pass@localhost:80#A",first);p.importNodes("http://user:pass@localhost:81#B",second);
        SubscriptionUsage.apply(p,"",first,SubscriptionUsage.parse("upload=10;download=20;total=100"));p.save();
        p=new Profiles(temp.getRoot());assertEquals(100,SubscriptionUpdates.options(p,"",first).getJSONObject("usage").getLong("total"));
        assertFalse(SubscriptionUpdates.options(p,"",second).has("usage"));
        SubscriptionUpdates.edit(p,"",first,"Renamed","https://example.org/new",false,1440);
        assertFalse(SubscriptionUpdates.options(p,"","https://example.org/new").has("usage"));
    }
    @Test public void missingHeaderClearsOldMetadataOnlyOnSuccessfulImport()throws Exception {
        Profiles p=new Profiles(temp.getRoot());String url="https://example.org/sub";
        p.importNodes("http://user:pass@localhost:80#A",url);
        SubscriptionUsage.apply(p,"",url,SubscriptionUsage.parse("total=100"));p.save();
        try{p.importNodes("invalid",url);fail();}catch(Exception expected){}
        assertEquals(100,SubscriptionUpdates.options(p,"",url).getJSONObject("usage").getLong("total"));
        p.importNodes("http://user:pass@localhost:81#A",url);SubscriptionUsage.apply(p,"",url,null);p.save();
        assertFalse(SubscriptionUpdates.options(new Profiles(temp.getRoot()),"",url).has("usage"));
    }
    @Test public void nodeSelectionSurvivesRefreshAndOnlySelectedNodeCompiles()throws Exception {
        Profiles p=new Profiles(temp.getRoot());p.setWorkspace("nodes");
        String url="https://example.org/sub";
        p.importNodes("http://user:pass@localhost:80#A\nhttp://user:pass@localhost:81#B",url);
        assertEquals("A",p.data.getString("selected"));
        p.data.put("selected","B");p.save();
        p.importNodes("http://user:pass@localhost:82#B\nhttp://user:pass@localhost:83#A",url);
        assertEquals("B",p.data.getString("selected"));
        for(String core:new String[]{"sing-box","mihomo"}){
            p.data.put("engine",core).put("ipv6",false);p.save();p=new Profiles(temp.getRoot());
            String compiled=p.compile("test",31080,31090,true,temp.getRoot());
            assertTrue(compiled.contains("82"));assertFalse(compiled.contains("83"));assertFalse(p.data.has("ipv6"));
            if(core.equals("mihomo"))assertEquals(Boolean.TRUE,Profiles.yamlMap(compiled).get("ipv6"));
        }
    }
}
