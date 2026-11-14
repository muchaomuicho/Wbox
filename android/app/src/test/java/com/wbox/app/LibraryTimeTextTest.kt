package com.wbox.app

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LibraryTimeTextTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    private val now=Instant.parse("2026-11-14T04:00:00Z").toEpochMilli()
    private val day=86_400_000L

    @Test fun expiryUsesLocalDateOrRemainingDays() {
        val expiry=Instant.parse("2026-11-16T16:00:00Z").epochSecond
        assertEquals("2026/11/17 到期",LibraryTimeText.expiry(expiry,false,now,zone))
        assertEquals("3 天后到期",LibraryTimeText.expiry(expiry,true,now,zone))
        assertEquals("1 天后到期",LibraryTimeText.expiry((now+1000)/1000,true,now,zone))
        assertEquals("2 天后到期",LibraryTimeText.expiry((now+2*day)/1000,true,now,zone))
    }

    @Test fun expiryHandlesMissingUnlimitedAndExpiredValues() {
        assertNull(LibraryTimeText.expiry(null,false,now,zone))
        assertNull(LibraryTimeText.expiry(-1,false,now,zone))
        assertNull(LibraryTimeText.expiry(Long.MAX_VALUE,false,now,zone))
        assertEquals("长期有效",LibraryTimeText.expiry(0,false,now,zone))
        assertEquals("长期有效",LibraryTimeText.expiry(0,true,now,zone))
        assertEquals("已到期",LibraryTimeText.expiry(now/1000,true,now,zone))
        assertEquals("2026/11/14 到期",LibraryTimeText.expiry((now-1000)/1000,false,now,zone))
    }

    @Test fun updateKeepsLongAgesRelativeAndCanShowFullDateTime() {
        assertEquals("更新于 刚刚",LibraryTimeText.update(now-59_000,true,now,zone))
        assertEquals("更新于 1 分钟前",LibraryTimeText.update(now-60_000,true,now,zone))
        assertEquals("更新于 2 小时前",LibraryTimeText.update(now-7_200_000,true,now,zone))
        assertEquals("更新于 20 天前",LibraryTimeText.update(now-20*day,true,now,zone))
        assertEquals("更新于 2026/11/14 10:00:00",LibraryTimeText.update(now-7_200_000,false,now,zone))
    }

    @Test fun updateHandlesMissingAndFutureTimestamps() {
        assertEquals("未更新",LibraryTimeText.update(0,true,now,zone))
        assertEquals("未更新",LibraryTimeText.update(-1,false,now,zone))
        assertEquals("更新于 刚刚",LibraryTimeText.update(now+30_000,true,now,zone))
        assertEquals("更新于 2 分钟后",LibraryTimeText.update(now+120_000,true,now,zone))
        assertEquals("更新于 3 天后",LibraryTimeText.update(now+3*day,true,now,zone))
    }
}
