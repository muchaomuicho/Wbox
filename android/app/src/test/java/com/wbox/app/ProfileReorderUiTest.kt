@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.wbox.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileReorderUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val m get()=ui.activity.model
    private val content="""{"outbounds":[{"type":"direct","tag":"DIRECT"}]}"""
    private lateinit var original:List<String>
    private var active=""
    @Before fun reset()=ui.runOnIdle {
        WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false;WboxVpnService.reloading=false
        val p=Profiles(ui.activity);p.data=JSONObject().put("mode","config").put("theme","light")
        listOf("Zulu","Alpha","Beta").forEach{name->p.importConfig(content,null,null,false);p.configs().getJSONObject(p.configs().length()-1).put("name",name)}
        original=p.configs().objects().map{it.getString("id")};active=original[2];p.activateConfig(active);p.save();m.reload();m.page="library";m.message=""
    }
    @After fun clear(){WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false;WboxVpnService.reloading=false}
    private fun drag(from:String,to:String,longPress:Boolean=true) {
        val grid=ui.onNodeWithTag("profiles-grid");val origin=grid.fetchSemanticsNode().boundsInRoot.topLeft
        val source=ui.onNodeWithTag("profile-$from").fetchSemanticsNode().boundsInRoot
        val target=ui.onNodeWithTag("profile-$to").fetchSemanticsNode().boundsInRoot
        val start=source.center-origin;val end=target.center-origin
        grid.performTouchInput {
            down(start);if(longPress)advanceEventTime(700)
            for(step in 1..16)moveTo(start+(end-start)*(step/16f),delayMillis=16)
            up()
        }
    }
    @Test fun wholeCardLongPressMovesAndPersistsWithoutSelectingOrOpeningMenu() {
        drag(original[0],original[2])
        ui.waitUntil(15000){m.profiles.data.optJSONArray("config_order")?.length()==3&&!m.busy}
        assertEquals(listOf(original[1],original[2],original[0]),m.configs.map{it.optString("id")})
        ui.runOnIdle {m.reload();assertEquals(active,m.profiles.data.optString("active_config"));assertEquals("",m.message);assertFalse(m.runtime.running)}
        assertTrue(ui.onNodeWithText("Alpha").fetchSemanticsNode().positionInRoot.y<ui.onNodeWithText("Zulu").fetchSemanticsNode().positionInRoot.y)
        ui.onNodeWithText("导出文件",substring=false).assertDoesNotExist()
        ui.runOnIdle {m.page="overview";m.sheet="picker"}
        ui.onNodeWithText("选择配置",substring=false).assertExists()
        assertTrue(ui.onNodeWithText("Alpha").fetchSemanticsNode().positionInRoot.y<ui.onNodeWithText("Zulu").fetchSemanticsNode().positionInRoot.y)
    }
    @Test fun draggingAutomaticallySortedCardsSwitchesToManualOrder() {
        ui.runOnIdle{val p=Profiles(ui.activity);p.data.put("config_sort","name");p.save();m.reload()}
        drag(original[1],original[0])
        ui.waitUntil(15000){m.profiles.data.optString("config_sort")=="default"&&!m.busy}
        assertEquals(listOf(original[2],original[0],original[1]),m.configs.map{it.optString("id")})
    }
    @Test fun ordinarySwipeDoesNotCommitAnOrder() {
        drag(original[2],original[0],longPress=false)
        ui.runOnIdle {assertFalse(Profiles(ui.activity).data.has("config_order"));assertEquals(active,m.profiles.data.optString("active_config"));assertEquals("",m.message)}
    }
    @Test fun firstLargeMoveAfterLongPressIsOwnedByTheCardRatherThanGridScroll() {
        val grid=ui.onNodeWithTag("profiles-grid");val origin=grid.fetchSemanticsNode().boundsInRoot.topLeft
        val start=ui.onNodeWithTag("profile-${original[0]}").fetchSemanticsNode().boundsInRoot.center-origin
        val end=ui.onNodeWithTag("profile-${original[2]}").fetchSemanticsNode().boundsInRoot.center-origin
        grid.performTouchInput {down(start);advanceEventTime(700);moveTo(end,delayMillis=16);up()}
        ui.waitUntil(5000){m.profiles.data.optJSONArray("config_order")?.length()==3&&!m.busy}
        assertEquals(listOf(original[1],original[2],original[0]),m.configs.map{it.optString("id")})
        ui.runOnIdle {assertEquals(active,m.profiles.data.optString("active_config"));assertEquals("",m.message)}
    }
    @Test @Config(sdk=[34],qualifiers="w800dp-h891dp") fun wholeCardCanMoveAcrossGridColumns() {
        val first=ui.onNodeWithTag("profile-${original[0]}").fetchSemanticsNode().boundsInRoot
        val second=ui.onNodeWithTag("profile-${original[1]}").fetchSemanticsNode().boundsInRoot
        assertTrue(second.left>first.left)
        drag(original[0],original[1])
        ui.waitUntil(15000){m.profiles.data.optJSONArray("config_order")?.length()==3&&!m.busy}
        assertEquals(listOf(original[1],original[0],original[2]),m.configs.map{it.optString("id")})
    }
    @Test fun holdingDraggedCardAtBottomScrollsToCardsBeyondTheViewport() {
        ui.runOnIdle {
            val p=Profiles(ui.activity)
            repeat(9){p.importConfig(content,null,null,false);p.configs().getJSONObject(p.configs().length()-1).put("name","Extra $it")}
            p.save();m.reload()
        }
        val grid=ui.onNodeWithTag("profiles-grid");val bounds=grid.fetchSemanticsNode().boundsInRoot
        val start=ui.onNodeWithTag("profile-${original[0]}").fetchSemanticsNode().boundsInRoot.center-bounds.topLeft
        grid.performTouchInput {down(start);advanceEventTime(700);moveTo(start+Offset(0f,4f),delayMillis=16)}
        ui.mainClock.autoAdvance=false
        try {
            grid.performTouchInput {moveTo(Offset(start.x,bounds.height-8f),delayMillis=16)}
            ui.mainClock.advanceTimeBy(1800)
            grid.performTouchInput {up()}
        } finally {ui.mainClock.autoAdvance=true}
        ui.waitUntil(15000){m.profiles.data.optJSONArray("config_order")?.length()==12&&!m.busy}
        assertTrue("drag should reach an initially offscreen slot",m.configs.indexOfFirst{it.optString("id")==original[0]}>4)
        ui.runOnIdle {assertEquals(active,m.profiles.data.optString("active_config"));assertEquals("",m.message)}
    }
}
