package com.wbox.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.io.ByteArrayOutputStream

internal data class UpdateRelease(val version:String,val url:String,val sha256:String,val size:Long) {
    fun json()=JSONObject().put("version",version).put("url",url).put("sha256",sha256).put("size",size)
    companion object {
        const val REPOSITORY="muchaomuicho/Wbox"
        const val API="https://api.github.com/repos/$REPOSITORY/releases/latest"
        const val CERTIFICATE="774e7eef8410a54c9d80fe18ebd3980d07701afb176d7e94ddfee5b796c7b026"
        private val versionPattern=Regex("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})")
        fun version(value:String):List<Int> {require(versionPattern.matches(value)){"发布版本格式不受支持"};return value.split('.').map(String::toInt)}
        fun newer(candidate:String,current:String):Boolean {
            val a=version(candidate);val b=version(current)
            return a.zip(b).firstOrNull{it.first!=it.second}?.let{it.first>it.second}?:false
        }
        fun restore(json:JSONObject)=UpdateRelease(json.getString("version"),json.getString("url"),json.getString("sha256"),json.getLong("size")).also{it.validate()}
        fun parse(json:JSONObject):UpdateRelease {
            require(!json.optBoolean("draft")&&!json.optBoolean("prerelease")){"仅支持正式发布版本"}
            val tag=json.getString("tag_name");require(tag.startsWith("v")){"发布版本格式不受支持"}
            val version=tag.removePrefix("v");UpdateRelease.version(version)
            val name="Wbox-$version-android-arm64-v8a.apk"
            val assets=json.getJSONArray("assets")
            val matches=(0 until assets.length()).map{assets.getJSONObject(it)}.filter{it.optString("name")==name}
            require(matches.size==1){"该版本未提供唯一的 Android 安装包"}
            val asset=matches.single();val digest=asset.optString("digest")
            require(digest.matches(Regex("sha256:[0-9a-fA-F]{64}"))){"发布未提供 SHA256，无法安全更新"}
            return UpdateRelease(version,asset.getString("browser_download_url"),digest.removePrefix("sha256:").lowercase(),asset.getLong("size")).also{it.validate()}
        }
        fun allowedDownload(uri:URI):Boolean = uri.scheme=="https"&&uri.port==-1&&uri.userInfo==null&&uri.fragment==null&&
            uri.host in setOf("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com","github-releases.githubusercontent.com")
        private fun connection(uri:URI):HttpURLConnection {
            return (uri.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects=false;connectTimeout=15000;readTimeout=20000
                setRequestProperty("User-Agent","Wbox-Updater");setRequestProperty("Accept","application/vnd.github+json")
            }
        }
        fun latest():UpdateRelease {
            val connection=connection(URI(API))
            try {
                check(connection.responseCode==200){when(connection.responseCode){403,429->"GitHub 请求受限，请稍后重试";else->"检查更新失败（HTTP ${connection.responseCode}）"}}
                val bytes=connection.inputStream.use{input->ByteArrayOutputStream().use{output->
                    val buffer=ByteArray(8192)
                    while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=2*1024*1024){"发布信息过大"};output.write(buffer,0,count)}
                    output.toByteArray()
                }}
                return parse(JSONObject(String(bytes,Charsets.UTF_8)))
            }finally{connection.disconnect()}
        }
        fun resolveDownload(url:String):String {
            var uri=URI(url)
            repeat(6) {
                require(allowedDownload(uri)){"下载地址不是 GitHub 官方资源"}
                val connection=connection(uri)
                try {
                    connection.setRequestProperty("Accept","application/octet-stream")
                    connection.setRequestProperty("Range","bytes=0-0")
                    when(val code=connection.responseCode) {
                        200,206->return uri.toASCIIString()
                        301,302,303,307,308->{val next=connection.getHeaderField("Location");require(!next.isNullOrBlank()){ "下载重定向缺少地址" };uri=uri.resolve(next)}
                        else->error("无法下载更新（HTTP $code）")
                    }
                }finally{connection.disconnect()}
            }
            error("下载重定向次数过多")
        }
    }
    fun validate() {
        UpdateRelease.version(version)
        require(url=="https://github.com/$REPOSITORY/releases/download/v$version/Wbox-$version-android-arm64-v8a.apk"){"安装包不属于 Wbox 官方发布"}
        require(sha256.matches(Regex("[0-9a-f]{64}"))&&size in 1..300L*1024*1024){"安装包校验信息无效"}
    }
}
