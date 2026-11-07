package com.wbox.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.*
import java.util.Locale

class MainActivity : ComponentActivity() {
    internal lateinit var model: MobileModel
    private var exportContent = ""
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startVpn() else model.message = "需要 VPN 权限才能接管流量"
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.work { val text = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use { Profiles.readLimited(it) } }; model.importText(text, "") }
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val content=exportContent
        if (uri != null) model.work { withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)!!.use { it.write(content.toByteArray()) } }; model.message = "配置已导出" }
        exportContent = ""
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        model = MobileModel(this)
        model.page = state?.getString("page") ?: "overview"
        exportContent = state?.getString("export") ?: ""
        setContent { WboxApp(model) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WboxVpnService.setVisible(true)
                try { while (isActive) { model.sample(); delay(1000) } }
                finally { WboxVpnService.setVisible(false);model.connections=emptyList();WboxVpnService.connectionRows=emptyList() }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    override fun onSaveInstanceState(out: Bundle) { out.putString("page", model.page); out.putString("export", exportContent); super.onSaveInstanceState(out) }
    internal fun connect() { val intent = VpnService.prepare(this); if (intent != null) vpnPermission.launch(intent) else startVpn() }
    private fun startVpn() { model.runtime = model.runtime.copy(starting = true, status = "连接中"); startForegroundService(Intent(this, WboxVpnService::class.java)) }
    internal fun pickFile() { importFile.launch(arrayOf("*/*")) }
    internal fun export(profile: JSONObject) { exportContent=profile.optString("content"); exportFile.launch(profile.optString("name")+if(profile.optString("core")=="sing-box")".json" else ".yaml") }
    companion object {
        @JvmStatic fun bytes(n: Long): String { if(n<1024)return "$n B"; var x=n.toDouble(); var i=0; val units=arrayOf("B","KB","MB","GB","TB"); while(x>=1024 && i<4){x/=1024;i++}; return String.format(Locale.ROOT,if(x>=100)"%.0f %s" else "%.1f %s",x,units[i]) }
    }
}

internal data class RuntimeState(
    val running: Boolean=false, val starting: Boolean=false, val stopping: Boolean=false,
    val status: String="未连接", val error: String="", val uptime: Long=0,
    val upload: Long=0, val download: Long=0, val up: Long=0, val down: Long=0,
    val connectionsIn: Int=0, val connectionsOut: Int=0, val coreMemory: Long=0, val appMemory: Long=0
)

@Stable internal class MobileModel(val activity: MainActivity) {
    var profiles by mutableStateOf(Profiles(activity)); private set
    var nodes by mutableStateOf(profiles.nodes().toList()); private set
    var configs by mutableStateOf(profiles.configs().objects()); private set
    var page by mutableStateOf("overview")
    var sheet by mutableStateOf("")
    var editing by mutableStateOf(false)
    var textInputFocused by mutableStateOf(false)
    var selectedRow by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    var testing by mutableStateOf(false); private set
    var message by mutableStateOf("")
    var runtime by mutableStateOf(RuntimeState())
    var history by mutableStateOf<List<WboxVpnService.TrafficSample>>(emptyList())
    var groups by mutableStateOf(JSONObject())
    var groupOrder by mutableStateOf<List<String>>(emptyList())
    var activeGroup by mutableStateOf("")
    private var groupsContext=""
    private fun proxyContext()=profiles.mode()+"|"+profiles.engine()+"|"+profiles.data.optString("config_"+profiles.engine())+"|"+profiles.data.opt("nodes")+"|"+profiles.data.optString("test_url",Profiles.TEST_URL)+"|"+(if(runtime.running)WboxVpnService.secret else "offline")
    var groupsLoading by mutableStateOf(false)
    var connectionsLoading by mutableStateOf(false)
    var groupsError by mutableStateOf("")
    var connectionsError by mutableStateOf("")
    var proxyMode by mutableStateOf("rule")
    var connections by mutableStateOf<List<ConnectionRecord>>(emptyList())
    var logs by mutableStateOf<List<String>>(WboxVpnService.logSnapshot())
    var logsPaused by mutableStateOf(false)
    val delays = mutableStateMapOf<String,String>()
    private var latencyJob: Job? = null
    private var sampleCount = 0
    val workspace get() = profiles.mode()
    val currentName get() = if(workspace=="config") profiles.activeConfig()?.optString("name") ?: "选择配置" else profiles.data.optString("selected").ifEmpty { "选择节点" }
    val transition get() = runtime.starting || runtime.stopping
    internal fun reload() { profiles=Profiles(activity); nodes=profiles.nodes().toList(); configs=profiles.configs().objects() }
    fun work(action: suspend () -> Unit) { activity.lifecycleScope.launch { try { action() } catch(e: CancellationException){throw e} catch(e: Exception){ message=ErrorMessage.describe(e); WboxVpnService.log("ERROR $message") } } }
    suspend fun refreshDetails(){
        val target=if(page=="proxies")"groups" else sheet
        WboxVpnService.wantsGroups=target=="groups";WboxVpnService.wantsConnections=target=="connections"
        if(target !in listOf("groups","connections"))return
        if(target=="groups"){
            val context=proxyContext()
            if(context!=groupsContext){latencyJob?.cancel();delays.clear();groups=JSONObject();activeGroup="";groupsContext=context}
            try {
                val snapshot=profiles.copy()
                val catalog=withContext(Dispatchers.IO){ProxyCatalog.offline(snapshot) to ProxyCatalog.order(ProxyCatalog.source(snapshot),snapshot.engine()=="sing-box")}
                groupOrder=if(runtime.running)WboxVpnService.groupOrder else catalog.second
                if(!runtime.running){groups=catalog.first;groupsLoading=false;groupsError="";return}
            }catch(e:Exception){groupsError=ErrorMessage.describe(e);return}
        }
        if(!runtime.running)return
        if(target=="groups"){groupsLoading=true;groupsError=""}else{connectionsLoading=true;connectionsError=""}
        try{
            withContext(Dispatchers.IO){WboxVpnService.refreshDetails(target=="groups").get(5,java.util.concurrent.TimeUnit.SECONDS)}
            if(target==(if(page=="proxies")"groups" else sheet)){groups=WboxVpnService.proxies;connections=WboxVpnService.connectionRows}
        }catch(e:CancellationException){throw e}catch(e:Exception){if(target=="groups")groupsError=ErrorMessage.describe(e.cause?:e)else connectionsError=ErrorMessage.describe(e.cause?:e)}
        finally{if(target=="groups")groupsLoading=false else connectionsLoading=false}
    }
    suspend fun sample() {
        WboxVpnService.wantsGroups = page=="proxies" || sheet=="groups"
        WboxVpnService.wantsConnections = sheet=="connections"
        WboxVpnService.wantsOverview = page=="overview" && sheet.isEmpty()
        if (sheet!="connections") WboxVpnService.connectionRows=emptyList()
        val mem = if (sampleCount++%10==0 && page=="overview") withContext(Dispatchers.Default){Debug.getPss().toLong()*1024} else runtime.appMemory
        runtime=RuntimeState(WboxVpnService.running,WboxVpnService.starting,WboxVpnService.stopping,WboxVpnService.status,WboxVpnService.lastError,
            if(WboxVpnService.running) (SystemClock.elapsedRealtime()-WboxVpnService.startedAt)/1000 else 0,
            WboxVpnService.upload,WboxVpnService.download,WboxVpnService.upRate,WboxVpnService.downRate,WboxVpnService.inbound,WboxVpnService.outbound,WboxVpnService.coreMemory,mem)
        if(page=="overview") history=WboxVpnService.trafficSnapshot()
        if(runtime.running&&page=="overview"&&profiles.engine()=="mihomo"&&sampleCount%3==0) try{proxyMode=withContext(Dispatchers.IO){WboxVpnService.api().request("GET","/configs",null).optString("mode","rule")}}catch(_:Exception){}
        if((page=="proxies"||sheet=="groups")&&runtime.running&&WboxVpnService.proxies.length()>0) groups=WboxVpnService.proxies
        if(sheet=="connections") connections=WboxVpnService.connectionRows else connections=emptyList()
        if(page=="logs" && !logsPaused) logs=WboxVpnService.logSnapshot()
    }
    private fun mutable(stopped: Boolean=false) {
        check(!busy && !testing && !transition){"正在处理，请稍候"}
        if(stopped) check(!WboxVpnService.running){"请断开连接后修改配置或内核"}
    }
    private suspend fun change(stopped: Boolean=false, action: (Profiles)->Unit) {
        mutable(stopped); busy=true
        try { withContext(Dispatchers.IO) { val stored=Profiles(activity);val staged=stored.copy();action(staged);stored.data=staged.data;stored.save() }; reload() }
        finally { busy=false }
    }
    private suspend fun changeNodes(action: (Profiles)->Unit) {
        mutable(); busy=true
        try { withContext(Dispatchers.IO) { val staged=Profiles(activity).copy(); action(staged); WboxVpnService.commitNodes(activity,staged.data).get() }; reload() }
        finally { busy=false }
    }
    fun switchWorkspace(value: String) = work { change(true){it.setWorkspace(value)}; sheet="" }
    fun setting(key: String, value: Any) = work { change(key=="engine") { it.data.put(key,value); if(key=="engine")it.data.put("node_engine",value) } }
    suspend fun saveApps(mode:String, apps:List<String>) { require(mode!="include" || apps.isNotEmpty()){ "请选择至少一个应用" };change {it.data.put("app_mode",mode).put("apps",JSONArray(apps))} }
    fun toggle() = work {
        if(runtime.stopping)return@work
        if(WboxVpnService.running || WboxVpnService.starting) {
            latencyJob?.cancel(); runtime=runtime.copy(stopping=true)
            activity.startService(Intent(activity,WboxVpnService::class.java).setAction(WboxVpnService.STOP))
        } else { mutable(); runtime=runtime.copy(starting=true); activity.connect() }
    }
    suspend fun importText(text: String, name: String) {
        val config=workspace=="config"; mutable(); busy=true
        try {
            withContext(Dispatchers.IO) {
                val url=Profiles.subscriptionAddress(text); val content=if(url)Profiles.fetch(text,!config) else text
                val p=Profiles(activity).copy()
                if(config){p.importConfig(content,if(url)text else null,null,!WboxVpnService.running);if(name.isNotBlank())p.configs().getJSONObject(p.configs().length()-1).put("name",name.trim().take(100));val stored=Profiles(activity);stored.data=p.data;stored.save()}
                else {p.importNodes(content,if(url)text else null);WboxVpnService.commitNodes(activity,p.data).get()}
            }; reload(); message="已添加"
        } finally {busy=false}
    }
    fun paste() = work {
        val manager=activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text=manager.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        require(text.isNotBlank()){ "剪贴板中没有节点链接或订阅地址" }; importText(text,"")
    }
    fun selectProfile(id: String) = work { change(true){it.activateConfig(id)} }
    suspend fun renameProfile(id: String, name: String) { require(name.isNotBlank()){ "请填写配置名称" }; change { p -> p.configs().objects().first {it.optString("id")==id}.put("name",name.trim().take(100)) } }
    fun updateProfile(p: JSONObject) = work { mutable(); busy=true;try { withContext(Dispatchers.IO){val content=Profiles.fetch(p.getString("url"));val stored=Profiles(activity);stored.importConfig(content,p.getString("url"),p.getString("id"),false)};reload();message=if(WboxVpnService.running)"配置已更新，下次连接生效" else "配置已更新"}finally{busy=false} }
    suspend fun writeProfile(p: JSONObject, content: String) { change {it.importConfig(content,p.optString("url").ifEmpty{null},p.getString("id"),false)};message=if(WboxVpnService.running)"配置已保存，下次连接生效" else "配置已保存" }
    fun removeProfile(id: String) = work { change(true){p -> val kept=p.configs().objects().filter {it.optString("id")!=id};p.data.put("configs",JSONArray(kept));if(p.data.optString("active_config")==id){p.data.remove("active_config");p.data.remove("config_sing-box");p.data.remove("config_mihomo");if(kept.isNotEmpty())p.activateConfig(kept.first().getString("id"))}} }
    fun selectNode(name: String, group: String?=null) = work {
        if(group==null) { check(workspace=="nodes"){"请先切换到节点工作区"};changeNodes{it.data.put("selected",name)} }
        else if(WboxVpnService.running) { withContext(Dispatchers.IO){WboxVpnService.api().select(group,name);WboxVpnService.proxies=WboxVpnService.api().proxies()};groups=WboxVpnService.proxies }
    }
    suspend fun writeNode(index: Int, revision: String, fields: JSONObject) { changeNodes {NodeEditing.replace(it,index,revision,fields)} }
    fun cloneNode(index: Int) = work { changeNodes{NodeEditing.cloneNode(it,index)} }
    fun removeNode(name: String) = work { changeNodes {p -> val keep=p.nodes().filter{it.name!=name};p.data.put("nodes",JSONArray(keep.map{it.storage()}));if(p.data.optString("selected")==name)p.data.put("selected",keep.firstOrNull()?.name ?: "")} }
    fun refreshSubscriptions(only: String?=null) = work { changeNodes { p -> val addresses=only?.let{listOf(it)} ?: p.data.optJSONObject("subscriptions")?.keys()?.asSequence()?.toList().orEmpty();for(url in addresses)p.importNodes(Profiles.fetch(url,true),url) };message="订阅已更新" }
    fun removeSubscription(url: String) = work { changeNodes { p -> val kept=p.nodes().filter{it.source!=url};p.data.put("nodes",JSONArray(kept.map{it.storage()}));p.data.optJSONObject("subscriptions")?.remove(url);if(kept.none{it.name==p.data.optString("selected")})p.data.put("selected",kept.firstOrNull()?.name ?: "") } }
    fun latency(names: List<String>) {
        if(testing){latencyJob?.cancel();return}
        latencyJob=activity.lifecycleScope.launch {
            try { mutable();testing=true;val snapshot=profiles.copy();val context=groupsContext
                val updates=kotlinx.coroutines.channels.Channel<Pair<String,String>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                val snapshots=kotlinx.coroutines.channels.Channel<JSONObject>(kotlinx.coroutines.channels.Channel.CONFLATED)
                coroutineScope {
                    val collect=launch {for((name,value) in updates)if(context==groupsContext)delays[name]=value}
                    val catalogs=launch {for(value in snapshots)if(context==groupsContext)groups=value}
                    try {runInterruptible(Dispatchers.IO){NodeLatencyTester.run(activity,snapshot,names,{name,value->updates.trySend(name to value)},{value->snapshots.trySend(value)})}}
                    finally {updates.close();snapshots.close();collect.join();catalogs.join()}
                }
            } catch(e:CancellationException){throw e} catch(e:Exception){message=e.message ?: "测速失败"}
            finally {testing=false;delays.keys.toList().forEach{if(delays[it]=="测试中")delays.remove(it)}}
        }
    }
    fun changeProxyMode(value:String) = work {check(profiles.engine()=="mihomo");withContext(Dispatchers.IO){WboxVpnService.api().setMode(value,WboxVpnService.globalTarget)};proxyMode=value}
    fun closeConnection(id: String?) = work { withContext(Dispatchers.IO){if(id==null)WboxVpnService.api().closeAllConnections() else WboxVpnService.api().closeConnection(id)} }
    fun copyLogs() { (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Wbox logs",WboxVpnService.logSnapshot().joinToString("\n")));message="日志已复制" }
    fun clearLogs() { synchronized(WboxVpnService.logs){WboxVpnService.logs.clear()};logs=emptyList() }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull {optJSONObject(it)}
internal fun JSONArray.strings(): List<String> = (0 until length()).map{optString(it)}
