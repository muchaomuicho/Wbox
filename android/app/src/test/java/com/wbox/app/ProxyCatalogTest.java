package com.wbox.app;

import org.json.*;
import org.junit.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class ProxyCatalogTest {
    @Test public void singleNodeDelayDoesNotExpandNestedGroup()throws Exception {
        var groups=new JSONObject("{\"Main\":{\"all\":[\"Nested\",\"B\"],\"now\":\"Nested\"},\"Nested\":{\"all\":[\"A\",\"B\"],\"now\":\"A\"}}");
        assertEquals(Set.of("A"),NodeLatencyTester.targets(groups,List.of("Nested"),false));
        assertEquals(Set.of("A"),NodeLatencyTester.targets(groups,List.of("A"),false));
        assertEquals(Set.of("A","B"),NodeLatencyTester.targets(groups,List.of("Main"),true));
        assertEquals(Set.of("A"),NodeLatencyTester.targets(groups,List.of("Nested","A"),false));
    }
    @Test public void configuredOrderWinsOverApiObjectOrder()throws Exception {
        var root=new JSONObject("{\"proxy-groups\":[{\"name\":\"Z\"},{\"name\":\"2\"},{\"name\":\"A\"}]}");
        var api=new JSONObject("{\"A\":{\"all\":[]},\"GLOBAL\":{\"all\":[]},\"2\":{\"all\":[]},\"Z\":{\"all\":[]},\"direct\":{}}");
        assertEquals(List.of("Z","2","A"),ProxyCatalog.ordered(api,ProxyCatalog.order(root,false)));
    }
    @Test public void nestedGroupDelayFollowsCurrentLeaf()throws Exception {
        var api=new JSONObject("{\"Main\":{\"now\":\"Auto\"},\"Auto\":{\"now\":\"One\"}}");
        assertEquals("One",ProxyCatalog.resolve(api,"Main"));
        api.getJSONObject("Auto").put("now","Two");assertEquals("Two",ProxyCatalog.resolve(api,"Main"));
        api.getJSONObject("Auto").put("now","Main");assertThrows(IllegalArgumentException.class,()->ProxyCatalog.resolve(api,"Main"));
    }
    @Test public void offlineGroupsExistWithoutStartingAService()throws Exception {
        var dir=Files.createTempDirectory("wbox-catalog");
        try {
            var p=new Profiles(dir.toFile());p.importConfig("{\"outbounds\":[{\"type\":\"selector\",\"tag\":\"First\",\"outbounds\":[\"DIRECT\"]},{\"type\":\"direct\",\"tag\":\"DIRECT\"}]}",null,null);
            var groups=ProxyCatalog.offline(p);assertEquals("DIRECT",groups.getJSONObject("First").getString("now"));assertEquals("direct",groups.getJSONObject("DIRECT").getString("type"));
        }finally{try(var paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(Exception ignored){}});}}
    }
    @Test public void disconnectedProbeRemovesNativeListenersAndKeepsTransportPath()throws Exception {
        var dir=Files.createTempDirectory("wbox-probe-config");
        try {
            var p=new Profiles(dir.toFile());p.importConfig("proxies: [{name: ws, type: vmess, server: example.org, port: 443, uuid: 00000000-0000-0000-0000-000000000001, network: ws, ws-opts: {path: /connect}}]\nproxy-groups: [{name: Main, type: select, proxies: [ws]}]\ntun: {enable: true}\nport: 7890\nlisteners: [{name: old, type: mixed, port: 8000}]\ndns: {enable: true, listen: '127.0.0.1:53'}\nrules: ['MATCH,Main']",null,null);
            var root=new JSONObject(Profiles.yamlMap(p.compile("secret",31080,31090,true,dir.toFile())));
            assertFalse(root.has("listeners"));assertFalse(root.has("port"));assertFalse(root.getJSONObject("tun").getBoolean("enable"));assertFalse(root.getJSONObject("dns").has("listen"));
            assertEquals("/connect",root.getJSONArray("proxies").getJSONObject(0).getJSONObject("ws-opts").getString("path"));
            assertFalse(root.getJSONObject("profile").getBoolean("store-selected"));
        }finally{try(var paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(Exception ignored){}});}}
    }
    @Test public void zeroDelayIsFailureAndUrlIsEncoded()throws Exception {
        try(var server=new java.net.ServerSocket(0)){
            var line=new java.util.concurrent.CompletableFuture<String>();
            Thread worker=new Thread(()->{try(var socket=server.accept()){
                var reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));line.complete(reader.readLine());while(!reader.readLine().isEmpty()){}
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\n{\"delay\":0}".getBytes());
            }catch(Exception e){line.completeExceptionally(e);}});worker.start();
            assertThrows(java.io.IOException.class,()->new CoreApi("secret",server.getLocalPort()).delay("a / b","https://example.org/test?a=1&b=2"));
            assertTrue(line.get().contains("timeout=5000"));assertTrue(line.get().contains("%26b%3D2"));worker.join();
        }
    }
}
