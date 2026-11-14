package com.wbox.app

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.util.LruCache
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ConnectionIconMemoryTest {
    @Suppress("UNCHECKED_CAST") @Test fun pressureEvictsCachedReferencesWithoutRecyclingDisplayedBitmap() {
        val cache=Class.forName("com.wbox.app.ConnectionIconKt").getDeclaredField("icons").apply{isAccessible=true}.get(null) as LruCache<String,ConnectionAppIcon>
        val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        try {
            cache.put("test",ConnectionAppIcon(bitmap,"test"))
            trimConnectionIcons(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE);assertNotNull(cache.get("test"))
            trimConnectionIcons(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);assertEquals(0,cache.size());assertFalse(bitmap.isRecycled)
        }finally{cache.evictAll()}
    }
}
