package com.wbox.app;

import org.json.*;
import org.junit.*;
import java.io.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.CancellationException;
import static org.junit.Assert.*;

public class RuntimeChangesTest {
    File folder; Profiles profiles;
    static final String CONFIG="{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"DIRECT\"}]}";
    @Before public void setup()throws Exception{folder=Files.createTempDirectory("wbox-runtime-").toFile();profiles=new Profiles(folder);profiles.importConfig(CONFIG,null,null,true);profiles.save();}
    @After public void cleanup()throws Exception{try(var paths=Files.walk(folder.toPath())){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException ignored){}});}}
    final class Connection implements NodeChanges.Connection {
        int starts,stops;boolean active=true,failNext,stopRequested,cancelDuringStart;
        public boolean running(){return active;}
        public boolean cancelled(){return stopRequested;}
        public void stop(){stops++;active=false;}
        public void start()throws Exception{starts++;if(cancelDuringStart){stopRequested=true;return;}if(failNext){failNext=false;throw new IOException("Synthetic startup failure");}active=true;}
    }
    @Test public void activeConfigEditReloadsAndFailedReloadRestoresData()throws Exception{
        Connection connection=new Connection();String previous=profiles.activeConfig().getString("content");
        Profiles next=profiles.copy();next.activeConfig().put("content",CONFIG.replace("DIRECT","Edited"));
        RuntimeChanges.commit(profiles,next.data,connection);assertEquals(1,connection.starts);assertEquals(1,connection.stops);assertTrue(profiles.activeConfig().getString("content").contains("Edited"));
        String committed=profiles.data.toString();next=profiles.copy();next.activeConfig().put("content",previous);connection.failNext=true;
        try{RuntimeChanges.commit(profiles,next.data,connection);fail();}catch(IOException expected){}
        assertEquals(committed,profiles.data.toString());assertTrue(new Profiles(folder).activeConfig().getString("content").contains("Edited"));
        assertTrue(connection.active);assertEquals(3,connection.starts);
    }
    @Test public void namesAndSortDoNotRestartRuntime()throws Exception{
        Connection connection=new Connection();Profiles next=profiles.copy();next.activeConfig().put("name","Renamed");next.data.put("config_sort","name");
        RuntimeChanges.commit(profiles,next.data,connection);assertEquals(0,connection.starts);assertEquals(0,connection.stops);assertEquals("Renamed",new Profiles(folder).activeConfig().getString("name"));
    }
    private void nodeWorkspace()throws Exception{
        profiles.setWorkspace("nodes");profiles.importNodes(ProfilesTest.SS+"\n"+ProfilesTest.SS.replace("#Test","#Next"),null);profiles.data.put("selected","Test");profiles.save();
    }
    @Test public void offlineNodeSelectionIsSavedWithoutStarting()throws Exception{
        nodeWorkspace();Connection connection=new Connection();connection.active=false;
        Profiles next=profiles.copy();next.data.put("selected","Next");RuntimeChanges.commit(profiles,next.data,connection);
        assertEquals("Next",new Profiles(folder).data.getString("selected"));assertEquals(0,connection.starts);assertEquals(0,connection.stops);assertFalse(connection.active);
    }
    @Test public void connectedNodeSelectionSwitchesTheRunningTarget()throws Exception{
        nodeWorkspace();Connection connection=new Connection();
        Profiles next=profiles.copy();next.data.put("selected","Next");RuntimeChanges.commit(profiles,next.data,connection);
        assertEquals("Next",new Profiles(folder).data.getString("selected"));assertEquals(1,connection.starts);assertEquals(1,connection.stops);assertTrue(connection.active);
    }
    @Test public void selectingCurrentNodeKeepsTheRunningConnection()throws Exception{
        nodeWorkspace();Connection connection=new Connection();
        Profiles next=profiles.copy();next.data.put("selected","Test");RuntimeChanges.commit(profiles,next.data,connection);
        assertEquals("Test",new Profiles(folder).data.getString("selected"));assertEquals(0,connection.starts);assertEquals(0,connection.stops);assertTrue(connection.active);
    }
    @Test public void appRoutingAppliesImmediatelyAndOrderingIsEquivalent()throws Exception{
        Connection connection=new Connection();Profiles next=profiles.copy();next.data.put("app_mode","exclude").put("apps",new JSONArray().put("b.app").put("a.app"));
        RuntimeChanges.commit(profiles,next.data,connection);assertEquals(1,connection.starts);
        next=profiles.copy();next.data.put("apps",new JSONArray().put("a.app").put("b.app"));RuntimeChanges.commit(profiles,next.data,connection);assertEquals(1,connection.starts);
    }
    @Test public void deletingLastProfileStopsWithoutRestart()throws Exception{
        Connection connection=new Connection();Profiles next=profiles.copy();next.data.put("configs",new JSONArray()).remove("active_config");next.data.remove("config_sing-box");next.data.remove("config_mihomo");
        RuntimeChanges.commit(profiles,next.data,connection);assertEquals(1,connection.stops);assertEquals(0,connection.starts);assertFalse(connection.active);
    }
    @Test public void userStopDuringTransactionPreventsRestart()throws Exception{
        Connection connection=new Connection();connection.stopRequested=true;Profiles next=profiles.copy();next.activeConfig().put("content",CONFIG.replace("DIRECT","Next"));
        String previous=profiles.data.toString();
        try{RuntimeChanges.commit(profiles,next.data,connection);fail();}catch(CancellationException expected){}
        assertEquals(0,connection.starts);assertFalse(connection.active);assertEquals(previous,profiles.data.toString());
        assertEquals(CONFIG,new Profiles(folder).activeConfig().getString("content"));
    }
    @Test public void cancellationDuringStartupRollsBackWithoutReconnecting()throws Exception{
        Connection connection=new Connection();connection.cancelDuringStart=true;Profiles next=profiles.copy();next.activeConfig().put("content",CONFIG.replace("DIRECT","Next"));
        String previous=profiles.data.toString();
        try{RuntimeChanges.commit(profiles,next.data,connection);fail();}catch(CancellationException expected){}
        assertEquals(1,connection.starts);assertFalse(connection.active);assertEquals(previous,profiles.data.toString());
        assertEquals(CONFIG,new Profiles(folder).activeConfig().getString("content"));
    }
}
