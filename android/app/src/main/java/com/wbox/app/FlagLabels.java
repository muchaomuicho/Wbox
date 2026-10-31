package com.wbox.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.util.LruCache;
import java.io.InputStream;

final class FlagLabels {
    private static final LruCache<String,Bitmap> cache=new LruCache<>(32);
    static CharSequence text(Context context,String value,int dp) {
        SpannableString text=new SpannableString(value);
        for(int i=0;i+3<value.length();i++){
            if(!Character.isSurrogatePair(value.charAt(i),value.charAt(i+1))||!Character.isSurrogatePair(value.charAt(i+2),value.charAt(i+3)))continue;
            int a=value.codePointAt(i),b=value.codePointAt(i+2);
            if(a<0x1f1e6||a>0x1f1ff||b<0x1f1e6||b>0x1f1ff)continue;
            String code=""+(char)('A'+a-0x1f1e6)+(char)('A'+b-0x1f1e6);
            try {
                Bitmap bitmap=cache.get(code);
                if(bitmap==null){try(InputStream in=context.getAssets().open("flags/"+code+".png")){BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=2;bitmap=BitmapFactory.decodeStream(in,null,options);}if(bitmap==null)continue;cache.put(code,bitmap);}
                BitmapDrawable image=new BitmapDrawable(context.getResources(),bitmap);int size=Math.round(dp*context.getResources().getDisplayMetrics().density);image.setBounds(0,0,size,size);
                text.setSpan(new ImageSpan(image,ImageSpan.ALIGN_BOTTOM),i,i+4,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);i+=3;
            }catch(java.io.IOException ignored){}
        }
        return text;
    }
}
