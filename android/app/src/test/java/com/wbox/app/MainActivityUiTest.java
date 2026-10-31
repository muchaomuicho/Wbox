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
        profiles.data.remove("nodes");profiles.data.remove("selected");profiles.data.put("mode","nodes");profiles.save();
        WboxVpnService.running=WboxVpnService.starting=WboxVpnService.stopping=false;
        WboxVpnService.clearTraffic();
    }
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void measure(View view,int width){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));view.layout(0,0,width,800);}
    private static void navigate(MainActivity a,int page)throws Exception{((LinearLayout)field(a,"nav")).getChildAt(page).performClick();}
    @Test public void rendersEveryPageAtPhoneWidths()throws Exception{
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();
            String[] names={"overview","nodes","profiles","logs","settings"};
            File dir=new File(System.getProperty("wbox.root"),"android/app/build/reports/ui");assertTrue(dir.isDirectory()||dir.mkdirs());
            for(int width:new int[]{360,412})for(int page=0;page<5;page++){
                navigate(activity,page);View root=(View)field(activity,"root");measure(root,width);
                assertEquals(5,((LinearLayout)field(activity,"nav")).getChildCount());
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
    @Test public void trafficHistoryUsesElapsedTimeAndRemainsBounded(){
        for(int i=0;i<100;i++)WboxVpnService.recordTraffic(i*1000L,i,-1);
        List<WboxVpnService.TrafficSample> history=WboxVpnService.trafficSnapshot();
        assertEquals(61,history.size());assertEquals(39000,history.get(0).time);assertEquals(0,history.get(0).down);
        WboxVpnService.recordTraffic(150000,42,64);
        assertEquals(61,history.size()); // A snapshot cannot be mutated by the service.
        assertEquals(11,WboxVpnService.trafficSnapshot().size());
        WboxVpnService.clearTraffic();assertTrue(WboxVpnService.trafficSnapshot().isEmpty());
    }
}
