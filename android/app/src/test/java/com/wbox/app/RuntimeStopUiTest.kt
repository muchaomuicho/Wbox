package com.wbox.app

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RuntimeStopUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @After fun clear(){WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false;WboxVpnService.reloading=false}

    @Test fun stopDuringReloadGapSendsStopInsteadOfStartingAgain(){
        val application=shadowOf(ui.activity.application)
        while(application.nextStartedService!=null) { }
        ui.runOnIdle {
            WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false;WboxVpnService.reloading=true
            ui.activity.model.runtime=RuntimeState()
            ui.activity.model.connectionRequested=true
            ui.activity.model.toggle()
        }
        var intent:Intent?=null
        ui.waitUntil(5000){application.nextStartedService?.also{intent=it}!=null}
        assertEquals(WboxVpnService.STOP,intent!!.action)
        assertFalse(ui.activity.model.connectionRequested)
    }

    @Test fun stopBeforeNodeChangeFinishesPreventsItsAutoConnect(){
        val application=shadowOf(ui.activity.application)
        while(application.nextStartedService!=null) { }
        ui.runOnIdle {
            WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false;WboxVpnService.reloading=false
            val p=Profiles(ui.activity);p.setWorkspace("nodes");p.importNodes(ProfilesTest.SS+"\n"+ProfilesTest.SS.replace("#Test","#Target"),null);p.data.put("selected","Test");p.save()
            val model=ui.activity.model;model.reload()
            // The last rendered state can still show the connection as active
            // after its old runtime closes and before the queued edit returns.
            model.runtime=RuntimeState(running=true);model.connectionRequested=true
            model.selectNode("Target",connect=true)
            model.toggle()
        }
        ui.waitUntil(5000){!ui.activity.model.busy&&ui.activity.model.profiles.data.optString("selected")=="Target"}
        ui.waitForIdle()
        assertNull(application.nextStartedService)
        assertFalse(ui.activity.model.connectionRequested)
    }
}
