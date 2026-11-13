package com.wbox.app;

import android.content.Context;
import android.graphics.*;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.OverScroller;
import java.util.*;
import java.util.regex.*;

/** Native selection/action mode, editable syntax colors and a visible-line gutter. */
public final class ConfigCodeView extends EditText {
    private static final Pattern TOKENS=Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:''|[^'])*'|(?m:^\\s*#.*$)|//[^\\n]*|/\\*[\\s\\S]*?\\*/|\\b(?:true|false|null|[-+]?\\d+(?:\\.\\d+)?)\\b|(?m:^[ \\t-]*[\\w.-]+(?=\\s*:))");
    private final Paint gutter=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] lineStarts={0};
    private boolean coloring;
    private boolean dark;
    private int foregroundColor;
    private boolean themed;
    private final Runnable highlight=this::highlight;
    private final OverScroller scroller;
    private final int touchSlop,minFlingVelocity,maxFlingVelocity;
    private VelocityTracker velocity;
    private float downX,downY,lastY;
    private long downTime;
    private boolean dragging,selectionGesture;
    public java.util.function.Consumer<String> onChange;
    public ConfigCodeView(Context context) {
        super(context);setGravity(Gravity.TOP|Gravity.START);setTypeface(Typeface.MONOSPACE);setTextSize(14);
        scroller=new OverScroller(context);
        ViewConfiguration gestures=ViewConfiguration.get(context);
        touchSlop=gestures.getScaledTouchSlop();minFlingVelocity=gestures.getScaledMinimumFlingVelocity();maxFlingVelocity=gestures.getScaledMaximumFlingVelocity();
        setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);setHorizontallyScrolling(false);setBackgroundColor(Color.TRANSPARENT);
        setPadding(dp(48),dp(12),dp(16),dp(24));gutter.setTypeface(Typeface.MONOSPACE);gutter.setTextSize(getTextSize());gutter.setTextAlign(Paint.Align.RIGHT);
        addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){}
            public void afterTextChanged(Editable text){if(coloring)return;String value=text.toString();ArrayList<Integer> lines=new ArrayList<>();lines.add(0);for(int i=0;i<value.length();i++)if(value.charAt(i)=='\n')lines.add(i+1);lineStarts=lines.stream().mapToInt(Integer::intValue).toArray();removeCallbacks(highlight);postDelayed(highlight,160);if(onChange!=null)onChange.accept(value);}
        });
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
    int scrollLimit(){Layout layout=getLayout();return layout==null?0:Math.max(0,layout.getHeight()-Math.max(0,getHeight()-getTotalPaddingTop()-getTotalPaddingBottom()));}
    private void scrollContent(int y){scrollTo(getScrollX(),Math.max(0,Math.min(scrollLimit(),y)));}
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!isEnabled())return super.onTouchEvent(event);
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            boolean moving=!scroller.isFinished();scroller.abortAnimation();
            if(velocity!=null)velocity.recycle();velocity=VelocityTracker.obtain();velocity.addMovement(event);
            downX=event.getX();downY=lastY=event.getY();downTime=event.getEventTime();dragging=moving;selectionGesture=hasSelection();
            boolean handled=super.onTouchEvent(event);
            if(dragging)cancelNativeTouch(event);
            return handled||dragging;
        }
        if(velocity!=null)velocity.addMovement(event);
        if(action==MotionEvent.ACTION_POINTER_DOWN)selectionGesture=true;
        if(action==MotionEvent.ACTION_MOVE){
            float distance=event.getY()-downY;
            // Keep long press, selected text and handle drags entirely in EditText.
            if(!dragging&&!selectionGesture&&!hasSelection()&&event.getPointerCount()==1
                &&event.getEventTime()-downTime<ViewConfiguration.getLongPressTimeout()
                &&Math.abs(distance)>touchSlop&&Math.abs(distance)>Math.abs(event.getX()-downX)&&scrollLimit()>0){
                dragging=true;cancelNativeTouch(event);
                lastY=downY+Math.copySign(touchSlop,distance);
            }
            if(dragging){
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                float y=event.getY();scrollContent(getScrollY()+Math.round(lastY-y));lastY=y;return true;
            }
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
            boolean consumed=dragging;
            if(dragging&&action==MotionEvent.ACTION_UP&&velocity!=null){
                velocity.computeCurrentVelocity(1000,maxFlingVelocity);
                int speed=Math.round(-velocity.getYVelocity());
                if(Math.abs(speed)>=minFlingVelocity){
                    scroller.fling(getScrollX(),getScrollY(),0,speed,getScrollX(),getScrollX(),0,scrollLimit());postInvalidateOnAnimation();
                }
            }
            dragging=false;if(velocity!=null){velocity.recycle();velocity=null;}
            if(consumed){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;}
        }
        return super.onTouchEvent(event);
    }
    private void cancelNativeTouch(MotionEvent event){MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);super.onTouchEvent(cancel);cancel.recycle();}
    @Override public void computeScroll(){
        super.computeScroll();
        if(scroller.computeScrollOffset()){scrollContent(scroller.getCurrY());postInvalidateOnAnimation();}
    }
    public void theme(boolean dark,int foreground){if(themed&&this.dark==dark&&foregroundColor==foreground)return;themed=true;foregroundColor=foreground;this.dark=dark;setTextColor(foreground);gutter.setColor(dark?0xff858585:0xff777777);setHighlightColor(dark?0xff405367:0xffcdddec);removeCallbacks(highlight);post(highlight);}
    private static class SyntaxSpan extends ForegroundColorSpan {SyntaxSpan(int color){super(color);}}
    private void highlight(){
        Editable text=getText();if(text==null)return;coloring=true;
        try {
            for(SyntaxSpan span:text.getSpans(0,text.length(),SyntaxSpan.class))text.removeSpan(span);
            Matcher m=TOKENS.matcher(text);while(m.find()){
                String token=m.group().trim();int after=m.end();while(after<text.length()&&Character.isWhitespace(text.charAt(after)))after++;
                boolean key=after<text.length()&&text.charAt(after)==':';
                int color=token.startsWith("#")||token.startsWith("//")||token.startsWith("/*")?(dark?0xff858585:0xff777777):key?(dark?0xff91b6d7:0xff19647a):token.startsWith("\"")||token.startsWith("'")?(dark?0xff86b49c:0xff626078):(dark?0xffd49b70:0xffa34d20);
                text.setSpan(new SyntaxSpan(color),m.start(),m.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }finally{coloring=false;}
    }
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);Layout layout=getLayout();if(layout==null)return;
        int top=getTotalPaddingTop();int first=layout.getLineForVertical(Math.max(0,getScrollY()-top)),last=layout.getLineForVertical(getScrollY()+getHeight());
        for(int line=first;line<=last;line++){int offset=layout.getLineStart(line),logical=Arrays.binarySearch(lineStarts,offset);if(logical>=0)canvas.drawText(Integer.toString(logical+1),getScrollX()+dp(36),top+layout.getLineBaseline(line),gutter);}
    }
    @Override protected void onDetachedFromWindow(){removeCallbacks(highlight);scroller.abortAnimation();if(velocity!=null){velocity.recycle();velocity=null;}super.onDetachedFromWindow();}
}
