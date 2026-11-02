package com.wbox.app;

import org.junit.*;
import org.json.*;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class ProfilesTest {
    @Test public void defaultsToConfigAndRemembersExplicitWorkspace()throws Exception {
        assertEquals("config",p.mode());p.setWorkspace("nodes");p.save();p.load();assertEquals("nodes",p.mode());
    }
    @Test public void jsonEscapesUseJsonGrammar()throws Exception {
        String text="{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"a\\/b\"}],\"route\":{\"final\":\"a\\/b\"}}";
        java.util.Map<?,?> route=(java.util.Map<?,?>)Profiles.yamlMap(text).get("route");
        assertEquals("a/b",route.get("final"));
    }
    @Test public void flagsAcrossReaderBoundaryDoNotBreakYamlImport()throws Exception {
        String prefix="proxies:\n  - name: \"";
        for(int boundary:new int[]{1023,2047,3071,4095}) {
            String name="x".repeat(boundary-prefix.length())+"🇯🇵 Tokyo";
            String yaml=prefix+name+"\"\n    type: ss\n    server: example.test\n    port: 443\n    cipher: aes-128-gcm\n    password: test\n";
            java.util.Map<?,?> node=(java.util.Map<?,?>)((java.util.List<?>)Profiles.yamlMap(yaml).get("proxies")).get(0);
            assertEquals(name,node.get("name"));
        }
    }
    private File folder;
    private Profiles p;
    static final String SS="ss://YWVzLTEyOC1nY206cGFzc3dvcmQ@127.0.0.1:8388#Test";
    @Before public void setup()throws Exception {folder=Files.createTempDirectory("wbox-test-").toFile();p=new Profiles(folder);}
    @After public void cleanup()throws Exception{try(var s=Files.walk(folder.toPath())){s.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(IOException ignored){}});}}
    @Test public void ipv6AndPercentPassword()throws Exception{Node n=Node.parse("trojan://p%40ss@[::1]:443?sni=example.com#IPv6");assertEquals("::1",n.server);assertEquals("p@ss",n.password);assertTrue(n.tls);assertEquals("example.com",n.sni);}
    @Test public void androidDoesNotRequestPrivilegedNetlinkOrDesktopBindings()throws Exception {
        String source="{\"route\":{\"auto_detect_interface\":true,\"default_interface\":\"Ethernet\",\"default_mark\":123,\"final\":\"direct\",\"rules\":[{\"domain_suffix\":[\"example.org\"],\"outbound\":\"direct\"}]},\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\",\"bind_interface\":\"Ethernet\",\"routing_mark\":123}],\"dns\":{\"servers\":[{\"type\":\"udp\",\"tag\":\"dns\",\"server\":\"1.1.1.1\",\"bind_interface\":\"Ethernet\"}]}}";
        p.importConfig(source,null,null);p.setWorkspace("config");JSONObject compiled=new JSONObject(p.compile("test"));
        assertFalse(compiled.getJSONObject("route").getBoolean("auto_detect_interface"));assertFalse(compiled.getJSONObject("route").has("default_interface"));assertEquals(1,compiled.getJSONObject("route").getJSONArray("rules").length());
        assertFalse(compiled.getJSONArray("outbounds").getJSONObject(0).has("bind_interface"));assertFalse(compiled.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).has("bind_interface"));assertEquals(source,p.activeConfig().getString("content"));
        p.importConfig("interface-name: Ethernet\nrouting-mark: 123\nproxies: []\nrules: [MATCH,DIRECT]\n",null,null);Map<String,Object> yaml=Profiles.yamlMap(p.compile("test"));assertFalse(yaml.containsKey("interface-name"));assertFalse(yaml.containsKey("routing-mark"));
    }
    @Test public void namedProfileKeepsNameAndIsolatedFakeIpCacheAcrossUpdates()throws Exception{
        String cfg="{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"DIRECT\"}]}";p.importConfig(cfg,"https://example.org/config",null);p.setWorkspace("config");String id=p.activeConfig().getString("id");p.activeConfig().put("name","我的配置");JSONObject first=new JSONObject(p.compile("s")).getJSONObject("experimental").getJSONObject("cache_file");assertTrue(first.getBoolean("enabled"));assertTrue(first.getBoolean("store_fakeip"));p.importConfig(cfg,"https://example.org/config",id);assertEquals("我的配置",p.activeConfig().getString("name"));assertEquals(first.getString("path"),new JSONObject(p.compile("s")).getJSONObject("experimental").getJSONObject("cache_file").getString("path"));p.importConfig(cfg,null,null);assertNotEquals(first.getString("path"),new JSONObject(p.compile("s")).getJSONObject("experimental").getJSONObject("cache_file").getString("path"));
    }
    @Test public void androidTunRulesFollowVpnTrafficThroughMixedInbound()throws Exception {
        p.importConfig("{\"inbounds\":[{\"type\":\"tun\",\"tag\":\"tun-in\"}],\"route\":{\"rules\":[{\"type\":\"logical\",\"mode\":\"and\",\"rules\":[{\"inbound\":[\"tun-in\",\"custom\"]}],\"outbound\":\"direct\"}]},\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}]}",null,null);p.setWorkspace("config");
        JSONArray in=new JSONObject(p.compile("s")).getJSONObject("route").getJSONArray("rules").getJSONObject(0).getJSONArray("rules").getJSONObject(0).getJSONArray("inbound");assertEquals("wbox-in",in.getString(0));assertEquals("custom",in.getString(1));
    }
    @Test public void groupLatencyTargetsResolveToRealNodeWithoutCycles()throws Exception{JSONObject groups=new JSONObject().put("A",new JSONObject().put("now","B")).put("B",new JSONObject().put("now","node"));assertEquals("node",NodeLatencyTester.resolve(groups,"A",new HashSet<>()));groups.getJSONObject("B").put("now","A");assertEquals("A",NodeLatencyTester.resolve(groups,"A",new HashSet<>()));}
    @Test public void subscriptionRefreshPreservesManual()throws Exception{p.importText(SS,null);String a=SS.replace("8388","8389");String b=SS.replace("8388","8390");p.importText(a,"https://example.com/sub");p.importText(b,"https://example.com/sub");assertEquals(2,p.nodes().size());String json=p.data.getJSONArray("nodes").toString();assertTrue(json.contains("8388"));assertTrue(json.contains("8390"));assertFalse(json.contains("8389"));}
    @Test public void base64Subscription()throws Exception{String b=Base64.getEncoder().encodeToString((SS+"\n"+SS.replace("8388","8389")).getBytes(StandardCharsets.UTF_8));p.importText(b,null);assertEquals(2,p.nodes().size());assertNotEquals(p.nodes().get(0).name,p.nodes().get(1).name);}
    @Test public void invalidPortRejected()throws Exception{try{Node.parse(SS.replace("8388","65536"));fail("invalid port");}catch(IllegalArgumentException expected){}}
    @Test public void defaultProxyPorts()throws Exception{assertEquals(80,Node.parse("http://example.org#HTTP").port);assertEquals(1080,Node.parse("socks://example.org#SOCKS").port);}
    @Test public void tlsAndSniAreNotDowngraded()throws Exception{Node https=Node.parse("https://user:password@127.0.0.1:443?sni=example.org#TLS");assertEquals(true,https.mihomo().get("tls"));assertEquals("example.org",https.mihomo().get("sni"));Node vless=Node.parse("vless://11111111-1111-4111-8111-111111111111@127.0.0.1:443?security=tls&sni=example.org");assertEquals("example.org",vless.mihomo().get("servername"));}
    @Test public void pluginExplicitlyRejected()throws Exception{try{Node.parse(SS.replace("#Test","?plugin=unknown-plugin#Test"));fail("must not silently discard plugin");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("插件"));}}
    @Test public void workspacesPreserveIndependentSelection()throws Exception{
        p.setWorkspace("nodes");
        p.importNodes(SS,null);p.data.put("engine","mihomo").put("selected","Test");
        p.importConfig("{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"DIRECT\"}]}","https://example.org/no-extension",null);
        assertEquals("nodes",p.mode());assertEquals("mihomo",p.engine());p.setWorkspace("config");assertEquals("sing-box",p.engine());
        p.importNodes(SS.replace("8388","8389"),"https://example.org/sub");assertEquals("config",p.mode());assertEquals("Test",p.data.getString("selected"));
        p.setWorkspace("nodes");assertEquals("mihomo",p.engine());assertEquals(2,p.nodes().size());
    }
    @Test public void sameLinkInManualGroupSurvivesSubscriptionRefresh()throws Exception{
        p.importNodes(SS,null);p.importNodes(SS,"https://example.org/sub");p.importNodes(SS.replace("8388","8389"),"https://example.org/sub");
        assertEquals(2,p.nodes().size());assertTrue(p.nodes().stream().anyMatch(n->n.port==8388&&n.source.isEmpty()));
    }
    @Test public void badUpdateKeepsPreviousConfigAndNodes()throws Exception{
        p.importConfig("proxies: []\nrules:\n - MATCH,DIRECT\n","https://example.org/sub",null);String before=p.data.toString();
        try{p.importConfig("<html>error</html>","https://example.org/sub",p.activeConfig().getString("id"));fail();}catch(IllegalArgumentException expected){}assertEquals(before,p.data.toString());
        p.importNodes(SS,"https://example.org/nodes");before=p.data.toString();try{p.importNodes(SS+"\ninvalid","https://example.org/nodes");fail();}catch(Exception expected){}assertEquals(before,p.data.toString());
    }
    @Test public void nativeSubscriptionRemainsNodeWorkspace()throws Exception{
        p.setWorkspace("nodes");
        p.importNodes("proxies:\n - {name: Native, type: ss, server: 127.0.0.1, port: 8388, cipher: aes-128-gcm, password: secret}\n","https://example.org/native");
        assertEquals("nodes",p.mode());p.data.put("engine","mihomo");assertTrue(p.compile("test").contains("secret"));assertNotNull(p.nodes().get(0).nativeMihomo);
    }
    @Test public void providedRemoteConfigImportsInBothWorkspaces()throws Exception{
        String path=System.getenv("WBOX_SUBSCRIPTION_TEST");Assume.assumeTrue(path!=null);String content=new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8);
        p.importConfig(content,"https://example.org/no-extension",null);p.setWorkspace("config");validateExpanded("sing-box");
        p.setWorkspace("nodes");p.importNodes(content,"https://example.org/no-extension");assertEquals(2,p.nodes().size());assertEquals("sing-box",p.engine());validateExpanded("sing-box");
    }
    @Test public void expandedProtocolsPassActualCores()throws Exception{
        p.setWorkspace("nodes");
        Assume.assumeTrue("1".equals(System.getenv("WBOX_CORE_TESTS")));
        byte[] bytes=new byte[32];for(int i=0;i<32;i++)bytes[i]=(byte)(i+1);String key=URLEncoder.encode(Base64.getEncoder().encodeToString(bytes),"UTF-8");
        String[] links={"anytls://pw@127.0.0.1:443?alpn=h2#AnyTLS","socks://user:p%2Bass@127.0.0.1:1080#SOCKS","https://user:pw@127.0.0.1:443#HTTPS","hysteria://127.0.0.1:443?auth=pw&upmbps=50&downmbps=100#HY1","hy2://pw@127.0.0.1:443?obfs=salamander&obfs-password=secret&alpn=h3#HY2","tuic://11111111-1111-4111-8111-111111111111:pw@127.0.0.1:443?congestion_control=bbr&udp_relay_mode=quic#TUIC",SS.replace("#Test","?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dexample.org#Obfs"),"wireguard://"+key+"@127.0.0.1:2408?publickey="+key+"&address=172.16.0.2%2F32%2Cfd00%3A%3A2%2F128&reserved=1,2,3#WG"};
        p.importNodes(String.join("\n",links),null);assertEquals("p+ass",p.nodes().get(1).password);
        for(String engine:List.of("sing-box","mihomo")){p.data.put("engine",engine);validateExpanded(engine);}
        p.data.remove("nodes");p.importNodes("naive+https://user:pw@127.0.0.1:443#Naive",null);p.data.put("engine","sing-box");assertFalse(p.nodes().get(0).compatible("sing-box"));
    }
    void prepareData(String engine,String content)throws Exception {
        if(engine.equals("mihomo"))Geodata.prepare(folder,Profiles.yamlMap(content),name->new FileInputStream(new File(System.getProperty("wbox.root"),"resources/geodata/"+name)),message->{});
    }
    void validateExpanded(String engine)throws Exception{
        File cf=new File(folder,"expanded.json");String compiled=p.compile("test");prepareData(engine,compiled);Files.write(cf.toPath(),compiled.getBytes(StandardCharsets.UTF_8));File exe=new File(System.getProperty("wbox.root"),"resources/cores/windows/"+engine+".exe");
        List<String> cmd=engine.equals("sing-box")?List.of(exe.getAbsolutePath(),"check","-c",cf.getAbsolutePath()):List.of(exe.getAbsolutePath(),"-t","-f",cf.getAbsolutePath(),"-d",folder.getAbsolutePath());
        Process process=new ProcessBuilder(cmd).redirectErrorStream(true).start();try{assertTrue(process.waitFor(25,TimeUnit.SECONDS));assertEquals(new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8),0,process.exitValue());}finally{if(process.isAlive())process.destroyForcibly();}
    }
    @Test public void yamlRetainsTypesAndRules()throws Exception{String src="proxies: []\nmode: rule\nipv6: false\nallow-lan: true\nrules: [MATCH,DIRECT]\ndns: {enable: false}\n";p.importText(src,null);Map<String,Object> out=Profiles.yamlMap(p.compile("secret"));assertEquals(false,out.get("ipv6"));assertEquals(false,((Map<?,?>)out.get("dns")).get("enable"));assertEquals(List.of("MATCH","DIRECT"),out.get("rules"));assertEquals(false,out.get("allow-lan"));assertEquals("secret",out.get("secret"));assertEquals(10808,out.get("mixed-port"));}
    @Test public void jsonRetainsRoutesAndOtherInbound()throws Exception{String src="{\"inbounds\":[{\"type\":\"tun\",\"tag\":\"old\"},{\"type\":\"http\",\"tag\":\"keep\",\"listen_port\":8099}],\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}],\"route\":{\"final\":\"direct\"}}";p.importText(src,null);JSONObject out=new JSONObject(p.compile("secret"));assertEquals("direct",out.getJSONObject("route").getString("final"));assertEquals(2,out.getJSONArray("inbounds").length());assertEquals("keep",out.getJSONArray("inbounds").getJSONObject(0).getString("tag"));assertEquals(src,p.data.getString("config_sing-box"));}
    @Test public void bothCompilersSupportProtocols()throws Exception{String[] links={SS,"vless://b831381d-6324-4d53-ad4f-8cda48b30811@127.0.0.1:443?security=tls&sni=example.com&type=ws&path=%2Fa#VLESS","trojan://pw@127.0.0.1:443#Trojan","hy2://pw@127.0.0.1:443#HY2","tuic://b831381d-6324-4d53-ad4f-8cda48b30811:pw@127.0.0.1:443#TUIC"};p.importText(String.join("\n",links),null);for(String e:List.of("sing-box","mihomo")){p.data.put("engine",e);String config=p.compile("s");assertTrue(config.contains("节点选择"));assertTrue(config.contains("127.0.0.1"));}}
    @Test public void selectedNodeAppliedToBoth()throws Exception{p.importText(SS+"\n"+SS.replace("#Test","#Second"),null);p.data.put("selected","Second");JSONObject sb=new JSONObject(p.compile("s"));JSONArray out=sb.getJSONArray("outbounds");assertEquals("Second",out.getJSONObject(0).getString("default"));p.data.put("engine","mihomo");Map<String,Object> yaml=Profiles.yamlMap(p.compile("s"));Map<?,?> group=(Map<?,?>)((List<?>)yaml.get("proxy-groups")).get(0);assertEquals("Second",((List<?>)group.get("proxies")).get(0));}
    @Test public void flagNamesPassBothCoreCompilers()throws Exception{
        p.setWorkspace("nodes");
        p.importNodes(SS.replace("#Test","#%F0%9F%87%AF%F0%9F%87%B5%20Tokyo"),null);
        p.data.put("selected","🇯🇵 Tokyo");
        for(String engine:List.of("sing-box","mihomo")){
            p.data.put("engine",engine);String compiled=p.compile("test");
            assertTrue(compiled.contains("🇯🇵 Tokyo")||Profiles.yamlMap(compiled).toString().contains("🇯🇵 Tokyo"));
            if("1".equals(System.getenv("WBOX_CORE_TESTS")))validateExpanded(engine);
        }
    }
    @Test public void protocolAndRealityConfigsPassActualCoreValidation()throws Exception{
        Assume.assumeTrue("1".equals(System.getenv("WBOX_CORE_TESTS")));
        String id="b831381d-6324-4d53-ad4f-8cda48b30811";
        String vmess="vmess://"+Base64.getEncoder().encodeToString(("{\"add\":\"127.0.0.1\",\"port\":443,\"id\":\""+id+"\",\"ps\":\"VMess\",\"net\":\"tcp\",\"scy\":\"auto\"}").getBytes(StandardCharsets.UTF_8));
        p.importText(String.join("\n",SS,vmess,"vless://"+id+"@127.0.0.1:443?security=tls&sni=example.com&type=ws&path=%2Fa#VLESS","trojan://pw@127.0.0.1:443#Trojan","hy2://pw@127.0.0.1:443#HY2","tuic://"+id+":pw@127.0.0.1:443#TUIC","vless://"+id+"@127.0.0.1:443?security=reality&sni=example.com&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=12345678&fp=chrome#Reality"),null);
        File root=new File(System.getProperty("wbox.root"));
        for(String engine:List.of("sing-box","mihomo")){p.data.put("engine",engine);File cf=new File(folder,engine.equals("sing-box")?"config.json":"config.yaml");String compiled=p.compile("s");prepareData(engine,compiled);Files.write(cf.toPath(),compiled.getBytes(StandardCharsets.UTF_8));File exe=new File(root,"resources/cores/windows/"+(engine.equals("sing-box")?"sing-box.exe":"mihomo.exe"));List<String> command=engine.equals("sing-box")?List.of(exe.getAbsolutePath(),"check","-c",cf.getAbsolutePath(),"-D",folder.getAbsolutePath()):List.of(exe.getAbsolutePath(),"-t","-f",cf.getAbsolutePath(),"-d",folder.getAbsolutePath());Process check=new ProcessBuilder(command).redirectErrorStream(true).start();try{assertTrue("config check timeout",check.waitFor(15,TimeUnit.SECONDS));String log=new String(check.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(engine+": "+log,0,check.exitValue());System.out.println("PASS "+engine+" six protocols + Reality config check");}finally{if(check.isAlive())check.destroyForcibly();}}
    }
    @Test public void realCoresForwardHttpOverIpv4AndIpv6()throws Exception{
        Assume.assumeTrue("Enable real-core integration via WBOX_CORE_TESTS=1", "1".equals(System.getenv("WBOX_CORE_TESTS")));
        File root=new File(System.getProperty("wbox.root"));p.importText(SS,null);
        for(String engine:List.of("sing-box","mihomo")){
            p.importConfig(engine.equals("sing-box")?"{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"DIRECT\"}],\"route\":{\"final\":\"DIRECT\"}}":"proxies: []\nrules: ['MATCH,DIRECT']\n",null,null);p.setWorkspace("config");int mixed,controller;try(ServerSocket a=new ServerSocket(0);ServerSocket b=new ServerSocket(0)){mixed=a.getLocalPort();controller=b.getLocalPort();}String config=p.compile("integration-secret",mixed,controller,true);File cf=new File(folder,engine.equals("sing-box")?"config.json":"config.yaml");Files.write(cf.toPath(),config.getBytes(StandardCharsets.UTF_8));
            File exe=new File(root,"resources/cores/windows/"+(engine.equals("sing-box")?"sing-box.exe":"mihomo.exe"));List<String> cmd=new ArrayList<>(List.of(exe.getAbsolutePath()));if(engine.equals("sing-box"))cmd.addAll(List.of("run","-c",cf.getAbsolutePath(),"-D",folder.getAbsolutePath()));else cmd.addAll(List.of("-f",cf.getAbsolutePath(),"-d",folder.getAbsolutePath()));
            File log=new File(folder,engine+".log");Process core=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start();
            try{CoreApi api=new CoreApi("integration-secret",controller);boolean ready=false;for(int i=0;i<100;i++){if(!core.isAlive())fail(engine+" failed: "+new String(Files.readAllBytes(log.toPath()),StandardCharsets.UTF_8));try{api.checkReady(1000,mixed);ready=true;break;}catch(Exception ignored){Thread.sleep(100);}}assertTrue("core ready",ready);
                try {for(String address:List.of("127.0.0.1","::1")){testHttp(address,mixed);testUdp(address,mixed);}}
                catch(Exception failure){throw new AssertionError(engine+" forwarding failed: "+new String(Files.readAllBytes(log.toPath()),StandardCharsets.UTF_8),failure);}
                JSONObject stats=api.connections();assertTrue(engine+" upload",stats.getLong("uploadTotal")>0);assertTrue(engine+" download",stats.getLong("downloadTotal")>0);
                System.out.println("PASS actual "+engine+" Android-generated config: selector / HTTP+SOCKS5 UDP IPv4+IPv6 / traffic counters");
            }finally{core.destroy();if(!core.waitFor(3,TimeUnit.SECONDS))core.destroyForcibly();}
        }
    }
    static void testHttp(String host,int mixed)throws Exception{
        ServerSocket server=new ServerSocket(0,1,InetAddress.getByName(host));ExecutorService service=Executors.newSingleThreadExecutor();Future<?> served=service.submit(()->{try(Socket s=server.accept()){BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));String line;while((line=in.readLine())!=null&&!line.isEmpty()){}byte[] body="wbox-real-proxy-ok".getBytes(StandardCharsets.UTF_8);s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));s.getOutputStream().write(body);s.getOutputStream().flush();}catch(IOException ex){throw new RuntimeException(ex);}});
        try{String address=host.contains(":")?"["+host+"]":host;HttpURLConnection c=(HttpURLConnection)new URL("http://"+address+":"+server.getLocalPort()+"/").openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",mixed)));c.setReadTimeout(5000);c.setConnectTimeout(3000);try{assertEquals("wbox-real-proxy-ok",new String(c.getInputStream().readAllBytes(),StandardCharsets.UTF_8));}finally{c.disconnect();}served.get(5,TimeUnit.SECONDS);}finally{server.close();service.shutdownNow();}
    }
    static void testUdp(String host,int mixed)throws Exception{
        DatagramSocket upstream=new DatagramSocket(0,InetAddress.getByName(host));upstream.setSoTimeout(5000);ExecutorService service=Executors.newSingleThreadExecutor();Future<?> echoed=service.submit(()->{try{byte[] b=new byte[1024];DatagramPacket packet=new DatagramPacket(b,b.length);upstream.receive(packet);upstream.send(packet);}catch(IOException ex){throw new RuntimeException(ex);}});
        try(Socket tcp=new Socket("127.0.0.1",mixed);DatagramSocket udp=new DatagramSocket()){
            tcp.setSoTimeout(5000);udp.setSoTimeout(5000);DataInputStream in=new DataInputStream(tcp.getInputStream());OutputStream out=tcp.getOutputStream();out.write(new byte[]{5,1,0});out.flush();assertEquals(5,in.readUnsignedByte());assertEquals(0,in.readUnsignedByte());out.write(new byte[]{5,3,0,1,0,0,0,0,0,0});out.flush();assertEquals(5,in.readUnsignedByte());assertEquals(0,in.readUnsignedByte());in.readUnsignedByte();int type=in.readUnsignedByte();int len=type==1?4:type==4?16:in.readUnsignedByte();byte[] addr=in.readNBytes(len);int port=in.readUnsignedShort();InetAddress relay=type==3?InetAddress.getByName(new String(addr,StandardCharsets.UTF_8)):InetAddress.getByAddress(addr);if(relay.isAnyLocalAddress())relay=InetAddress.getByName("127.0.0.1");
            byte[] target=InetAddress.getByName(host).getAddress(),payload="wbox-udp-ok".getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream frame=new ByteArrayOutputStream();frame.write(new byte[]{0,0,0,(byte)(target.length==4?1:4)});frame.write(target);frame.write((upstream.getLocalPort()>>>8)&255);frame.write(upstream.getLocalPort()&255);frame.write(payload);byte[] request=frame.toByteArray();udp.send(new DatagramPacket(request,request.length,relay,port));byte[] response=new byte[2048];DatagramPacket reply=new DatagramPacket(response,response.length);udp.receive(reply);int skip=response[3]==1?10:response[3]==4?22:7+(response[4]&255);assertEquals("wbox-udp-ok",new String(response,skip,reply.getLength()-skip,StandardCharsets.UTF_8));echoed.get(5,TimeUnit.SECONDS);
        }finally{upstream.close();service.shutdownNow();}
    }
}
