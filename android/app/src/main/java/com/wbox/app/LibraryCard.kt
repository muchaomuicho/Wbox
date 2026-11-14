@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.wbox.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
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
    menuDescription:String,tag:String,onUpdate:(()->Unit)?=null,modifier:Modifier=Modifier
) {
    var menu by remember{mutableStateOf(false)}
    var relativeExpiry by rememberSaveable(tag){mutableStateOf(false)}
    var relativeUpdate by rememberSaveable(tag){mutableStateOf(true)}
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
    val expire=usage?.takeIf{it.has("expire")}?.optLong("expire")
    val expires=LibraryTimeText.expiry(expire,relativeExpiry)
    // FlClash ProfileItem: compact title/subtitle, trailing update time and selection badge.
    Surface(onClick=onClick,modifier=modifier.fillMaxWidth().testTag(tag),shape=RoundedCornerShape(20.dp),
        color=if(active)MaterialTheme.colorScheme.primaryContainer.copy(alpha=.48f) else MaterialTheme.colorScheme.surfaceVariant,
        border=if(active)BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.45f))else null) {
        Column(Modifier.padding(start=16.dp,end=8.dp,top=10.dp,bottom=14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Text(name,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(active)Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Outlined.Check,"当前配置",Modifier.padding(4.dp).size(16.dp),tint=MaterialTheme.colorScheme.onPrimary)
                }
                if(onUpdate!=null)SubscriptionUpdateButton(updating,"更新订阅 · $name",onUpdate)
                Box{
                    IconButton(onClick={menu=true},modifier=Modifier.size(40.dp).semantics{contentDescription=menuDescription}){Icon(Icons.Outlined.MoreVert,null,Modifier.size(21.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                        PopupWindowBackHandler{menu=false}
                        actions.forEachIndexed{index,action->
                            if(action.destructive&&index>0)HorizontalDivider()
                            DropdownMenuItem(text={Text(action.label,color=if(action.destructive)MaterialTheme.colorScheme.error else Color.Unspecified)},
                                leadingIcon={Icon(action.icon,null,tint=if(action.destructive)MaterialTheme.colorScheme.error else LocalContentColor.current)},
                                enabled=action.enabled,onClick={menu=false;action.run()})
                        }
                    }
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(end=8.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(traffic,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(expires!=null)Text("· $expires",Modifier.testTag("$tag-expiry").clickable(
                    role=Role.Button,onClickLabel=if(relativeExpiry)"显示具体到期日期" else "显示相对到期时间"
                ){relativeExpiry=!relativeExpiry},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(quota)LinearProgressIndicator(progress={(used!!/total!!).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(end=8.dp).height(4.dp).clip(CircleShape),color=MaterialTheme.colorScheme.primary,trackColor=MaterialTheme.colorScheme.primary.copy(alpha=.12f))
            FlowRow(Modifier.fillMaxWidth().padding(end=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(quota)Text("剩余 "+MainActivity.bytes((total!!-used!!).coerceAtLeast(0.0).toLong()),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(LibraryTimeText.update(updated,relativeUpdate),Modifier.testTag("$tag-updated").clickable(
                    role=Role.Button,onClickLabel=if(relativeUpdate)"显示具体更新时间" else "显示相对更新时间"
                ){relativeUpdate=!relativeUpdate},style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable internal fun LibraryToolbarActions(m:MobileModel,config:Boolean) {
    var sorting by remember{mutableStateOf(false)}
    val sort=m.profiles.data.optString(if(config)"config_sort" else "subscription_sort","default")
    val hasSources=if(config)m.configs.any{it.optString("url").isNotEmpty()}else m.profiles.data.optJSONObject("subscriptions")?.length()?.let{it>0}==true
    val updating=if(config)m.updatingAllProfiles else m.updatingAllSubscriptions
    SubscriptionUpdateButton(updating,"更新全部",{if(config)m.updateAllProfiles()else m.refreshSubscriptions()},enabled=hasSources)
    Box{
        IconButton(onClick={sorting=true}){Icon(Icons.Outlined.Sort,"排序订阅")}
        DropdownMenu(sorting,{sorting=false}){
            PopupWindowBackHandler{sorting=false}
            listOf("default" to if(config)"手动顺序" else "默认顺序","name" to "名称","updated" to "最近更新").forEach{(key,label)->
                DropdownMenuItem(text={Text(label)},onClick={sorting=false;m.sortLibrary(config,key)},trailingIcon={if(sort==key)Icon(Icons.Outlined.Check,null)})
            }
        }
    }
}
