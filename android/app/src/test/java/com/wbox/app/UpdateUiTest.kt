package com.wbox.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDownloadManager
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val updates get()=ui.activity.updates
    private val release=UpdateRelease("9.0.0","https://github.com/muchaomuicho/Wbox/releases/download/v9.0.0/Wbox-9.0.0-android-arm64-v8a.apk","a".repeat(64),1000)
    @Before fun reset()=ui.runOnIdle{updates.state.value=UpdateState();updates.hide();ui.activity.model.page="settings"}
    @After fun clear() {ui.runOnIdle{updates.state.value=UpdateState();updates.hide();ui.activity.getSharedPreferences("app-update",Context.MODE_PRIVATE).edit().clear().commit()}}
    private fun screenshot(name:String) {
        if(System.getenv("WBOX_UPDATE_SCREENSHOTS")!="1")return
        val image=ui.runOnIdle {
            val view=WindowInspector.getGlobalWindowViews().last{it.visibility==android.view.View.VISIBLE}
            Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888).also{view.draw(Canvas(it))}
        }
        File(System.getProperty("wbox.root"),"docs/previews/0.7.9-android-update-$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun foundReleaseAsksBeforeDownloadingAndCanBeDeclined() {
        ui.runOnIdle{updates.state.value=UpdateState("available",release);updates.show()}
        ui.onNodeWithText("发现新版本",useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithText("下载更新",useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithText("稍后",useUnmergedTree=true).performClick()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("检查更新"))
        screenshot("settings")
        ui.runOnIdle{assertFalse(updates.panelOpen.value);assertEquals("available",updates.state.value.phase)}
        val manager=ui.activity.getSystemService(DownloadManager::class.java)
        assertEquals(0,Shadow.extract<ShadowDownloadManager>(manager).requestCount)
    }
    @Test fun hidingProgressAndChangingPagesKeepsTheDownload() {
        val manager=ui.activity.getSystemService(DownloadManager::class.java)
        val nonce="11111111-2222-3333-4444-555555555555"
        val request=DownloadManager.Request(Uri.parse(release.url)).setDestinationInExternalFilesDir(ui.activity,Environment.DIRECTORY_DOWNLOADS,"wbox-update-$nonce.apk")
        val id=manager.enqueue(request)
        Shadow.extract<ShadowDownloadManager.ShadowRequest>(request).apply{setStatus(DownloadManager.STATUS_RUNNING);setBytesSoFar(400);setTotalSize(1000)}
        ui.runOnIdle{updates.state.value=UpdateState("downloading",release,downloadId=id,nonce=nonce,bytes=400,total=1000,speed=100);updates.show()}
        screenshot("progress")
        ui.onNodeWithText("收起",useUnmergedTree=true).performClick()
        ui.runOnIdle{ui.activity.model.page="overview"}
        ui.runOnIdle{ui.activity.model.page="settings";updates.show()}
        ui.onNodeWithText("40.0% · 400 B / 1000 B",useUnmergedTree=true).assertIsDisplayed()
        screenshot("restored")
        ui.runOnIdle{assertEquals(id,updates.state.value.downloadId);assertNotNull(Shadow.extract<ShadowDownloadManager>(manager).getRequest(id))}
        manager.remove(id)
    }
    @Test fun deniedUnknownSourcePermissionKeepsAVisibleRecoveryAction() {
        ui.runOnIdle{
            updates.state.value=UpdateState("ready",release)
            AppUpdate::class.java.getDeclaredField("automatic").apply{isAccessible=true}.set(updates,true)
            runBlocking{updates.advance(ui.activity)}
            assertEquals("permission",updates.state.value.phase)
        }
        ui.onNodeWithText("允许安装",useUnmergedTree=true).assertIsDisplayed()
        updates.permissionResult()
        ui.waitUntil(5000){updates.state.value.error.isNotEmpty()}
        ui.runOnIdle{assertEquals("permission",updates.state.value.phase)}
    }
}
