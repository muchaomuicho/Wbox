@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.wbox.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
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
class LibraryUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val m get()=ui.activity.model
    private val content="""{"outbounds":[{"type":"direct","tag":"DIRECT"}]}"""
    @Before fun reset(){ui.runOnIdle{
        WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false;WboxVpnService.lastError=""
        val p=Profiles(ui.activity);p.data=JSONObject().put("mode","config").put("theme","light")
        p.importConfig(content,"https://example.org/z",null)
        p.configs().getJSONObject(0).put("name","Zeta")
        p.importConfig(content,"https://example.org/a",null)
        p.configs().getJSONObject(1).put("name","Alpha")
        p.save();m.reload();m.page="library"
    }}
    @After fun clear(){WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false}
    @Test fun configurationActionsOpenReadOnlyPreviewAndCodeEditor(){
        ui.onNodeWithContentDescription("更新订阅 · Zeta").assertExists().assertIsEnabled()
        ui.onAllNodesWithContentDescription("配置操作").onFirst().performClick()
        listOf("编辑","预览","更多","导出文件","删除").forEach{ui.onNodeWithText(it,substring=false).assertExists()}
        ui.onNodeWithText("同步",substring=false).assertDoesNotExist()
        ui.onNodeWithText("预览",substring=false).performClick()
        ui.onNodeWithText("预览配置").assertExists()
        ui.onNodeWithContentDescription("配置预览").assertExists()
        ui.onNodeWithContentDescription("导出文件").assertExists()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onAllNodesWithContentDescription("配置操作").onFirst().performClick()
        ui.onNodeWithText("编辑",substring=false).performClick()
        ui.onNodeWithText("编辑配置",substring=false).assertExists()
        ui.onNodeWithContentDescription("配置内容").assertExists()
    }
    @Test fun selectedSortSurvivesReloadAndChangesVisibleOrder(){
        val before=ui.onNodeWithText("Zeta").fetchSemanticsNode().positionInRoot.y
        assertTrue(before<ui.onNodeWithText("Alpha").fetchSemanticsNode().positionInRoot.y)
        ui.onNodeWithContentDescription("排序订阅").performClick()
        ui.onNodeWithText("名称",substring=false).performClick()
        ui.waitUntil(10000){m.profiles.data.optString("config_sort")=="name" || m.message.isNotEmpty()}
        assertEquals(m.message,"name",m.profiles.data.optString("config_sort"))
        assertTrue(ui.onNodeWithText("Alpha").fetchSemanticsNode().positionInRoot.y<ui.onNodeWithText("Zeta").fetchSemanticsNode().positionInRoot.y)
        ui.runOnIdle{m.reload()}
        assertEquals("name",Profiles(ui.activity).data.optString("config_sort"))
        ui.onNodeWithContentDescription("更新全部").assertExists().assertIsEnabled()
    }
    @Test fun subscriptionCardPreviewShowsOnlyItsNodes(){
        val url="https://example.org/nodes"
        ui.runOnIdle{val p=Profiles(ui.activity);p.setWorkspace("nodes");p.importNodes(ProfilesTest.SS.replace("#Test","#Subscribed"),url);p.importNodes(ProfilesTest.SS.replace("#Test","#Manual"),null);p.save();m.reload();m.page="library"}
        ui.onNodeWithText("订阅",substring=false).performClick()
        ui.onNodeWithContentDescription("订阅操作").performClick()
        ui.onNodeWithText("预览",substring=false).performClick()
        ui.onNodeWithText("Subscribed",substring=false).assertExists()
        ui.onNodeWithText("Manual",substring=false).assertDoesNotExist()
    }
}
