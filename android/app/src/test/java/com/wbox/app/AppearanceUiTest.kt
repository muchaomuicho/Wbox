package com.wbox.app

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
class AppearanceUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val m get()=ui.activity.model

    @Before fun reset()=ui.runOnIdle {
        WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false
        val profiles=Profiles(ui.activity)
        profiles.data=JSONObject().put("mode","config").put("theme","dark")
        profiles.importConfig("""{"outbounds":[{"type":"direct","tag":"DIRECT"}]}""",null,null)
        profiles.save();m.reload();m.runtime=RuntimeState();m.page="settings"
    }
    @After fun clear(){WboxVpnService.running=false;WboxVpnService.starting=false;WboxVpnService.stopping=false}

    @Test fun palettesPersistWithoutChangingBrightnessPreference() {
        ui.onNodeWithText("雾蓝").assertIsSelected()
        for((key,label) in listOf("pink" to "柔粉","default" to "黑白","blue" to "雾蓝")) {
            ui.onNodeWithText(label).performClick()
            ui.waitUntil(10000){m.profiles.data.optString("accent")==key}
            ui.runOnIdle {
                assertEquals(key,Profiles(ui.activity).data.optString("accent"))
                m.reload()
                assertEquals("dark",m.profiles.data.optString("theme"))
            }
            ui.onNodeWithText(label).assertIsSelected()
        }
    }

    @Test fun configurationUsesFloatingStartAndShowsConnectionsOnlyWhileRunning() {
        for(page in listOf("overview","settings","library")) {
            ui.runOnIdle{m.page=page}
            ui.onNodeWithTag("connection-fab").assertIsDisplayed()
            ui.onAllNodesWithContentDescription("连接代理").assertCountEquals(1)
            ui.onNodeWithContentDescription("查看连接").assertDoesNotExist()
        }
        ui.onNodeWithText("未连接").assertDoesNotExist()
        ui.runOnIdle {
            WboxVpnService.starting=true
            m.runtime=RuntimeState(starting=true,status="启动中")
        }
        ui.onNodeWithTag("connection-fab").assertIsDisplayed()
        ui.onNodeWithContentDescription("取消连接").assertExists()
        ui.onNodeWithContentDescription("查看连接").assertDoesNotExist()
        ui.runOnIdle {
            WboxVpnService.starting=false;WboxVpnService.running=true
            m.runtime=RuntimeState(running=true,status="已连接")
        }
        ui.onNodeWithTag("connection-fab").assertDoesNotExist()
        ui.onNodeWithContentDescription("查看连接").assertIsDisplayed()
    }

    @Test fun monochromeKeepsNeutralSurfacesAndAccentsInBothBrightnessModes() {
        for(dark in listOf(false,true)) {
            val colors=wboxColorScheme(dark,"default")
            val neutrals=listOf(colors.primary,colors.primaryContainer,colors.secondary,colors.secondaryContainer,
                colors.tertiary,colors.tertiaryContainer,colors.background,colors.surface,colors.surfaceVariant,
                colors.onSurface,colors.onSurfaceVariant,colors.outline,colors.outlineVariant,colors.surfaceTint,colors.inversePrimary)
            for(color in neutrals) {
                assertEquals(color.red,color.green,.0001f)
                assertEquals(color.green,color.blue,.0001f)
            }
            assertNotEquals(wboxColorScheme(dark,"blue").primary,wboxColorScheme(dark,"pink").primary)
            assertEquals(wboxColorScheme(dark,"blue").primary,wboxColorScheme(dark).primary)
        }
    }
}
