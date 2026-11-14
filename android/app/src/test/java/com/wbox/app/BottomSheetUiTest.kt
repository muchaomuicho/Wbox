@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wbox.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.ColorDrawable
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomSheetUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val m get()=ui.activity.model

    @Before fun reset()=ui.runOnIdle {
        WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false
        val profiles=Profiles(ui.activity)
        profiles.data=JSONObject().put("mode","nodes").put("theme","light").put("app_mode","exclude")
        profiles.save();m.reload();m.page="settings"
    }

    @Test fun applicationPickerLoadsIconsAndKeepsSelectionAcrossSearch() {
        val packageName="org.example.iconbrowser"
        val application=ApplicationInfo().apply{this.packageName=packageName;nonLocalizedLabel="Icon Browser";uid=11001}
        val pm=shadowOf(ui.activity.packageManager)
        pm.installPackage(PackageInfo().apply{this.packageName=packageName;applicationInfo=application})
        pm.setApplicationIcon(packageName,ColorDrawable(android.graphics.Color.CYAN))
        ui.runOnIdle{m.sheet="apps"}
        ui.waitUntil(10000){ui.onAllNodesWithText("Icon Browser").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription("应用图标 · Icon Browser",useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithText("Icon Browser").performClick()
        ui.onNodeWithText("搜索应用").performTextInput("org.example.iconbrowser")
        ui.onAllNodesWithText(packageName).assertCountEquals(2)
        ui.onAllNodesWithText("保存",substring=false).onLast().performClick()
        ui.waitUntil(10000){m.sheet.isEmpty()}
        assertTrue(Profiles(ui.activity).data.getJSONArray("apps").strings().contains(packageName))
    }
}
