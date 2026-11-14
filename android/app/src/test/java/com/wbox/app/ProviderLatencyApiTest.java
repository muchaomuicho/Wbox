package com.wbox.app;

import org.json.*;
import org.junit.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class ProviderLatencyApiTest {
    ServerSocket server;Thread worker;CoreApi api;AtomicReference<String> delayPath=new AtomicReference<>();volatile String providerBody;
    @Before public void setup()throws Exception {
        providerBody="{\"providers\":{\"Remote\":{\"proxies\":[{\"name\":\"A\",\"type\":\"Http\"}]}}}";
        server=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));
        worker=new Thread(()->{while(!server.isClosed())try(Socket client=server.accept()){
            BufferedReader reader=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8));
            String path=reader.readLine().split(" ")[1].split("\\?")[0];String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}String body;
            if(path.equals("/proxies"))body="{\"proxies\":{\"Main\":{\"type\":\"Selector\",\"all\":[\"A\"],\"now\":\"A\"}}}";
            else if(path.equals("/providers/proxies"))body=providerBody;
            else{delayPath.set(path);body="{\"delay\":42}";}
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(bytes);out.flush();
        }catch(IOException ignored){}});worker.setDaemon(true);worker.start();api=new CoreApi("test",server.getLocalPort(),Set.of("Remote","Other"));
    }
    @After public void cleanup()throws Exception{server.close();worker.join(1000);}
    @Test public void nativeProviderLeafUsesItsOwnHealthcheck()throws Exception {
        assertEquals("Http",api.proxies().getJSONObject("A").getString("type"));assertEquals(42,api.delay("A","http://example.test/probe"));
        assertEquals("/providers/proxies/Remote/A/healthcheck",delayPath.get());
    }
    @Test public void duplicateProviderNamesAreRejectedBeforeRequest()throws Exception {
        providerBody="{\"providers\":{\"Remote\":{\"proxies\":[{\"name\":\"A\"}]},\"Other\":{\"proxies\":[{\"name\":\"A\"}]}}}";
        api.proxies();try{api.delay("A","http://example.test/probe");fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("同名节点"));}
        assertNull(delayPath.get());
    }
}
