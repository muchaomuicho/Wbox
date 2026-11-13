@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.wbox.app

import android.view.View
import android.view.ViewGroup
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
class EditorBackUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val original="""{"outbounds":[{"type":"direct","tag":"DIRECT"}]}"""
    @Before fun reset(){ui.runOnIdle{
        WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false
        val p=Profiles(ui.activity);p.data=JSONObject().put("mode","config").put("theme","light")
        p.importConfig(original,null,null);p.save();ui.activity.model.reload();ui.activity.model.page="overview"
    }}
    private fun findCode(view:View):ConfigCodeView? {
        if(view is ConfigCodeView)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)findCode(view.getChildAt(i))?.let{return it}
        return null
    }
    @Test fun systemBackClosesCleanPageButKeepsUnsavedDraftUntilDiscarded(){
        ui.onNodeWithContentDescription("编辑配置").performClick()
        ui.runOnIdle{assertNotNull("Editor belongs to Activity, not a Popup window",findCode(ui.activity.window.decorView));ui.activity.onBackPressedDispatcher.onBackPressed()}
        ui.onNodeWithContentDescription("配置内容").assertDoesNotExist()
        ui.onNodeWithContentDescription("编辑配置").performClick()
        ui.runOnIdle{findCode(ui.activity.window.decorView)!!.append("\n")}
        ui.runOnIdle{ui.activity.onBackPressedDispatcher.onBackPressed()}
        ui.onNodeWithText("放弃未保存的修改？").assertExists()
        ui.onNodeWithText("继续编辑").performClick()
        ui.runOnIdle{assertEquals(original+"\n",findCode(ui.activity.window.decorView)!!.text.toString());ui.activity.onBackPressedDispatcher.onBackPressed()}
        ui.onNodeWithText("放弃修改").performClick()
        ui.onNodeWithContentDescription("配置内容").assertDoesNotExist()
        ui.runOnIdle{assertEquals(original,Profiles(ui.activity).configs().getJSONObject(0).optString("content"));assertFalse(ui.activity.isFinishing)}
    }
}
