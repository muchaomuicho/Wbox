@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.ExperimentalFoundationApi::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.wbox.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun ProxiesPage(m:MobileModel) {
    val groups=remember(m.groups,m.groupOrder){ProxyCatalog.ordered(m.groups,m.groupOrder)}
    val active=m.activeGroup.takeIf{it in groups}?:groups.firstOrNull().orEmpty()
    LaunchedEffect(active){m.activeGroup=active}
    var choosing by remember{mutableStateOf(false)}
    var searching by remember{mutableStateOf(false)}
    var query by remember(active){mutableStateOf("")}
    var fullName by remember{mutableStateOf<String?>(null)}
    val list=rememberLazyListState()
    LaunchedEffect(active,query){list.scrollToItem(0)}
    if(groups.isEmpty()){
        Box(Modifier.fillMaxSize().padding(24.dp),contentAlignment=Alignment.Center){
            if(m.groupsLoading)CircularProgressIndicator()else Text(m.groupsError.ifEmpty{"添加并选择配置后，代理组会显示在这里。无需连接即可测试延迟。"},color=MaterialTheme.colorScheme.onSurfaceVariant)
        };return
    }
    val group=m.groups.getJSONObject(active)
    val nodes=group.getJSONArray("all").strings().filter{it.contains(query,true)}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            ScrollableTabRow(selectedTabIndex=groups.indexOf(active),modifier=Modifier.weight(1f),edgePadding=16.dp,containerColor=MaterialTheme.colorScheme.surface){
                groups.forEach{tag->Tab(selected=active==tag,onClick={m.activeGroup=tag},text={Text(tag,maxLines=1)})}
            }
            IconButton(onClick={choosing=true}){Icon(Icons.Outlined.ExpandMore,"选择代理组")}
        }
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically){
            Text(group.optString("type")+" · "+group.getJSONArray("all").length()+" 个节点"+if(m.runtime.running)"" else " · 未连接",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick={searching=!searching;if(!searching)query=""}){Icon(Icons.Outlined.Search,"搜索代理")}
        }
        if(searching)OutlinedTextField(value=query,onValueChange={query=it},placeholder={Text("搜索节点")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp))
        LazyColumn(state=list,modifier=Modifier.weight(1f).testTag("proxy-groups"),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=4.dp,bottom=100.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            items(count=(nodes.size+1)/2,key={"$active:$it"}){index->
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    nodes.subList(index*2,minOf(index*2+2,nodes.size)).forEach{name->
                        val selected=group.optString("now")==name
                        val leaf=try{ProxyCatalog.resolve(m.groups,name)}catch(_:Exception){name}
                        val value=m.delays[leaf]?:"未测试"
                        Surface(modifier=Modifier.weight(1f),shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),color=if(selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant){
                            Column(Modifier.fillMaxWidth().combinedClickable(onClick={m.selectNode(name,active)},onLongClick={fullName=name}).padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                                Text(name,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium,minLines=2,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(proxyTypeLabel(m.groups.optJSONObject(name)?.optString("type").orEmpty(),m.groups.optJSONObject(name)?.has("all")==true),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                NodeDelay(name,value,!m.testing&&!m.transition,{m.latency(listOf(name),expandGroup=false)},Modifier.fillMaxWidth())
                            }
                        }
                    }
                    if(index*2+1>=nodes.size)Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    if(choosing)ModalBottomSheet(onDismissRequest={choosing=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Box(Modifier.fillMaxWidth().padding(horizontal=16.dp)){
            Text("策略组",Modifier.align(Alignment.Center),style=MaterialTheme.typography.titleMedium)
            FilledTonalIconButton(onClick={choosing=false},modifier=Modifier.align(Alignment.CenterEnd)){Icon(Icons.Outlined.Close,"关闭策略组")}
        }
        FlowRow(Modifier.fillMaxWidth().heightIn(max=420.dp).verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterHorizontally),verticalArrangement=Arrangement.spacedBy(4.dp)){
            groups.forEach{tag->FilterChip(selected=tag==active,onClick={m.activeGroup=tag;choosing=false},label={Text(tag,style=MaterialTheme.typography.bodyMedium)},shape=RoundedCornerShape(12.dp))}
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
    fullName?.let{name->AlertDialog(onDismissRequest={fullName=null},title={Text("节点名称")},text={SelectionContainer{Text(name,Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()))}},confirmButton={TextButton(onClick={fullName=null}){Text("关闭")}})}
}
