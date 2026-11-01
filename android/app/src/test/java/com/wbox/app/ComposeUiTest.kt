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
    @Test fun addAndEditConfigWhileConnectedPreservesActiveProfile(){
        val original="""{"outbounds":[{"type":"direct","tag":"DIRECT"}]}"""
        ui.runOnIdle{val p=Profiles(ui.activity);p.setWorkspace("config");p.importConfig(original,null,null);p.save();m.reload();WboxVpnService.running=true}
        val active=m.profiles.activeConfig().getString("id")
        val before=m.profiles.compile("fixed-secret")
        runBlocking{m.importText("proxies: []\nproxy-groups: []\nrules: [MATCH,DIRECT]","New YAML")}
        var saved=Profiles(ui.activity)
        assertEquals(2,saved.configs().length());assertEquals(active,saved.activeConfig().getString("id"))
        assertEquals("sing-box",saved.engine());assertEquals(before,saved.compile("fixed-secret"));assertTrue(WboxVpnService.running)
        assertEquals("New YAML",saved.configs().getJSONObject(1).getString("name"))
        runBlocking{m.writeProfile(saved.configs().getJSONObject(1),"proxies: []\nrules: ['MATCH,DIRECT']")}
        saved=Profiles(ui.activity);assertEquals(active,saved.activeConfig().getString("id"))
        assertEquals(before,saved.compile("fixed-secret"));assertTrue(WboxVpnService.running)
        runBlocking{m.writeProfile(saved.activeConfig(),original.replace("DIRECT","Updated"))}
        assertTrue(WboxVpnService.running);assertTrue(Profiles(ui.activity).activeConfig().getString("content").contains("Updated"))
    }
    @Test fun groupsExpandFirstAndKeepOtherChildrenHidden(){
        ui.runOnIdle{m.groups=JSONObject("""{"First":{"all":["🇯🇵 Tokyo"],"now":"🇯🇵 Tokyo"},"Second":{"all":["Hidden node"],"now":"DIRECT"}}""");m.sheet="groups";WboxVpnService.proxies=m.groups}
        ui.onAllNodesWithText("🇯🇵 Tokyo").assertCountEquals(1);ui.onNodeWithText("Hidden node").assertDoesNotExist()
        ui.onNodeWithContentDescription("展开 Second").performClick();ui.onNodeWithText("Hidden node").assertExists()
    }
    @Test fun logColorSpansKeepOriginalText(){
        val text="+0800 2026-11-01 11:15:51 FATAL[0000] netlink socket banned\n11:15:52 INFO [123 0ms] connected"
        for(dark in listOf(false,true)){val styled=LogStyle.color(text,dark) as Spanned;assertEquals(text,styled.toString());assertTrue(styled.getSpans(0,styled.length,ForegroundColorSpan::class.java).size>=3)}
    }
    @Test fun largeGroupsStayLazyAndIgnoreNullMembership(){
        val nodes=JSONArray((0..3999).map{"Node $it"})
        val root=JSONObject().put("DIRECT",JSONObject().put("all",JSONObject.NULL))
        for(tag in listOf("0","header","nodes","Large"))root.put(tag,JSONObject().put("all",nodes).put("now","Node 0").put("type","Selector"))
        ui.runOnIdle{m.groups=root;WboxVpnService.proxies=root;m.sheet="groups"}
        ui.onNodeWithText("Node 0").assertIsDisplayed()
        ui.onNodeWithText("Node 3999").assertDoesNotExist()
        ui.onNodeWithContentDescription("展开或收起全部").performClick()
        ui.onNodeWithText("Node 0").assertDoesNotExist()
        ui.onNodeWithContentDescription("展开或收起全部").performClick()
        ui.onNodeWithText("Node 0").assertIsDisplayed()
        ui.onNodeWithText("Node 3999").assertDoesNotExist()
    }
    @Test fun connectionsRenderApplicationIconAndDetails(){
        val row=ConnectionRecord().apply{id="app";network="TCP";destination="example.test:443";packageName=ui.activity.packageName}
        ui.runOnIdle{m.connections=listOf(row);WboxVpnService.connectionRows=listOf(row);m.sheet="connections"}
        ui.waitUntil(5000){ui.onAllNodesWithContentDescription("Wbox").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("example.test:443").performClick()
        ui.onNodeWithText(ui.activity.packageName).assertExists()
    }
    @Test fun logsFollowNewRowsUntilUserReadsHistory(){
        ui.runOnIdle{m.logsPaused=true;m.logs=(1..350).map{"INFO log-row-$it"};m.page="logs"}
        ui.onNodeWithTag("log-auto-scroll").assertIsOn()
        ui.onNodeWithText("INFO log-row-350").assertIsDisplayed()
        ui.onNodeWithTag("log-list").performTouchInput{swipeDown()}
        ui.onNodeWithText("正在查看历史日志").assertExists()
        ui.runOnIdle{m.logs=(2..351).map{"INFO log-row-$it"}}
        ui.onNodeWithText("INFO log-row-351").assertIsNotDisplayed()
        ui.onNodeWithText("滚动到底部").performClick()
        ui.onNodeWithText("INFO log-row-351").assertIsDisplayed()
        ui.runOnIdle{m.logs=(3..352).map{"INFO log-row-$it"}}
        ui.onNodeWithText("INFO log-row-352").assertIsDisplayed()
        ui.onNodeWithTag("log-auto-scroll").performClick().assertIsOff()
        ui.onNodeWithTag("log-list").performTouchInput{swipeDown()}
        ui.runOnIdle{m.logs=(4..353).map{"INFO log-row-$it"}}
        ui.onNodeWithText("INFO log-row-353").assertIsNotDisplayed()
        ui.onNodeWithText("滚动到底部").performClick()
        ui.onNodeWithTag("log-auto-scroll").assertIsOn()
        ui.onNodeWithText("INFO log-row-353").assertIsDisplayed()
        ui.onNodeWithText("搜索日志").performTextInput("log-row-350")
        ui.onNodeWithText("INFO log-row-350").assertIsDisplayed()
        ui.onNodeWithContentDescription("清空日志").performClick()
        ui.onNodeWithText("暂无日志").assertExists()
    }
}
