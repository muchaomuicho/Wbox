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
    private final boolean upload;
    private int ink=0xff1a1a1a;
    public void setInk(int color){ink=color;invalidate();}
    public WaveView(Context context){this(context,false);}
    public WaveView(Context context,boolean upload){super(context);this.upload=upload;setContentDescription(upload?"上传流量波形":"下载流量波形");}
    public void setSamples(List<WboxVpnService.TrafficSample> values){
        if(samples.size()==values.size()&&(values.isEmpty()||samples.get(samples.size()-1)==values.get(values.size()-1)))return;
        samples=values;invalidate();
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float d=getResources().getDisplayMetrics().density,l=0,r=getWidth(),t=16*d,b=getHeight()-3*d;
        long max=1024;for(WboxVpnService.TrafficSample s:samples)max=Math.max(max,upload?s.up:s.down);max=(long)(max*1.1);
        if(samples.size()<2){paint.setColor((ink&0xffffff)|0x30000000);paint.setStrokeWidth(1.5f*d);c.drawLine(l,b,r,b,paint);return;}
        drawSeries(c,upload,l,r,t,b,max,d);
    }
    private void drawSeries(Canvas c,boolean up,float l,float r,float t,float b,long max,float d){
        line.rewind();fill.rewind();long end=samples.get(samples.size()-1).time;float prevX=0,prevY=0;boolean first=true;
        for(WboxVpnService.TrafficSample s:samples){
            float x=l+(r-l)*(1-(end-s.time)/60000f),y=b-(b-t)*(up?s.up:s.down)/max;
            if(first){line.moveTo(x,y);fill.moveTo(x,b);fill.lineTo(x,y);first=false;}
            else{float mid=(prevX+x)/2;line.lineTo(x,y);fill.lineTo(x,y);}prevX=x;prevY=y;
        }
        fill.lineTo(prevX,b);fill.close();
        {paint.setShader(new LinearGradient(0,t,0,b,(ink&0xffffff)|0x24000000,ink&0xffffff,Shader.TileMode.CLAMP));paint.setStyle(Paint.Style.FILL);c.drawPath(fill,paint);paint.setShader(null);}
        paint.setStyle(Paint.Style.STROKE);paint.setColor(ink);paint.setStrokeWidth(1.8f*d);c.drawPath(line,paint);paint.setPathEffect(null);
    }
}
