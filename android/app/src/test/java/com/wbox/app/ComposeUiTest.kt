@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.wbox.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.content.*
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.compose.ui.input.key.Key

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val m get()=ui.activity.model
    @Before fun reset() {ui.runOnIdle{
        WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false;WboxVpnService.lastError=""
        val p=Profiles(ui.activity);p.data=JSONObject().put("mode","nodes");p.importNodes(ProfilesTest.SS,null);p.save();m.reload()
    }}
    @After fun clear(){WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false}
    @Test fun navigationUsesFourDestinationsAndCompactStart(){
        ui.onNodeWithContentDescription("连接代理").assertExists()
        ui.onNodeWithText("当前节点").assertExists()
        ui.onAllNodesWithText("日志").onLast().performClick();ui.onNodeWithContentDescription("复制日志").assertExists()
        ui.onAllNodesWithText("设置").onLast().performClick();ui.onNodeWithText("主题").assertExists()
        assertEquals("0:09",uptime(9));assertEquals("10:00",uptime(600));assertEquals("1:00:00",uptime(3600))
    }
    @Test fun nodeImportPersistsAndLongPressOffersEditing(){
        ui.runOnIdle{m.page="library"}
        ui.onNodeWithContentDescription("添加节点或订阅").performClick()
        ui.onNodeWithText("订阅地址或节点链接").performTextInput(ProfilesTest.SS.replace("#Test","#Imported"))
        ui.onNodeWithText("添加",substring=false).performClick()
        ui.waitUntil(10000){m.nodes.size==2}
        ui.onNodeWithText("Imported").assertExists()
        ui.onNodeWithText("Imported").performTouchInput{longClick()}
        ui.onNodeWithText("编辑节点").performClick()
        ui.onNodeWithText("端口",substring=false).performTextReplacement("70000")
        ui.onNodeWithText("保存",substring=false).performClick()
        ui.onNodeWithText("请填写名称、服务器地址和有效端口。",substring=false).assertExists()
        assertEquals(8388,Profiles(ui.activity).nodes().last().port)
    }
    @Test fun largeNodeListComposesOnlyVisibleRows(){
        ui.runOnIdle{val p=Profiles(ui.activity);p.data=JSONObject().put("mode","nodes");p.importNodes((1..1000).joinToString("\n"){ProfilesTest.SS.replace("#Test","#Node$it")},null);p.save();m.reload();m.page="library"}
        ui.onNodeWithText("Node1").assertExists();ui.onNodeWithText("Node1000").assertDoesNotExist()
        ui.onNodeWithText("搜索节点").performTextInput("Node1000")
        ui.onAllNodesWithText("Node1000").assertCountEquals(2)
    }
    @Test fun keyboardPasteImportsOnlyOutsideInputs(){
        ui.runOnIdle{(ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("node",ProfilesTest.SS.replace("#Test","#Keyboard")))}
        ui.onRoot().performKeyInput{keyDown(Key.CtrlLeft);pressKey(Key.V);keyUp(Key.CtrlLeft)}
        ui.waitUntil(10000){m.nodes.size==2}
        ui.runOnIdle{m.page="library";m.selectedRow="Keyboard"}
        ui.onRoot().performKeyInput{keyDown(Key.CtrlLeft);pressKey(Key.D);keyUp(Key.CtrlLeft)}
        ui.onNodeWithText("编辑节点").assertExists();ui.onNodeWithText("取消").performClick()
        ui.onNodeWithText("搜索节点").performClick()
        ui.onRoot().performKeyInput{keyDown(Key.CtrlLeft);pressKey(Key.V);keyUp(Key.CtrlLeft)}
        ui.runOnIdle{assertEquals(2,m.nodes.size)}
    }
    @Test fun remoteNameAndSettingsSurviveReload(){
        val config="""{"outbounds":[{"type":"direct","tag":"DIRECT"}]}"""
        ui.runOnIdle{val p=Profiles(ui.activity);p.setWorkspace("config");p.importConfig(config,"https://example.org/config",null);p.save();m.reload()}
        val id=m.configs.first().getString("id")
        runBlocking{m.renameProfile(id,"我的配置");m.saveApps("include",listOf("org.example.browser"))}
        assertEquals("我的配置",Profiles(ui.activity).activeConfig().getString("name"))
        assertEquals("org.example.browser",Profiles(ui.activity).data.getJSONArray("apps").getString(0))
    }
    @Test fun groupsExpandFirstAndKeepOtherChildrenHidden(){
        ui.runOnIdle{m.groups=JSONObject("""{"First":{"all":["🇯🇵 Tokyo"],"now":"🇯🇵 Tokyo"},"Second":{"all":["Hidden node"],"now":"DIRECT"}}""");m.sheet="groups";WboxVpnService.proxies=m.groups}
        ui.onAllNodesWithText("🇯🇵 Tokyo").assertCountEquals(2);ui.onNodeWithText("Hidden node").assertDoesNotExist()
        ui.onNodeWithContentDescription("展开 Second").performClick();ui.onNodeWithText("Hidden node").assertExists()
    }
    @Test fun logColorSpansKeepOriginalText(){
        val text="+0800 2026-11-01 11:15:51 FATAL[0000] netlink socket banned\n11:15:52 INFO [123 0ms] connected"
        for(dark in listOf(false,true)){val styled=LogStyle.color(text,dark) as Spanned;assertEquals(text,styled.toString());assertTrue(styled.getSpans(0,styled.length,ForegroundColorSpan::class.java).size>=3)}
    }
}
