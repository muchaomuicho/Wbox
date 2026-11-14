package com.wbox.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDownloadManager
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class UpdateLifecycleTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val manager get()=context.getSystemService(DownloadManager::class.java)
    private val installer get()=context.packageManager.packageInstaller
    private val nonce="11111111-2222-3333-4444-555555555555"
    private val release=UpdateRelease("9.0.0","https://github.com/muchaomuicho/Wbox/releases/download/v9.0.0/Wbox-9.0.0-android-arm64-v8a.apk","a".repeat(64),1000)
    private fun updater(initial:UpdateState):AppUpdate {
        context.getSharedPreferences("app-update",Context.MODE_PRIVATE).edit().clear().putString("state",initial.json().toString()).commit()
        return AppUpdate::class.java.getDeclaredConstructor(Context::class.java).apply{isAccessible=true}.newInstance(context)
    }
    private fun download(name:String="wbox-update-$nonce.apk"):Pair<Long,ShadowDownloadManager.ShadowRequest> {
        val request=DownloadManager.Request(Uri.parse(release.url)).setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,name)
        val id=manager.enqueue(request)
        return id to Shadow.extract<ShadowDownloadManager.ShadowRequest>(request).apply{setStatus(DownloadManager.STATUS_RUNNING);setBytesSoFar(400);setTotalSize(1000)}
    }
    private fun session(label:String=AppUpdate.SESSION_LABEL):Int=installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply{setAppPackageName(context.packageName);setAppLabel(label)})
    @Test fun downloadProgressSurvivesControllerRecreationAndIsNotPolledWhenHidden()=runBlocking {
        val (id,request)=download();val initial=UpdateState("downloading",release,downloadId=id,nonce=nonce)
        val first=updater(initial);first.refresh();assertEquals(400,first.state.value.bytes)
        request.setBytesSoFar(650);first.refresh(progress=false);assertEquals(400,first.state.value.bytes)
        val recreated=updater(initial);recreated.refresh();assertEquals(650,recreated.state.value.bytes)
        first.show();first.hide();assertEquals(id,first.state.value.downloadId)
    }
    @Test fun cancelRemovesOnlyThisDownloadAndSession()=runBlocking {
        val (id,_)=download();val (unrelated,_)=download("saved-file.apk");val sid=session();val other=session("Other task")
        val folder=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
        val partial=File(folder,"wbox-update-$nonce.apk").apply{writeText("partial")}
        val renamed=File(folder,"wbox-update-$nonce-1.apk").apply{writeText("complete")}
        val update=updater(UpdateState("ready",release,downloadId=id,sessionId=sid,nonce=nonce))
        update.cancel();withTimeout(5000){while(update.state.value.phase!="available")delay(10)}
        assertNull(installer.getSessionInfo(sid));assertNotNull(installer.getSessionInfo(other))
        assertFalse(partial.exists());assertFalse(renamed.exists())
        val shadow=Shadow.extract<ShadowDownloadManager>(manager);assertNull(shadow.getRequest(id));assertNotNull(shadow.getRequest(unrelated))
    }
    @Test fun stagedInstallerCopySurvivesSourceRemovalAndReopening()=runBlocking {
        val (id,_)=download();val sid=session()
        val update=updater(UpdateState("ready",release,downloadId=id,sessionId=sid,nonce=nonce))
        update.refresh();assertEquals(-1,update.state.value.downloadId);assertNotNull(installer.getSessionInfo(sid));assertEquals("ready",update.state.value.phase)
        val reopened=updater(update.state.value);reopened.refresh();assertNotNull(installer.getSessionInfo(sid))
        reopened.receive(Intent(AppUpdate.INSTALL_STATUS).setData(Uri.parse("wbox-update://wrong")).putExtra(PackageInstaller.EXTRA_SESSION_ID,sid).putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_SUCCESS))
        assertEquals("ready",reopened.state.value.phase)
    }
    @Test fun recoversEnqueuedDownloadAndClearsOnlyTaggedOrphans()=runBlocking {
        val (id,_)=download();val other=session("Other task");val orphan=session()
        val folder=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
        val orphanFile=File(folder,"wbox-update-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.apk").apply{writeText("orphan")}
        val keep=File(folder,"saved-export.apk").apply{writeText("keep")}
        try {
            val update=updater(UpdateState("preparing",release,nonce=nonce));update.refresh()
            assertEquals("downloading",update.state.value.phase);assertEquals(id,update.state.value.downloadId)
            assertNull(installer.getSessionInfo(orphan));assertNotNull(installer.getSessionInfo(other));assertFalse(orphanFile.exists());assertTrue(keep.exists())
        }finally{orphanFile.delete();keep.delete()}
    }
    @Test fun queuedAdvanceCannotReviveAnAlreadyCancelledUpdate()=runBlocking {
        val activity=Robolectric.buildActivity(MainActivity::class.java).get()
        for(phase in listOf("ready","confirmation")) {
            val sid=session();val update=updater(UpdateState(phase,release,sessionId=sid,nonce=nonce,confirmation="invalid"))
            AppUpdate::class.java.getDeclaredField("scope").apply{isAccessible=true}.set(update,CoroutineScope(SupervisorJob()+Dispatchers.Unconfined))
            val mutex=AppUpdate::class.java.getDeclaredField("mutex").apply{isAccessible=true}.get(update) as Mutex
            mutex.lock();update.cancel()
            val advancing=launch(start=CoroutineStart.UNDISPATCHED){update.advance(activity)}
            mutex.unlock();advancing.join()
            assertEquals("available",update.state.value.phase);assertEquals(-1,update.state.value.sessionId);assertEquals("",update.state.value.error)
        }
    }
}
