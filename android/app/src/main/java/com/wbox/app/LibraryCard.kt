@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.wbox.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

internal data class LibraryAction(val label:String,val icon:ImageVector,val enabled:Boolean=true,val destructive:Boolean=false,val run:()->Unit)

@Composable internal fun LibraryCard(
    name:String,subtitle:String,updated:Long,usage:JSONObject?,remote:Boolean,
    active:Boolean=false,updating:Boolean=false,onClick:()->Unit,actions:List<LibraryAction>,
    menuDescription:String,tag:String
) {
    var menu by remember{mutableStateOf(false)}
    val used=usage?.takeIf{it.has("upload")&&it.has("download")}?.let{it.optLong("upload").toDouble()+it.optLong("download").toDouble()}
    val total=usage?.optLong("total")?.takeIf{it>0}
    val quota=remote&&used!=null&&total!=null
    val traffic=when {
        !remote->subtitle
        quota->MainActivity.bytes(used!!.toLong())+" / "+MainActivity.bytes(total!!)
        used!=null->"已用 "+MainActivity.bytes(used.toLong())+" · 总量未提供"
        total!=null->"已用未提供 · 总量 "+MainActivity.bytes(total)
        else->"未提供流量信息"
    }
    val largeText=LocalDensity.current.fontScale>1.15f
    val expire=usage?.takeIf{it.has("expire")}?.optLong("expire")
    val expires=expire?.let { if(it==0L)"长期有效" else {
        val remaining=it*1000.0-System.currentTimeMillis()
        val days=kotlin.math.ceil(remaining/86400000.0).toLong()
        if(remaining<=0)"已到期" else if(days==1L)"1 天内到期" else "${days} 天后到期"
    } }
    // FlClash ProfileItem: compact title/subtitle, trailing update time and selection badge.
    Surface(onClick=onClick,modifier=Modifier.fillMaxWidth().testTag(tag),shape=RoundedCornerShape(20.dp),
        color=if(active)MaterialTheme.colorScheme.primaryContainer.copy(alpha=.48f) else MaterialTheme.colorScheme.surfaceVariant,
        border=if(active)BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.45f))else null) {
        Column(Modifier.padding(start=16.dp,end=8.dp,top=10.dp,bottom=14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(traffic,
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(!largeText)Text(compactUpdateTime(updated),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(active)Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Outlined.Check,"当前配置",Modifier.padding(4.dp).size(16.dp),tint=MaterialTheme.colorScheme.onPrimary)
                }
                Box{
                    IconButton(onClick={menu=true},modifier=Modifier.size(40.dp).semantics{contentDescription=menuDescription}){if(updating)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Icon(Icons.Outlined.MoreVert,null,Modifier.size(21.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                        actions.forEachIndexed{index,action->
                            if(action.destructive&&index>0)HorizontalDivider()
                            DropdownMenuItem(text={Text(action.label,color=if(action.destructive)MaterialTheme.colorScheme.error else Color.Unspecified)},
                                leadingIcon={Icon(action.icon,null,tint=if(action.destructive)MaterialTheme.colorScheme.error else LocalContentColor.current)},
                                enabled=action.enabled,onClick={menu=false;action.run()})
                        }
                    }
                }
            }
            if(quota)LinearProgressIndicator(progress={(used!!/total!!).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(end=8.dp).height(4.dp).clip(CircleShape),color=MaterialTheme.colorScheme.primary,trackColor=MaterialTheme.colorScheme.primary.copy(alpha=.12f))
            if(quota||expires!=null||largeText)FlowRow(Modifier.fillMaxWidth().padding(end=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(quota)Text("剩余 "+MainActivity.bytes((total!!-used!!).coerceAtLeast(0.0).toLong()),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(expires!=null)Surface(shape=RoundedCornerShape(6.dp),color=MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(expires,Modifier.padding(horizontal=7.dp,vertical=3.dp),fontSize=13.sp,color=MaterialTheme.colorScheme.onTertiaryContainer)
                }
                if(largeText)Text("更新于 "+compactUpdateTime(updated),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun compactUpdateTime(updated:Long):String {
    if(updated<=0)return "未更新"
    val elapsed=(System.currentTimeMillis()-updated).coerceAtLeast(0)
    return when {
        elapsed<60_000->"刚刚"
        elapsed<3_600_000->"${elapsed/60_000} 分钟前"
        elapsed<86_400_000->"${elapsed/3_600_000} 小时前"
        elapsed<604_800_000->"${elapsed/86_400_000} 天前"
        else->java.text.SimpleDateFormat("M/d",java.util.Locale.getDefault()).format(java.util.Date(updated))
    }
}

@Composable internal fun LibraryToolbarActions(m:MobileModel,config:Boolean) {
    var sorting by remember{mutableStateOf(false)}
    val sort=m.profiles.data.optString(if(config)"config_sort" else "subscription_sort","default")
    val hasSources=if(config)m.configs.any{it.optString("url").isNotEmpty()}else m.profiles.data.optJSONObject("subscriptions")?.length()?.let{it>0}==true
    val updating=if(config)m.configs.any{m.isUpdating(it.optString("id"))}else m.updatingSources.any{it.startsWith("http")}
    IconButton(enabled=hasSources&&!updating,onClick={if(config)m.updateAllProfiles()else m.refreshSubscriptions()},modifier=Modifier.semantics{contentDescription="更新全部"}){
        if(updating)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Icon(Icons.Outlined.Sync,null)
    }
    Box{
        IconButton(onClick={sorting=true}){Icon(Icons.Outlined.Sort,"排序订阅")}
        DropdownMenu(sorting,{sorting=false}){
            listOf("default" to "默认顺序","name" to "名称","updated" to "最近更新").forEach{(key,label)->
                DropdownMenuItem(text={Text(label)},onClick={sorting=false;m.sortLibrary(config,key)},trailingIcon={if(sort==key)Icon(Icons.Outlined.Check,null)})
            }
        }
    }
}
