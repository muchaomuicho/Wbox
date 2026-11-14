import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Shell-side test input, invoked only by the emulator-5556 harness. */
public final class TouchGesture {
    public static void main(String[] args) throws Exception {
        Class<?> type=Class.forName("android.hardware.input.InputManagerGlobal");
        Object manager=type.getMethod("getInstance").invoke(null);
        Method inject=type.getMethod("injectInputEvent",InputEvent.class,int.class);
        float x1=Float.parseFloat(args[0]),y1=Float.parseFloat(args[1]);
        float x2=Float.parseFloat(args[2]),y2=Float.parseFloat(args[3]);
        long down=SystemClock.uptimeMillis();
        send(manager,inject,down,MotionEvent.ACTION_DOWN,x1,y1);
        SystemClock.sleep(900);
        // The first move intentionally exceeds touch slop to verify that the
        // long-pressed card owns fast movement rather than the grid scroller.
        for(int step=1;step<=8;step++) {
            float fraction=.35f+.65f*step/8;
            send(manager,inject,down,MotionEvent.ACTION_MOVE,x1+(x2-x1)*fraction,y1+(y2-y1)*fraction);
            SystemClock.sleep(45);
        }
        send(manager,inject,down,MotionEvent.ACTION_UP,x2,y2);
    }
    private static void send(Object manager,Method inject,long down,int action,float x,float y)throws Exception {
        MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();
        pointer.id=0;pointer.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();
        coords.x=x;coords.y=y;coords.pressure=action==MotionEvent.ACTION_UP?0:1;coords.size=1;
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,
            new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{coords},
            0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try {
            if(!Boolean.TRUE.equals(inject.invoke(manager,event,2)))throw new IllegalStateException("Input injection rejected");
        } finally {event.recycle();}
    }
}
