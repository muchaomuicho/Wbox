package com.wbox.app

import android.app.Activity
import android.content.pm.ApplicationInfo
import org.junit.Assert.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.util.ReflectionHelpers

internal fun enableNativePopupBack(activity:Activity) {
    // Robolectric does not map this manifest attribute to ApplicationInfo on API 34.
    val info=activity.applicationInfo
    val enabled=ReflectionHelpers.getStaticField<Int>(ApplicationInfo::class.java,"PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK")
    ReflectionHelpers.setField(info,"privateFlagsExt",ReflectionHelpers.getField<Int>(info,"privateFlagsExt") or enabled)
}

internal class PopupBackRegistration(activity:Activity) {
    private val popup=Shadow.extract<ShadowWindowManagerImpl>(activity.windowManager).views.single{it.javaClass.simpleName=="PopupLayout"}
    private val dispatcher=requireNotNull(popup.findOnBackInvokedDispatcher())
    private fun callbacks()=ReflectionHelpers.getField<Map<Any,Int>>(dispatcher,"mAllCallbacks")
    private val registered=callbacks().filter{it.key.javaClass.name.startsWith("com.wbox.app.")&&it.value>=0}.keys.toSet()
    init {assertEquals("The popup must register its own app Back callback",1,registered.size)}
    fun assertReleased(){assertTrue("Closing the popup must unregister its Back callback",callbacks().keys.none{it in registered})}
}
