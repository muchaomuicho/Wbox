@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.wbox.app

// Compose layout and traffic cards adapted from SagerNet/sing-box-for-android
// (a548a7903e96266a0046018562c73b192edbfc18), GPL-3.0-or-later.
// Wbox supplies the dual-core/workspace model and a monochrome color scheme.
import android.content.res.Configuration
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.*
import java.net.URI

private val LightColors=lightColorScheme(primary=Color(0xff262626),onPrimary=Color.White,primaryContainer=Color(0xffe8e8e8),onPrimaryContainer=Color(0xff191919),secondaryContainer=Color(0xffe8e8e8),onSecondaryContainer=Color(0xff222222),background=Color(0xfffafafa),surface=Color(0xfffafafa),surfaceVariant=Color(0xfff0f0f0),onSurface=Color(0xff1a1a1a),onSurfaceVariant=Color(0xff6e6e6e),outlineVariant=Color(0xffe5e5e5))
private val DarkColors=darkColorScheme(primary=Color(0xffe5e5e5),onPrimary=Color(0xff202020),primaryContainer=Color(0xff393939),onPrimaryContainer=Color(0xffeeeeee),secondaryContainer=Color(0xff393939),onSecondaryContainer=Color(0xffeeeeee),background=Color(0xff1f1f1f),surface=Color(0xff1f1f1f),surfaceVariant=Color(0xff292929),onSurface=Color(0xffe5e5e5),onSurfaceVariant=Color(0xffa1a1a1),outlineVariant=Color(0xff3b3b3b))
internal data class Editor(val type: String, val profile: JSONObject?=null, val index: Int=-1)

@Composable internal fun WboxApp(m: MobileModel) {
    val systemDark=isSystemInDarkTheme()
    val dark=when(m.profiles.data.optString("theme","auto")){"dark"->true;"light"->false;else->systemDark}
    MaterialTheme(colorScheme=if(dark)DarkColors else LightColors, shapes=Shapes(medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(20.dp),extraLarge=RoundedCornerShape(24.dp))) {
        WboxSystemBars(m)
        var editor by remember { mutableStateOf<Editor?>(null) }
        var librarySubscriptions by remember(m.workspace){mutableStateOf(false)}
        val focus=remember{FocusRequester()}
        LaunchedEffect(Unit){focus.requestFocus()}
        var showWorkspace by remember { mutableStateOf(false) }
        val snackbar=remember { SnackbarHostState() }
        DisposableEffect(m.page,m.sheet) { onDispose { m.textInputFocused=false } }
        LaunchedEffect(m.message) { if(m.message.isNotEmpty()){val text=m.message;snackbar.showSnackbar(text);if(m.message==text)m.message=""} }
        SideEffect { m.editing=editor!=null }
        LaunchedEffect(m.page,m.sheet,m.runtime.running,m.profiles){m.refreshDetails()}
        val title=when(m.page){"overview"->"概览";"proxies"->"代理";"library"->if(m.workspace=="config")"配置" else "节点";"logs"->"日志";else->"设置"}
        Scaffold(
            modifier=Modifier.focusRequester(focus).onPreviewKeyEvent{event ->
                val key=event.nativeKeyEvent
                if(key.action!=android.view.KeyEvent.ACTION_DOWN || m.workspace!="nodes" || editor!=null || m.textInputFocused) false
                else when {
                    key.isCtrlPressed && key.keyCode==android.view.KeyEvent.KEYCODE_V -> {m.paste();true}
                    key.isCtrlPressed && key.keyCode==android.view.KeyEvent.KEYCODE_D && m.selectedRow!=null -> {val index=m.nodes.indexOfFirst{it.name==m.selectedRow};if(index>=0)editor=Editor("node",index=index);true}
                    key.keyCode==android.view.KeyEvent.KEYCODE_ENTER && m.page=="library" && m.selectedRow!=null -> {m.selectNode(m.selectedRow!!,connect=true);true}
                    else -> false
                }
            }.focusable(),
            topBar={TopAppBar(title={Text(title,style=MaterialTheme.typography.headlineMedium)},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface,scrolledContainerColor=MaterialTheme.colorScheme.surface),actions={
                if(m.page=="library"&&(m.workspace=="config"||librarySubscriptions))LibraryToolbarActions(m,m.workspace=="config")
                Box { TextButton(onClick={showWorkspace=true}){Text(if(m.workspace=="config")"配置工作区" else "节点工作区",fontSize=12.sp);Icon(Icons.Outlined.ExpandMore,null,Modifier.size(18.dp))}
                    DropdownMenu(showWorkspace,{showWorkspace=false}){listOf("config" to "配置工作区","nodes" to "节点工作区").forEach{(key,label)->DropdownMenuItem(text={Text(label)},onClick={showWorkspace=false;m.switchWorkspace(key)},leadingIcon={Icon(if(key=="config")Icons.Outlined.Folder else Icons.Outlined.Dns,null)},trailingIcon={if(m.workspace==key)Icon(Icons.Outlined.Check,null)})}}
            }})},
            snackbarHost={SnackbarHost(snackbar){data->Snackbar(
                snackbarData=data,
                containerColor=MaterialTheme.colorScheme.surfaceVariant,
                contentColor=MaterialTheme.colorScheme.onSurface,
                actionColor=MaterialTheme.colorScheme.primary,
                dismissActionContentColor=MaterialTheme.colorScheme.onSurfaceVariant,
                shape=RoundedCornerShape(12.dp)
            )}},
            floatingActionButton={
                if(m.page=="proxies" && !m.transition && ProxyCatalog.ordered(m.groups,m.groupOrder).isNotEmpty()) ExtendedFloatingActionButton(
                    modifier=Modifier.semantics{contentDescription=if(m.testing)"取消测试" else "延迟测试"},
                    onClick={val tag=m.activeGroup.ifEmpty{ProxyCatalog.ordered(m.groups,m.groupOrder).first()};m.latency(if(m.workspace=="nodes")m.nodes.map{it.name}else listOf(tag))},
                    icon={if(m.testing)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)else Icon(Icons.Outlined.Speed,null)},
                    text={Text(if(m.testing)"取消测试" else "延迟测试")},
                    containerColor=MaterialTheme.colorScheme.secondaryContainer,contentColor=MaterialTheme.colorScheme.onSecondaryContainer
                )
                else if(m.page!="proxies" && !m.runtime.running && !m.runtime.stopping) FloatingActionButton(
                    modifier=Modifier.semantics{if(m.runtime.starting)contentDescription="取消连接"},
                    onClick={m.toggle()},shape=RoundedCornerShape(18.dp),
                    containerColor=if(dark)Color(0xff355147)else Color(0xffd3e4df),
                    contentColor=if(dark)Color(0xffecf8f2)else Color(0xff163e30)
                ){
                    if(m.runtime.starting)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp,color=LocalContentColor.current)
                    else Icon(Icons.Outlined.PlayArrow,"连接代理",Modifier.size(26.dp))
                }
            },
            bottomBar={Column{
                if(m.runtime.running || m.runtime.stopping) ServiceStatusBar(m,dark)
                NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp){
                    listOf(Triple("overview","概览",Icons.Outlined.Dashboard),Triple("proxies","代理",Icons.Outlined.AccountTree),Triple("library",if(m.workspace=="config")"配置" else "节点",Icons.Outlined.Folder),Triple("logs","日志",Icons.Outlined.Article),Triple("settings","设置",Icons.Outlined.Settings)).filter{m.workspace=="config"||it.first!="proxies"}.forEach{(key,label,icon)->NavigationBarItem(selected=m.page==key,onClick={m.page=key},icon={Icon(icon,label)},label={Text(label)})}
                }
            }}
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when(m.page){"overview"->Dashboard(m){editor=it};"proxies"->if(m.workspace=="nodes")Library(m,librarySubscriptions,{librarySubscriptions=it}){editor=it}else ProxiesPage(m);"library"->Library(m,librarySubscriptions,{librarySubscriptions=it}){editor=it};"logs"->Logs(m,dark);else->Settings(m)}
            }
        }
        if(m.sheet.isNotEmpty()) {
            val sheet=m.sheet
            ModalBottomSheet(onDismissRequest={m.sheet=""},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp){
                Box(Modifier.fillMaxHeight()) {when(sheet){"connections"->Connections(m);"picker"->ProfilePicker(m);"apps"->Apps(m)}}
            }
        }
        editor?.let { EditSheet(m,it){editor=null} }
    }
}

@Composable private fun PowerControl(m:MobileModel,dark:Boolean) {
    val r=m.runtime
    // Intrinsic width follows the text. No elapsed-time-dependent sizing.
    Surface(onClick={m.toggle()},enabled=!r.stopping,modifier=Modifier.semantics{if(r.starting)contentDescription="取消连接"},shape=RoundedCornerShape(12.dp),color=if(dark)Color(0xff355147)else Color(0xffcce8dc),contentColor=if(dark)Color(0xffecf8f2)else Color(0xff163e30)) {
        Row(Modifier.defaultMinSize(minWidth=40.dp,minHeight=40.dp).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
            if(r.starting || r.stopping) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
            else {if(r.running)Text(uptime(r.uptime),fontSize=12.sp,fontFamily=FontFamily.Monospace);Icon(if(r.running)Icons.Filled.Stop else Icons.Outlined.PlayArrow,if(r.running)"断开代理连接" else "连接代理",Modifier.size(20.dp))}
        }
    }
}
internal fun uptime(seconds:Long):String = if(seconds>=3600)"%d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60) else "%d:%02d".format(seconds/60,seconds%60)

@Composable private fun ServiceStatusBar(m:MobileModel,dark:Boolean) {
    // SFA ServiceStatusBar: compact status, connections and group shortcuts.
    Surface(color=MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(m.runtime.status,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
            TextButton(onClick={m.sheet="connections"}){Icon(Icons.Outlined.Cable,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(m.runtime.connectionsIn.toString())}
            PowerControl(m,dark)
        }
    }
}
@Composable private fun Panel(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) { Card(modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)} }
@Composable private fun SectionTitle(text:String,icon:ImageVector,action:(@Composable ()->Unit)?=null) {Row(verticalAlignment=Alignment.CenterVertically){Icon(icon,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.width(10.dp));Text(text,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);action?.invoke()} }
@Composable private fun Muted(text:String,modifier:Modifier=Modifier){Text(text,modifier,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
@Composable private fun EmptyState(title:String,detail:String,icon:ImageVector=Icons.Outlined.FolderOpen) {Column(Modifier.fillMaxWidth().padding(vertical=44.dp,horizontal=24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){Icon(icon,null,Modifier.size(42.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant);Text(title,style=MaterialTheme.typography.titleMedium);Muted(detail)} }

@Composable private fun Dashboard(m:MobileModel,edit:(Editor)->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(m.runtime.error.isNotEmpty()) item {Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("连接失败",fontWeight=FontWeight.SemiBold);Text(m.runtime.error,style=MaterialTheme.typography.bodySmall);TextButton(onClick={m.page="logs"}){Text("查看日志")}}} }
        item {Panel{
            SectionTitle(if(m.workspace=="config")"配置" else "当前节点",Icons.Outlined.Folder){IconButton(onClick={edit(Editor("import"))}){Icon(Icons.Outlined.Add,"添加")}}
            Surface(onClick={if(m.workspace=="config")m.sheet="picker" else m.page="library"},shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.background){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Text(m.currentName,Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis);Icon(Icons.Outlined.UnfoldMore,"选择配置",Modifier.size(18.dp))}}
            Row(verticalAlignment=Alignment.CenterVertically){
                Icon(Icons.Outlined.Public,null,Modifier.size(15.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Muted(m.profiles.engine()+if(m.workspace=="config" && !m.profiles.activeConfig()?.optString("url").isNullOrEmpty())" · 远程配置" else " · 本地")
                Spacer(Modifier.weight(1f))
                if(m.workspace=="config")m.profiles.activeConfig()?.let{profile->
                    IconButton(onClick={edit(Editor("config",profile))}){Icon(Icons.Outlined.Edit,"编辑配置",Modifier.size(20.dp))}
                }
                IconButton(onClick={m.page="library"}){Icon(Icons.Outlined.ChevronRight,"管理",Modifier.size(20.dp))}
            }
        }}
        if(m.runtime.running) {
            if(m.profiles.engine()=="mihomo")item{Panel{SectionTitle("代理模式",Icons.Outlined.Route);SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){listOf("rule" to "规则","global" to "全局","direct" to "直连").forEachIndexed{index,(mode,label)->SegmentedButton(selected=m.proxyMode==mode,onClick={m.changeProxyMode(mode)},shape=SegmentedButtonDefaults.itemShape(index,3)){Text(label)}}}}}

            item {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){TrafficCard("上传",m.runtime.up,m.runtime.upload,m.history.map{it.up.toFloat()},Icons.Outlined.Upload,Modifier.weight(1f));TrafficCard("下载",m.runtime.down,m.runtime.download,m.history.map{it.down.toFloat()},Icons.Outlined.Download,Modifier.weight(1f))}}
            item {Panel{SectionTitle("连接",Icons.Outlined.Cable){TextButton(onClick={m.sheet="connections"}){Text("查看")}};Row{Metric("入站",m.runtime.connectionsIn.toString(),Modifier.weight(1f));Metric("出站",m.runtime.connectionsOut.toString(),Modifier.weight(1f))}}}
            item {Panel{SectionTitle("内存",Icons.Outlined.Memory);Row{Metric("内核",MainActivity.bytes(m.runtime.coreMemory),Modifier.weight(1f));Metric("应用",MainActivity.bytes(m.runtime.appMemory),Modifier.weight(1f))}}}
        } else if(m.nodes.isEmpty() && m.configs.isEmpty()) item {EmptyState("尚未连接","添加配置或节点后，点击右下角开始。",Icons.Outlined.PowerSettingsNew)}
    }
}
// Derived from upstream UploadTrafficCard.kt; both directions share one card.
@Composable private fun TrafficCard(title:String,speed:Long,total:Long,history:List<Float>,icon:ImageVector,modifier:Modifier) {
    Panel(modifier){SectionTitle(title,icon);Text(MainActivity.bytes(speed)+"/s",style=MaterialTheme.typography.titleLarge,maxLines=1);Muted(MainActivity.bytes(total));LineChart(history,lineColor=MaterialTheme.colorScheme.primary,animate=false,modifier=Modifier.fillMaxWidth())}
}
@Composable private fun Metric(label:String,value:String,modifier:Modifier){Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)){Muted(label);Text(value,style=MaterialTheme.typography.titleLarge)} }

@Composable private fun Library(m:MobileModel,subscriptions:Boolean,onSubscriptionsChange:(Boolean)->Unit,edit:(Editor)->Unit) {
    var query by remember {mutableStateOf("")}
    var source by remember {mutableStateOf<String?>(null)}
    val selected=m.selectedRow
    var nodeMenu by remember {mutableStateOf<Node?>(null)}
    
    var subscriptionEditor by remember {mutableStateOf<SubscriptionEdit?>(null)}
    var subscriptionMore by remember{mutableStateOf<SubscriptionEdit?>(null)}
    var preview by remember{mutableStateOf<JSONObject?>(null)}
    var removing by remember{mutableStateOf<Pair<String,()->Unit>?>(null)}
    val config=m.workspace=="config"
    Column(Modifier.fillMaxSize()) {
        if(!config) TabRow(if(subscriptions)1 else 0,containerColor=MaterialTheme.colorScheme.surface){Tab(!subscriptions,{onSubscriptionsChange(false)},text={Text("节点")});Tab(subscriptions,{onSubscriptionsChange(true)},text={Text("订阅")})}
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
            Text(if(config)"${m.configs.size} 份配置" else if(subscriptions)"${m.profiles.data.optJSONObject("subscriptions")?.length()?:0} 个订阅" else "${m.nodes.size} 个节点",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(!config && !subscriptions){IconButton(onClick={m.paste()}){Icon(Icons.Outlined.ContentPaste,"从剪贴板导入")};IconButton(onClick={m.latency(m.nodes.filter{source==null||it.source==source}.map{it.name})}){if(m.testing)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Icon(Icons.Outlined.Speed,"测试延迟")}}
            if(!config&&!subscriptions)Text("仅运行当前节点",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick={edit(Editor("import"))}){Icon(Icons.Outlined.Add,if(config)"添加配置" else "添加节点或订阅")}
        }
        if(!config && !subscriptions) {
            OutlinedTextField(query,{query=it},Modifier.onFocusChanged{m.textInputFocused=it.isFocused}.fillMaxWidth().padding(horizontal=16.dp),placeholder={Text("搜索节点")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,shape=RoundedCornerShape(16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(source==null,{source=null},label={Text("全部")});FilterChip(source=="",{source=""},label={Text("手动节点")})
                m.nodes.map{it.source}.filter{it.isNotEmpty()}.distinct().forEach{url->FilterChip(source==url,{source=url},label={Text(SubscriptionUpdates.name(m.profiles,url))})}
            }
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=96.dp),verticalArrangement=Arrangement.spacedBy(if(config||subscriptions)16.dp else 8.dp)) {
            if(config){
                if(m.configs.isEmpty())item{EmptyState("还没有配置","添加远程 JSON / YAML 或本地配置文件。")}
                val ordered=when(m.profiles.data.optString("config_sort")){"name"->m.configs.sortedBy{it.optString("name").lowercase()};"updated"->m.configs.sortedByDescending{it.optLong("updated")};else->m.configs}
                items(ordered,key={it.optString("id")}){profile->
                    val id=profile.getString("id");val url=profile.optString("url");val name=profile.optString("name")
                    val active=profile.optString("id")==m.profiles.data.optString("active_config")
                    LibraryCard(name,profile.optString("core")+" · "+if(url.isEmpty())"本地配置" else "远程配置",profile.optLong("updated"),profile.optJSONObject("subscription")?.optJSONObject("usage"),url.isNotEmpty(),active,m.isUpdating(id),{m.selectProfile(id)},
                        actions=buildList{
                            add(LibraryAction("编辑",Icons.Outlined.Edit){edit(Editor("config",profile))})
                            add(LibraryAction("预览",Icons.Outlined.Visibility){preview=profile})
                            if(url.isNotEmpty())add(LibraryAction("同步",Icons.Outlined.Sync,enabled=!m.isUpdating(id)){m.updateProfile(profile)})
                            add(LibraryAction("更多",Icons.Outlined.Tune){subscriptionEditor=SubscriptionEdit(id,url,name)})
                            add(LibraryAction("删除",Icons.Outlined.DeleteOutline,destructive=true){removing=name to {m.removeProfile(id)}})
                        },menuDescription="配置操作",tag="profile-$id")
                }
            }else if(subscriptions){
                val urls=m.profiles.data.optJSONObject("subscriptions")?.keys()?.asSequence()?.toList().orEmpty()
                val ordered=when(m.profiles.data.optString("subscription_sort")){"name"->urls.sortedBy{SubscriptionUpdates.name(m.profiles,it).lowercase()};"updated"->urls.sortedByDescending{SubscriptionUpdates.options(m.profiles,"",it)?.optLong("last_success")?:0};else->urls}
                if(urls.isEmpty())item{EmptyState("还没有订阅","订阅地址可下载并持续更新一组节点。")}
                items(ordered,key={it}){url->
                    val name=SubscriptionUpdates.name(m.profiles,url);val options=SubscriptionUpdates.options(m.profiles,"",url)
                    val openEditor={subscriptionEditor=SubscriptionEdit(url=url,name=name)}
                    LibraryCard(name,"${m.nodes.count{it.source==url}} 个节点 · "+if(options?.optBoolean("auto_update")==true)"每 ${options.optInt("update_minutes")} 分钟更新" else "手动更新",options?.optLong("last_success")?:0,options?.optJSONObject("usage"),true,updating=m.isUpdating(url),onClick=openEditor,
                        actions=listOf(
                            LibraryAction("编辑",Icons.Outlined.Edit,run=openEditor),
                            LibraryAction("预览",Icons.Outlined.Visibility){source=url;query="";onSubscriptionsChange(false)},
                            LibraryAction("同步",Icons.Outlined.Sync,enabled=!m.isUpdating(url)){m.refreshSubscriptions(url)},
                            LibraryAction("更多",Icons.Outlined.Tune){subscriptionMore=SubscriptionEdit(url=url,name=name)},
                            LibraryAction("删除",Icons.Outlined.DeleteOutline,destructive=true){removing=name to {m.removeSubscription(url)}}
                        ),menuDescription="订阅操作",tag="subscription-$url")
                }
            }else{
                val visible=m.nodes.filter{(source==null||it.source==source)&&(it.name+it.server+it.protocol).contains(query,true)}
                if(visible.isEmpty())item{EmptyState("没有匹配的节点","可粘贴节点链接或添加订阅地址。",Icons.Outlined.Dns)}
                items(visible,key={it.name}){node->
                    val active=m.profiles.data.optString("selected")==node.name
                    Surface(shape=RoundedCornerShape(14.dp),color=if(active)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,border=if(selected==node.name)BorderStroke(1.dp,MaterialTheme.colorScheme.outline) else null){
                        Column(Modifier.fillMaxWidth().combinedClickable(onClick={m.selectedRow=node.name},onDoubleClick={m.selectNode(node.name,connect=true)},onLongClick={nodeMenu=node}).padding(start=14.dp,end=6.dp,top=8.dp,bottom=10.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically){if(active){Icon(Icons.Outlined.CheckCircle,null,Modifier.size(17.dp));Spacer(Modifier.width(8.dp))};Text(node.name,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis);IconButton(onClick={nodeMenu=node},Modifier.size(40.dp)){Icon(Icons.Outlined.MoreVert,"节点操作",Modifier.size(20.dp))}}
                            Row(Modifier.padding(end=8.dp)){Muted(proxyTypeLabel(node.protocol)+" · "+node.server+":"+node.port,Modifier.weight(1f));NodeDelay(node.name,m.delays[node.name],!m.testing&&!m.transition,{m.latency(listOf(node.name),expandGroup=false)})}
                        }
                    }
                }
            }
        }
    }
    nodeMenu?.let{node->ActionSheet(node.name,{nodeMenu=null},listOf("连接此节点" to {m.selectNode(node.name,connect=true)},"设为当前节点" to {m.selectNode(node.name)},"测试延迟" to {m.latency(listOf(node.name))},"编辑节点" to {edit(Editor("node",index=m.nodes.indexOfFirst{it.name==node.name}))},"克隆为手动节点" to {m.cloneNode(m.nodes.indexOfFirst{it.name==node.name})},"移除节点" to {m.removeNode(node.name)}))}
    subscriptionEditor?.let{SubscriptionEditor(m,it){subscriptionEditor=null}}
    subscriptionMore?.let{value->ActionSheet(value.name,{subscriptionMore=null},listOf(
        "复制订阅地址" to {(m.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("订阅地址",value.url));m.message="订阅地址已复制"},
        "订阅设置" to {subscriptionEditor=value},
        "查看节点" to {source=value.url;query="";onSubscriptionsChange(false)}
    ))}
    preview?.let{ConfigPreviewScreen(m,it){preview=null}}
    removing?.let{(name,remove)->AlertDialog(onDismissRequest={removing=null},title={Text(if(config)"删除配置" else "删除订阅")},text={Text(name)},confirmButton={TextButton(onClick={removing=null;remove()},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("删除")}},dismissButton={TextButton(onClick={removing=null}){Text("取消")}})}
}

@Composable private fun ActionSheet(title:String,dismiss:()->Unit,actions:List<Pair<String,()->Unit>>) {ModalBottomSheet(onDismissRequest=dismiss,containerColor=MaterialTheme.colorScheme.surface){SelectionContainer{Text(title,Modifier.heightIn(max=180.dp).verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=12.dp),style=MaterialTheme.typography.titleLarge)};actions.forEach{(label,action)->ListItem(headlineContent={Text(label)},modifier=Modifier.clickable{dismiss();action()},colors=ListItemDefaults.colors(containerColor=Color.Transparent))};Spacer(Modifier.height(16.dp))} }
private fun host(url:String):String=try{URI(url).host ?: "订阅"}catch(_:Exception){"订阅"}

@Composable private fun ProfilePicker(m:MobileModel) {Column{Text("选择配置",Modifier.padding(20.dp),style=MaterialTheme.typography.titleLarge);LazyColumn{items(m.configs,key={it.optString("id")}){p->ListItem(headlineContent={Text(p.optString("name"))},supportingContent={Muted(p.optString("core"))},trailingContent={if(p.optString("id")==m.profiles.data.optString("active_config"))Icon(Icons.Outlined.Check,null)},modifier=Modifier.clickable{m.selectProfile(p.getString("id"));m.sheet=""})}}} }
@Composable private fun LoadingPanel(text:String){Row(Modifier.fillMaxWidth().padding(24.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);Spacer(Modifier.width(12.dp));Muted(text)}}
@Composable private fun Connections(m:MobileModel) {
    var query by remember{mutableStateOf("")};var detail by remember{mutableStateOf<ConnectionRecord?>(null)}
    Column(Modifier.fillMaxSize()) {Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){Text("连接",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);TextButton(onClick={m.closeConnection(null)}){Text("关闭全部")}}
        OutlinedTextField(query,{query=it},Modifier.onFocusChanged{m.textInputFocused=it.isFocused}.fillMaxWidth().padding(16.dp),placeholder={Text("搜索连接")},singleLine=true,leadingIcon={Icon(Icons.Outlined.Search,null)},shape=RoundedCornerShape(16.dp))
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            val rows=m.connections.filter{it.matches(query)}
            if(rows.isEmpty())item{if(m.connectionsLoading)LoadingPanel("正在加载连接")else EmptyState(if(m.connectionsError.isEmpty())"暂无活动连接" else "连接加载失败",m.connectionsError.ifEmpty{"产生网络流量后，连接会显示在这里。"},Icons.Outlined.Cable)}
            items(rows,key={it.id}){row->Surface(onClick={detail=row},shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(verticalAlignment=Alignment.CenterVertically){ConnectionIcon(row.packageName);Spacer(Modifier.width(10.dp));Muted(row.network);Spacer(Modifier.width(8.dp));Text(row.destination,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)};Muted(row.inbound+" → "+row.outbound);Row{Muted("↑ ${MainActivity.bytes(row.upRate)}/s",Modifier.weight(1f));Muted("↓ ${MainActivity.bytes(row.downRate)}/s")}}}}
        }
    }
    detail?.let{c->AlertDialog(onDismissRequest={detail=null},title={Text("连接详情")},text={SelectionContainer{Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){listOf("应用" to c.packageName,"网络" to c.network,"来源" to c.source,"目标" to c.destination,"入站" to c.inbound,"出站" to c.outbound,"规则" to c.rule,"上传" to MainActivity.bytes(c.upload),"下载" to MainActivity.bytes(c.download)).forEach{(k,v)->Muted(k);Text(v.ifEmpty{"—"},style=MaterialTheme.typography.bodyMedium)}}}},confirmButton={TextButton(onClick={m.closeConnection(c.id);detail=null}){Text("关闭连接")}},dismissButton={TextButton(onClick={detail=null}){Text("返回")}})}
}

@Composable private fun Logs(m:MobileModel,dark:Boolean) {
    var query by remember{mutableStateOf("")}
    var autoScroll by remember{mutableStateOf(true)}
    var following by remember{mutableStateOf(true)}
    var userScrolling by remember{mutableStateOf(false)}
    val list=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val rows=remember(m.logs,query){m.logs.filter{it.contains(query,true)}}
    val gestures=remember{object:NestedScrollConnection {
        override fun onPreScroll(available:Offset,source:NestedScrollSource):Offset {
            if(source==NestedScrollSource.Drag && available.y!=0f){userScrolling=true;following=false}
            return Offset.Zero
        }
    }}
    LaunchedEffect(list) {
        snapshotFlow{list.isScrollInProgress}.collect{scrolling->
            if(!scrolling && userScrolling){userScrolling=false;following=!list.canScrollForward}
        }
    }
    suspend fun bottom(){if(rows.isNotEmpty()){list.scrollToItem(rows.lastIndex);list.scrollBy((list.layoutInfo.visibleItemsInfo.lastOrNull()?.size?:0).toFloat())}}
    LaunchedEffect(rows,autoScroll,following){if(autoScroll && following && !userScrolling)bottom()}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Text("最近 350 条",Modifier.weight(1f).padding(start=8.dp),style=MaterialTheme.typography.labelMedium);IconButton(onClick={m.logsPaused=!m.logsPaused}){Icon(if(m.logsPaused)Icons.Outlined.PlayArrow else Icons.Outlined.Pause,if(m.logsPaused)"继续日志" else "暂停日志")};IconButton(onClick={m.copyLogs()}){Icon(Icons.Outlined.ContentCopy,"复制日志")};IconButton(onClick={m.clearLogs()}){Icon(Icons.Outlined.DeleteOutline,"清空日志")}}
        OutlinedTextField(query,{query=it},Modifier.onFocusChanged{m.textInputFocused=it.isFocused}.fillMaxWidth().padding(horizontal=16.dp),placeholder={Text("搜索日志")},singleLine=true,leadingIcon={Icon(Icons.Outlined.Search,null)},shape=RoundedCornerShape(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
            Text("自动滚动",style=MaterialTheme.typography.labelMedium)
            Switch(checked=autoScroll,onCheckedChange={enabled->autoScroll=enabled;if(enabled){userScrolling=false;following=true;scope.launch{bottom()}}},modifier=Modifier.padding(start=8.dp).testTag("log-auto-scroll"))
            Spacer(Modifier.weight(1f))
            TextButton(onClick={userScrolling=false;autoScroll=true;following=true;scope.launch{bottom()}}){Icon(Icons.Outlined.VerticalAlignBottom,null,Modifier.size(18.dp));Text("滚动到底部")}
        }
        if(autoScroll && !following)Text("正在查看历史日志",Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelSmall)
        LazyColumn(state=list,modifier=Modifier.weight(1f).testTag("log-list").nestedScroll(gestures),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            if(rows.isEmpty())item{EmptyState("暂无日志","连接后可查看内核运行记录。",Icons.Outlined.Article)}
            items(rows){line->val text=remember(line,dark){val span=LogStyle.color(line,dark) as Spanned;buildAnnotatedString{append(span.toString());span.getSpans(0,span.length,ForegroundColorSpan::class.java).forEach{addStyle(SpanStyle(color=Color(it.foregroundColor)),span.getSpanStart(it),span.getSpanEnd(it))}}};SelectionContainer{Text(text,fontSize=11.sp,fontFamily=FontFamily.Monospace,lineHeight=17.sp)}}
        }
    }
}
