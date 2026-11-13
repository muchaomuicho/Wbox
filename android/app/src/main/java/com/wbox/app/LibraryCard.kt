@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wbox.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal data class LibraryAction(val label:String,val icon:ImageVector,val enabled:Boolean=true,val destructive:Boolean=false,val run:()->Unit)

@Composable internal fun LibraryCard(
    name:String,subtitle:String,updated:Long,usage:JSONObject?,remote:Boolean,
    active:Boolean=false,updating:Boolean=false,onClick:()->Unit,actions:List<LibraryAction>,
    menuDescription:String,tag:String
) {
    var menu by remember{mutableStateOf(false)}
    Surface(onClick=onClick,modifier=Modifier.fillMaxWidth().testTag(tag),shape=RoundedCornerShape(24.dp),
        color=if(active)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border=BorderStroke(1.dp,if(active)MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(start=20.dp,end=14.dp,top=14.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                Text(name,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(active)Icon(Icons.Outlined.CheckCircle,"当前配置",Modifier.padding(start=8.dp).size(20.dp))
                Box{
                    IconButton(onClick={menu=true},modifier=Modifier.semantics{contentDescription=menuDescription}){if(updating)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Icon(Icons.Outlined.MoreVert,null)}
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
            Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(remote)SubscriptionTraffic(usage)
            Text(if(updated>0)"更新于 "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(java.util.Date(updated))else "尚未更新",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
