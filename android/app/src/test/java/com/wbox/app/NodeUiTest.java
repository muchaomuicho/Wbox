package com.wbox.app;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w360dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NodeUiTest {
    @Before public void reset()throws Exception{Profiles p=new Profiles(RuntimeEnvironment.getApplication());p.data=new JSONObject().put("mode","nodes");p.importNodes(ProfilesTest.SS,null);p.save();WboxVpnService.running=WboxVpnService.starting=WboxVpnService.stopping=false;}
    static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static void invoke(MainActivity a,String name)throws Exception{Method method=MainActivity.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(a);}
    static void page(MainActivity a,int page)throws Exception{Field f=MainActivity.class.getDeclaredField("page");f.setAccessible(true);f.setInt(a,page);invoke(a,"build");}
    static EditText input(View view,String label){if(view instanceof EditText&&label.contentEquals(view.getContentDescription()))return (EditText)view;if(view instanceof ViewGroup){ViewGroup parent=(ViewGroup)view;for(int i=0;i<parent.getChildCount();i++){EditText found=input(parent.getChildAt(i),label);if(found!=null)return found;}}return null;}
    static void idle(MainActivity a)throws Exception{for(int i=0;i<300;i++){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();if(!(boolean)field(a,"importing"))return;Thread.sleep(10);}fail("Mutation timed out");}
    static void clearDetachedIndicators(View view){if(!view.isAttachedToWindow())view.setScrollIndicators(0);if(view instanceof ViewGroup){ViewGroup parent=(ViewGroup)view;for(int i=0;i<parent.getChildCount();i++)clearDetachedIndicators(parent.getChildAt(i));}}
    static void render(View view,String name,boolean dialog)throws Exception{clearDetachedIndicators(view);int width=dialog?328:360;view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(760,dialog?View.MeasureSpec.AT_MOST:View.MeasureSpec.EXACTLY));view.layout(0,0,width,view.getMeasuredHeight());Bitmap b=Bitmap.createBitmap(width,view.getMeasuredHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(b));File file=new File(System.getProperty("wbox.root"),"android/app/build/reports/ui/"+name+".png");file.getParentFile().mkdirs();try(OutputStream out=new FileOutputStream(file)){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    @Test public void clipboardShortcutAndDialogGuard()throws Exception{
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity a=c.get();page(a,1);Button power=(Button)field(a,"power");power.setFocusableInTouchMode(true);power.requestFocus();ClipboardManager clipboard=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("node",ProfilesTest.SS.replace("#Test","#Clipboard")));
            KeyEvent paste=new KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_V,0,KeyEvent.META_CTRL_ON);assertTrue(a.dispatchKeyShortcutEvent(paste));idle(a);assertEquals(2,new Profiles(a).nodes().size());
            invoke(a,"importDialog");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(input(dialog.getWindow().getDecorView(),"订阅地址或节点链接"));a.dispatchKeyEvent(paste);idle(a);assertEquals(2,new Profiles(a).nodes().size());dialog.dismiss();
        }
    }
    @Test public void longPressEditsAndRejectsInvalidValues()throws Exception{
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity a=c.get();page(a,1);render((View)field(a,"root"),"nodes-0.5.1",false);LinearLayout body=(LinearLayout)field(a,"body");ListView list=null;for(int i=0;i<body.getChildCount();i++)if(body.getChildAt(i) instanceof ListView)list=(ListView)body.getChildAt(i);assertNotNull(list);LinearLayout row=(LinearLayout)list.getChildAt(0);assertTrue(row.getChildAt(0).performLongClick());
            AlertDialog menu=ShadowAlertDialog.getLatestAlertDialog();assertEquals(4,menu.getListView().getAdapter().getCount());menu.getListView().performItemClick(null,1,1);AlertDialog edit=ShadowAlertDialog.getLatestAlertDialog();View decor=edit.getWindow().getDecorView();EditText port=input(decor,"端口"),name=input(decor,"名称");assertNotNull(port);port.setText("70000");edit.getButton(DialogInterface.BUTTON_POSITIVE).performClick();assertTrue(edit.isShowing());assertEquals(8388,new Profiles(a).nodes().get(0).port);port.setText("9001");name.setText("Edited phone");render(decor,"node-editor-0.5.1",true);edit.getButton(DialogInterface.BUTTON_POSITIVE).performClick();idle(a);assertFalse(edit.isShowing());assertEquals("Edited phone",new Profiles(a).nodes().get(0).name);assertEquals(9001,new Profiles(a).nodes().get(0).port);
        }
    }
    @Test public void importFormsMatchThemesAndAddButtonIsInCard()throws Exception{
        for(String theme:List.of("light","dark")){
            Profiles p=new Profiles(RuntimeEnvironment.getApplication());p.data.put("theme",theme);p.save();
            try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()){
                MainActivity a=c.get();page(a,1);LinearLayout body=(LinearLayout)field(a,"body");assertTrue(body.getChildAt(0) instanceof LinearLayout);invoke(a,"importDialog");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();render(dialog.getWindow().getDecorView(),"import-0.5.1-"+theme,true);EditText input=input(dialog.getWindow().getDecorView(),"订阅地址或节点链接");assertNotNull(input);assertTrue(input.getBackground() instanceof android.graphics.drawable.GradientDrawable);dialog.dismiss();
                page(a,4);Field section=MainActivity.class.getDeclaredField("settingsSection");section.setAccessible(true);section.set(a,"延迟测试");invoke(a,"build");render((View)field(a,"root"),"latency-0.5.1-"+theme,false);assertNotNull(input((View)field(a,"root"),"测试地址"));
            }
        }
    }
}
