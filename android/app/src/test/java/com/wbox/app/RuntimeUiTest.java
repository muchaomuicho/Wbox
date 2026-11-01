package com.wbox.app;
import android.graphics.*;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w412dp-h900dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RuntimeUiTest {
    static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static void page(MainActivity a,int index)throws Exception{Field p=MainActivity.class.getDeclaredField("page");p.setAccessible(true);p.set(a,index);Method build=MainActivity.class.getDeclaredMethod("build");build.setAccessible(true);build.invoke(a);}
    static void screenshot(MainActivity a,String name)throws Exception{View root=(View)field(a,"root");root.measure(View.MeasureSpec.makeMeasureSpec(412,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(900,View.MeasureSpec.EXACTLY));root.layout(0,0,412,900);Bitmap bitmap=Bitmap.createBitmap(412,900,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));File file=new File(System.getProperty("wbox.root"),"android/app/build/reports/ui/"+name+".png");file.getParentFile().mkdirs();try(OutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
    @Before public void reset()throws Exception{Profiles p=new Profiles(RuntimeEnvironment.getApplication());p.data=new JSONObject();p.data.put("mode","config");p.save();WboxVpnService.running=WboxVpnService.starting=WboxVpnService.stopping=false;WboxVpnService.startedAt=100;WboxVpnService.proxies=new JSONObject();synchronized(WboxVpnService.logs){WboxVpnService.logs.clear();}}
    @Test public void logColorsPreserveCopyableTextAndConnectionIdentity()throws Exception{
        String lines="+0800 2026-11-01 09:54:42 INFO [123 0ms] inbound/mixed: connected\n+0800 2026-11-01 09:54:43 WARN [123 1s] router: retry\n+0800 2026-11-01 09:54:44 ERROR [456 2s] connection: timeout";
        for(boolean dark:new boolean[]{false,true}){Spanned colored=(Spanned)LogStyle.color(lines,dark);assertEquals(lines,colored.toString());assertEquals(6,colored.getSpans(0,colored.length(),ForegroundColorSpan.class).length);}
        WboxVpnService.log(lines);try(var c=Robolectric.buildActivity(MainActivity.class).setup()){page(c.get(),3);assertTrue(((TextView)field(c.get(),"logText")).getText() instanceof Spanned);screenshot(c.get(),"colored-logs");}
    }
    @Test public void onlyFirstGroupExpandsAndCollapsedGroupsRenderDelaySquares()throws Exception{
        JSONObject proxies=new JSONObject();JSONArray names=new JSONArray(List.of("🇯🇵 东京","🇭🇰 香港","🇺🇸 美国"));
        for(String group:List.of("节点选择","流媒体","自动选择"))proxies.put(group,new JSONObject().put("type","Selector").put("now","🇯🇵 东京").put("all",names));
        for(int i=0;i<3;i++)proxies.put(names.getString(i),new JSONObject().put("type","Shadowsocks").put("history",new JSONArray().put(new JSONObject().put("delay",new int[]{120,920,1700}[i]))));
        WboxVpnService.proxies=proxies;WboxVpnService.running=true;
        try(var c=Robolectric.buildActivity(MainActivity.class).setup()){page(c.get(),1);Set<?> expanded=(Set<?>)field(c.get(),"expandedGroups");assertEquals(1,expanded.size());BaseAdapter adapter=(BaseAdapter)field(c.get(),"nodeAdapter");assertEquals(6,adapter.getCount());screenshot(c.get(),"group-latency");}
    }
    @Test public void startupAndStoppingUseProgressInsteadOfJumpingText()throws Exception{
        try(var c=Robolectric.buildActivity(MainActivity.class).setup()){WboxVpnService.starting=true;page(c.get(),0);assertEquals(View.VISIBLE,((ProgressBar)field(c.get(),"powerProgress")).getVisibility());assertEquals("",((Button)field(c.get(),"power")).getText().toString());WboxVpnService.starting=false;WboxVpnService.stopping=true;page(c.get(),0);assertFalse(((Button)field(c.get(),"power")).isEnabled());}
    }
    @Test public void delayColorsFollowUpstreamBands(){UiPalette palette=new UiPalette(false);assertEquals(palette.good,GroupDelayView.tone("799 ms",palette));assertNotEquals(palette.good,GroupDelayView.tone("800 ms",palette));assertEquals(GroupDelayView.tone("1500 ms",palette),GroupDelayView.tone("失败",palette));assertEquals(palette.selected,GroupDelayView.tone("未测试",palette));}
}
