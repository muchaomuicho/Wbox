package com.wbox.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object LibraryTimeText {
    private const val MINUTE=60_000L
    private const val HOUR=60*MINUTE
    private const val DAY=24*HOUR
    private val date=DateTimeFormatter.ofPattern("yyyy/M/d",Locale.ROOT)
    private val dateTime=DateTimeFormatter.ofPattern("yyyy/M/d HH:mm:ss",Locale.ROOT)

    fun expiry(seconds:Long?,relative:Boolean,now:Long=System.currentTimeMillis(),zone:ZoneId=ZoneId.systemDefault()):String? {
        if(seconds==null||seconds<0||seconds>253_402_300_799L)return null
        if(seconds==0L)return "长期有效"
        val timestamp=seconds*1000
        if(!relative)return date.format(Instant.ofEpochMilli(timestamp).atZone(zone))+" 到期"
        if(timestamp<=now)return "已到期"
        return "${(timestamp-now+DAY-1)/DAY} 天后到期"
    }

    fun update(timestamp:Long,relative:Boolean,now:Long=System.currentTimeMillis(),zone:ZoneId=ZoneId.systemDefault()):String {
        if(timestamp<=0)return "未更新"
        if(!relative)return "更新于 "+dateTime.format(Instant.ofEpochMilli(timestamp).atZone(zone))
        val future=timestamp>now
        val elapsed=if(future)timestamp-now else now-timestamp
        val direction=if(future)"后" else "前"
        val text=when {
            elapsed<MINUTE->"刚刚"
            elapsed<HOUR->"${elapsed/MINUTE} 分钟$direction"
            elapsed<DAY->"${elapsed/HOUR} 小时$direction"
            else->"${elapsed/DAY} 天$direction"
        }
        return "更新于 $text"
    }
}
