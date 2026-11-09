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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

internal data class SubscriptionEdit(val id:String="",val url:String,val name:String)
@Composable internal fun SubscriptionEditor(m:MobileModel,value:SubscriptionEdit,onClose:()->Unit) {
    val options=remember(value){SubscriptionUpdates.options(m.profiles,value.id,value.url)}
    var name by remember{mutableStateOf(value.name)}
    var url by remember{mutableStateOf(value.url)}
    var enabled by remember{mutableStateOf(options?.optBoolean("auto_update")?:false)}
    var minutes by remember{mutableStateOf((options?.optInt("update_minutes",1440)?:1440).toString())}
    var saving by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()
    Dialog(onDismissRequest={if(!saving)onClose()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
            Scaffold(topBar={TopAppBar(title={Text("编辑订阅")},navigationIcon={IconButton(onClick={if(!saving)onClose()}){Icon(Icons.Outlined.ArrowBack,"返回")}})},
                bottomBar={Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),horizontalArrangement=Arrangement.End){Button(enabled=!saving&&name.isNotBlank()&&url.isNotBlank()&&minutes.toIntOrNull() in 15..10080,onClick={scope.launch{saving=true;error="";try{m.editSubscription(value.id,value.url,name,url,enabled,minutes.toInt());onClose()}catch(e:Exception){error=e.message?:"保存失败"}finally{saving=false}}}){Icon(Icons.Outlined.Save,null);Spacer(Modifier.width(8.dp));Text("保存")}}}){padding->
                Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    OutlinedTextField(name,{name=it.take(100)},Modifier.fillMaxWidth(),label={Text("名称")},singleLine=true)
                    OutlinedTextField(url,{url=it},Modifier.fillMaxWidth(),label={Text("URL")},minLines=2,maxLines=4,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("自动更新",Modifier.weight(1f));Switch(enabled,{enabled=it})}
                    OutlinedTextField(minutes,{minutes=it},Modifier.fillMaxWidth(),label={Text("自动更新间隔（分钟）")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                    Text("仅更新此订阅，间隔为 15–10080 分钟。系统会根据网络和省电状态安排后台任务，可能延后。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    val last=options?.optLong("last_success")?:0
                    if(last>0)Text("最近更新："+java.text.DateFormat.getDateTimeInstance().format(java.util.Date(last)),style=MaterialTheme.typography.bodySmall)
                    val status=error.ifEmpty{options?.optString("error").orEmpty()}
                    if(status.isNotEmpty())Text(status,color=MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
