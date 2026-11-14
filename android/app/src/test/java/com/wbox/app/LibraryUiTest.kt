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
        enableNativePopupBack(ui.activity)
        WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false;WboxVpnService.lastError=""
        val p=Profiles(ui.activity);p.data=JSONObject().put("mode","config").put("theme","light")
        p.importConfig(content,"https://example.org/z",null)
        p.configs().getJSONObject(0).put("name","Zeta")
        p.importConfig(content,"https://example.org/a",null)
        p.configs().getJSONObject(1).put("name","Alpha")
        p.save();m.reload();m.page="library"
    }}
    @After fun clear(){WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false}
    @Test fun configurationPopupRegistersAndReleasesNativeBackCallback(){
        val selected=m.profiles.data.optString("active_config")
        ui.onAllNodesWithContentDescription("配置操作").onLast().performClick()
        val registration=ui.runOnIdle{PopupBackRegistration(ui.activity)}
        ui.onNodeWithText("预览",substring=false).performClick()
        ui.onNodeWithText("预览",substring=false).assertDoesNotExist()
        ui.runOnIdle{registration.assertReleased()}
        assertEquals(selected,m.profiles.data.optString("active_config"));assertFalse(ui.activity.isFinishing)
    }
    @Test fun sortingPopupRegistersAndReleasesNativeBackCallback(){
        ui.onNodeWithContentDescription("排序订阅").performClick()
        val registration=ui.runOnIdle{PopupBackRegistration(ui.activity)}
        ui.onNodeWithText("名称",substring=false).performClick()
        ui.onNodeWithText("最近更新",substring=false).assertDoesNotExist()
        ui.runOnIdle{registration.assertReleased()}
        assertFalse(ui.activity.isFinishing)
    }
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
    @Test fun dateTogglesAreIndependentAndDoNotSelectConfiguration(){
        val now=System.currentTimeMillis()
        val updated=now-7_200_000
        val expire=(now+2*86_400_000+60_000)/1000
        var first="";var second="";var saved=""
        ui.runOnIdle{
            val p=Profiles(ui.activity)
            first=p.configs().getJSONObject(0).getString("id")
            second=p.configs().getJSONObject(1).getString("id")
            p.configs().objects().forEach{it.put("updated",updated).getJSONObject("subscription").put("usage",JSONObject().put("upload",0).put("download",1024).put("total",4096).put("expire",expire))}
            p.data.put("active_config",second);p.save();saved=p.data.toString();m.reload()
        }
        val firstExpiry=ui.onNodeWithTag("profile-$first-expiry")
        val firstUpdated=ui.onNodeWithTag("profile-$first-updated")
        val secondExpiry=ui.onNodeWithTag("profile-$second-expiry")
        firstExpiry.assertTextEquals("· "+LibraryTimeText.expiry(expire,false))
        firstUpdated.assertTextEquals("更新于 2 小时前")
        firstExpiry.performTouchInput{click()}.assertTextEquals("· 3 天后到期")
        firstUpdated.assertTextEquals("更新于 2 小时前")
        secondExpiry.assertTextEquals("· "+LibraryTimeText.expiry(expire,false))
        firstUpdated.performTouchInput{click()}.assertTextEquals(LibraryTimeText.update(updated,false))
        firstExpiry.assertTextEquals("· 3 天后到期")
        ui.onNodeWithTag("profile-$second-updated").assertTextEquals("更新于 2 小时前")
        ui.runOnIdle{assertEquals(second,m.profiles.data.optString("active_config"));assertEquals(saved,Profiles(ui.activity).data.toString());assertFalse(m.runtime.running)}
        firstExpiry.performClick().assertTextEquals("· "+LibraryTimeText.expiry(expire,false))
        firstUpdated.performClick().assertTextEquals("更新于 2 小时前")
    }
    @Test fun subscriptionDateTogglesDoNotOpenTheEditor(){
        val url="https://example.org/time-toggle"
        val updated=System.currentTimeMillis()-7_200_000
        val expire=(System.currentTimeMillis()+2*86_400_000+60_000)/1000
        var saved=""
        ui.runOnIdle{
            val p=Profiles(ui.activity);p.setWorkspace("nodes");p.importNodes(ProfilesTest.SS,url)
            SubscriptionUpdates.options(p,"",url)!!.put("last_success",updated).put("usage",JSONObject().put("expire",expire))
            p.save();saved=p.data.toString();m.reload();m.page="library"
        }
        ui.onNodeWithText("订阅",substring=false).performClick()
        ui.onNodeWithTag("subscription-$url-expiry").performTouchInput{click()}.assertTextEquals("· 3 天后到期")
        ui.onNodeWithTag("subscription-$url-updated").performTouchInput{click()}.assertTextEquals(LibraryTimeText.update(updated,false))
        ui.onNodeWithText("编辑订阅",substring=false).assertDoesNotExist()
        ui.runOnIdle{assertEquals(saved,Profiles(ui.activity).data.toString());assertFalse(m.runtime.running)}
    }
}
