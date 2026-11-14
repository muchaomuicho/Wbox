package com.wbox.app;

import org.json.*;
import org.junit.*;
import java.io.*;
import java.nio.file.*;
import java.util.Comparator;
import static org.junit.Assert.*;

public class LatencyContextTest {
    File folder;Profiles profiles;
    static final String CONFIG="{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"DIRECT\"}]}";
    @Before public void setup()throws Exception {folder=Files.createTempDirectory("wbox-latency-context-").toFile();profiles=new Profiles(folder);profiles.importConfig(CONFIG,null,null,true);}
    @After public void cleanup()throws Exception {try(var paths=Files.walk(folder.toPath())){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException ignored){}});}}

    @Test public void selectionsAppearanceAndAppRoutingDoNotChangeProbeIdentity()throws Exception {
        Profiles source=profiles.copy();String identity=LatencyContext.key(source);
        profiles.activeConfig().put("group_selections",new JSONObject().put("Main","Next"));
        profiles.data.put("theme","dark").put("app_mode","exclude").put("apps",new JSONArray().put("a.app"));
        assertEquals(identity,LatencyContext.key(profiles));assertTrue(LatencyContext.valid(profiles,source));
    }
    @Test public void switchingConfigurationKeepsOldSourceValidAndResultsDistinct()throws Exception {
        Profiles source=profiles.copy();String id=source.activeConfig().getString("id"),identity=LatencyContext.key(source);
        profiles.importConfig(CONFIG.replace("DIRECT","OTHER"),null,null,true);
        assertNotEquals(identity,LatencyContext.key(profiles));assertTrue(LatencyContext.valid(profiles,source));
        profiles.activateConfig(id);assertEquals(identity,LatencyContext.key(profiles));
        profiles.activeConfig().put("content",CONFIG.replace("DIRECT","EDITED"));assertFalse(LatencyContext.valid(profiles,source));
    }
    @Test public void removingSourceOrChangingTestAddressInvalidatesProbe()throws Exception {
        Profiles source=profiles.copy();profiles.data.put("test_url","https://other.test/probe");assertFalse(LatencyContext.valid(profiles,source));
        profiles.data.remove("test_url");profiles.data.put("configs",new JSONArray());assertFalse(LatencyContext.valid(profiles,source));
    }
    @Test public void nodeSelectionAndWorkspaceSwitchKeepNodeProbeValid()throws Exception {
        profiles.setWorkspace("nodes");profiles.importNodes(ProfilesTest.SS+"\n"+ProfilesTest.SS.replace("#Test","#Next"),null);
        Profiles source=profiles.copy();String identity=LatencyContext.key(source);
        profiles.data.put("selected","Next");assertEquals(identity,LatencyContext.key(profiles));assertTrue(LatencyContext.valid(profiles,source));
        profiles.setWorkspace("config");assertTrue(LatencyContext.valid(profiles,source));
        profiles.data.put("nodes",new JSONArray());assertFalse(LatencyContext.valid(profiles,source));
    }
}
