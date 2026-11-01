package com.wbox.app;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;
import java.io.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class RuntimeDataTest {
    @Test public void trafficHistoryUsesElapsedTimeAndRemainsBounded(){
        for(int i=0;i<100;i++)WboxVpnService.recordTraffic(i*1000L,i,-1);
        List<WboxVpnService.TrafficSample> history=WboxVpnService.trafficSnapshot();
        assertEquals(61,history.size());assertEquals(39000,history.get(0).time);assertEquals(0,history.get(0).down);
        WboxVpnService.recordTraffic(150000,42,64);
        assertEquals(61,history.size()); // A snapshot cannot be mutated by the service.
        assertEquals(11,WboxVpnService.trafficSnapshot().size());
        WboxVpnService.clearTraffic();assertTrue(WboxVpnService.trafficSnapshot().isEmpty());
    }
    @Test public void streamingStatsCountsConnectionsWithoutKeepingDetails()throws Exception{
        try(java.net.ServerSocket server=new java.net.ServerSocket(19090,1,java.net.InetAddress.getLoopbackAddress())){
            Thread response=new Thread(()->{try(java.net.Socket socket=server.accept()){
                BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream()));String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
                StringBuilder body=new StringBuilder("{\"uploadTotal\":123456789,\"downloadTotal\":987654321,\"connections\":[");for(int i=0;i<2000;i++){if(i>0)body.append(',');body.append("{\"metadata\":{\"host\":\"example.org\"},\"chains\":[\"A\",\"B\"]}");}body.append("]}");byte[] bytes=body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().write(bytes);
            }catch(Exception e){throw new RuntimeException(e);}});response.start();
            CoreApi.Stats stats=new CoreApi("test").stats();assertEquals(123456789,stats.upload);assertEquals(987654321,stats.download);assertEquals(2000,stats.connections);response.join(3000);
        }
    }
    @Test public void connectionReaderRetainsMetadataAndSkipsUnknownFields()throws Exception {
        String json="{\"id\":\"test\",\"metadata\":{\"network\":\"tcp\",\"host\":\"example.org\",\"destinationPort\":443,\"sourceIP\":\"127.0.0.1\",\"sourcePort\":5000,\"inboundName\":\"tun/0\",\"unknown\":{\"large\":[1,2,3]}},\"chains\":[\"JP\",\"Proxy\"],\"upload\":123,\"download\":456}";
        try(android.util.JsonReader reader=new android.util.JsonReader(new StringReader(json))){ConnectionRecord row=ConnectionRecord.read(reader);assertEquals("example.org:443",row.destination);assertEquals("tun/0",row.inbound);assertEquals("JP → Proxy",row.outbound);assertEquals(456,row.download);assertTrue(row.matches("PROXY"));}
    }
    @Test public void connectionCloseUsesAuthenticatedDelete()throws Exception {
        try(java.net.ServerSocket server=new java.net.ServerSocket(19090,2,java.net.InetAddress.getLoopbackAddress())){
            java.util.concurrent.CompletableFuture<List<String>> received=java.util.concurrent.CompletableFuture.supplyAsync(()->{java.util.ArrayList<String> paths=new java.util.ArrayList<>();try{for(int i=0;i<2;i++)try(java.net.Socket socket=server.accept()){
                BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream()));paths.add(input.readLine());String line;boolean auth=false;while((line=input.readLine())!=null&&!line.isEmpty())if(line.equals("Authorization: Bearer close-test"))auth=true;if(!auth)throw new AssertionError("Missing authorization");socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }}catch(Exception error){throw new RuntimeException(error);}return paths;});
            CoreApi api=new CoreApi("close-test");api.closeConnection("id with space");api.closeAllConnections();
            assertEquals(List.of("DELETE /connections/id%20with%20space HTTP/1.1","DELETE /connections HTTP/1.1"),received.get(5,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
