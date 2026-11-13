@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wbox.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class SubscriptionEdit(val id:String="",val url:String,val name:String)
@Composable internal fun SubscriptionEditor(m:MobileModel,value:SubscriptionEdit,onClose:()->Unit) {
    val profile=m.configs.firstOrNull{it.optString("id")==value.id}
    val remote=value.url.isNotEmpty()
    val options=if(remote)SubscriptionUpdates.options(m.profiles,value.id,value.url)else null
    val initialEnabled=remember(value){options?.optBoolean("auto_update")?:false}
    val initialMinutes=remember(value){(options?.optInt("update_minutes",1440)?:1440).toString()}
    var name by remember{mutableStateOf(value.name)}
    var url by remember{mutableStateOf(value.url)}
    var enabled by remember{mutableStateOf(initialEnabled)}
    var minutes by remember{mutableStateOf(initialMinutes)}
    var saving by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var editingConfig by remember{mutableStateOf(false)}
    val updating=m.isUpdating(if(profile!=null)value.id else value.url)
    val scope=rememberCoroutineScope()
    if(editingConfig&&profile!=null){ConfigEditorScreen(m,profile){editingConfig=false};return}
    val changed=name!=value.name||remote&&(url!=value.url||enabled!=initialEnabled||minutes!=initialMinutes)
    val back=editorPageBack(saving,changed,onClose)
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
        Scaffold(modifier=Modifier.safeDrawingPadding().imePadding(),topBar={TopAppBar(title={Text(if(remote)"编辑订阅" else "编辑配置资料")},windowInsets=WindowInsets(0),colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface),navigationIcon={IconButton(enabled=!saving,onClick=back){Icon(Icons.Outlined.ArrowBack,"返回")}})},
            bottomBar={Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.End){Button(enabled=!saving&&name.isNotBlank()&&(!remote||url.isNotBlank()&&minutes.toIntOrNull() in 15..10080),onClick={if(!saving){saving=true;scope.launch{error="";try{if(remote)m.editSubscription(value.id,value.url,name,url,enabled,minutes.toInt())else m.renameProfile(value.id,name);onClose()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"保存失败"}finally{saving=false}}}}){Icon(Icons.Outlined.Save,null);Spacer(Modifier.width(8.dp));Text("保存")}}}){padding->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                OutlinedTextField(name,{name=it.take(100)},Modifier.fillMaxWidth(),enabled=!saving,label={Text("名称")},singleLine=true)
                if(remote){
                OutlinedTextField(url,{url=it},Modifier.fillMaxWidth(),enabled=!saving,label={Text("URL")},minLines=2,maxLines=4,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("自动更新",Modifier.weight(1f));Switch(enabled,{enabled=it},enabled=!saving)}
                OutlinedTextField(minutes,{minutes=it},Modifier.fillMaxWidth(),enabled=enabled&&!saving,label={Text("自动更新间隔（分钟）")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                Text("仅更新此订阅，间隔为 15–10080 分钟。系统会根据网络和省电状态安排后台任务，可能延后。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                SubscriptionTraffic(options?.optJSONObject("usage"))
                }
                HorizontalDivider()
                Text(if(profile!=null)"配置" else "节点订阅",style=MaterialTheme.typography.titleMedium)
                if(profile!=null){
                    Text(profile.optString("core")+" · "+MainActivity.bytes(profile.optString("content").toByteArray().size.toLong()),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        OutlinedButton(enabled=!saving,onClick={editingConfig=true}){Icon(Icons.Outlined.Edit,null);Spacer(Modifier.width(8.dp));Text("编辑配置")}
                        OutlinedButton(enabled=!saving,onClick={m.activity.export(profile)}){Icon(Icons.Outlined.FileUpload,null);Spacer(Modifier.width(8.dp));Text("导出文件")}
                    }
                }else Text("${m.nodes.count{it.source==value.url}} 个节点",style=MaterialTheme.typography.bodySmall)
                if(remote)OutlinedButton(enabled=!updating&&!saving&&url==value.url,onClick={if(profile!=null)m.updateProfile(profile)else m.refreshSubscriptions(value.url)}){
                    if(updating)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Icon(Icons.Outlined.Sync,null)
                    Spacer(Modifier.width(8.dp));Text("立即更新")
                }
                val last=options?.optLong("last_success")?:0
                if(last>0)Text("最近更新："+java.text.DateFormat.getDateTimeInstance().format(java.util.Date(last)),style=MaterialTheme.typography.bodySmall)
                val status=error.ifEmpty{options?.optString("error").orEmpty()}
                if(status.isNotEmpty())Text(status,color=MaterialTheme.colorScheme.error)
                TextButton(enabled=!saving,onClick={if(profile!=null)m.removeProfile(value.id)else m.removeSubscription(value.url);onClose()},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Icon(Icons.Outlined.DeleteOutline,null);Spacer(Modifier.width(8.dp));Text(if(profile!=null)"移除配置" else "移除订阅")}
            }
        }
    }
}
