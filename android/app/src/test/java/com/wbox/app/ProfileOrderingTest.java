package com.wbox.app;

import org.json.*;
import org.junit.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class ProfileOrderingTest {
    private File folder;
    private Profiles profiles;
    private String a,b,c;
    private static final String CONFIG="{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"DIRECT\"}]}";
    @Before public void setup()throws Exception {
        folder=Files.createTempDirectory("wbox-order-").toFile();profiles=new Profiles(folder);
        a=add("Zulu");b=add("Alpha");c=add("Beta");profiles.activateConfig(b);profiles.save();
    }
    private String add(String name)throws Exception {
        profiles.importConfig(CONFIG,null,null,false);JSONObject p=profiles.configs().getJSONObject(profiles.configs().length()-1);p.put("name",name);profiles.save();return p.getString("id");
    }
    private static List<String> ids(Profiles p){return p.orderedConfigs().stream().map(j->j.optString("id")).toList();}
    @After public void cleanup()throws Exception {
        try(var paths=Files.walk(folder.toPath())){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException ignored){}});}
    }
    @Test public void manualOrderPersistsWithoutChangingProfileOrSelection()throws Exception {
        String content=profiles.configs().toString();String identity=RuntimeChanges.identity(profiles);
        profiles.data.put("config_sort","name");profiles.reorderConfigs(List.of(c,a,b));profiles.save();
        Profiles restored=new Profiles(folder);assertEquals(List.of(c,a,b),ids(restored));assertEquals("default",restored.data.getString("config_sort"));
        assertEquals(content,restored.configs().toString());assertEquals(b,restored.data.getString("active_config"));assertEquals(identity,RuntimeChanges.identity(restored));
    }
    @Test public void automaticSortRetainsManualOrderForReturningToIt()throws Exception {
        profiles.reorderConfigs(List.of(c,a,b));profiles.data.put("config_sort","name");assertEquals(List.of(b,c,a),ids(profiles));
        profiles.data.put("config_sort","default");assertEquals(List.of(c,a,b),ids(profiles));
        profiles.data.put("config_sort","updated");profiles.configs().getJSONObject(0).put("updated",3);profiles.configs().getJSONObject(1).put("updated",1);profiles.configs().getJSONObject(2).put("updated",2);
        assertEquals(List.of(a,c,b),ids(profiles));
    }
    @Test public void oldDragSnapshotNeverRecreatesDeletedProfilesAndAppendsNewOnes()throws Exception {
        profiles.data.put("configs",new JSONArray().put(profiles.configs().getJSONObject(1)).put(profiles.configs().getJSONObject(2)));profiles.save();
        String d=add("New");profiles.reorderConfigs(List.of(c,a,b,c,"unknown"));profiles.save();
        assertEquals(List.of(c,b,d),ids(new Profiles(folder)));assertEquals(3,new Profiles(folder).configs().length());
    }
    @Test public void backgroundUpdateAfterDragPreservesBothOrderAndDownloadedContent()throws Exception {
        Profiles download=new Profiles(folder);JSONObject p=download.configs().getJSONObject(0);p.put("content",CONFIG.replace("DIRECT","Downloaded")).put("updated",987654321);
        profiles.reorderConfigs(List.of(c,b,a));profiles.save();download.save();
        Profiles restored=new Profiles(folder);assertEquals(List.of(c,b,a),ids(restored));assertTrue(restored.configs().getJSONObject(0).getString("content").contains("Downloaded"));
        assertEquals(987654321,restored.configs().getJSONObject(0).getLong("updated"));
    }
    @Test public void concurrentImportAndRemovalArePreservedWhenDragSavesLast()throws Exception {
        Profiles drag=new Profiles(folder);drag.reorderConfigs(List.of(c,a,b));
        profiles.data.put("configs",new JSONArray().put(profiles.configs().getJSONObject(0)).put(profiles.configs().getJSONObject(2)));profiles.save();String d=add("Concurrent");
        drag.save();Profiles restored=new Profiles(folder);assertEquals(List.of(c,a,d),ids(restored));assertEquals(3,restored.configs().length());
    }
}
