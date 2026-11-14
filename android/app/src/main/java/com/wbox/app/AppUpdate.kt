package com.wbox.app

import android.app.*
import android.content.*
import android.content.pm.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

internal data class UpdateState(
    val phase:String="idle",val release:UpdateRelease?=null,val error:String="",
    val downloadId:Long=-1,val sessionId:Int=-1,val nonce:String="",val confirmation:String="",
    val bytes:Long=0,val total:Long=0,val speed:Long=0
) {
    fun json()=JSONObject().put("phase",phase).put("release",release?.json()).put("error",error)
        .put("downloadId",downloadId).put("sessionId",sessionId).put("nonce",nonce).put("confirmation",confirmation)
    companion object {
        fun restore(json:JSONObject)=UpdateState(json.optString("phase","idle"),json.optJSONObject("release")?.let(UpdateRelease::restore),json.optString("error"),
            json.optLong("downloadId",-1),json.optInt("sessionId",-1),json.optString("nonce"),json.optString("confirmation")).also {
                require(it.nonce.isEmpty()||it.nonce.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))){"更新任务标识无效"}
            }
    }
}

/** DownloadManager owns network work; this object only polls while an Activity is visible. */
internal class AppUpdate private constructor(private val context:Context) {
    private val prefs=context.getSharedPreferences("app-update",Context.MODE_PRIVATE)
    private val downloads=context.getSystemService(DownloadManager::class.java)
    private val installer=context.packageManager.packageInstaller
    private val mutex=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val state=MutableStateFlow(runCatching{UpdateState.restore(JSONObject(prefs.getString("state","{}")!!))}.getOrDefault(UpdateState()))
    val panelOpen=MutableStateFlow(false)
    @Volatile private var automatic=state.value.phase in setOf("downloading","downloaded","verifying","ready","confirmation")
    private var lastBytes=0L
    private var lastSample=0L
    private var recovered=false
    private fun save(value:UpdateState) {check(prefs.edit().putString("state",value.json().toString()).commit()){ "无法保存更新进度" };state.value=value}
    private fun action(block:suspend ()->Unit) {scope.launch{mutex.withLock{try{block()}catch(e:Exception){if(e is CancellationException)throw e;fail(e)}}}}
    private fun fail(error:Exception) {
        automatic=false
        var value=state.value
        if(value.phase in setOf("preparing","downloading","downloaded","verifying")) {
            runCatching{discard()};value=value.copy(downloadId=-1,sessionId=-1)
        }
        value=value.copy(phase="failed",error=error.message?:"更新失败，请重试",speed=0)
        try{save(value)}catch(_:Exception){state.value=value}
        notifyReady("更新失败，点击查看")
    }
    fun show() {panelOpen.value=true}
    fun hide() {panelOpen.value=false}
    fun check() {show();action {
        if(state.value.phase in setOf("checking","preparing","downloading","downloaded","verifying","ready","permission","confirmation","installing"))return@action
        discard()
        save(UpdateState(phase="checking"))
        val release=UpdateRelease.latest()
        save(UpdateState(phase=if(UpdateRelease.newer(release.version,installed().versionName?:"0.0.0"))"available" else "current",release=release))
    }}
    fun download() {action {
        if(state.value.phase !in setOf("available","failed"))return@action
        val release=state.value.release?:error("请先检查更新")
        require(UpdateRelease.newer(release.version,installed().versionName?:"0.0.0")){"当前已是此版本或更新版本"}
        discard();save(UpdateState(phase="preparing",release=release));automatic=true
        val resolved=UpdateRelease.resolveDownload(release.url)
        val nonce=UUID.randomUUID().toString()
        save(state.value.copy(nonce=nonce))
        val request=DownloadManager.Request(Uri.parse(resolved)).setTitle("Wbox ${release.version}")
            .setDescription("正在下载应用更新").setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true).setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,"wbox-update-$nonce.apk")
        val id=downloads.enqueue(request)
        try{save(UpdateState(phase="downloading",release=release,downloadId=id,nonce=nonce,total=release.size))}catch(e:Exception){downloads.remove(id);throw e}
        lastBytes=0;lastSample=SystemClock.elapsedRealtime()
    }}
    fun cancel() {action {discard();automatic=false;save(UpdateState(phase=if(state.value.release==null)"idle" else "available",release=state.value.release));clearNotification()}}
    private fun installed():PackageInfo = context.packageManager.getPackageInfo(context.packageName,signatureFlags())
    private fun ownSession(id:Int)=installer.mySessions.firstOrNull{it.sessionId==id&&it.appPackageName==context.packageName}
    private fun discard() {
        val s=state.value
        if(s.sessionId>=0&&ownSession(s.sessionId)!=null)installer.abandonSession(s.sessionId)
        if(s.downloadId>=0)downloads.remove(s.downloadId)
        removePartialFiles(s.nonce)
    }
    private fun removeSource() {
        val s=state.value
        if(s.downloadId>=0){downloads.remove(s.downloadId);removePartialFiles(s.nonce);save(s.copy(downloadId=-1))}
    }
    private fun removePartialFiles(nonce:String) {
        if(nonce.isEmpty())return
        val directory=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.canonicalFile?:return
        directory.listFiles().orEmpty().filter{fileName(it.name,nonce)&&it.canonicalFile.parentFile==directory}.forEach{it.delete()}
    }
    private fun downloadFile(s:UpdateState):File {
        downloads.query(DownloadManager.Query().setFilterById(s.downloadId)).use{cursor->
            require(cursor!=null&&cursor.moveToFirst()&&cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))==DownloadManager.STATUS_SUCCESSFUL){"下载尚未完成"}
            val uri=cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))?:error("下载文件位置丢失")
            val directory=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?:error("下载目录不可用")
            return sourceFile(directory,s.nonce,uri)
        }
    }
    private fun recoverOwnedFiles() {
        var s=state.value
        val directory=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.canonicalFile?:return
        val owned=Regex("wbox-update-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:-[0-9]+)?\\.apk")
        val remove=mutableListOf<Long>()
        downloads.query(DownloadManager.Query()).use{cursor->
            while(cursor!=null&&cursor.moveToNext()) {
                val uri=cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))?.let(Uri::parse)?:continue
                if(uri.scheme!="file")continue
                val file=File(uri.path?:continue);val name=owned.matchEntire(file.name)?:continue
                if(file.canonicalFile.parentFile!=directory)continue
                val id=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                if(name.groupValues[1]==s.nonce&&s.downloadId<0&&s.phase=="preparing") {
                    save(s.copy(phase="downloading",downloadId=id,error=""));s=state.value;automatic=true
                }else if(id!=s.downloadId)remove.add(id)
            }
        }
        remove.forEach{downloads.remove(it)}
        for(session in installer.mySessions) {
            if(session.appPackageName==context.packageName&&session.appLabel?.toString()==SESSION_LABEL&&session.sessionId!=s.sessionId)installer.abandonSession(session.sessionId)
        }
        directory.listFiles().orEmpty().filter{owned.matches(it.name)&&!fileName(it.name,s.nonce)&&it.canonicalFile.parentFile==directory}.forEach{it.delete()}
    }
    suspend fun refresh(verify:Boolean=true,progress:Boolean=true)=withContext(Dispatchers.IO) {mutex.withLock {
        try {
            if(!recovered)recoverOwnedFiles()
            var s=state.value
            val release=s.release
            if(release!=null&&!UpdateRelease.newer(release.version,installed().versionName?:"0.0.0")&&s.phase !in setOf("idle","current","available","checking")) {
                discard();automatic=false;save(UpdateState(phase="installed",release=release));clearNotification();return@withLock
            }
            if(!recovered) {
                recovered=true
                when(s.phase) {
                    "checking","preparing"->save(s.copy(phase="failed",error="更新任务已中断，请重试"))
                    "verifying"->{if(s.sessionId>=0&&ownSession(s.sessionId)!=null)installer.abandonSession(s.sessionId);save(s.copy(phase="downloaded",sessionId=-1))}
                    "installing"->{if(s.sessionId>=0&&ownSession(s.sessionId)==null)save(s.copy(phase="failed",error="安装未完成，可重新下载"))}
                }
                s=state.value
            }
            if(s.phase=="downloading"&&progress)queryDownload()
            s=state.value
            if(verify&&s.phase=="downloaded")stage()
            if(state.value.phase in setOf("ready","permission","confirmation","installing"))removeSource()
        }catch(e:Exception){if(e is CancellationException)throw e;fail(e)}
    }}
    private fun queryDownload() {
        val s=state.value
        downloads.query(DownloadManager.Query().setFilterById(s.downloadId)).use {cursor->
            check(cursor!=null&&cursor.moveToFirst()){ "下载已被移除，请重新下载" }
            fun long(name:String)=cursor.getLong(cursor.getColumnIndexOrThrow(name))
            when(long(DownloadManager.COLUMN_STATUS).toInt()) {
                DownloadManager.STATUS_SUCCESSFUL->{save(s.copy(phase="downloaded",bytes=s.release!!.size,total=s.release.size,speed=0));notifyReady("下载完成，点击验证并安装")}
                DownloadManager.STATUS_FAILED->error("下载失败（${long(DownloadManager.COLUMN_REASON)}），请重新下载")
                else->{
                    val count=long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);val now=SystemClock.elapsedRealtime()
                    val speed=if(lastSample==0L||now<=lastSample||count<lastBytes)0 else (count-lastBytes)*1000/(now-lastSample)
                    lastBytes=count;lastSample=now
                    val paused=long(DownloadManager.COLUMN_STATUS).toInt()==DownloadManager.STATUS_PAUSED
                    state.value=s.copy(bytes=count,total=s.release!!.size,speed=if(paused)0 else speed,error=if(paused)"等待网络或系统恢复下载" else "")
                }
            }
        }
    }
    private fun stage() {
        val s=state.value;val release=s.release?:error("缺少发布信息")
        save(s.copy(phase="verifying",error=""))
        // DownloadManager may suffix the destination after retrying a partial download.
        val file=downloadFile(s)
        require(file.isFile&&file.length()==release.size){"安装包不完整，请重新下载"}
        val archive=context.packageManager.getPackageArchiveInfo(file.absolutePath,signatureFlags())?:error("无法读取安装包")
        validatePackage(release,archive,installed())
        val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName);setAppLabel(SESSION_LABEL);setSize(release.size)
            if(Build.VERSION.SDK_INT>=31)setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id=installer.createSession(params)
        try {
            save(state.value.copy(sessionId=id))
            installer.openSession(id).use {session->
                session.openWrite("base.apk",0,release.size).use {output->
                    file.inputStream().use {input->copyVerified(input,output,release.size,release.sha256)}
                    session.fsync(output)
                }
            }
            save(state.value.copy(phase="ready"))
            // PackageInstaller now owns a complete fsynced copy; it never reads this source APK.
            removeSource();notifyReady("更新已就绪，点击安装")
        }catch(e:Exception){installer.abandonSession(id);downloads.remove(s.downloadId);save(state.value.copy(sessionId=-1,downloadId=-1));throw e}
    }
    suspend fun advance(activity:MainActivity) {
        var commitNonce:String?=null
        mutex.withLock {
            if(!automatic)return@withLock
            try {
                val s=state.value
                when(s.phase) {
                    "ready"->if(!context.packageManager.canRequestPackageInstalls()) {
                        save(s.copy(phase="permission"));show();automatic=false
                    }else commitNonce=s.nonce
                    "confirmation"->{
                        require(ownSession(s.sessionId)!=null){"安装会话已过期，请重新下载"}
                        val intent=Intent.parseUri(s.confirmation,Intent.URI_INTENT_SCHEME)
                        requireSystemInstaller(intent,s.sessionId)
                        save(s.copy(phase="installing"));automatic=false
                        activity.openUpdateInstaller(intent)
                    }
                }
            }catch(e:Exception){if(e is CancellationException)throw e;fail(e)}
        }
        commitNonce?.let{commit(it)}
    }
    private suspend fun commit(expectedNonce:String)=withContext(Dispatchers.IO){mutex.withLock{
        try {
            val s=state.value;if(!automatic||s.phase!="ready"||s.nonce!=expectedNonce)return@withLock
            require(ownSession(s.sessionId)!=null){"安装会话已过期，请重新下载"}
            val intent=Intent(context,UpdateReceiver::class.java).setAction(INSTALL_STATUS).setData(Uri.parse("wbox-update://${s.nonce}"))
            val flags=PendingIntent.FLAG_UPDATE_CURRENT or (if(Build.VERSION.SDK_INT>=31)PendingIntent.FLAG_MUTABLE else 0)
            val result=PendingIntent.getBroadcast(context,s.sessionId,intent,flags)
            save(s.copy(phase="installing"))
            installer.openSession(s.sessionId).use{it.commit(result.intentSender)}
        }catch(e:Exception){fail(e)}
    }}
    fun permissionResult() {action {
        if(state.value.phase=="permission"&&context.packageManager.canRequestPackageInstalls()) {save(state.value.copy(phase="ready",error=""));automatic=true}
        else if(state.value.phase=="permission")save(state.value.copy(error="尚未允许安装，请授权后重试"))
    }}
    fun continueInstall() {action {
        when(state.value.phase) {
            "installing"->{require(state.value.confirmation.isNotEmpty()){ "安装器尚未返回，可稍后重试或取消" };save(state.value.copy(phase="confirmation"));automatic=true}
            "ready","confirmation"->automatic=true
        }
    }}
    private fun requireSystemInstaller(intent:Intent,id:Int) {
        require(intent.action=="android.content.pm.action.CONFIRM_INSTALL"&&intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1)==id){"安装器返回了无效会话"}
        val app=context.packageManager.resolveActivity(intent,0)?.activityInfo?.applicationInfo?:error("没有可用的系统安装器")
        require(app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0){"无法验证系统安装器"}
    }
    suspend fun receive(intent:Intent) {
        if(intent.action==DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            if(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1)==state.value.downloadId)refresh(verify=false)
            return
        }
        if(intent.action==Intent.ACTION_MY_PACKAGE_REPLACED){refresh(verify=false);return}
        if(intent.action!=INSTALL_STATUS)return
        mutex.withLock {
            val s=state.value
            if(intent.data?.host!=s.nonce||intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1)!=s.sessionId||s.sessionId<0)return@withLock
            try {
                when(intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION->{
                        @Suppress("DEPRECATION") val confirmation=intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?:error("系统未提供安装确认界面")
                        requireSystemInstaller(confirmation,s.sessionId)
                        save(s.copy(phase="confirmation",confirmation=confirmation.toUri(Intent.URI_INTENT_SCHEME)))
                        automatic=true;notifyReady("请确认安装 Wbox 更新")
                    }
                    PackageInstaller.STATUS_SUCCESS->{removeSource();save(UpdateState(phase="installed",release=s.release));automatic=false;clearNotification()}
                    else->{removeSource();save(s.copy(phase="failed",sessionId=-1,downloadId=-1,confirmation="",error="安装未完成或已取消，可重新下载"));automatic=false;notifyReady("安装未完成，点击查看")}
                }
            }catch(e:Exception){fail(e)}
        }
    }
    private fun notifyReady(text:String) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("app-update","应用更新",NotificationManager.IMPORTANCE_DEFAULT))
        val open=Intent(context,MainActivity::class.java).setAction(SHOW_UPDATE).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending=PendingIntent.getActivity(context,790,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try{manager.notify(790,Notification.Builder(context,"app-update").setSmallIcon(com.wbox.app.R.drawable.wbox_icon).setContentTitle("Wbox 更新").setContentText(text).setContentIntent(pending).setAutoCancel(true).build())}catch(_:SecurityException){}
    }
    private fun clearNotification(){context.getSystemService(NotificationManager::class.java).cancel(790)}
    companion object {
        const val SHOW_UPDATE="com.wbox.app.SHOW_UPDATE"
        const val INSTALL_STATUS="com.wbox.app.UPDATE_INSTALL_STATUS"
        const val SESSION_LABEL="Wbox 更新"
        // This singleton retains only the application context, never an Activity.
        @android.annotation.SuppressLint("StaticFieldLeak") @Volatile private var instance:AppUpdate?=null
        fun get(context:Context):AppUpdate=instance?:synchronized(this){instance?:AppUpdate(context.applicationContext).also{instance=it}}
        @Suppress("DEPRECATION") private fun signatureFlags()=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        internal fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it)}
        @Suppress("DEPRECATION") private fun signers(info:PackageInfo):Set<String> {
            val signatures=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map{hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))}.toSet()
        }
        @Suppress("DEPRECATION") internal fun code(info:PackageInfo)=if(Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
        internal fun validatePackage(release:UpdateRelease,archive:PackageInfo,current:PackageInfo) {
            validateIdentity(release,archive,current,signers(archive),signers(current))
        }
        internal fun validateIdentity(release:UpdateRelease,archive:PackageInfo,current:PackageInfo,archiveSigners:Set<String>,currentSigners:Set<String>) {
            require(archive.packageName=="com.wbox.app"&&archive.packageName==current.packageName){"安装包的应用标识不匹配"}
            require(archive.versionName==release.version&&code(archive)>code(current)){"安装包版本不匹配或不是新版本"}
            require(archiveSigners==setOf(UpdateRelease.CERTIFICATE)&&archiveSigners==currentSigners){"安装包签名与当前 Wbox 不一致"}
        }
        internal fun copyVerified(input:InputStream,output:OutputStream,size:Long,sha256:String) {
            val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024);var count=0L
            while(true){val n=input.read(buffer);if(n<0)break;count+=n;require(count<=size){"安装包大小发生变化"};digest.update(buffer,0,n);output.write(buffer,0,n)}
            require(count==size&&hex(digest.digest())==sha256){"安装包 SHA256 校验失败"}
        }
        internal fun fileName(name:String,nonce:String)=nonce.isNotEmpty()&&name.matches(Regex("wbox-update-${Regex.escape(nonce)}(?:-[0-9]+)?\\.apk"))
        internal fun sourceFile(directory:File,nonce:String,value:String):File {
            val uri=java.net.URI(value)
            require(uri.scheme=="file"&&uri.authority==null&&uri.query==null&&uri.fragment==null){"下载文件地址无效"}
            val file=File(uri).canonicalFile
            require(file.parentFile==directory.canonicalFile&&fileName(file.name,nonce)){"下载文件不属于本次更新"}
            return file
        }
    }
}
