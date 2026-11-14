@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.wbox.app

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowVpnService
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NodeInteractionUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val model get()=ui.activity.model

    @Before fun reset()=ui.runOnIdle {
        enableNativePopupBack(ui.activity)
        WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false;WboxVpnService.reloading=false
        WboxVpnService.lastError=""
        ShadowVpnService.setPrepareResult(null)
        val p=Profiles(ui.activity);p.data=JSONObject().put("mode","nodes").put("theme","light")
        p.importNodes(ProfilesTest.SS+"\n"+ProfilesTest.SS.replace("#Test","#Next"),null)
        p.data.put("selected","Test");p.save()
        model.reload();model.page="library";model.selectedRow=null;model.connectionRequested=false;model.message=""
        while(shadowOf(ui.activity.application).nextStartedService!=null) { }
    }

    @After fun clear() {
        WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false;WboxVpnService.reloading=false
        ShadowVpnService.reset()
    }

    private fun awaitSelection(name:String) {
        ui.waitUntil(10000){model.profiles.data.optString("selected")==name&&!model.busy}
        ui.onNodeWithTag("node-$name").assertIsSelected()
        assertEquals(name,Profiles(ui.activity).data.optString("selected"))
    }

    private fun assertOffline()=ui.runOnIdle {
        assertFalse(model.connectionRequested)
        assertFalse(WboxVpnService.running)
        assertNull(shadowOf(ui.activity.application).nextStartedService)
        assertEquals("",model.message)
    }

    private fun popupWindow()=Shadow.extract<ShadowWindowManagerImpl>(ui.activity.windowManager).views.single {
        it.javaClass.simpleName=="PopupLayout"
    }

    @Test fun oneTapPersistsSelectionWithoutStartingOrShowingFeedback() {
        ui.onNodeWithText("Next",substring=false).performTouchInput{click()}
        awaitSelection("Next")
        ui.onNodeWithTag("node-Test").assertIsNotSelected()
        assertEquals("Next",model.selectedRow)
        assertOffline()
    }

    @Test fun rapidDoubleTapStillOnlySelects() {
        ui.onNodeWithText("Next",substring=false).performTouchInput{doubleClick()}
        awaitSelection("Next")
        assertOffline()
    }

    @Test fun overflowOpensCompactAnchoredMenuWithoutSelecting() {
        val button=ui.onNodeWithTag("node-menu-Next").fetchSemanticsNode().boundsInWindow
        val card=ui.onNodeWithTag("node-Next").fetchSemanticsNode().boundsInWindow
        ui.onNodeWithTag("node-menu-Next").performTouchInput{click()}
        ui.onNodeWithTag("node-actions-Next").assertIsDisplayed()
        // Popup content has a separate coordinate origin; WindowManager owns its screen position.
        val window=popupWindow().layoutParams as WindowManager.LayoutParams
        listOf("编辑节点","克隆为手动节点","移除节点").forEach {
            ui.onNodeWithText(it,substring=false).assertIsDisplayed()
        }
        listOf("连接此节点","设为当前节点","测试延迟").forEach{ui.onNodeWithText(it,substring=false).assertDoesNotExist()}
        assertTrue("The node menu should be narrower than its card",window.width<card.width)
        assertTrue("The menu should open next to its button",window.y>=button.top&&window.y<=button.bottom+8)
        assertTrue("The menu should end next to its button",kotlin.math.abs(window.x+window.width-button.right)<=8)
        assertEquals("Test",model.profiles.data.optString("selected"))
        assertNull(model.selectedRow)
        assertOffline()
    }

    @Test fun popupRegistersNativeBackAfterEachReopenAndReleasesOnClose() {
        repeat(2) {
            ui.onNodeWithTag("node-menu-Test").performClick()
            val registration=ui.runOnIdle{PopupBackRegistration(ui.activity)}
            ui.onNodeWithText("编辑节点",substring=false).performClick()
            ui.onNodeWithTag("node-actions-Test").assertDoesNotExist()
            ui.runOnIdle{registration.assertReleased()}
            ui.onNodeWithText("取消",substring=false).performClick()
            awaitSelection("Test")
            ui.onNodeWithTag("node-Test").assertIsSelected()
            assertFalse(ui.activity.isFinishing)
            assertOffline()
        }
    }

    @Test fun cloneActionClosesMenuWithoutSelectingOrStarting() {
        ui.onNodeWithTag("node-menu-Next").performClick()
        ui.onNodeWithText("克隆为手动节点",substring=false).performClick()
        ui.onNodeWithTag("node-actions-Next").assertDoesNotExist()
        ui.waitUntil(10000){model.nodes.size==3&&!model.busy}
        awaitSelection("Test")
        assertOffline()
    }

    @Test fun longPressMenuCanOpenEditorWithoutSelecting() {
        ui.onNodeWithText("Next",substring=false).performTouchInput{longClick()}
        ui.onNodeWithText("编辑节点",substring=false).performClick()
        ui.onNodeWithTag("node-actions-Next").assertDoesNotExist()
        ui.onNodeWithText("编辑节点",substring=false).assertIsDisplayed()
        assertEquals("Test",model.profiles.data.optString("selected"))
        assertOffline()
    }

    @Test fun removeActionKeepsOtherSelectionAndStaysOffline() {
        ui.onNodeWithTag("node-menu-Next").performClick()
        ui.onNodeWithText("移除节点",substring=false).performClick()
        ui.waitUntil(10000){model.nodes.size==1&&!model.busy}
        awaitSelection("Test")
        assertOffline()
        ui.onNodeWithTag("node-actions-Next").assertDoesNotExist()
    }
}
