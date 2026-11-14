package com.wbox.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class UpdateReleaseTest {
    private fun release()=JSONObject("""{"tag_name":"v0.7.10","draft":false,"prerelease":false,"assets":[{"name":"Wbox-0.7.10-android-arm64-v8a.apk","size":56000000,"digest":"sha256:${"a".repeat(64)}","browser_download_url":"https://github.com/muchaomuicho/Wbox/releases/download/v0.7.10/Wbox-0.7.10-android-arm64-v8a.apk"}]}""")
    private fun rejects(block:()->Unit) {try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}}
    @Test fun stableNumericVersionsCompareWithoutLexicalErrors() {
        assertTrue(UpdateRelease.newer("0.7.10","0.7.9"));assertFalse(UpdateRelease.newer("0.7.9","0.7.10"))
        assertFalse(UpdateRelease.newer("0.7.9","0.7.9"));assertTrue(UpdateRelease.newer("1.0.0","0.99.99"))
        for(value in listOf("0.7.9-rc1","0.7","00.7.9","0.7.9+build","0.7.9999999"))rejects{UpdateRelease.version(value)}
    }
    @Test fun requiresStableReleaseExactAssetAndDigest() {
        assertEquals("0.7.10",UpdateRelease.parse(release()).version)
        rejects{UpdateRelease.parse(release().put("draft",true))};rejects{UpdateRelease.parse(release().put("prerelease",true))}
        for(key in listOf("name","browser_download_url","digest"))rejects{val json=release();json.getJSONArray("assets").getJSONObject(0).put(key,"incorrect");UpdateRelease.parse(json)}
        for(size in listOf(0L,-1L,400L*1024*1024))rejects{val json=release();json.getJSONArray("assets").getJSONObject(0).put("size",size);UpdateRelease.parse(json)}
        rejects{val json=release();json.getJSONArray("assets").put(json.getJSONArray("assets").getJSONObject(0));UpdateRelease.parse(json)}
    }
    @Test fun downloadRedirectsAcceptOnlyOfficialHttpsHosts() {
        for(url in listOf("https://github.com/path","https://release-assets.githubusercontent.com/path?sig=abc","https://objects.githubusercontent.com/path"))assertTrue(url,UpdateRelease.allowedDownload(URI(url)))
        for(url in listOf("http://github.com/path","https://github.com.evil.test/path","https://evil.test/path","https://github.com@evil.test/path","https://github.com:443/path","https://github.com/path#fragment"))assertFalse(url,UpdateRelease.allowedDownload(URI(url)))
        rejects{val json=release();json.getJSONArray("assets").getJSONObject(0).put("browser_download_url","https://github.com/other/Wbox/releases/download/v0.7.10/Wbox-0.7.10-android-arm64-v8a.apk");UpdateRelease.parse(json)}
    }
    @Test fun persistedReleaseIsValidatedAgain() {
        val selected=UpdateRelease.parse(release());assertEquals(selected,UpdateRelease.restore(selected.json()))
        rejects{UpdateRelease.restore(selected.json().put("url","https://evil.test/update.apk"))}
    }
}
