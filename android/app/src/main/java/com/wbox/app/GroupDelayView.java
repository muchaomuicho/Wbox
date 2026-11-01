package com.wbox.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.util.List;

/** The same delay bands as sing-box-dashboard: <800, <1500, >=1500 ms. */
final class GroupDelayView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors;private final int selected;private final float unit;
    GroupDelayView(Context context,List<String> values,int selected,UiPalette palette,String description){super(context);unit=getResources().getDisplayMetrics().density;this.selected=selected;colors=new int[values.size()];for(int i=0;i<colors.length;i++)colors[i]=tone(values.get(i),palette);setContentDescription(description);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
    static int tone(String value,UiPalette palette){
        if(value.endsWith(" ms")){try{int delay=Integer.parseInt(value.substring(0,value.length()-3));return delay<800?palette.good:delay<1500?(palette.dark?0xffe5bb65:0xffb78a38):(palette.dark?0xffdc9292:0xffbf5252);}catch(NumberFormatException ignored){}}
        if(value.equals("失败")||value.equals("超时"))return palette.dark?0xffdc9292:0xffbf5252;
        return palette.selected;
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){int width=MeasureSpec.getSize(widthSpec);int columns=Math.max(1,(int)(width/(15*unit)));setMeasuredDimension(width,(int)(Math.ceil(colors.length/(double)columns)*15*unit));}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);int columns=Math.max(1,(int)(getWidth()/(15*unit)));for(int i=0;i<colors.length;i++){float x=i%columns*15*unit,y=i/columns*15*unit;paint.setColor(colors[i]);canvas.drawRoundRect(x,y,x+11*unit,y+11*unit,2*unit,2*unit,paint);if(i==selected){paint.setColor(0xffffffff);canvas.drawCircle(x+5.5f*unit,y+5.5f*unit,1.7f*unit,paint);}}}
}
