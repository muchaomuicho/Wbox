package com.wbox.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LatencySelectionUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val model get()=ui.activity.model
    private lateinit var probe:Job
    private val config="""{"outbounds":[{"type":"selector","tag":"Main","outbounds":["A","B"]},{"type":"direct","tag":"A"},{"type":"direct","tag":"B"}]}"""

    @Before fun reset()=ui.runOnIdle {
        WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false;WboxVpnService.reloading=false
        val p=Profiles(ui.activity);p.data=JSONObject().put("mode","config");p.importConfig(config,null,null,true);p.save()
        model.reload();model.page="proxies"
    }
    private fun holdProbe()=ui.runOnIdle {
        probe=ui.activity.lifecycleScope.launch {awaitCancellation()}
        MobileModel::class.java.getDeclaredField("latencyJob").apply{isAccessible=true}.set(model,probe)
        MobileModel::class.java.getDeclaredField("latencySource").apply{isAccessible=true}.set(model,model.profiles.copy())
        MobileModel::class.java.getDeclaredMethod("setTesting",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(model,true)
    }
    @After fun clear() {
        if(::probe.isInitialized)probe.cancel()
        WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false;WboxVpnService.reloading=false
    }
    @Test fun offlineGroupSelectionDoesNotCancelProbeOrShowSelectionSnackbar() {
        ui.waitUntil(10000){model.groups.has("Main")}
        holdProbe()
        ui.runOnIdle{model.message="";model.selectNode("B","Main")}
        ui.waitUntil(10000){model.profiles.activeConfig()?.optJSONObject("group_selections")?.optString("Main")=="B"&&!model.busy}
        assertFalse(probe.isCancelled);assertTrue(model.testing);assertEquals("",model.message)
    }
    @Test fun choosingAnotherProfileAndReturningKeepOriginalProbeActive() {
        var original="";var other=""
        ui.runOnIdle {
            val p=Profiles(ui.activity);original=p.activeConfig()!!.getString("id");p.importConfig(config.replace("Main","Other"),null,null,false)
            other=p.configs().getJSONObject(1).getString("id");p.save();model.reload()
        }
        holdProbe()
        for(id in listOf(other,original)) {
            ui.runOnIdle{model.selectProfile(id)}
            ui.waitUntil(10000){model.profiles.activeConfig()?.optString("id")==id&&!model.busy}
            ui.waitForIdle();assertFalse(probe.isCancelled);assertTrue(model.testing)
        }
    }
    @Test fun selectingStandaloneNodeKeepsItsProbeUntilSourceChanges() {
        ui.runOnIdle {
            val p=Profiles(ui.activity);p.setWorkspace("nodes");p.importNodes(ProfilesTest.SS+"\n"+ProfilesTest.SS.replace("#Test","#Next"),null);p.save();model.reload();model.page="library"
        }
        holdProbe()
        ui.runOnIdle{model.selectNode("Next")}
        ui.waitUntil(10000){model.profiles.data.optString("selected")=="Next"&&!model.busy}
        assertFalse(probe.isCancelled);assertTrue(model.testing)
        val snapshot=MobileModel::class.java.getDeclaredField("latencySource").apply{isAccessible=true}.get(model) as Profiles
        assertTrue(snapshot.nodes().any{it.name=="Test"})
        ui.runOnIdle{model.removeNode("Test")}
        ui.waitUntil(10000){!model.busy}
        ui.runOnIdle{assertTrue("removeNode failed: ${model.message}; nodes=${model.nodes.map{it.name}}; valid=${LatencyContext.valid(model.profiles,snapshot)}",probe.isCancelled)}
    }
}
