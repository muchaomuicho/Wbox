package com.wbox.app;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import java.util.regex.*;

final class LogStyle {
    private static final Pattern ANSI=Pattern.compile("\u001b\\[[0-9;]*m");
    private static final Pattern LEVEL=Pattern.compile("(?m)^(?:\\d{2}:\\d{2}:\\d{2}\\s+)?(?:\\+\\d{4}\\s+)?(?:\\d{4}-\\d{2}-\\d{2}\\s+)?(?:\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\s+)?(TRACE|DEBUG|INFO|WARN(?:ING)?|ERROR|FATAL|PANIC)\\b|^(?:\\d{2}:\\d{2}:\\d{2}\\s+)?time=.*?\\slevel=(\\w+)\\b",Pattern.CASE_INSENSITIVE|Pattern.MULTILINE);
    private static final Pattern CONNECTION=Pattern.compile("\\[(\\d+) [^\\]\\n]+\\]");
    private static final Pattern TIMESTAMP=Pattern.compile("^(?:\\+\\d{4} |\\d{4}-|time=)");
    static String plain(String text){return ANSI.matcher(text).replaceAll("");}
    static boolean timestamped(String text){return TIMESTAMP.matcher(text).find();}
    static CharSequence color(String text,boolean dark){
        SpannableString result=new SpannableString(plain(text));Matcher levels=LEVEL.matcher(result);
        while(levels.find()){int group=levels.group(1)!=null?1:2;String level=levels.group(group).toUpperCase(java.util.Locale.ROOT);int color;
            switch(level){case "ERROR":case "FATAL":case "PANIC":color=dark?0xffff9090:0xffb3261e;break;case "WARN":case "WARNING":color=dark?0xffe5bb65:0xff946500;break;case "INFO":color=dark?0xff8ab4f8:0xff245fba;break;default:color=dark?0xff80c9ce:0xff247a80;}
            result.setSpan(new ForegroundColorSpan(color),levels.start(group),levels.end(group),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int[] colors=dark?new int[]{0xff80c9ce,0xffd5a6e8,0xff8ab4f8,0xff88b49f,0xffe5bb65}:new int[]{0xff247a80,0xff894fa5,0xff245fba,0xff3d8168,0xff946500};
        Matcher ids=CONNECTION.matcher(result);while(ids.find()){long id;try{id=Long.parseLong(ids.group(1));}catch(NumberFormatException e){continue;}result.setSpan(new ForegroundColorSpan(colors[(int)(id%colors.length)]),ids.start(),ids.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
        return result;
    }
}
