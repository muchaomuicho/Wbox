@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wbox.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalTextToolbar
import kotlinx.coroutines.*
import org.json.*
import java.net.URI

@Composable internal fun Settings(m:MobileModel) {
    var testUrl by remember(m.profiles){mutableStateOf(m.profiles.data.optString("test_url",Profiles.TEST_URL))}
    val mode=m.profiles.data.optString("app_mode","all")
    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {SettingsPanel("外观") {Choice("主题",m.profiles.data.optString("theme","auto"),listOf("auto" to "跟随系统","light" to "浅色","dark" to "深色")){m.setting("theme",it)}}}
        item {SettingsPanel("代理") {
            if(m.workspace=="nodes")Choice("内核",m.profiles.engine(),listOf("sing-box" to "sing-box","mihomo" to "mihomo")){m.setting("engine",it)}
            else ListItem(headlineContent={Text("内核")},supportingContent={Text("由当前配置决定")},trailingContent={Text(m.profiles.engine())},colors=ListItemDefaults.colors(containerColor=Color.Transparent))
            Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("IPv6");Text("下次连接生效",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(m.profiles.data.optBoolean("ipv6",true),{m.setting("ipv6",it)})}
            ListItem(headlineContent={Text("分应用代理")},supportingContent={Text(when(mode){"include"->"只代理所选应用";"exclude"->"绕过所选应用";else->"全部应用"})},trailingContent={Icon(Icons.Outlined.ChevronRight,null)},modifier=Modifier.clickable{m.sheet="apps"},colors=ListItemDefaults.colors(containerColor=Color.Transparent))
        }}
        item {SettingsPanel("延迟测试") {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("默认使用 Apple 测试页。sing-box 需要 HTTPS；mihomo 支持 HTTP / HTTPS。双内核均使用复用连接后的实际请求延迟。比较其他客户端时，请使用相同测试地址。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(testUrl,{testUrl=it},Modifier.onFocusChanged{m.textInputFocused=it.isFocused}.fillMaxWidth(),label={Text("测试地址")},singleLine=true,shape=RoundedCornerShape(12.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick={testUrl=Profiles.TEST_URL}){Text("恢复默认")};Button(onClick={m.work{val uri=URI(testUrl);require(uri.scheme in listOf("https","http") && uri.host!=null){"请填写 HTTP / HTTPS 地址"};require(m.profiles.engine()!="sing-box" || uri.scheme=="https"){"sing-box 延迟测试需要 HTTPS 地址"};m.setting("test_url",testUrl.trim())}}){Text("保存")}}
            }
        }}
        item {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text("Wbox 0.6.10",style=MaterialTheme.typography.titleMedium);Text("sing-box 1.14.4-wbox.1 · mihomo 1.19.33",style=MaterialTheme.typography.bodySmall);Text("界面参考 sing-box for Android · GPL-3.0\n后台按需采样 · 同时仅运行所选内核",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
    }
}
@Composable private fun SettingsPanel(title:String,content:@Composable ColumnScope.()->Unit) {Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(title,Modifier.padding(start=12.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant);Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceVariant){Column(content=content)}}}
@Composable internal fun Choice(label:String,value:String,options:List<Pair<String,String>>,changed:(String)->Unit) {
    var open by remember{mutableStateOf(false)}
    Box{ListItem(headlineContent={Text(label)},supportingContent={Text(options.firstOrNull{it.first==value}?.second ?: value)},trailingContent={Icon(Icons.Outlined.ExpandMore,null)},modifier=Modifier.clickable{open=true},colors=ListItemDefaults.colors(containerColor=Color.Transparent));DropdownMenu(open,{open=false}){options.forEach{(key,title)->DropdownMenuItem(text={Text(title)},onClick={open=false;changed(key)},trailingIcon={if(value==key)Icon(Icons.Outlined.Check,null)})}}}
}

@Composable internal fun Apps(m:MobileModel) {
    var mode by remember{mutableStateOf(m.profiles.data.optString("app_mode","all"))}
    var query by remember{mutableStateOf("")}
    var applications by remember{mutableStateOf<List<Pair<String,String>>>(emptyList())}
    val selected=remember{mutableStateListOf<String>().apply{addAll(m.profiles.data.optJSONArray("apps")?.strings().orEmpty())}}
    LaunchedEffect(Unit){applications=withContext(Dispatchers.IO){val pm=m.activity.packageManager;pm.getInstalledApplications(0).filter{it.packageName!=m.activity.packageName}.map{it.packageName to pm.getApplicationLabel(it).toString()}.sortedBy{it.second.lowercase()}}}
    Column {
        Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){Text("分应用代理",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);TextButton(onClick={m.work{require(mode!="include" || selected.isNotEmpty()){ "请选择至少一个应用" };m.saveApps(mode,selected.toList());m.sheet="";m.message="已保存，下次连接生效"}}){Text("保存")}}
        Choice("代理范围",mode,listOf("all" to "全部应用","include" to "只代理所选应用","exclude" to "绕过所选应用")){mode=it}
        if(mode!="all"){
            OutlinedTextField(query,{query=it},Modifier.onFocusChanged{m.textInputFocused=it.isFocused}.fillMaxWidth().padding(16.dp),placeholder={Text("搜索应用")},singleLine=true,shape=RoundedCornerShape(12.dp))
            LazyColumn {items(applications.filter{(it.first+it.second).contains(query,true)},key={it.first}){(pkg,label)->ListItem(headlineContent={Text(label)},supportingContent={Text(pkg,style=MaterialTheme.typography.bodySmall)},trailingContent={Checkbox(pkg in selected,{if(it)selected.add(pkg) else selected.remove(pkg)})},modifier=Modifier.clickable{if(pkg in selected)selected.remove(pkg) else selected.add(pkg)})}}
        }
    }
}

@Composable internal fun EditSheet(m:MobileModel,editor:Editor,dismiss:()->Unit) {
    val config=editor.type=="config"
    if(config) { ConfigEditorScreen(m,editor.profile!!,dismiss);return }
    val rename=editor.type=="rename"
    val node=remember(editor){if(editor.type=="node")m.nodes.getOrNull(editor.index) else null}
    val revision=remember(node){node?.let{NodeEditing.revision(it)} ?: ""}
    val native= node!=null && (node.nativeSingbox!=null || node.nativeMihomo!=null)
    val initial=remember(editor){node?.let{if(native)it.nativeSingbox ?: it.nativeMihomo else it.fields()} ?: JSONObject()}
    val fields=remember(editor){mutableStateMapOf<String,String>().apply{initial.keys().forEach{key->put(key,initial.optString(key))}}}
    var name by remember{mutableStateOf(editor.profile?.optString("name") ?: "")}
    var content by remember{mutableStateOf(if(native)initial.toString(2) else editor.profile?.optString("content") ?: "")}
    var advanced by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var saving by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val title=when(editor.type){"rename"->"重命名配置";"config"->"编辑配置";"node"->"编辑节点";else->if(m.workspace=="config")"添加配置" else "添加节点或订阅"}
    ModalBottomSheet(onDismissRequest={if(!saving)dismiss()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal=20.dp).heightIn(max=680.dp)) {
            Text(title,Modifier.padding(vertical=12.dp),style=MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                if(rename || (editor.type=="import" && m.workspace=="config")) Field("名称",name,{name=it;error=""})
                when {
                    rename -> Unit
                    node!=null && !native -> {
                        Text(node.protocol.uppercase(),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        fun set(key:String,value:String){fields[key]=value;error=""}
                        listOf("name" to "名称","server" to "服务器地址","port" to "端口").forEach{(key,label)->Field(label,fields[key].orEmpty(),{set(key,it)})}
                        if(node.protocol in listOf("vmess","vless","tuic"))Field("UUID",fields["uuid"].orEmpty(),{set("uuid",it)})
                        if(node.protocol in listOf("http","socks","naive"))Field("用户名",fields["username"].orEmpty(),{set("username",it)})
                        if(node.protocol !in listOf("vmess","vless"))Field(if(node.protocol=="wireguard")"私钥" else "密码",fields["password"].orEmpty(),{set("password",it)},secret=true)
                        if(node.protocol in listOf("ss","vmess"))Field("加密方式",fields["cipher"].orEmpty(),{set("cipher",it)})
                        if(node.protocol in listOf("vmess","vless","trojan")){
                            Choice("传输协议",fields["network"].orEmpty(),listOf("tcp","ws","grpc","http","h2").map{it to it}){set("network",it)}
                            Field("Host",fields["host"].orEmpty(),{set("host",it)});Field("路径 / gRPC Service name",fields["path"].orEmpty(),{set("path",it)})
                        }
                        if(node.protocol=="vless")Field("Flow",fields["flow"].orEmpty(),{set("flow",it)})
                        if(node.protocol !in listOf("ss","wireguard")){
                            Row(verticalAlignment=Alignment.CenterVertically){Text("TLS",Modifier.weight(1f));Switch(fields["tls"]=="true",{set("tls",it.toString())})}
                            Field("SNI",fields["sni"].orEmpty(),{set("sni",it)})
                        }
                        TextButton(onClick={advanced=!advanced}){Text("高级参数");Icon(if(advanced)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)}
                        if(advanced){
                            if(node.protocol=="vmess")Field("Alter ID",fields["alterId"].orEmpty(),{set("alterId",it)})
                            if(node.protocol in listOf("vmess","vless","trojan"))listOf("fingerprint" to "TLS 指纹","realityKey" to "Reality 公钥","shortId" to "Reality Short ID").forEach{(k,l)->Field(l,fields[k].orEmpty(),{set(k,it)})}
                            Row(verticalAlignment=Alignment.CenterVertically){Text("跳过证书验证",Modifier.weight(1f));Switch(fields["insecure"]=="true",{set("insecure",it.toString())})}
                            Field("其他协议参数（JSON）",fields["options"] ?: "{}",{set("options",it)},multiline=true)
                        }
                        if(node.source.isNotEmpty())Text("订阅更新会覆盖修改；长期保留可先克隆为手动节点。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        val label=if(config)"配置内容" else if(native)"原生节点（JSON）" else if(m.workspace=="config")"配置地址或 JSON / YAML 内容" else "订阅地址或节点链接"
                        Field(label,content,{content=it;error=""},multiline=true,code=config||native)
                        if(editor.type=="import") {
                            Text(if(m.workspace=="config")"远程地址会保存为可更新的配置。" else "订阅地址用于更新一组节点；节点链接（如 ss://、vless://）用于添加单个节点。支持一次粘贴多行。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick={dismiss();m.activity.pickFile()}){Icon(Icons.Outlined.FileOpen,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("从文件选择")}
                        }
                    }
                }
                if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(2.dp))
            }
            Row(Modifier.fillMaxWidth().padding(vertical=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp,Alignment.End)){
                TextButton(onClick=dismiss,enabled=!saving){Text("取消")}
                Button(enabled=!saving,onClick={scope.launch{
                    saving=true;error=""
                    try {
                        when {
                            rename->m.renameProfile(editor.profile!!.getString("id"),name)
                            config->m.writeProfile(editor.profile!!,content)
                            node!=null->{val json=if(native)JSONObject(content) else JSONObject(initial.toString()).apply{fields.forEach{(k,v)->put(k,when(k){"port","alterId"->v.toInt();"tls","insecure"->v.toBoolean();"options"->JSONObject(v);else->v})}};NodeEditing.prepare(node,json);m.writeNode(editor.index,revision,json)}
                            else->{require(content.isNotBlank()){ "请填写地址或内容" };m.importText(content.trim(),name)}
                        };dismiss()
                    }catch(e:CancellationException){throw e}catch(e:Exception){error=ErrorMessage.describe(e);WboxVpnService.log("ERROR 保存配置或节点失败 · $error")}finally{saving=false}
                }}){if(saving){CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp,color=MaterialTheme.colorScheme.onPrimary);Spacer(Modifier.width(8.dp))};Text(if(editor.type=="import")"添加" else "保存")}
            }
        }
    }
}
@Composable private fun Field(label:String,value:String,changed:(String)->Unit,multiline:Boolean=false,secret:Boolean=false,code:Boolean=false) {
    var visible by remember { mutableStateOf(false) }
    val toolbar=remember{SelectionToolbar()}
    DisposableEffect(toolbar){onDispose{toolbar.hide()}}
    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
    OutlinedTextField(value,changed,Modifier.fillMaxWidth(),label={Text(label)},singleLine=!multiline,minLines=if(multiline)4 else 1,maxLines=if(multiline)12 else 1,shape=RoundedCornerShape(12.dp),visualTransformation=if(secret&&!visible)PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon=if(secret){{IconButton(onClick={visible=!visible}){Icon(if(visible)Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(visible)"隐藏密码" else "显示密码")}}}else null,
        textStyle=LocalTextStyle.current.copy(fontSize=14.sp,fontFamily=if(code)FontFamily.Monospace else FontFamily.Default))
        toolbar.Menu()
    }
}
