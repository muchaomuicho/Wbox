@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wbox.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable internal fun ConfigEditorScreen(m:MobileModel,profile:JSONObject,dismiss:()->Unit) {
    var content by remember {mutableStateOf(profile.optString("content"))}
    var error by remember {mutableStateOf("")}
    var saving by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val foreground=MaterialTheme.colorScheme.onSurface.toArgb()
    val dark=MaterialTheme.colorScheme.surface.red<.5f
    Dialog(onDismissRequest={if(!saving)dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                TopAppBar(title={Text("编辑配置",style=MaterialTheme.typography.titleLarge)},navigationIcon={IconButton(onClick={if(!saving)dismiss()}){Icon(Icons.Outlined.ArrowBack,"返回")}},actions={TextButton(enabled=!saving,onClick={scope.launch{saving=true;error="";try{m.writeProfile(profile,content);dismiss()}catch(e:Exception){error=ErrorMessage.describe(e)}finally{saving=false}}}){Text(if(saving)"保存中…" else "保存")}})
                Text(profile.optString("name"),Modifier.padding(horizontal=16.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(error.isNotEmpty())Text(error,Modifier.padding(16.dp),color=MaterialTheme.colorScheme.error)
                HorizontalDivider()
                AndroidView(factory={context->ConfigCodeView(context).apply{setText(content);contentDescription="配置内容";onChange=java.util.function.Consumer {content=it}}},modifier=Modifier.weight(1f).fillMaxWidth(),update={view->if(view.text.toString()!=content)view.setText(content);view.theme(dark,foreground)})
            }
        }
    }
}
