@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class,androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.wbox.app

// Compose adaptation of FlClash's profiles.dart / add.dart at
// be84736605527034689c95bf27828ff27d912c5e (GPL-3.0), modified for Wbox on 2026-11-13.
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable internal fun ProfilesScreen(
    m:MobileModel, config:Boolean, edit:(Editor)->Unit,
    editSubscription:(SubscriptionEdit)->Unit, preview:(JSONObject)->Unit,
    showNodes:(String)->Unit, add:()->Unit
) {
    var removing by remember { mutableStateOf<Pair<String,()->Unit>?>(null) }
    val storedProfiles=m.configs
    val urls=m.profiles.data.optJSONObject("subscriptions")?.keys()?.asSequence()?.toList().orEmpty()
    val subscriptions=when(m.profiles.data.optString("subscription_sort")) {
        "name"->urls.sortedBy{SubscriptionUpdates.name(m.profiles,it).lowercase()}
        "updated"->urls.sortedByDescending{SubscriptionUpdates.options(m.profiles,"",it)?.optLong("last_success")?:0}
        else->urls
    }
    if(if(config)storedProfiles.isEmpty() else subscriptions.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp),contentAlignment=Alignment.Center) {
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Outlined.FolderOpen,null,Modifier.padding(22.dp).size(40.dp),tint=MaterialTheme.colorScheme.primary)
                }
                Text(if(config)"还没有配置" else "还没有订阅",style=MaterialTheme.typography.titleLarge)
                Text(if(config)"从链接或文件导入，即可开始连接。" else "添加订阅，集中管理和更新节点。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick=add) { Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));Text(if(config)"添加配置" else "添加订阅") }
            }
        }
    } else BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns=((maxWidth.value-32f+8f)/(300f+8f)).toInt().coerceIn(1,3)
        val grid=rememberLazyGridState()
        val keys=storedProfiles.map{it.getString("id")}
        val reorder=rememberProfileCardReorder(grid,keys,m::reorderProfiles)
        val byId=storedProfiles.associateBy{it.getString("id")}
        val profiles=reorder.displayedKeys(keys).mapNotNull{byId[it]}
        LazyVerticalGrid(columns=GridCells.Fixed(columns),state=grid,userScrollEnabled=!config||reorder.dragging==null,
            modifier=Modifier.fillMaxSize().testTag("profiles-grid").then(if(config)Modifier.reorderProfileCards(reorder)else Modifier),
            contentPadding=PaddingValues(start=16.dp,end=16.dp,top=12.dp,bottom=if(!m.runtime.running&&!m.runtime.stopping)96.dp else 20.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(config) items(profiles,key={it.getString("id")}) { profile ->
                val id=profile.getString("id");val url=profile.optString("url");val name=profile.optString("name")
                LibraryCard(name,profile.optString("core")+" · "+if(url.isEmpty())"本地配置" else "远程配置",
                    profile.optLong("updated"),profile.optJSONObject("subscription")?.optJSONObject("usage"),url.isNotEmpty(),
                    active=id==m.profiles.data.optString("active_config"),updating=m.isUpdating(id),onClick={if(reorder.dragging==null)m.selectProfile(id)},
                    onUpdate=if(url.isNotEmpty())({m.updateProfile(profile)})else null,
                    actions=buildList {
                        add(LibraryAction("编辑",Icons.Outlined.Edit){edit(Editor("config",profile))})
                        add(LibraryAction("预览",Icons.Outlined.Visibility){preview(profile)})
                        add(LibraryAction("更多",Icons.Outlined.Tune){editSubscription(SubscriptionEdit(id,url,name))})
                        add(LibraryAction("导出文件",Icons.Outlined.FileUpload){m.activity.export(profile)})
                        add(LibraryAction("删除",Icons.Outlined.DeleteOutline,destructive=true){removing=name to {m.removeProfile(id)}})
                    },menuDescription="配置操作",tag="profile-$id",
                    modifier=(if(reorder.dragging==id)Modifier else Modifier.animateItemPlacement()).then(reorder.cardModifier(id)))
            } else items(subscriptions,key={it}) { url ->
                val name=SubscriptionUpdates.name(m.profiles,url);val options=SubscriptionUpdates.options(m.profiles,"",url)
                val open={editSubscription(SubscriptionEdit(url=url,name=name))}
                LibraryCard(name,"${m.nodes.count{it.source==url}} 个节点 · "+if(options?.optBoolean("auto_update")==true)"自动更新" else "手动更新",
                    options?.optLong("last_success")?:0,options?.optJSONObject("usage"),true,
                    updating=m.isUpdating(url),onClick=open,onUpdate={m.refreshSubscriptions(url)},actions=listOf(
                        LibraryAction("编辑",Icons.Outlined.Edit,run=open),
                        LibraryAction("预览",Icons.Outlined.Visibility){showNodes(url)},
                        LibraryAction("更多",Icons.Outlined.Tune,run=open),
                        LibraryAction("复制订阅地址",Icons.Outlined.ContentCopy){
                            (m.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("订阅地址",url));m.message="订阅地址已复制"
                        },
                        LibraryAction("删除",Icons.Outlined.DeleteOutline,destructive=true){removing=name to {m.removeSubscription(url)}}
                    ),menuDescription="订阅操作",tag="subscription-$url")
            }
        }
    }
    removing?.let{(name,remove)->AlertDialog(onDismissRequest={removing=null},title={Text(if(config)"删除配置" else "删除订阅")},text={Text(name)},
        confirmButton={TextButton(onClick={removing=null;remove()},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("删除")}},
        dismissButton={TextButton(onClick={removing=null}){Text("取消")}})}
}

@Composable internal fun ProfileImportMenu(m:MobileModel,edit:(Editor)->Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick={open=true}) { Icon(Icons.Outlined.AddCircleOutline,if(m.workspace=="config")"添加配置" else "添加节点或订阅",tint=MaterialTheme.colorScheme.primary) }
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            PopupWindowBackHandler{open=false}
            DropdownMenuItem(text={Text("从链接或文本导入")},leadingIcon={Icon(Icons.Outlined.Link,null)},onClick={open=false;edit(Editor("import"))})
            DropdownMenuItem(text={Text("从文件导入")},leadingIcon={Icon(Icons.Outlined.FileOpen,null)},onClick={open=false;m.activity.pickFile()})
            DropdownMenuItem(text={Text("从剪贴板导入")},leadingIcon={Icon(Icons.Outlined.ContentPaste,null)},onClick={open=false;m.paste()})
        }
    }
}
