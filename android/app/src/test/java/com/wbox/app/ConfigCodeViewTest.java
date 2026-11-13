package com.wbox.app;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ConfigCodeViewTest {
    private ConfigCodeView editor(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        ConfigCodeView view=new ConfigCodeView(activity);
        activity.setContentView(view);
        StringBuilder text=new StringBuilder();
        for(int i=0;i<200;i++)text.append("  key_").append(i).append(": value\n");
        view.setText(text);view.setSelection(0);view.clearFocus();
        view.measure(View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));
        view.layout(0,0,600,600);
        return view;
    }
    private void touch(ConfigCodeView view,long start,long time,int action,float y){
        MotionEvent event=MotionEvent.obtain(start,start+time,action,200,y,0);
        view.onTouchEvent(event);event.recycle();
    }
    @Test public void quickDragContinuesWithInertiaAndStaysInsideDocument(){
        ConfigCodeView view=editor();String original=view.getText().toString();
        assertTrue(view.scrollLimit()>600);
        long start=SystemClock.uptimeMillis();
        touch(view,start,0,MotionEvent.ACTION_DOWN,500);
        touch(view,start,25,MotionEvent.ACTION_MOVE,440);
        touch(view,start,50,MotionEvent.ACTION_MOVE,350);
        int dragged=view.getScrollY();assertTrue(dragged>80);
        touch(view,start,65,MotionEvent.ACTION_UP,300);
        for(int i=0;i<30;i++){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));view.computeScroll();}
        assertTrue("A fast swipe keeps moving after lift",view.getScrollY()>dragged+40);
        assertTrue(view.getScrollY()<=view.scrollLimit());
        assertEquals(original,view.getText().toString());
        start=SystemClock.uptimeMillis();
        touch(view,start,0,MotionEvent.ACTION_DOWN,100);
        touch(view,start,25,MotionEvent.ACTION_MOVE,9000);
        touch(view,start,40,MotionEvent.ACTION_UP,10000);
        for(int i=0;i<20;i++){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));view.computeScroll();}
        assertEquals(0,view.getScrollY());
    }
    @Test public void longPressDragKeepsNativeSelectionWithoutStartingFling(){
        ConfigCodeView view=editor();
        long start=SystemClock.uptimeMillis();
        touch(view,start,0,MotionEvent.ACTION_DOWN,300);
        touch(view,start,800,MotionEvent.ACTION_MOVE,220);
        touch(view,start,850,MotionEvent.ACTION_UP,180);
        int released=view.getScrollY();
        for(int i=0;i<20;i++){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));view.computeScroll();}
        assertEquals(released,view.getScrollY());
    }
}
