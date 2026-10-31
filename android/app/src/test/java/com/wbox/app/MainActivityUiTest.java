package com.wbox.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.*;
import java.lang.reflect.Field;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w360dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainActivityUiTest {
    @Before public void reset() throws Exception {
        Profiles profiles=new Profiles(RuntimeEnvironment.getApplication());
        profiles.data=new org.json.JSONObject();profiles.data.put("mode","nodes");profiles.save();
        WboxVpnService.running=WboxVpnService.starting=WboxVpnService.stopping=false;
        WboxVpnService.clearTraffic();
        WboxVpnService.connectionRows=List.of();WboxVpnService.proxies=new org.json.JSONObject();
    }
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void measure(View view,int width){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));view.layout(0,0,width,800);}
    private static void navigate(MainActivity a,int page)throws Exception{((LinearLayout)field(a,"nav")).getChildAt(new int[]{0,1,2,4,5,3}[page]).performClick();}
    @Test public void rendersEveryPageAtPhoneWidths()throws Exception{
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();
            String[] names={"overview","nodes","profiles","logs","settings","connections"};
            File dir=new File(System.getProperty("wbox.root"),"android/app/build/reports/ui");assertTrue(dir.isDirectory()||dir.mkdirs());
            for(int width:new int[]{360,412})for(int page=0;page<6;page++){
                navigate(activity,page);View root=(View)field(activity,"root");measure(root,width);
                assertEquals(6,((LinearLayout)field(activity,"nav")).getChildCount());
                assertTrue(((LinearLayout)field(activity,"body")).getHeight()>0);
                Bitmap bitmap=Bitmap.createBitmap(width,800,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
                try(OutputStream output=new FileOutputStream(new File(dir,names[page]+"-"+width+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}bitmap.recycle();
            }
        }
    }
    @Test public void largeNodeListRecyclesViewsAndPersistsSelection()throws Exception{
        Profiles profiles=new Profiles(RuntimeEnvironment.getApplication());JSONArray nodes=new JSONArray();
        for(int i=0;i<400;i++)nodes.put("vless://11111111-1111-4111-8111-111111111111@node"+i+".example:443?security=tls#Node%20"+i);
        profiles.data.put("nodes",nodes);profiles.save();
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();navigate(activity,1);View root=(View)field(activity,"root");measure(root,360);
            LinearLayout body=(LinearLayout)field(activity,"body");ListView list=null;
            for(int i=0;i<body.getChildCount();i++)if(body.getChildAt(i) instanceof ListView)list=(ListView)body.getChildAt(i);
            assertNotNull(list);assertEquals(400,list.getAdapter().getCount());assertTrue(list.getChildCount()>0);assertTrue("Only visible rows should exist",list.getChildCount()<20);
            LinearLayout first=(LinearLayout)list.getChildAt(0);first.getChildAt(0).performClick();
            assertEquals("Node 0",new Profiles(activity).data.getString("selected"));
        }
    }
    @Test public void configWorkspaceRendersRemoteLibrary()throws Exception{
        Profiles profiles=new Profiles(RuntimeEnvironment.getApplication());profiles.importConfig("{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"DIRECT\"}]}","https://example.org/profile",null);profiles.setWorkspace("config");profiles.save();
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();navigate(activity,2);View root=(View)field(activity,"root");measure(root,360);
            Bitmap bitmap=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));File file=new File(System.getProperty("wbox.root"),"android/app/build/reports/ui/config-library-360.png");file.getParentFile().mkdirs();try(OutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
            assertEquals("config",new Profiles(activity).mode());assertEquals(1,new Profiles(activity).configs().length());
        }
    }
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
    private static void screenshot(MainActivity activity,String name)throws Exception {
        View root=(View)field(activity,"root");measure(root,360);Bitmap bitmap=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));File file=new File(System.getProperty("wbox.root"),"android/app/build/reports/ui/"+name+".png");file.getParentFile().mkdirs();try(OutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
    @Test public void populatedConnectionsRecycleSearchAndReleaseDetails()throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();navigate(activity,5);java.util.ArrayList<ConnectionRecord> rows=new java.util.ArrayList<>();
            for(int i=0;i<400;i++){ConnectionRecord row=new ConnectionRecord();row.id=""+i;row.network="TCP";row.destination=i%2==0?"example.org:443":"203.0.113.10:443";row.inbound="tun/0";row.outbound="节点选择";row.upload=314;row.download=2048;row.upRate=23;row.downRate=512;rows.add(row);}WboxVpnService.connectionRows=rows;
            java.lang.reflect.Method update=MainActivity.class.getDeclaredMethod("update");update.setAccessible(true);update.invoke(activity);screenshot(activity,"connections-populated-360");
            LinearLayout body=(LinearLayout)field(activity,"body");ListView list=null;EditText search=null;for(int i=0;i<body.getChildCount();i++){View v=body.getChildAt(i);if(v instanceof ListView)list=(ListView)v;if(v instanceof EditText)search=(EditText)v;}
            assertNotNull(list);assertNotNull(search);assertEquals(400,list.getAdapter().getCount());assertTrue(list.getChildCount()<15);TextView route=(TextView)field(list.getChildAt(0).getTag(),"route");assertTrue(route.getText().toString().contains("tun/0"));assertTrue(route.getWidth()>0);assertTrue(route.getHeight()>0);System.out.println("Connection route bounds="+route.getWidth()+"x"+route.getHeight()+", text="+route.getText());search.setText("example.org");assertEquals(200,list.getAdapter().getCount());navigate(activity,0);assertFalse(WboxVpnService.wantsConnections);assertTrue(WboxVpnService.connectionRows.isEmpty());
        }
    }
    @Test public void groupCardsCollapseAndRenderFlags()throws Exception {
        Profiles profiles=new Profiles(RuntimeEnvironment.getApplication());profiles.importConfig("{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"DIRECT\"}]}",null,null);profiles.setWorkspace("config");profiles.save();
        WboxVpnService.proxies=new org.json.JSONObject("{\"节点选择\":{\"type\":\"Selector\",\"now\":\"🇯🇵 东京 IPLC\",\"all\":[\"🇯🇵 东京 IPLC\",\"🇭🇰 香港住宅\",\"DIRECT\"]},\"🇯🇵 东京 IPLC\":{\"type\":\"SSH\",\"history\":[{\"delay\":191}]},\"🇭🇰 香港住宅\":{\"type\":\"HTTP\",\"history\":[{\"delay\":526}]},\"DIRECT\":{\"type\":\"Direct\"},\"Microsoft\":{\"type\":\"Selector\",\"all\":[\"DIRECT\"]}}");WboxVpnService.running=true;
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();navigate(activity,1);screenshot(activity,"groups-populated-360");LinearLayout body=(LinearLayout)field(activity,"body");ListView list=null;for(int i=0;i<body.getChildCount();i++)if(body.getChildAt(i) instanceof ListView)list=(ListView)body.getChildAt(i);assertNotNull(list);assertEquals(5,list.getAdapter().getCount());((LinearLayout)list.getChildAt(0)).getChildAt(0).performClick();assertEquals(2,list.getAdapter().getCount());
            CharSequence flag=FlagLabels.text(activity,"🇯🇵 Tokyo · 🇭🇰 Hong Kong",20);assertEquals(2,((android.text.Spanned)flag).getSpans(0,flag.length(),android.text.style.ImageSpan.class).length);
        } finally {WboxVpnService.running=false;}
    }
    @Test public void connectionReaderRetainsMetadataAndSkipsUnknownFields()throws Exception {
        String json="{\"id\":\"test\",\"metadata\":{\"network\":\"tcp\",\"host\":\"example.org\",\"destinationPort\":443,\"sourceIP\":\"127.0.0.1\",\"sourcePort\":5000,\"inboundName\":\"tun/0\",\"unknown\":{\"large\":[1,2,3]}},\"chains\":[\"JP\",\"Proxy\"],\"upload\":123,\"download\":456}";
        try(android.util.JsonReader reader=new android.util.JsonReader(new StringReader(json))){ConnectionRecord row=ConnectionRecord.read(reader);assertEquals("example.org:443",row.destination);assertEquals("tun/0",row.inbound);assertEquals("JP → Proxy",row.outbound);assertEquals(456,row.download);assertTrue(row.matches("PROXY"));}
    }
}
