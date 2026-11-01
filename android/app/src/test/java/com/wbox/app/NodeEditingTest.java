package com.wbox.app;

import org.json.*;
import org.junit.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class NodeEditingTest {
    private File folder;private Profiles profiles;
    @Before public void setup()throws Exception{folder=Files.createTempDirectory("wbox-edit-").toFile();profiles=new Profiles(folder);profiles.importNodes(ProfilesTest.SS,"https://example.org/sub");}
    @After public void cleanup()throws Exception{try(var paths=Files.walk(folder.toPath())){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException ignored){}});}}
    @Test public void editsPersistAcrossReloadAndSubscriptionUpdates()throws Exception{
        Node original=profiles.nodes().get(0);profiles.data.put("selected",original.name);JSONObject fields=original.fields().put("name","Edited").put("port",9000).put("options",new JSONObject().put("plugin","obfs-local;obfs=http;obfs-host=example.org"));
        NodeEditing.replace(profiles,0,NodeEditing.revision(original),fields);profiles.save();Profiles loaded=new Profiles(folder);Node edited=loaded.nodes().get(0);
        assertEquals("Edited",edited.name);assertEquals("Edited",loaded.data.optString("selected"));assertEquals(9000,edited.port);assertEquals(original.source,edited.source);assertTrue(edited.singbox().getString("plugin_opts").contains("obfs-host"));
        NodeEditing.cloneNode(loaded,0);loaded.save();loaded.importNodes(ProfilesTest.SS,original.source);assertEquals(2,loaded.nodes().size());assertTrue(loaded.nodes().stream().anyMatch(n->n.name.equals("Edited 副本")&&n.source.isEmpty()&&n.port==9000));
    }
    @Test public void invalidPortAndStaleRevisionKeepOriginal()throws Exception{
        Node original=profiles.nodes().get(0);String before=profiles.data.toString(),revision=NodeEditing.revision(original);
        try{NodeEditing.replace(profiles,0,revision,original.fields().put("port",70000));fail();}catch(IllegalArgumentException expected){}assertEquals(before,profiles.data.toString());
        NodeEditing.replace(profiles,0,revision,original.fields().put("server","new.example.org"));before=profiles.data.toString();try{NodeEditing.replace(profiles,0,revision,original.fields());fail();}catch(IllegalArgumentException expected){}assertEquals(before,profiles.data.toString());
    }
    @Test public void nativeFieldsAndAdvancedParametersSurviveEditing()throws Exception{
        for(boolean sb:List.of(true,false)){JSONObject nativeNode=new JSONObject().put("type",sb?"shadowsocks":"ss").put(sb?"tag":"name","Native").put("server","example.org").put(sb?"server_port":"port",8388).put(sb?"method":"cipher","aes-128-gcm").put("password","synthetic").put("udp",true);Node original=Node.stored(new JSONObject().put(sb?"singbox":"mihomo",nativeNode));Node edited=NodeEditing.prepare(original,new JSONObject(nativeNode.toString()).put(sb?"tag":"name","New"));Node reloaded=Node.stored(edited.storage());assertEquals("New",reloaded.name);assertTrue((sb?reloaded.nativeSingbox:reloaded.nativeMihomo).getBoolean("udp"));}
        Node reality=Node.parse("vless://11111111-1111-4111-8111-111111111111@example.org:443?security=reality&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=12345678&fp=chrome#Reality");Node changed=NodeEditing.prepare(reality,reality.fields().put("name","R"));assertEquals(reality.realityKey,changed.realityKey);assertEquals(reality.shortId,changed.shortId);
    }
    @Test public void automaticImportDistinguishesSubscriptionAndNodeUrls(){assertTrue(Profiles.subscriptionAddress("https://example.org/sub?token=test"));assertFalse(Profiles.subscriptionAddress(ProfilesTest.SS));assertFalse(Profiles.subscriptionAddress("https://user:pass@example.org:443#Proxy"));assertFalse(Profiles.subscriptionAddress("http://example.org:8080#Proxy"));assertFalse(Profiles.subscriptionAddress("https://example.org/sub\n"+ProfilesTest.SS));}
    static class Connection implements NodeChanges.Connection {boolean live=true,cancelled,failNext;int starts,stops;public boolean running(){return live;}public boolean cancelled(){return cancelled;}public void stop(){live=false;stops++;}public void start()throws Exception{starts++;if(failNext){failNext=false;throw new IOException("synthetic startup failure");}live=true;}}
    @Test public void failedActivationRestoresSavedNodesAndConnection()throws Exception{
        profiles.data.put("mode","nodes");profiles.save();String before=profiles.data.toString();Profiles staged=profiles.copy();staged.importNodes(ProfilesTest.SS.replace("#Test","#Second"),null);assertEquals(1,new Profiles(folder).nodes().size());
        Connection connection=new Connection();connection.failNext=true;try{NodeChanges.commit(profiles,staged.data,connection);fail();}catch(IOException expected){}assertTrue(connection.live);assertEquals(2,connection.starts);assertEquals(before,new Profiles(folder).data.toString());
    }
    @Test public void cancelledChangeNeverRestartsAndConfigWorkspaceDoesNotRestart()throws Exception{
        profiles.data.put("mode","nodes");Profiles staged=profiles.copy();staged.importNodes(ProfilesTest.SS.replace("#Test","#Second"),null);Connection connection=new Connection();connection.cancelled=true;NodeChanges.commit(profiles,staged.data,connection);assertEquals(0,connection.starts);assertFalse(connection.live);
        profiles.data.put("mode","config");staged=profiles.copy();connection=new Connection();NodeChanges.commit(profiles,staged.data,connection);assertEquals(0,connection.stops);assertEquals(0,connection.starts);assertTrue(connection.live);
    }
}
