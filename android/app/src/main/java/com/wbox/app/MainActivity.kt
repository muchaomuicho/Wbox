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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.util.Locale

class MainActivity : ComponentActivity() {
    internal lateinit var model: MobileModel
    internal val updates by lazy { AppUpdate.get(applicationContext) }
    private val updatePermission=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){updates.permissionResult()}
    private val updateInstaller=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){}
    private var exportContent = ""
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startVpn() else {model.connectionRequested=false;model.runtime=model.runtime.copy(starting=false);model.message = "需要 VPN 权限才能接管流量"}
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
        SubscriptionUpdates.schedule(this)
        model.page = state?.getString("page") ?: "overview"
        exportContent = state?.getString("export") ?: ""
        setContent { WboxApp(model) }
        if(intent.action==AppUpdate.SHOW_UPDATE){model.page="settings";updates.show()}
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updates.refresh()
                while(isActive) {
                    if(updates.state.value.phase !in setOf("idle","current","available","checking","installed","failed")) {
                        updates.refresh(progress=updates.panelOpen.value||model.page=="settings")
                        updates.advance(this@MainActivity)
                    }
                    delay(1000)
                }
            }
        }
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
    override fun onTrimMemory(level:Int) {super.onTrimMemory(level);trimConnectionIcons(level)}
    override fun onLowMemory() {super.onLowMemory();trimConnectionIcons(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)}
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);setIntent(intent);if(intent.action==AppUpdate.SHOW_UPDATE){model.page="settings";updates.show()}}
    internal fun openUpdateInstaller(intent:Intent) {updateInstaller.launch(intent)}
    internal fun requestUpdatePermission() {try{updatePermission.launch(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,android.net.Uri.parse("package:$packageName")))}catch(e:Exception){model.message="无法打开安装授权设置：${e.message}"}}
    internal fun connect() { val intent = VpnService.prepare(this); if (intent != null) vpnPermission.launch(intent) else startVpn() }
    private fun startVpn() {if(!model.connectionRequested)return;model.runtime = model.runtime.copy(starting = true, status = "连接中"); startForegroundService(Intent(this, WboxVpnService::class.java)) }
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
    private var profileStamp=profiles.file.lastModified()
    var nodes by mutableStateOf(profiles.nodes().toList()); private set
    var configs by mutableStateOf(profiles.orderedConfigs()); private set
    var page by mutableStateOf("overview")
    var sheet by mutableStateOf("")
    var editing by mutableStateOf(false)
    var textInputFocused by mutableStateOf(false)
    var selectedRow by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    var testing by mutableStateOf(false); private set
    var message by mutableStateOf("")
    var runtime by mutableStateOf(RuntimeState())
    internal var connectionRequested=WboxVpnService.running||WboxVpnService.starting
    private var connectionEpoch=0L
    var history by mutableStateOf<List<WboxVpnService.TrafficSample>>(emptyList())
    var groups by mutableStateOf(JSONObject())
    var groupOrder by mutableStateOf<List<String>>(emptyList())
    var activeGroup by mutableStateOf("")
    private var groupsContext=""
    private fun proxyContext()=LatencyContext.key(profiles)
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
    private var latencySource:Profiles? = null
    private val latencyResults=linkedMapOf<String,MutableMap<String,String>>()
    private var shownLatencyContext=""
    private fun showLatencyResults(context:String) {
        shownLatencyContext=context;delays.clear();delays.putAll(latencyResults[context].orEmpty())
    }
    private val mutations=Mutex()
    val updatingSources=mutableStateListOf<String>()
    var updatingAllProfiles by mutableStateOf(false); private set
    var updatingAllSubscriptions by mutableStateOf(false); private set
    fun isUpdating(key:String)=key in updatingSources
    private var sampleCount = 0
    val workspace get() = profiles.mode()
    val currentName get() = if(workspace=="config") profiles.activeConfig()?.optString("name") ?: "选择配置" else profiles.data.optString("selected").ifEmpty { "选择节点" }
    val transition get() = runtime.starting || runtime.stopping
    internal fun reload() { profiles=Profiles(activity);profileStamp=profiles.file.lastModified(); nodes=profiles.nodes().toList(); configs=profiles.orderedConfigs();val context=proxyContext();if(context!=shownLatencyContext)showLatencyResults(context) }
    fun work(action: suspend () -> Unit) { activity.lifecycleScope.launch { try { action() } catch(e: CancellationException){throw e} catch(e: Exception){ message=ErrorMessage.describe(e); WboxVpnService.log("ERROR $message") } } }
    suspend fun refreshDetails(){
        val target=if(page=="proxies")"groups" else sheet
        WboxVpnService.wantsGroups=target=="groups";WboxVpnService.wantsConnections=target=="connections"
        if(target !in listOf("groups","connections"))return
        if(target=="groups"){
            val context=proxyContext()
            if(context!=groupsContext){showLatencyResults(context);groups=JSONObject();activeGroup="";groupsContext=context}
            try {
                val snapshot=profiles.copy()
                val catalog=withContext(Dispatchers.IO){ProxyCatalog.offline(snapshot) to ProxyCatalog.order(ProxyCatalog.source(snapshot),snapshot.engine()=="sing-box")}
                groupOrder=if(runtime.running)WboxVpnService.groupOrder else catalog.second
                if(!runtime.running||workspace=="nodes"){groups=catalog.first;groupsLoading=false;groupsError="";return}
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
        if(!busy&&profiles.file.lastModified()!=profileStamp)reload()
        if(!busy)latencySource?.let { if(!LatencyContext.valid(profiles,it))cancelLatency() }
        WboxVpnService.wantsGroups = page=="proxies" || sheet=="groups"
        WboxVpnService.wantsConnections = sheet=="connections"
        WboxVpnService.wantsOverview = page=="overview" && sheet.isEmpty()
        if (sheet!="connections") WboxVpnService.connectionRows=emptyList()
        val mem = if (sampleCount++%10==0 && page=="overview") withContext(Dispatchers.Default){Debug.getPss().toLong()*1024} else runtime.appMemory
        runtime=RuntimeState(WboxVpnService.running,WboxVpnService.starting||WboxVpnService.reloading,WboxVpnService.stopping&&!WboxVpnService.reloading,WboxVpnService.status,WboxVpnService.lastError,
            if(WboxVpnService.running) (SystemClock.elapsedRealtime()-WboxVpnService.startedAt)/1000 else 0,
            WboxVpnService.upload,WboxVpnService.download,WboxVpnService.upRate,WboxVpnService.downRate,WboxVpnService.inbound,WboxVpnService.outbound,WboxVpnService.coreMemory,mem)
        if(page=="overview") history=WboxVpnService.trafficSnapshot()
        if(runtime.running&&page=="overview"&&profiles.engine()=="mihomo"&&sampleCount%3==0) try{proxyMode=withContext(Dispatchers.IO){WboxVpnService.api().request("GET","/configs",null).optString("mode","rule")}}catch(_:Exception){}
        if((page=="proxies"||sheet=="groups")&&workspace=="config"&&runtime.running&&WboxVpnService.proxies.length()>0) groups=WboxVpnService.proxies
        if(sheet=="connections") connections=WboxVpnService.connectionRows else connections=emptyList()
        if(page=="logs" && !logsPaused) logs=WboxVpnService.logSnapshot()
    }
    private suspend fun cancelLatency() {latencyJob?.cancelAndJoin();latencyJob=null}
    private suspend fun change(action: (Profiles)->Unit) = mutations.withLock {
        busy=true
        try {
            val stored=withContext(Dispatchers.IO){Profiles(activity)}
            val staged=withContext(Dispatchers.IO){stored.copy().also(action)}
            // Selection and runtime changes do not alter an isolated probe's source.
            latencySource?.let { if(!LatencyContext.valid(staged,it))cancelLatency() }
            withContext(Dispatchers.IO){WboxVpnService.commitProfiles(activity,stored,staged.data).get()}
            reload()
        } finally {busy=false}
    }
    private suspend fun changeNodes(action: (Profiles)->Unit) = change(action)
    fun switchWorkspace(value: String) = work { change{it.setWorkspace(value)}; sheet="";if(value=="nodes")page="library" else if(page=="proxies")page="overview" }
    fun setting(key: String, value: Any) = work { change { it.data.put(key,value); if(key=="engine")it.data.put("node_engine",value) };if(key.startsWith("subscription_"))SubscriptionUpdates.schedule(activity) }
    fun sortLibrary(config:Boolean,order:String) {require(order in listOf("default","name","updated"));setting(if(config)"config_sort" else "subscription_sort",order)}
    fun reorderProfiles(order:List<String>,onFinished:()->Unit) = work {
        try {change{it.reorderConfigs(order)}} finally {onFinished()}
    }
    suspend fun editSubscription(id:String,oldUrl:String,name:String,url:String,enabled:Boolean,minutes:Int) {
        val address=url.trim()
        val download=if(address!=oldUrl)withContext(Dispatchers.IO){SubscriptionFetch.download(address)} else null
        change {p ->
            SubscriptionUpdates.edit(p,id,oldUrl,name,address,enabled,minutes)
            if(download!=null){
                if(id.isEmpty())p.importNodes(download.content(),address)else p.importConfig(download.content(),address,id,false)
                SubscriptionUsage.apply(p,id,address,download.usage())
            }
        }
        SubscriptionUpdates.schedule(activity);message="订阅设置已保存"
    }
    suspend fun saveApps(mode:String, apps:List<String>) { require(mode!="include" || apps.isNotEmpty()){ "请选择至少一个应用" };change {it.data.put("app_mode",mode).put("apps",JSONArray(apps))};message="分应用代理已应用" }
    fun toggle() = work {
        if(runtime.stopping&&!WboxVpnService.reloading)return@work
        if(WboxVpnService.running || WboxVpnService.starting || WboxVpnService.reloading || runtime.running || runtime.starting) {
            connectionEpoch++;connectionRequested=false;latencyJob?.cancel()
            if(WboxVpnService.running||WboxVpnService.starting||WboxVpnService.reloading){runtime=runtime.copy(stopping=true);activity.startService(Intent(activity,WboxVpnService::class.java).setAction(WboxVpnService.STOP))}
            else runtime=runtime.copy(starting=false,status="未连接")
        } else {
            connectionRequested=true
            runtime=runtime.copy(starting=true)
            mutations.withLock {if(connectionRequested&&!WboxVpnService.running&&!WboxVpnService.starting)activity.connect()}
        }
    }
    suspend fun importText(text: String, name: String) {
        val config=workspace=="config";val url=Profiles.subscriptionAddress(text)
        val download=if(url)withContext(Dispatchers.IO){SubscriptionFetch.download(text)} else null
        val content=download?.content()?:text
        change {p ->
            if(config){p.importConfig(content,if(url)text else null,null,!WboxVpnService.running);if(name.isNotBlank())p.configs().getJSONObject(p.configs().length()-1).put("name",name.trim().take(100));if(url)SubscriptionUsage.apply(p,p.configs().getJSONObject(p.configs().length()-1).getString("id"),text,download?.usage())}
            else {p.importNodes(content,if(url)text else null);if(url)SubscriptionUsage.apply(p,"",text,download?.usage())}
        };message="已添加"
    }
    fun paste() = work {
        val manager=activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text=manager.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        require(text.isNotBlank()){ "剪贴板中没有节点链接或订阅地址" }; importText(text,"")
    }
    fun selectProfile(id: String) = work { change{it.activateConfig(id)};message="已切换配置" }
    suspend fun renameProfile(id: String, name: String) { require(name.isNotBlank()){ "请填写配置名称" }; change { p -> p.configs().objects().first {it.optString("id")==id}.put("name",name.trim().take(100)) } }
    private suspend fun downloadProfile(p:JSONObject) {
        val id=p.getString("id");val url=p.getString("url")
        val download=withContext(Dispatchers.IO){SubscriptionFetch.download(url)}
        change {next ->
            val latest=next.configs().objects().firstOrNull{it.optString("id")==id && it.optString("url")==url}?:error("配置已移除或订阅地址已变化")
            check(latest.optString("content")==p.optString("content")){"配置已编辑，请重新更新"}
            next.importConfig(download.content(),url,id,false);SubscriptionUsage.apply(next,id,url,download.usage())
        }
    }
    fun updateProfile(p: JSONObject) {
        val id=p.getString("id")
        if(isUpdating(id))return
        updatingSources.add(id)
        work {try{downloadProfile(p);message="配置已更新"}finally{updatingSources.remove(id)}}
    }
    fun updateAllProfiles() {
        if(updatingAllProfiles)return
        val targets=configs.filter{it.optString("url").isNotEmpty() && !isUpdating(it.optString("id"))}
        if(targets.isEmpty())return
        val ids=targets.map{it.getString("id")}
        val pending=ids.toMutableSet()
        updatingAllProfiles=true;updatingSources.addAll(ids)
        work {
            try {
                var success=0;val errors=mutableListOf<String>()
                for(p in targets)try{downloadProfile(p);success++}catch(e:CancellationException){throw e}catch(e:Exception){errors.add(p.optString("name")+"："+ErrorMessage.describe(e))}finally{val id=p.getString("id");pending.remove(id);updatingSources.remove(id)}
                message=if(errors.isEmpty())"已更新 $success 份配置" else "已更新 $success 份配置；"+errors.joinToString("；")
            }finally{updatingSources.removeAll(pending);updatingAllProfiles=false}
        }
    }
    suspend fun writeProfile(p: JSONObject, content: String) { change {next ->check(next.configs().objects().any{it.optString("id")==p.getString("id")}){"配置已移除"};next.importConfig(content,p.optString("url").ifEmpty{null},p.getString("id"),false)};message="配置已保存并应用" }
    fun removeProfile(id: String) = work { change{p -> val kept=p.configs().objects().filter {it.optString("id")!=id};p.data.put("configs",JSONArray(kept));if(p.data.optString("active_config")==id){p.data.remove("active_config");p.data.remove("config_sing-box");p.data.remove("config_mihomo");if(kept.isNotEmpty())p.activateConfig(kept.first().getString("id"))}} }
    fun selectNode(name: String, group: String?=null, connect:Boolean=false) = work {
        val intentEpoch=connectionEpoch
        if(group==null || workspace=="nodes") {
            check(workspace=="nodes"){"请先切换到节点工作区"}
            val node=nodes.firstOrNull{it.name==name || BundledRules.nodeTag(it.name)==name}?:error("节点不存在")
            changeNodes{it.data.put("selected",node.name)}
            if(connect&&!WboxVpnService.running&&intentEpoch==connectionEpoch){connectionRequested=true;runtime=runtime.copy(starting=true);if(connectionRequested&&intentEpoch==connectionEpoch)activity.connect()}
        } else {
            mutations.withLock {
            busy=true
            try {
                val candidate=JSONObject(groups.toString())
                val target=candidate.optJSONObject(group)?:error("代理组不存在")
                check(target.optString("type")=="Selector"){"该代理组不支持手动选择"}
                check(ProxyCatalog.contains(target.optJSONArray("all"),name)){"节点不属于该代理组"}
                target.put("now",name);ProxyCatalog.resolve(candidate,group)
                withContext(Dispatchers.IO){
                    if(WboxVpnService.running)WboxVpnService.api().select(group,name)
                    val stored=Profiles(activity);val profile=stored.activeConfig()?:error("配置不存在")
                    val saved=profile.optJSONObject("group_selections")?:JSONObject()
                    profile.put("group_selections",saved.put(group,name));stored.save()
                    if(WboxVpnService.running)WboxVpnService.proxies=WboxVpnService.api().proxies()
                }
                reload();groups=if(WboxVpnService.running)WboxVpnService.proxies else candidate
            } finally {busy=false}
            }
        }
    }
    suspend fun writeNode(index: Int, revision: String, fields: JSONObject) { changeNodes {NodeEditing.replace(it,index,revision,fields)} }
    fun cloneNode(index: Int) = work { changeNodes{NodeEditing.cloneNode(it,index)} }
    fun removeNode(name: String) = work { changeNodes {p -> val keep=p.nodes().filter{it.name!=name};p.data.put("nodes",JSONArray(keep.map{it.storage()}));if(p.data.optString("selected")==name)p.data.put("selected",keep.firstOrNull()?.name ?: "")} }
    fun refreshSubscriptions(only: String?=null) {
        if(only==null&&updatingAllSubscriptions)return
        val addresses=(only?.let{listOf(it)} ?: profiles.data.optJSONObject("subscriptions")?.keys()?.asSequence()?.toList().orEmpty()).filterNot{isUpdating(it)}
        if(addresses.isEmpty())return
        val pending=addresses.toMutableSet()
        updatingSources.addAll(addresses);if(only==null)updatingAllSubscriptions=true
        work {
            try {
                var success=0;val errors=mutableListOf<String>()
                for(url in addresses)try {
                    val revision=withContext(Dispatchers.IO){SubscriptionUpdates.revision(Profiles(activity),url)}
                    val download=withContext(Dispatchers.IO){SubscriptionFetch.download(url)}
                    changeNodes {p ->check(p.data.optJSONObject("subscriptions")?.has(url)==true && SubscriptionUpdates.revision(p,url)==revision){"订阅已编辑或移除，请重新更新"};p.importNodes(download.content(),url);SubscriptionUsage.apply(p,"",url,download.usage())};success++
                }catch(e:CancellationException){throw e}catch(e:Exception){errors.add(SubscriptionUpdates.name(profiles,url)+"："+ErrorMessage.describe(e))}finally{pending.remove(url);updatingSources.remove(url)}
                message=if(errors.isEmpty())"已更新 $success 个订阅" else "已更新 $success 个订阅；"+errors.joinToString("；")
            }finally{updatingSources.removeAll(pending);if(only==null)updatingAllSubscriptions=false}
        }
    }
    fun removeSubscription(url: String) = work { changeNodes { p -> val kept=p.nodes().filter{it.source!=url};p.data.put("nodes",JSONArray(kept.map{it.storage()}));p.data.optJSONObject("subscriptions")?.remove(url);p.data.optJSONObject("subscription_settings")?.remove(url);if(kept.none{it.name==p.data.optString("selected")})p.data.put("selected",kept.firstOrNull()?.name ?: "") };SubscriptionUpdates.schedule(activity) }
    fun latency(names: List<String>,expandGroup:Boolean=true) {
        if(testing){if(expandGroup)latencyJob?.cancel();return}
        testing=true
        latencyJob=activity.lifecycleScope.launch {
            var results:MutableMap<String,String>?=null
            var context=""
            try {val snapshot=profiles.copy();latencySource=snapshot;context=LatencyContext.key(snapshot)
                var saved=latencyResults.getOrPut(context){mutableMapOf()};results=saved
                while(latencyResults.size>8)latencyResults.remove(latencyResults.keys.first())
                val initial=if(snapshot.mode()=="config")NodeLatencyTester.targets(groups,names,expandGroup)else names
                initial.forEach{saved[it]="测试中"};if(context==proxyContext())showLatencyResults(context)
                withContext(Dispatchers.IO){LatencyResources.capture(snapshot)}
                val capturedContext=LatencyContext.key(snapshot)
                if(capturedContext!=context){
                    saved.entries.removeAll{it.value=="测试中"};context=capturedContext
                    saved=latencyResults.getOrPut(context){mutableMapOf()};results=saved
                    initial.forEach{saved[it]="测试中"};if(context==proxyContext())showLatencyResults(context)
                }
                val updates=kotlinx.coroutines.channels.Channel<Pair<String,String>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                val snapshots=kotlinx.coroutines.channels.Channel<JSONObject>(kotlinx.coroutines.channels.Channel.CONFLATED)
                coroutineScope {
                    val collect=launch {for((name,value) in updates){saved[name]=value;if(context==proxyContext())delays[name]=value}}
                    val catalogs=launch {for(value in snapshots)if(context==proxyContext()&&!runtime.running){ProxyCatalog.applySelections(profiles,value);groups=value}}
                    try {runInterruptible(Dispatchers.IO){NodeLatencyTester.run(activity,snapshot,names,{name,value->updates.trySend(name to value)},{value->snapshots.trySend(value)},expandGroup)}}
                    finally {updates.close();snapshots.close();collect.join();catalogs.join()}
                }
            } catch(e:CancellationException){throw e} catch(e:Exception){message=e.message ?: "测速失败"}
            finally {testing=false;latencySource=null;results?.entries?.removeAll{it.value=="测试中"};if(context==proxyContext())showLatencyResults(context)}
        }
    }
    fun changeProxyMode(value:String) = work {check(profiles.engine()=="mihomo");withContext(Dispatchers.IO){WboxVpnService.api().setMode(value,WboxVpnService.globalTarget)};proxyMode=value}
    fun closeConnection(id: String?) = work { withContext(Dispatchers.IO){if(id==null)WboxVpnService.api().closeAllConnections() else WboxVpnService.api().closeConnection(id)} }
    fun copyLogs() { (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Wbox logs",WboxVpnService.logSnapshot().joinToString("\n")));message="日志已复制" }
    fun clearLogs() { synchronized(WboxVpnService.logs){WboxVpnService.logs.clear()};logs=emptyList() }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull {optJSONObject(it)}
internal fun JSONArray.strings(): List<String> = (0 until length()).map{optString(it)}
