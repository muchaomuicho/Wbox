@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wbox.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

@Composable internal fun ConfigPreviewScreen(m:MobileModel,profile:JSONObject,dismiss:()->Unit) {
    val foreground=MaterialTheme.colorScheme.onSurface.toArgb()
    val dark=MaterialTheme.colorScheme.surface.red<.5f
    val back=editorPageBack(onLeave=dismiss)
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface){
        Column(Modifier.fillMaxSize().safeDrawingPadding()){
            TopAppBar(title={Text("预览配置")},navigationIcon={IconButton(onClick=back){Icon(Icons.Outlined.ArrowBack,"返回")}},
                actions={IconButton(onClick={m.activity.export(profile)}){Icon(Icons.Outlined.FileUpload,"导出文件")}},
                windowInsets=WindowInsets(0),colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface))
            Text(profile.optString("name"),Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            AndroidView(factory={context->ConfigCodeView(context).apply{setText(profile.optString("content"));keyListener=null;setTextIsSelectable(true);contentDescription="配置预览"}},
                modifier=Modifier.weight(1f).fillMaxWidth().semantics{contentDescription="配置预览"},update={it.theme(dark,foreground)})
        }
    }
}
