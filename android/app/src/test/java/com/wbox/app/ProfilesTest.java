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
    private File folder;
    private Profiles p;
    static final String SS="ss://YWVzLTEyOC1nY206cGFzc3dvcmQ@127.0.0.1:8388#Test";
    @Before public void setup()throws Exception {folder=Files.createTempDirectory("wbox-test-").toFile();p=new Profiles(folder);}
    @After public void cleanup()throws Exception{try(var s=Files.walk(folder.toPath())){s.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(IOException ignored){}});}}
    @Test public void ipv6AndPercentPassword()throws Exception{Node n=Node.parse("trojan://p%40ss@[::1]:443?sni=example.com#IPv6");assertEquals("::1",n.server);assertEquals("p@ss",n.password);assertTrue(n.tls);assertEquals("example.com",n.sni);}
    @Test public void subscriptionRefreshPreservesManual()throws Exception{p.importText(SS,null);String a=SS.replace("8388","8389");String b=SS.replace("8388","8390");p.importText(a,"https://example.com/sub");p.importText(b,"https://example.com/sub");assertEquals(2,p.nodes().size());String json=p.data.getJSONArray("nodes").toString();assertTrue(json.contains("8388"));assertTrue(json.contains("8390"));assertFalse(json.contains("8389"));}
    @Test public void base64Subscription()throws Exception{String b=Base64.getEncoder().encodeToString((SS+"\n"+SS.replace("8388","8389")).getBytes(StandardCharsets.UTF_8));p.importText(b,null);assertEquals(2,p.nodes().size());assertNotEquals(p.nodes().get(0).name,p.nodes().get(1).name);}
    @Test public void invalidPortRejected()throws Exception{try{Node.parse(SS.replace("8388","65536"));fail("invalid port");}catch(IllegalArgumentException expected){}}
    @Test public void pluginExplicitlyRejected()throws Exception{try{Node.parse(SS.replace("#Test","?plugin=v2ray-plugin#Test"));fail("must not silently discard plugin");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("插件"));}}
    @Test public void yamlRetainsTypesAndRules()throws Exception{String src="proxies: []\nmode: rule\nipv6: false\nallow-lan: true\nrules: [MATCH,DIRECT]\ndns: {enable: false}\n";p.importText(src,null);Map<String,Object> out=Profiles.yamlMap(p.compile("secret"));assertEquals(false,out.get("ipv6"));assertEquals(false,((Map<?,?>)out.get("dns")).get("enable"));assertEquals(List.of("MATCH","DIRECT"),out.get("rules"));assertEquals(false,out.get("allow-lan"));assertEquals("secret",out.get("secret"));assertEquals(10808,out.get("mixed-port"));}
    @Test public void jsonRetainsRoutesAndOtherInbound()throws Exception{String src="{\"inbounds\":[{\"type\":\"tun\",\"tag\":\"old\"},{\"type\":\"http\",\"tag\":\"keep\",\"listen_port\":8099}],\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}],\"route\":{\"final\":\"direct\"}}";p.importText(src,null);JSONObject out=new JSONObject(p.compile("secret"));assertEquals("direct",out.getJSONObject("route").getString("final"));assertEquals(2,out.getJSONArray("inbounds").length());assertEquals("keep",out.getJSONArray("inbounds").getJSONObject(0).getString("tag"));assertEquals(src,p.data.getString("config_sing-box"));}
    @Test public void bothCompilersSupportProtocols()throws Exception{String[] links={SS,"vless://b831381d-6324-4d53-ad4f-8cda48b30811@127.0.0.1:443?security=tls&sni=example.com&type=ws&path=%2Fa#VLESS","trojan://pw@127.0.0.1:443#Trojan","hy2://pw@127.0.0.1:443#HY2","tuic://b831381d-6324-4d53-ad4f-8cda48b30811:pw@127.0.0.1:443#TUIC"};p.importText(String.join("\n",links),null);for(String e:List.of("sing-box","mihomo")){p.data.put("engine",e);String config=p.compile("s");assertTrue(config.contains("Wbox"));assertTrue(config.contains("127.0.0.1"));}}
    @Test public void selectedNodeAppliedToBoth()throws Exception{p.importText(SS+"\n"+SS.replace("#Test","#Second"),null);p.data.put("selected","Second");JSONObject sb=new JSONObject(p.compile("s"));JSONArray out=sb.getJSONArray("outbounds");assertEquals("Second",out.getJSONObject(out.length()-1).getString("default"));p.data.put("engine","mihomo");Map<String,Object> yaml=Profiles.yamlMap(p.compile("s"));Map<?,?> group=(Map<?,?>)((List<?>)yaml.get("proxy-groups")).get(0);assertEquals("Second",((List<?>)group.get("proxies")).get(0));}
    @Test public void protocolAndRealityConfigsPassActualCoreValidation()throws Exception{
        Assume.assumeTrue("1".equals(System.getenv("WBOX_CORE_TESTS")));
        String id="b831381d-6324-4d53-ad4f-8cda48b30811";
        String vmess="vmess://"+Base64.getEncoder().encodeToString(("{\"add\":\"127.0.0.1\",\"port\":443,\"id\":\""+id+"\",\"ps\":\"VMess\",\"net\":\"tcp\",\"scy\":\"auto\"}").getBytes(StandardCharsets.UTF_8));
        p.importText(String.join("\n",SS,vmess,"vless://"+id+"@127.0.0.1:443?security=tls&sni=example.com&type=ws&path=%2Fa#VLESS","trojan://pw@127.0.0.1:443#Trojan","hy2://pw@127.0.0.1:443#HY2","tuic://"+id+":pw@127.0.0.1:443#TUIC","vless://"+id+"@127.0.0.1:443?security=reality&sni=example.com&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=12345678&fp=chrome#Reality"),null);
        File root=new File(System.getProperty("wbox.root"));
        for(String engine:List.of("sing-box","mihomo")){p.data.put("engine",engine);File cf=new File(folder,engine.equals("sing-box")?"config.json":"config.yaml");Files.write(cf.toPath(),p.compile("s").getBytes(StandardCharsets.UTF_8));File exe=new File(root,"resources/cores/windows/"+(engine.equals("sing-box")?"sing-box.exe":"mihomo.exe"));List<String> command=engine.equals("sing-box")?List.of(exe.getAbsolutePath(),"check","-c",cf.getAbsolutePath(),"-D",folder.getAbsolutePath()):List.of(exe.getAbsolutePath(),"-t","-f",cf.getAbsolutePath(),"-d",folder.getAbsolutePath());Process check=new ProcessBuilder(command).redirectErrorStream(true).start();try{assertTrue("config check timeout",check.waitFor(15,TimeUnit.SECONDS));String log=new String(check.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(engine+": "+log,0,check.exitValue());System.out.println("PASS "+engine+" six protocols + Reality config check");}finally{if(check.isAlive())check.destroyForcibly();}}
    }
    @Test public void realCoresForwardHttpOverIpv4AndIpv6()throws Exception{
        Assume.assumeTrue("Enable real-core integration via WBOX_CORE_TESTS=1", "1".equals(System.getenv("WBOX_CORE_TESTS")));
        File root=new File(System.getProperty("wbox.root"));p.importText(SS,null);
        for(String engine:List.of("sing-box","mihomo")){
            p.data.put("engine",engine);String config=p.compile("integration-secret");File cf=new File(folder,engine.equals("sing-box")?"config.json":"config.yaml");Files.write(cf.toPath(),config.getBytes(StandardCharsets.UTF_8));
            File exe=new File(root,"resources/cores/windows/"+(engine.equals("sing-box")?"sing-box.exe":"mihomo.exe"));List<String> cmd=new ArrayList<>(List.of(exe.getAbsolutePath()));if(engine.equals("sing-box"))cmd.addAll(List.of("run","-c",cf.getAbsolutePath(),"-D",folder.getAbsolutePath()));else cmd.addAll(List.of("-f",cf.getAbsolutePath(),"-d",folder.getAbsolutePath()));
            File log=new File(folder,engine+".log");Process core=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start();
            try{CoreApi api=new CoreApi("integration-secret");boolean ready=false;for(int i=0;i<100;i++){if(!core.isAlive())fail(engine+" failed: "+new String(Files.readAllBytes(log.toPath()),StandardCharsets.UTF_8));try{api.proxies();ready=true;break;}catch(Exception ignored){Thread.sleep(100);}}assertTrue("core ready",ready);api.select("Wbox","DIRECT");
                for(String address:List.of("127.0.0.1","::1")){testHttp(address);testUdp(address);}
                JSONObject stats=api.connections();assertTrue(engine+" upload",stats.getLong("uploadTotal")>0);assertTrue(engine+" download",stats.getLong("downloadTotal")>0);
                System.out.println("PASS actual "+engine+" Android-generated config: selector / HTTP+SOCKS5 UDP IPv4+IPv6 / traffic counters");
            }finally{core.destroy();if(!core.waitFor(3,TimeUnit.SECONDS))core.destroyForcibly();}
        }
    }
    static void testHttp(String host)throws Exception{
        ServerSocket server=new ServerSocket(0,1,InetAddress.getByName(host));ExecutorService service=Executors.newSingleThreadExecutor();Future<?> served=service.submit(()->{try(Socket s=server.accept()){BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));String line;while((line=in.readLine())!=null&&!line.isEmpty()){}byte[] body="wbox-real-proxy-ok".getBytes(StandardCharsets.UTF_8);s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));s.getOutputStream().write(body);s.getOutputStream().flush();}catch(IOException ex){throw new RuntimeException(ex);}});
        try{String address=host.contains(":")?"["+host+"]":host;HttpURLConnection c=(HttpURLConnection)new URL("http://"+address+":"+server.getLocalPort()+"/").openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",10808)));c.setReadTimeout(5000);c.setConnectTimeout(3000);try{assertEquals("wbox-real-proxy-ok",new String(c.getInputStream().readAllBytes(),StandardCharsets.UTF_8));}finally{c.disconnect();}served.get(5,TimeUnit.SECONDS);}finally{server.close();service.shutdownNow();}
    }
    static void testUdp(String host)throws Exception{
        DatagramSocket upstream=new DatagramSocket(0,InetAddress.getByName(host));upstream.setSoTimeout(5000);ExecutorService service=Executors.newSingleThreadExecutor();Future<?> echoed=service.submit(()->{try{byte[] b=new byte[1024];DatagramPacket packet=new DatagramPacket(b,b.length);upstream.receive(packet);upstream.send(packet);}catch(IOException ex){throw new RuntimeException(ex);}});
        try(Socket tcp=new Socket("127.0.0.1",10808);DatagramSocket udp=new DatagramSocket()){
            tcp.setSoTimeout(5000);udp.setSoTimeout(5000);DataInputStream in=new DataInputStream(tcp.getInputStream());OutputStream out=tcp.getOutputStream();out.write(new byte[]{5,1,0});out.flush();assertEquals(5,in.readUnsignedByte());assertEquals(0,in.readUnsignedByte());out.write(new byte[]{5,3,0,1,0,0,0,0,0,0});out.flush();assertEquals(5,in.readUnsignedByte());assertEquals(0,in.readUnsignedByte());in.readUnsignedByte();int type=in.readUnsignedByte();int len=type==1?4:type==4?16:in.readUnsignedByte();byte[] addr=in.readNBytes(len);int port=in.readUnsignedShort();InetAddress relay=type==3?InetAddress.getByName(new String(addr,StandardCharsets.UTF_8)):InetAddress.getByAddress(addr);if(relay.isAnyLocalAddress())relay=InetAddress.getByName("127.0.0.1");
            byte[] target=InetAddress.getByName(host).getAddress(),payload="wbox-udp-ok".getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream frame=new ByteArrayOutputStream();frame.write(new byte[]{0,0,0,(byte)(target.length==4?1:4)});frame.write(target);frame.write((upstream.getLocalPort()>>>8)&255);frame.write(upstream.getLocalPort()&255);frame.write(payload);byte[] request=frame.toByteArray();udp.send(new DatagramPacket(request,request.length,relay,port));byte[] response=new byte[2048];DatagramPacket reply=new DatagramPacket(response,response.length);udp.receive(reply);int skip=response[3]==1?10:response[3]==4?22:7+(response[4]&255);assertEquals("wbox-udp-ok",new String(response,skip,reply.getLength()-skip,StandardCharsets.UTF_8));echoed.get(5,TimeUnit.SECONDS);
        }finally{upstream.close();service.shutdownNow();}
    }
}
