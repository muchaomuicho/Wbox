package com.wbox.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class ReliabilityTest {
    @Test public void backgroundSamplingStops(){assertEquals(-1,PollPolicy.delaySeconds(false,true));assertEquals(1,PollPolicy.delaySeconds(true,true));assertEquals(5,PollPolicy.delaySeconds(true,false));}
    @Test public void singboxUrlIsNeverSilentlyReplaced(){NodeLatencyTester.validateUrl("sing-box","https://example.test/probe");NodeLatencyTester.validateUrl("mihomo","http://example.test/probe");try{NodeLatencyTester.validateUrl("sing-box","http://example.test/probe");fail();}catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("HTTPS"));}}
    @Test public void transientGatewayRetriesWithMihomoUa()throws Exception {
        try(ServerSocket server=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);List<String> requests=new ArrayList<>();
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                Future<?> serving=worker.submit(()->{try{respond(server,requests,502,"");respond(server,requests,200,"proxies: []");}catch(Exception e){throw new RuntimeException(e);}});
                assertEquals("proxies: []",SubscriptionFetch.fetch("http://127.0.0.1:"+server.getLocalPort()+"/sub?token=synthetic&clash"));serving.get(5,TimeUnit.SECONDS);
                assertEquals(2,requests.size());for(String request:requests){assertTrue(request.toLowerCase(Locale.ROOT).contains("user-agent: clash.meta/1.19.33"));assertTrue(request.contains("?token=synthetic&clash"));}
            }finally{worker.shutdownNow();}
        }
    }
    private static void respond(ServerSocket server,List<String> requests,int status,String body)throws Exception {
        try(Socket peer=server.accept()){BufferedReader reader=new BufferedReader(new InputStreamReader(peer.getInputStream(),StandardCharsets.US_ASCII));StringBuilder headers=new StringBuilder();String line;while((line=reader.readLine())!=null&&!line.isEmpty())headers.append(line).append('\n');requests.add(headers.toString());byte[] content=body.getBytes(StandardCharsets.UTF_8);OutputStream out=peer.getOutputStream();out.write(("HTTP/1.1 "+status+" Response\r\nConnection: close\r\nContent-Length: "+content.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(content);out.flush();}
    }
}
