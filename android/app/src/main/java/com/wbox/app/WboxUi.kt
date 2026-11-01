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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    SideEffect {
        m.activity.window.statusBarColor=if(dark)0xff1f1f1f.toInt() else 0xfffafafa.toInt()
        m.activity.window.navigationBarColor=if(dark)0xff1f1f1f.toInt() else 0xfffafafa.toInt()
        m.activity.window.decorView.systemUiVisibility=if(dark)0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    MaterialTheme(colorScheme=if(dark)DarkColors else LightColors, shapes=Shapes(medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(20.dp),extraLarge=RoundedCornerShape(24.dp))) {
        var editor by remember { mutableStateOf<Editor?>(null) }
        val focus=remember{FocusRequester()}
        LaunchedEffect(Unit){focus.requestFocus()}
        var showWorkspace by remember { mutableStateOf(false) }
        val snackbar=remember { SnackbarHostState() }
        DisposableEffect(m.page,m.sheet) { onDispose { m.textInputFocused=false } }
        LaunchedEffect(m.message) { if(m.message.isNotEmpty()){val text=m.message;snackbar.showSnackbar(text);if(m.message==text)m.message=""} }
        SideEffect { m.editing=editor!=null }
        val title=when(m.page){"overview"->"概览";"library"->if(m.workspace=="config")"配置" else "节点";"logs"->"日志";else->"设置"}
        Scaffold(
            modifier=Modifier.focusRequester(focus).onPreviewKeyEvent{event ->
                val key=event.nativeKeyEvent
                if(key.action!=android.view.KeyEvent.ACTION_DOWN || m.workspace!="nodes" || editor!=null || m.textInputFocused) false
                else when {
                    key.isCtrlPressed && key.keyCode==android.view.KeyEvent.KEYCODE_V -> {m.paste();true}
                    key.isCtrlPressed && key.keyCode==android.view.KeyEvent.KEYCODE_D && m.selectedRow!=null -> {val index=m.nodes.indexOfFirst{it.name==m.selectedRow};if(index>=0)editor=Editor("node",index=index);true}
                    key.keyCode==android.view.KeyEvent.KEYCODE_ENTER && m.page=="library" && m.selectedRow!=null -> {m.selectNode(m.selectedRow!!);true}
                    else -> false
                }
            }.focusable(),
            topBar={TopAppBar(title={Text(title,style=MaterialTheme.typography.headlineMedium)},actions={
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
                if(!m.runtime.running && !m.runtime.stopping) FloatingActionButton(
                    onClick={if(!m.busy)m.toggle()},shape=RoundedCornerShape(18.dp),
                    containerColor=if(dark)Color(0xff355147)else Color(0xffd3e4df),
                    contentColor=if(dark)Color(0xffecf8f2)else Color(0xff163e30)
                ){
                    if(m.runtime.starting)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp,color=LocalContentColor.current)
                    else Icon(Icons.Outlined.PlayArrow,"连接代理",Modifier.size(26.dp))
                }
            },
            bottomBar={Column{
                if(m.runtime.running || m.runtime.stopping) ServiceStatusBar(m)
                NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp){
                    listOf(Triple("overview","概览",Icons.Outlined.Dashboard),Triple("library",if(m.workspace=="config")"配置" else "节点",Icons.Outlined.Folder),Triple("logs","日志",Icons.Outlined.Article),Triple("settings","设置",Icons.Outlined.Settings)).forEach{(key,label,icon)->NavigationBarItem(selected=m.page==key,onClick={m.page=key},icon={Icon(icon,label)},label={Text(label)})}
                }
            }}
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when(m.page){"overview"->Dashboard(m){editor=it};"library"->Library(m){editor=it};"logs"->Logs(m,dark);else->Settings(m)}
            }
        }
        if(m.sheet.isNotEmpty()) {
            val sheet=m.sheet
            ModalBottomSheet(onDismissRequest={m.sheet=""},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp){
                Box(Modifier.fillMaxHeight()) {when(sheet){"groups"->Groups(m);"connections"->Connections(m);"picker"->ProfilePicker(m);"apps"->Apps(m)}}
            }
        }
        editor?.let { EditSheet(m,it){editor=null} }
    }
}

@Composable private fun PowerControl(m:MobileModel) {
    val r=m.runtime
    // Intrinsic width follows the text. No elapsed-time-dependent sizing.
    Surface(onClick={m.toggle()},enabled=!r.stopping && !m.busy,shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.defaultMinSize(minWidth=40.dp,minHeight=40.dp).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
            if(r.starting || r.stopping) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
            else {if(r.running)Text(uptime(r.uptime),fontSize=12.sp,fontFamily=FontFamily.Monospace);Icon(if(r.running)Icons.Outlined.Stop else Icons.Outlined.PlayArrow,if(r.running)"断开代理连接" else "连接代理",Modifier.size(20.dp))}
        }
    }
}
internal fun uptime(seconds:Long):String = if(seconds>=3600)"%d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60) else "%d:%02d".format(seconds/60,seconds%60)

@Composable private fun ServiceStatusBar(m:MobileModel) {
    // SFA ServiceStatusBar: compact status, connections and group shortcuts.
    Surface(color=MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(m.runtime.status,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
            TextButton(onClick={m.sheet="connections"}){Icon(Icons.Outlined.Cable,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(m.runtime.connectionsIn.toString())}
            IconButton(onClick={m.sheet="groups"}){Icon(Icons.Outlined.AccountTree,"代理组",Modifier.size(20.dp))}
            PowerControl(m)
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

@Composable private fun Library(m:MobileModel,edit:(Editor)->Unit) {
    var subscriptions by remember {mutableStateOf(false)}
    var query by remember {mutableStateOf("")}
    var source by remember {mutableStateOf<String?>(null)}
    val selected=m.selectedRow
    var nodeMenu by remember {mutableStateOf<Node?>(null)}
    var profileMenu by remember {mutableStateOf<JSONObject?>(null)}
    val config=m.workspace=="config"
    Column(Modifier.fillMaxSize()) {
        if(!config) TabRow(if(subscriptions)1 else 0){Tab(!subscriptions,{subscriptions=false},text={Text("节点")});Tab(subscriptions,{subscriptions=true},text={Text("订阅")})}
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
            Text(if(config)"我的配置" else if(subscriptions)"订阅来源" else "${m.nodes.size} 个节点",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            if(!config && !subscriptions){IconButton(onClick={m.paste()}){Icon(Icons.Outlined.ContentPaste,"从剪贴板导入")};IconButton(onClick={m.latency(m.nodes.filter{source==null||it.source==source}.map{it.name})}){if(m.testing)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Icon(Icons.Outlined.Speed,"测试延迟")}}
            if(subscriptions && !config)IconButton(onClick={m.refreshSubscriptions()}){Icon(Icons.Outlined.Refresh,"更新全部")}
            IconButton(onClick={edit(Editor("import"))}){Icon(Icons.Outlined.Add,if(config)"添加配置" else "添加节点或订阅")}
        }
        if(!config && !subscriptions) {
            OutlinedTextField(query,{query=it},Modifier.onFocusChanged{m.textInputFocused=it.isFocused}.fillMaxWidth().padding(horizontal=16.dp),placeholder={Text("搜索节点")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,shape=RoundedCornerShape(16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(source==null,{source=null},label={Text("全部")});FilterChip(source=="",{source=""},label={Text("手动节点")})
                m.nodes.map{it.source}.filter{it.isNotEmpty()}.distinct().forEach{url->FilterChip(source==url,{source=url},label={Text(host(url))})}
            }
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(config){
                if(m.configs.isEmpty())item{EmptyState("还没有配置","添加远程 JSON / YAML 或本地配置文件。")}
                items(m.configs,key={it.optString("id")}){profile->
                    val active=profile.optString("id")==m.profiles.data.optString("active_config")
                    Surface(onClick={m.selectProfile(profile.getString("id"))},shape=RoundedCornerShape(16.dp),color=if(active)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant){
                        ListItem(headlineContent={Text(profile.optString("name"))},supportingContent={Muted(profile.optString("core")+" · "+if(profile.optString("url").isEmpty())"本地配置" else "远程配置")},leadingContent={Icon(if(active)Icons.Outlined.CheckCircle else Icons.Outlined.Description,null)},trailingContent={IconButton(onClick={profileMenu=profile}){Icon(Icons.Outlined.MoreVert,"配置操作")}},colors=ListItemDefaults.colors(containerColor=Color.Transparent))
                    }
                }
            }else if(subscriptions){
                val urls=m.profiles.data.optJSONObject("subscriptions")?.keys()?.asSequence()?.toList().orEmpty()
                if(urls.isEmpty())item{EmptyState("还没有订阅","订阅地址可下载并持续更新一组节点。")}
                items(urls){url->Panel{SectionTitle(host(url),Icons.Outlined.Public);Muted("${m.nodes.count{it.source==url}} 个节点");Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick={m.removeSubscription(url)}){Text("移除")};TextButton(onClick={m.refreshSubscriptions(url)}){Text("更新")}}}}
            }else{
                val visible=m.nodes.filter{(source==null||it.source==source)&&(it.name+it.server+it.protocol).contains(query,true)}
                if(visible.isEmpty())item{EmptyState("没有匹配的节点","可粘贴节点链接或添加订阅地址。",Icons.Outlined.Dns)}
                items(visible,key={it.name}){node->
                    val active=m.profiles.data.optString("selected")==node.name
                    Surface(shape=RoundedCornerShape(14.dp),color=if(active)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,border=if(selected==node.name)BorderStroke(1.dp,MaterialTheme.colorScheme.outline) else null){
                        Column(Modifier.fillMaxWidth().combinedClickable(onClick={m.selectedRow=node.name},onDoubleClick={m.selectNode(node.name)},onLongClick={nodeMenu=node}).padding(start=14.dp,end=6.dp,top=8.dp,bottom=10.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically){if(active){Icon(Icons.Outlined.CheckCircle,null,Modifier.size(17.dp));Spacer(Modifier.width(8.dp))};Text(node.name,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis);IconButton(onClick={nodeMenu=node},Modifier.size(40.dp)){Icon(Icons.Outlined.MoreVert,"节点操作",Modifier.size(20.dp))}}
                            Row(Modifier.padding(end=8.dp)){Muted(node.protocol.uppercase()+" · "+node.server+":"+node.port,Modifier.weight(1f));DelayText(m.delays[node.name])}
                        }
                    }
                }
            }
        }
    }
    nodeMenu?.let{node->ActionSheet(node.name,{nodeMenu=null},listOf("设为当前节点" to {m.selectNode(node.name)},"测试延迟" to {m.latency(listOf(node.name))},"编辑节点" to {edit(Editor("node",index=m.nodes.indexOfFirst{it.name==node.name}))},"克隆为手动节点" to {m.cloneNode(m.nodes.indexOfFirst{it.name==node.name})},"移除节点" to {m.removeNode(node.name)}))}
    profileMenu?.let{p->
        ActionSheet(p.optString("name"),{profileMenu=null},buildList{
            add("重命名" to {edit(Editor("rename",p))})
            add("编辑配置" to {edit(Editor("config",p))})
            if(p.optString("url").isNotEmpty())add("更新配置" to {m.updateProfile(p)})
            add("导出文件" to {m.activity.export(p)})
            add("移除配置" to {m.removeProfile(p.getString("id"))})
        })
    }
}

@Composable private fun ActionSheet(title:String,dismiss:()->Unit,actions:List<Pair<String,()->Unit>>) {ModalBottomSheet(onDismissRequest=dismiss,containerColor=MaterialTheme.colorScheme.surface){Text(title,Modifier.padding(horizontal=24.dp,vertical=12.dp),style=MaterialTheme.typography.titleLarge);actions.forEach{(label,action)->ListItem(headlineContent={Text(label)},modifier=Modifier.clickable{dismiss();action()},colors=ListItemDefaults.colors(containerColor=Color.Transparent))};Spacer(Modifier.height(16.dp))} }
private fun host(url:String):String=try{URI(url).host ?: "订阅"}catch(_:Exception){"订阅"}

@Composable private fun ProfilePicker(m:MobileModel) {Column{Text("选择配置",Modifier.padding(20.dp),style=MaterialTheme.typography.titleLarge);LazyColumn{items(m.configs,key={it.optString("id")}){p->ListItem(headlineContent={Text(p.optString("name"))},supportingContent={Muted(p.optString("core"))},trailingContent={if(p.optString("id")==m.profiles.data.optString("active_config"))Icon(Icons.Outlined.Check,null)},modifier=Modifier.clickable{m.selectProfile(p.getString("id"));m.sheet=""})}}} }
@Composable private fun DelayText(value:String?) {Text(value ?: "未测试",fontFamily=FontFamily.Monospace,fontSize=11.sp,color=delayColor(value))}
@Composable private fun delayColor(value:String?):Color {val n=value?.substringBefore(" ")?.toLongOrNull();return when{value=="失败"->MaterialTheme.colorScheme.error;n==null->MaterialTheme.colorScheme.onSurfaceVariant;n<800->Color(0xff539675);n<1500->Color(0xffbd9747);else->Color(0xffce6e63)}}
private fun delay(m:MobileModel,name:String):String? {m.delays[name]?.let{return it};val values=m.groups.optJSONObject(name)?.optJSONArray("history");val n=values?.optJSONObject(values.length()-1)?.optInt("delay") ?: return null;return if(n>0)"$n ms" else "失败"}
// Group header, rounded row surfaces and selected latency dots follow SFA GroupsCard.
@Composable private fun Groups(m:MobileModel) {
    val groups=m.groups.keys().asSequence().filter{it!="GLOBAL"&&m.groups.optJSONObject(it)?.has("all")==true}.toList()
    val expanded=remember{mutableStateMapOf<String,Boolean>()}
    LaunchedEffect(groups.firstOrNull()){if(expanded.isEmpty())groups.firstOrNull()?.let{expanded[it]=true}}
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Text("组",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall);IconButton(onClick={val open=groups.none{expanded[it]==true};groups.forEach{expanded[it]=open}}){Icon(if(groups.any{expanded[it]==true})Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,"展开或收起全部")}}
        LazyColumn(contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp)){
            if(groups.isEmpty())item{EmptyState("暂无代理组","连接配置后可选择节点。",Icons.Outlined.AccountTree)}
            groups.forEach{tag->
                val group=m.groups.getJSONObject(tag);val all=group.getJSONArray("all").strings();val open=expanded[tag]==true
                val testing=m.testing&&all.any{m.delays[it]=="测试中"}
                item(key="header:$tag"){
                    Surface(color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,shape=RoundedCornerShape(topStart=20.dp,topEnd=20.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start=16.dp,end=6.dp,top=6.dp,bottom=2.dp),verticalAlignment=Alignment.CenterVertically){
                            Row(Modifier.weight(1f).clickable{expanded[tag]=!open},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){Text(tag,Modifier.weight(1f,fill=false),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Muted(group.optString("type","Selector"))}
                            Surface(shape=RoundedCornerShape(99.dp),color=MaterialTheme.colorScheme.onSurface.copy(alpha=.08f)){Text(all.size.toString(),Modifier.padding(horizontal=8.dp,vertical=2.dp),style=MaterialTheme.typography.labelSmall)}
                            IconButton(onClick={m.latency(all)},modifier=Modifier.size(40.dp)){if(testing)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Icon(Icons.Outlined.Speed,"测试 $tag",Modifier.size(20.dp))}
                            IconButton(onClick={expanded[tag]=!open},modifier=Modifier.size(40.dp)){Icon(if(open)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,if(open)"收起 $tag" else "展开 $tag")}
                        }
                    }
                }
                if(!open)item(key="dots:$tag"){
                    Surface(Modifier.fillMaxWidth().padding(bottom=12.dp),color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(bottomStart=20.dp,bottomEnd=20.dp)){
                        FlowRow(Modifier.padding(start=16.dp,end=16.dp,top=6.dp,bottom=16.dp),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){all.forEach{node->Box(Modifier.size(11.dp).clip(RoundedCornerShape(4.dp)).background(delayColor(delay(m,node))),contentAlignment=Alignment.Center){if(group.optString("now")==node)Box(Modifier.size(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))}}}
                    }
                }
                else all.chunked(2).forEachIndexed{index,pair->val last=index==(all.size-1)/2
                    item(key="$tag:$index"){
                        Surface(Modifier.fillMaxWidth().padding(bottom=if(last)12.dp else 0.dp),color=MaterialTheme.colorScheme.surfaceVariant,shape=if(last)RoundedCornerShape(bottomStart=20.dp,bottomEnd=20.dp)else RoundedCornerShape(0.dp)){
                            Row(Modifier.padding(start=16.dp,end=16.dp,top=8.dp,bottom=if(last)16.dp else 0.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                pair.forEach{node->val selected=group.optString("now")==node
                                    Surface(onClick={m.selectNode(node,tag)},modifier=Modifier.weight(1f),shape=RoundedCornerShape(16.dp),color=if(selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface){
                                        Column(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                            Text(node,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                                            Row(verticalAlignment=Alignment.CenterVertically){Muted(m.groups.optJSONObject(node)?.optString("type") ?: "",Modifier.weight(1f));DelayText(delay(m,node))}
                                        }
                                    }
                                };if(pair.size==1)Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Connections(m:MobileModel) {
    var query by remember{mutableStateOf("")};var detail by remember{mutableStateOf<ConnectionRecord?>(null)}
    Column {Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){Text("连接",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);TextButton(onClick={m.closeConnection(null)}){Text("关闭全部")}}
        OutlinedTextField(query,{query=it},Modifier.onFocusChanged{m.textInputFocused=it.isFocused}.fillMaxWidth().padding(16.dp),placeholder={Text("搜索连接")},singleLine=true,leadingIcon={Icon(Icons.Outlined.Search,null)},shape=RoundedCornerShape(16.dp))
        LazyColumn(contentPadding=PaddingValues(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            val rows=m.connections.filter{it.matches(query)}
            if(rows.isEmpty())item{EmptyState("暂无活动连接","产生网络流量后，连接会显示在这里。",Icons.Outlined.Cable)}
            items(rows,key={it.id}){row->Surface(onClick={detail=row},shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row{Muted(row.network);Spacer(Modifier.width(8.dp));Text(row.destination,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)};Muted(row.inbound+" → "+row.outbound);Row{Muted("↑ ${MainActivity.bytes(row.upRate)}/s",Modifier.weight(1f));Muted("↓ ${MainActivity.bytes(row.downRate)}/s")}}}}
        }
    }
    detail?.let{c->AlertDialog(onDismissRequest={detail=null},title={Text("连接详情")},text={SelectionContainer{Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){listOf("网络" to c.network,"来源" to c.source,"目标" to c.destination,"入站" to c.inbound,"出站" to c.outbound,"规则" to c.rule,"上传" to MainActivity.bytes(c.upload),"下载" to MainActivity.bytes(c.download)).forEach{(k,v)->Muted(k);Text(v.ifEmpty{"—"},style=MaterialTheme.typography.bodyMedium)}}}},confirmButton={TextButton(onClick={m.closeConnection(c.id);detail=null}){Text("关闭连接")}},dismissButton={TextButton(onClick={detail=null}){Text("返回")}})}
}

@Composable private fun Logs(m:MobileModel,dark:Boolean) {
    var query by remember{mutableStateOf("")}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Text("最近 350 条",Modifier.weight(1f).padding(start=8.dp),style=MaterialTheme.typography.labelMedium);IconButton(onClick={m.logsPaused=!m.logsPaused}){Icon(if(m.logsPaused)Icons.Outlined.PlayArrow else Icons.Outlined.Pause,if(m.logsPaused)"继续日志" else "暂停日志")};IconButton(onClick={m.copyLogs()}){Icon(Icons.Outlined.ContentCopy,"复制日志")};IconButton(onClick={m.clearLogs()}){Icon(Icons.Outlined.DeleteOutline,"清空日志")}}
        OutlinedTextField(query,{query=it},Modifier.onFocusChanged{m.textInputFocused=it.isFocused}.fillMaxWidth().padding(horizontal=16.dp),placeholder={Text("搜索日志")},singleLine=true,leadingIcon={Icon(Icons.Outlined.Search,null)},shape=RoundedCornerShape(16.dp))
        LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            val rows=m.logs.filter{it.contains(query,true)}
            if(rows.isEmpty())item{EmptyState("暂无日志","连接后可查看内核运行记录。",Icons.Outlined.Article)}
            items(rows){line->val text=remember(line,dark){val span=LogStyle.color(line,dark) as Spanned;buildAnnotatedString{append(span.toString());span.getSpans(0,span.length,ForegroundColorSpan::class.java).forEach{addStyle(SpanStyle(color=Color(it.foregroundColor)),span.getSpanStart(it),span.getSpanEnd(it))}}};SelectionContainer{Text(text,fontSize=11.sp,fontFamily=FontFamily.Monospace,lineHeight=17.sp)}}
        }
    }
}
