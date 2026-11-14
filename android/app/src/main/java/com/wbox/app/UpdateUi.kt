package com.wbox.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable internal fun UpdateSettingsRow(m:MobileModel) {
    val update=m.activity.updates
    val state by update.state.collectAsState()
    val active=state.phase in setOf("preparing","downloading","downloaded","verifying")
    ListItem(
        headlineContent={Text(if(active)"查看下载进度" else "检查更新")},
        supportingContent={Text(when(state.phase){
            "downloading"->"${MainActivity.bytes(state.bytes)} / ${MainActivity.bytes(state.total)}"
            "ready","permission","confirmation","installing"->"${state.release?.version} 已下载，点击继续安装"
            "failed"->"上次更新未完成，点击重试"
            "available"->"发现新版本 ${state.release?.version}"
            else->"从 GitHub 官方发布获取更新"
        })},
        trailingContent={Icon(Icons.Outlined.ChevronRight,null)},
        modifier=Modifier.clickable{if(state.phase in setOf("idle","current","available","failed","installed"))update.check()else update.show()},
        colors=ListItemDefaults.colors(containerColor=Color.Transparent)
    )
}

@Composable internal fun AppUpdateDialog(m:MobileModel) {
    val update=m.activity.updates
    val open by update.panelOpen.collectAsState()
    val state by update.state.collectAsState()
    if(!open)return
    val busy=state.phase in setOf("checking","preparing","downloading","downloaded","verifying")
    val progress=if(state.total>0)(state.bytes.toFloat()/state.total).coerceIn(0f,1f)else 0f
    AlertDialog(onDismissRequest=update::hide,
        title={Text(when(state.phase){"available"->"发现新版本";"current"->"已是最新版本";"installed"->"更新完成";"failed"->"更新未完成";else->"应用更新"})},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            state.release?.let{Text("Wbox ${it.version}",style=MaterialTheme.typography.titleMedium)}
            when(state.phase) {
                "checking"->{CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp);Text("正在检查更新")}
                "available"->Text("发现新的正式版本，是否下载更新？安装包约 ${MainActivity.bytes(state.release!!.size)}。")
                "preparing"->{CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp);Text("正在准备下载")}
                "downloading"->{
                    LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth())
                    Text("${String.format(Locale.ROOT,"%.1f",progress*100)}% · ${MainActivity.bytes(state.bytes)} / ${MainActivity.bytes(state.total)}")
                    Text("${MainActivity.bytes(state.speed)}/s",style=MaterialTheme.typography.bodyMedium)
                    Text("收起后继续下载，可从设置重新查看。",style=MaterialTheme.typography.bodySmall)
                }
                "downloaded","verifying"->{CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp);Text("正在验证并准备安装")}
                "ready"->Text("安装包已验证，即将打开系统安装器。")
                "permission"->Text("需要允许 Wbox 安装应用更新。授权后返回即可继续安装。")
                "confirmation","installing"->Text("请在系统安装器中确认更新。未完成时可继续安装或取消。")
                "current"->Text("当前版本无需更新。")
                "installed"->Text("已安装更新，临时安装包已清理。")
            }
            if(state.error.isNotEmpty())Text(state.error,color=if(state.phase=="downloading")MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }},
        confirmButton={TextButton(onClick={when(state.phase){
            "available"->update.download()
            "failed"->if(state.release!=null)update.download()else update.check()
            "permission"->m.activity.requestUpdatePermission()
            "ready","confirmation","installing"->update.continueInstall()
            else->update.hide()
        }}){Text(when(state.phase){"available"->"下载更新";"failed"->"重试";"permission"->"允许安装";"ready","confirmation","installing"->"继续安装";else->if(busy)"收起" else "知道了"})}},
        dismissButton={if(state.phase in setOf("downloading","downloaded","verifying","ready","permission","confirmation","installing")) {
            TextButton(onClick=update::cancel){Text(if(state.phase=="downloading")"取消下载" else "取消更新")}
        }else if(state.phase in setOf("available","failed")){TextButton(onClick=update::hide){Text("稍后")}}}
    )
}
