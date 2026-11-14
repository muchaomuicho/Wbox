package com.wbox.app

import android.content.pm.PackageInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.io.File
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class UpdatePackageTest {
    private val release=UpdateRelease("0.7.9","https://github.com/muchaomuicho/Wbox/releases/download/v0.7.9/Wbox-0.7.9-android-arm64-v8a.apk","a".repeat(64),10)
    @Suppress("DEPRECATION") private fun info(version:String,code:Int)=PackageInfo().apply{packageName="com.wbox.app";versionName=version;versionCode=code}
    private fun rejects(block:()->Unit) {try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}}
    @Test fun identityRequiresOriginalSignerMatchingPackageAndStrictlyNewerCode() {
        val old=info("0.7.8",28);val next=info("0.7.9",29);val trusted=setOf(UpdateRelease.CERTIFICATE)
        AppUpdate.validateIdentity(release,next,old,trusted,trusted)
        rejects{AppUpdate.validateIdentity(release,next.apply{packageName="other"},old,trusted,trusted)};next.packageName="com.wbox.app"
        rejects{AppUpdate.validateIdentity(release,info("0.8.0",29),old,trusted,trusted)}
        rejects{AppUpdate.validateIdentity(release,info("0.7.9",28),old,trusted,trusted)}
        rejects{AppUpdate.validateIdentity(release,next,old,setOf("other"),trusted)}
        rejects{AppUpdate.validateIdentity(release,next,old,trusted,setOf("debug"))}
        rejects{AppUpdate.validateIdentity(release,next,old,trusted+"additional",trusted+"additional")}
    }
    @Test fun streamingCopyRejectsTruncationExtraBytesAndHashMismatch() {
        val bytes=ByteArray(160000){(it%253).toByte()};val digest=AppUpdate.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val output=ByteArrayOutputStream()
        val limited=object:ByteArrayInputStream(bytes){override fun read(target:ByteArray,offset:Int,length:Int):Int {assertTrue(length<=64*1024);return super.read(target,offset,length)}}
        AppUpdate.copyVerified(limited,output,bytes.size.toLong(),digest);assertArrayEquals(bytes,output.toByteArray())
        for(size in listOf(bytes.size-1,bytes.size+1))rejects{AppUpdate.copyVerified(ByteArrayInputStream(bytes),ByteArrayOutputStream(),size.toLong(),digest)}
        rejects{AppUpdate.copyVerified(ByteArrayInputStream(bytes),ByteArrayOutputStream(),bytes.size.toLong(),"0".repeat(64))}
    }
    @Test fun persistedDownloadAndInstallSessionsKeepTheirIdentity() {
        val state=UpdateState("installing",release,downloadId=72,sessionId=14,nonce="11111111-2222-3333-4444-555555555555",confirmation="intent:#Intent;end")
        assertEquals(state,UpdateState.restore(state.json()))
    }
    @Test fun renamedDownloadUsesItsActualUriAndRejectsOtherTasksAndDirectories() {
        val directory=File(RuntimeEnvironment.getApplication().cacheDir,"update-files").apply{mkdirs()}
        val nonce="11111111-2222-3333-4444-555555555555"
        val renamed=File(directory,"wbox-update-$nonce-1.apk")
        assertEquals(renamed.canonicalFile,AppUpdate.sourceFile(directory,nonce,renamed.toURI().toString()))
        rejects{AppUpdate.sourceFile(directory,nonce,File(directory,"wbox-update-other.apk").toURI().toString())}
        rejects{AppUpdate.sourceFile(directory,nonce,File(directory.parentFile,renamed.name).toURI().toString())}
        rejects{AppUpdate.sourceFile(directory,nonce,"https://github.com/${renamed.name}")}
        rejects{AppUpdate.sourceFile(directory,nonce,renamed.toURI().toString()+"#fragment")}
    }
}
