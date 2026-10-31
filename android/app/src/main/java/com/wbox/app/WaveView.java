package com.wbox.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;

/** Renders the service's bounded history, so changing pages does not reset the chart. */
public final class WaveView extends View {
    private List<WboxVpnService.TrafficSample> samples=List.of();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line=new Path(),fill=new Path();
    public WaveView(Context context){super(context);setContentDescription("最近 60 秒上传与下载流量趋势");}
    public void setSamples(List<WboxVpnService.TrafficSample> values){
        if(samples.size()==values.size()&&(values.isEmpty()||samples.get(samples.size()-1)==values.get(values.size()-1)))return;
        samples=values;invalidate();
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float d=getResources().getDisplayMetrics().density,l=0,r=getWidth(),t=28*d,b=getHeight()-27*d;
        long max=1024;for(WboxVpnService.TrafficSample s:samples)max=Math.max(max,Math.max(s.up,s.down));max=(long)(max*1.1);
        paint.setStrokeWidth(d);paint.setStyle(Paint.Style.STROKE);paint.setColor(0xff3b3b3b);
        for(int i=0;i<4;i++){float y=t+(b-t)*i/3;c.drawLine(l,y,r,y,paint);}
        paint.setStyle(Paint.Style.FILL);paint.setTextSize(10*d);paint.setColor(0xff8d96a2);
        c.drawText(MainActivity.bytes(max)+"/s",l,t-9*d,paint);c.drawText("60 秒前",l,b+19*d,paint);c.drawText("现在",r-paint.measureText("现在"),b+19*d,paint);
        if(samples.size()<2){paint.setColor(0xffb9c0ca);paint.setStrokeWidth(1.5f*d);c.drawLine(l,b,r,b,paint);return;}
        drawSeries(c,false,l,r,t,b,max,d);drawSeries(c,true,l,r,t,b,max,d);
    }
    private void drawSeries(Canvas c,boolean up,float l,float r,float t,float b,long max,float d){
        line.rewind();fill.rewind();long end=samples.get(samples.size()-1).time;float prevX=0,prevY=0;boolean first=true;
        for(WboxVpnService.TrafficSample s:samples){
            float x=l+(r-l)*(1-(end-s.time)/60000f),y=b-(b-t)*(up?s.up:s.down)/max;
            if(first){line.moveTo(x,y);fill.moveTo(x,b);fill.lineTo(x,y);first=false;}
            else{float mid=(prevX+x)/2;line.cubicTo(mid,prevY,mid,y,x,y);fill.cubicTo(mid,prevY,mid,y,x,y);}prevX=x;prevY=y;
        }
        fill.lineTo(prevX,b);fill.close();
        if(!up){paint.setShader(new LinearGradient(0,t,0,b,0x22dddddd,0x02dddddd,Shader.TileMode.CLAMP));paint.setStyle(Paint.Style.FILL);c.drawPath(fill,paint);paint.setShader(null);}
        paint.setStyle(Paint.Style.STROKE);paint.setColor(up?0xff98a0ad:0xffe0e0e0);paint.setStrokeWidth(1.8f*d);paint.setPathEffect(up?new DashPathEffect(new float[]{4*d,4*d},0):null);c.drawPath(line,paint);paint.setPathEffect(null);
    }
}
