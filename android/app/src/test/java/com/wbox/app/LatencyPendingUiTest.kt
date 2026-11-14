package com.wbox.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LatencyPendingUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val model get()=ui.activity.model
    @Before fun reset()=ui.runOnIdle{
        WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false;WboxVpnService.reloading=false
        val p=Profiles(ui.activity);p.data=JSONObject().put("mode","nodes")
        p.importNodes(listOf("Test","Next","Other").joinToString("\n"){ProfilesTest.SS.replace("#Test","#$it")},null)
        p.save();model.reload();model.page="library"
    }
    @After fun clear(){WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false;WboxVpnService.reloading=false}

    @Suppress("UNCHECKED_CAST") private fun seed(vararg values:Pair<String,String>) {
        val cache=MobileModel::class.java.getDeclaredField("latencyResults").apply{isAccessible=true}.get(model) as MutableMap<String,MutableMap<String,String>>
        cache[LatencyContext.key(model.profiles)]=values.toMap().toMutableMap()
        model.delays.clear();model.delays.putAll(values.toMap())
    }
    private fun awaitCancelled(){ui.waitUntil(10000){!model.testing};ui.waitForIdle()}

    @Test fun nodeBatchReplacesAllOldNumbersBeforePreparationAndClearsCancelledTargets(){
        ui.runOnIdle {
            seed("Test" to "80 ms","Next" to "90 ms","Other" to "100 ms")
            model.latency(listOf("Test","Next"))
            assertTrue(model.testing);assertEquals("测试中",model.delays["Test"]);assertEquals("测试中",model.delays["Next"])
            assertEquals("100 ms",model.delays["Other"])
            model.latency(listOf("Test","Next"))
        }
        awaitCancelled()
        ui.runOnIdle{assertFalse(model.delays.containsKey("Test"));assertFalse(model.delays.containsKey("Next"));assertEquals("100 ms",model.delays["Other"])}
    }
    @Test fun configurationBatchMarksResolvedTargetsImmediatelyAndLeavesOtherGroupsAlone(){
        ui.runOnIdle {
            val p=Profiles(ui.activity);p.setWorkspace("config")
            p.importConfig("""{"outbounds":[{"type":"selector","tag":"Main","outbounds":["A","Nested"]},{"type":"selector","tag":"Nested","outbounds":["B","C"]},{"type":"direct","tag":"A"},{"type":"direct","tag":"B"},{"type":"direct","tag":"C"}]}""",null,null,true)
            p.save();model.reload();model.groups=ProxyCatalog.offline(model.profiles)
            seed("A" to "80 ms","B" to "90 ms","C" to "100 ms")
            model.latency(listOf("Main"))
            assertTrue(model.testing);assertEquals("测试中",model.delays["A"]);assertEquals("测试中",model.delays["B"])
            assertEquals("100 ms",model.delays["C"]);assertFalse(model.delays.containsKey("Main"));assertFalse(model.delays.containsKey("Nested"))
            model.latency(listOf("Main"))
        }
        awaitCancelled()
        ui.runOnIdle{assertFalse(model.delays.containsKey("A"));assertFalse(model.delays.containsKey("B"));assertEquals("100 ms",model.delays["C"])}
    }
    @Test fun singleNestedTargetDoesNotMarkItsOtherMembers(){
        ui.runOnIdle {
            val p=Profiles(ui.activity);p.setWorkspace("config")
            p.importConfig("""{"outbounds":[{"type":"selector","tag":"Main","outbounds":["A","B"]},{"type":"direct","tag":"A"},{"type":"direct","tag":"B"}]}""",null,null,true)
            p.save();model.reload();model.groups=ProxyCatalog.offline(model.profiles)
            seed("A" to "80 ms","B" to "90 ms")
            model.latency(listOf("Main"),expandGroup=false)
            assertEquals("测试中",model.delays["A"]);assertEquals("90 ms",model.delays["B"])
            model.latency(listOf("Main"))
        }
        awaitCancelled()
        ui.runOnIdle{assertFalse(model.delays.containsKey("A"));assertEquals("90 ms",model.delays["B"])}
    }
}
