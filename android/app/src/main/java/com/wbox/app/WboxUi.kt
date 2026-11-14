@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.wbox.app

// Compose layout and traffic cards adapted from SagerNet/sing-box-for-android
// (a548a7903e96266a0046018562c73b192edbfc18), GPL-3.0-or-later.
// Wbox supplies the dual-core/workspace model; profile layouts also adapt FlClash.
import android.content.res.Configuration
import android.os.Build
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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

private val WboxTypography=Typography(bodySmall=TextStyle(fontSize=14.sp,lineHeight=20.sp),labelSmall=TextStyle(fontSize=13.sp,lineHeight=19.sp),labelMedium=TextStyle(fontSize=13.sp,lineHeight=19.sp,fontWeight=FontWeight.Medium))
internal data class Editor(val type: String, val profile: JSONObject?=null, val index: Int=-1)

@Composable internal fun WboxApp(m: MobileModel) {
    val systemDark=isSystemInDarkTheme()
    val dark=when(m.profiles.data.optString("theme","auto")){"dark"->true;"light"->false;else->systemDark}
    MaterialTheme(colorScheme=wboxColorScheme(dark,m.profiles.data.optString("accent","blue")),typography=WboxTypography, shapes=Shapes(medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(20.dp),extraLarge=RoundedCornerShape(24.dp))) {
        WboxSystemBars(m)
        var editor by remember { mutableStateOf<Editor?>(null) }
        var subscriptionEditor by remember { mutableStateOf<SubscriptionEdit?>(null) }
        var preview by remember { mutableStateOf<JSONObject?>(null) }
        val pageState=rememberSaveableStateHolder()
        var librarySubscriptions by remember(m.workspace){mutableStateOf(false)}
        val focus=remember{FocusRequester()}
        val fullPage=editor?.type=="config"||subscriptionEditor!=null||preview!=null
        LaunchedEffect(fullPage){if(!fullPage)focus.requestFocus()}
        var showWorkspace by remember { mutableStateOf(false) }
        val snackbar=remember { SnackbarHostState() }
        DisposableEffect(m.page,m.sheet) { onDispose { m.textInputFocused=false } }
        LaunchedEffect(m.message) { if(m.message.isNotEmpty()){val text=m.message;snackbar.showSnackbar(text);if(m.message==text)m.message=""} }
        SideEffect { m.editing=editor!=null||fullPage }
        LaunchedEffect(m.page,m.sheet,m.runtime.running,m.profiles){m.refreshDetails()}
        val title=when(m.page){"overview"->"概览";"proxies"->"代理";"library"->if(m.workspace=="config")"配置" else "节点";"logs"->"日志";else->"设置"}
        if(fullPage) {
            when {
                subscriptionEditor!=null->SubscriptionEditor(m,subscriptionEditor!!){subscriptionEditor=null}
                preview!=null->ConfigPreviewScreen(m,preview!!){preview=null}
                else->ConfigEditorScreen(m,editor!!.profile!!){editor=null}
            }
        } else pageState.SaveableStateProvider("main") { Scaffold(
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
            topBar={TopAppBar(title={Text(title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,maxLines=1)},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface,scrolledContainerColor=MaterialTheme.colorScheme.surface),actions={
                if(m.page=="library"&&(m.workspace=="config"||librarySubscriptions))LibraryToolbarActions(m,m.workspace=="config")
                if(m.page=="library")ProfileImportMenu(m){editor=it}
                Box { IconButton(onClick={showWorkspace=true}){Icon(Icons.Outlined.SwapHoriz,"切换工作区")}
                    DropdownMenu(showWorkspace,{showWorkspace=false}){PopupWindowBackHandler{showWorkspace=false};listOf("config" to "配置工作区","nodes" to "节点工作区").forEach{(key,label)->DropdownMenuItem(text={Text(label)},onClick={showWorkspace=false;m.switchWorkspace(key)},leadingIcon={Icon(if(key=="config")Icons.Outlined.Folder else Icons.Outlined.Dns,null)},trailingIcon={if(m.workspace==key)Icon(Icons.Outlined.Check,null)})}}
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
                    modifier=Modifier.testTag("connection-fab").semantics{if(m.runtime.starting)contentDescription="取消连接"},
                    onClick={m.toggle()},shape=RoundedCornerShape(18.dp),
                    containerColor=MaterialTheme.colorScheme.primaryContainer,
                    contentColor=MaterialTheme.colorScheme.primary
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
                when(m.page){"overview"->Dashboard(m){editor=it};"proxies"->if(m.workspace=="nodes")Library(m,librarySubscriptions,{librarySubscriptions=it},{subscriptionEditor=it},{preview=it}){editor=it}else ProxiesPage(m);"library"->Library(m,librarySubscriptions,{librarySubscriptions=it},{subscriptionEditor=it},{preview=it}){editor=it};"logs"->Logs(m,dark);else->Settings(m)}
            }
        } }
        if(!fullPage&&m.sheet.isNotEmpty()) {
            val sheet=m.sheet
            WboxBottomSheet(onDismissRequest={m.sheet=""}){
                Box(Modifier.fillMaxHeight()) {when(sheet){"connections"->Connections(m);"picker"->ProfilePicker(m);"apps"->Apps(m)}}
            }
        }
        if(!fullPage)editor?.let { EditSheet(m,it){editor=null} }
    }
}

@Composable private fun PowerControl(m:MobileModel,dark:Boolean) {
    val r=m.runtime
    // Intrinsic width follows the text. No elapsed-time-dependent sizing.
    Surface(onClick={m.toggle()},enabled=!r.stopping,modifier=Modifier.semantics{if(r.starting)contentDescription="取消连接"},shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.primaryContainer,contentColor=MaterialTheme.colorScheme.primary) {
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
            if(m.runtime.running)TextButton(onClick={m.sheet="connections"},modifier=Modifier.semantics{contentDescription="查看连接"}){Icon(Icons.Outlined.Cable,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(m.runtime.connectionsIn.toString())}
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

@Composable private fun Library(m:MobileModel,subscriptions:Boolean,onSubscriptionsChange:(Boolean)->Unit,editSubscription:(SubscriptionEdit)->Unit,preview:(JSONObject)->Unit,edit:(Editor)->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    var source by rememberSaveable {mutableStateOf<String?>(null)}
    val selected=m.selectedRow

    val config=m.workspace=="config"
    Column(Modifier.fillMaxSize()) {
        if(!config) TabRow(if(subscriptions)1 else 0,containerColor=MaterialTheme.colorScheme.surface){Tab(!subscriptions,{onSubscriptionsChange(false)},text={Text("节点")});Tab(subscriptions,{onSubscriptionsChange(true)},text={Text("订阅")})}
        if(!config&&!subscriptions)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
            Text("${m.nodes.size} 个节点",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick={m.paste()}){Icon(Icons.Outlined.ContentPaste,"从剪贴板导入")}
            IconButton(onClick={m.latency(m.nodes.filter{source==null||it.source==source}.map{it.name})}){if(m.testing)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Icon(Icons.Outlined.Speed,"测试延迟")}
        }
        if(!config && !subscriptions) {
            OutlinedTextField(query,{query=it},Modifier.onFocusChanged{m.textInputFocused=it.isFocused}.fillMaxWidth().padding(horizontal=16.dp),placeholder={Text("搜索节点")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,shape=RoundedCornerShape(16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(source==null,{source=null},label={Text("全部")});FilterChip(source=="",{source=""},label={Text("手动节点")})
                m.nodes.map{it.source}.filter{it.isNotEmpty()}.distinct().forEach{url->FilterChip(source==url,{source=url},label={Text(SubscriptionUpdates.name(m.profiles,url))})}
            }
        }
        if(config||subscriptions)ProfilesScreen(m,config,edit,editSubscription,preview,{url->source=url;query="";onSubscriptionsChange(false)},{edit(Editor("import"))})
        else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=if(!m.runtime.running&&!m.runtime.stopping)96.dp else 20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                val visible=m.nodes.filter{(source==null||it.source==source)&&(it.name+it.server+it.protocol).contains(query,true)}
                if(visible.isEmpty())item{EmptyState("没有匹配的节点","可粘贴节点链接或添加订阅地址。",Icons.Outlined.Dns)}
                items(visible,key={it.name}){node->
                    val active=m.profiles.data.optString("selected")==node.name
                    var menu by remember(node.name){mutableStateOf(false)}
                    Surface(modifier=Modifier.testTag("node-${node.name}").semantics{this.selected=active},shape=RoundedCornerShape(14.dp),color=if(active)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,border=if(selected==node.name)BorderStroke(1.dp,MaterialTheme.colorScheme.outline) else null){
                        Column(Modifier.fillMaxWidth().combinedClickable(onClick={m.selectedRow=node.name;m.selectNode(node.name)},onLongClick={menu=true}).padding(start=14.dp,end=6.dp,top=8.dp,bottom=10.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically){
                                if(active){Icon(Icons.Outlined.CheckCircle,null,Modifier.size(17.dp));Spacer(Modifier.width(8.dp))}
                                Text(node.name,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Box {
                                    IconButton(onClick={menu=true},Modifier.size(40.dp).testTag("node-menu-${node.name}")){Icon(Icons.Outlined.MoreVert,"节点操作",Modifier.size(20.dp))}
                                    DropdownMenu(expanded=menu,onDismissRequest={menu=false},modifier=Modifier.widthIn(min=208.dp,max=280.dp).testTag("node-actions-${node.name}")){
                                        PopupWindowBackHandler{menu=false}
                                        val actions=listOf(
                                            LibraryAction("连接此节点",Icons.Outlined.PlayArrow){m.selectNode(node.name,connect=true)},
                                            LibraryAction("设为当前节点",Icons.Outlined.CheckCircle){m.selectedRow=node.name;m.selectNode(node.name)},
                                            LibraryAction("测试延迟",Icons.Outlined.Speed){m.latency(listOf(node.name),expandGroup=false)},
                                            LibraryAction("编辑节点",Icons.Outlined.Edit){edit(Editor("node",index=m.nodes.indexOfFirst{it.name==node.name}))},
                                            LibraryAction("克隆为手动节点",Icons.Outlined.ContentCopy){m.cloneNode(m.nodes.indexOfFirst{it.name==node.name})},
                                            LibraryAction("移除节点",Icons.Outlined.Delete,destructive=true){m.removeNode(node.name)}
                                        )
                                        actions.forEach{action->
                                            if(action.destructive)HorizontalDivider()
                                            DropdownMenuItem(text={Text(action.label,color=if(action.destructive)MaterialTheme.colorScheme.error else Color.Unspecified)},
                                                leadingIcon={Icon(action.icon,null,tint=if(action.destructive)MaterialTheme.colorScheme.error else LocalContentColor.current)},
                                                onClick={menu=false;action.run()})
                                        }
                                    }
                                }
                            }
                            Row(Modifier.padding(end=8.dp)){Muted(proxyTypeLabel(node.protocol)+" · "+node.server+":"+node.port,Modifier.weight(1f));NodeDelay(node.name,m.delays[node.name],!m.testing&&!m.transition,{m.latency(listOf(node.name),expandGroup=false)})}
                        }
                    }
                }
        }
    }
}

/** Compose UI 1.6 popups only handle legacy Back keys, so register with their own window on 13+. */
@Composable internal fun PopupWindowBackHandler(onBack:()->Unit) {
    val view=LocalView.current
    val latestBack=rememberUpdatedState(onBack)
    DisposableEffect(view) {
        if(Build.VERSION.SDK_INT>=33) {
            val callback=OnBackInvokedCallback{latestBack.value()}
            var dispatcher:OnBackInvokedDispatcher?=null
            fun unregister() {
                dispatcher?.unregisterOnBackInvokedCallback(callback)
                dispatcher=null
            }
            fun register() {
                val attached=view.findOnBackInvokedDispatcher()?:return
                if(dispatcher===attached)return
                unregister()
                attached.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback)
                dispatcher=attached
            }
            val listener=object:View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v:View)=register()
                override fun onViewDetachedFromWindow(v:View)=unregister()
            }
            view.addOnAttachStateChangeListener(listener)
            if(view.isAttachedToWindow)register()
            onDispose{view.removeOnAttachStateChangeListener(listener);unregister()}
        }else onDispose{}
    }
}

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
