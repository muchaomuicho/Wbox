package com.wbox.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Small vector navigation icons without font or image dependencies. */
final class IconView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final int kind,color;
    public IconView(Context context){this(context,0,0xff1b1e23);}
    IconView(Context context,int kind,int color){super(context);this.kind=kind;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float size=20*getResources().getDisplayMetrics().density;
        c.save();c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/24,size/24);
        paint.setStyle(Paint.Style.STROKE);paint.setColor(color);paint.setStrokeWidth(1.65f);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);path.reset();
        switch(kind){
            case 0:path.moveTo(3,10);path.lineTo(12,3);path.lineTo(21,10);path.moveTo(5,9);path.lineTo(5,21);path.lineTo(10,21);path.lineTo(10,14);path.lineTo(14,14);path.lineTo(14,21);path.lineTo(19,21);path.lineTo(19,9);c.drawPath(path,paint);break;
            case 1:c.drawCircle(12,5,2.5f,paint);c.drawCircle(5,19,2.5f,paint);c.drawCircle(19,19,2.5f,paint);c.drawLine(12,8,12,12,paint);c.drawLine(5,12,19,12,paint);c.drawLine(5,12,5,16,paint);c.drawLine(19,12,19,16,paint);break;
            case 2:path.moveTo(5,3);path.lineTo(14,3);path.lineTo(19,8);path.lineTo(19,21);path.lineTo(5,21);path.close();path.moveTo(14,3);path.lineTo(14,8);path.lineTo(19,8);c.drawPath(path,paint);c.drawLine(8,12,16,12,paint);c.drawLine(8,16,14,16,paint);break;
            case 3:c.drawRoundRect(3,3,21,21,3,3,paint);c.drawLine(7,8,17,8,paint);c.drawLine(7,12,17,12,paint);c.drawLine(7,16,13,16,paint);break;
            default:c.drawCircle(12,12,7,paint);c.drawCircle(12,12,2.5f,paint);for(int i=0;i<8;i++){c.save();c.rotate(i*45,12,12);c.drawLine(12,2,12,5,paint);c.restore();}break;
        }c.restore();
    }
}
