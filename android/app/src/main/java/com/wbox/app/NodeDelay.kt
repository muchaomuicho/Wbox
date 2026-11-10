package com.wbox.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.util.Locale

internal fun proxyTypeLabel(type:String,isGroup:Boolean=false)=when {
    isGroup->type.replaceFirstChar{it.titlecase(Locale.ROOT)}
    type.equals("direct",ignoreCase=true)->"Direct"
    else->type.uppercase(Locale.ROOT)
}

@Composable internal fun NodeDelay(name:String,value:String?,enabled:Boolean,onTest:()->Unit,modifier:Modifier=Modifier) {
    val testing=value=="测试中"
    val number=value?.substringBefore(" ")?.toIntOrNull()
    val color=when {
        value=="Timeout"||value=="失败"->MaterialTheme.colorScheme.error
        number==null->MaterialTheme.colorScheme.onSurfaceVariant
        number<800->Color(0xff539675)
        number<1500->Color(0xffbd9747)
        else->Color(0xffce6e63)
    }
    Box(modifier.height(24.dp).widthIn(min=48.dp).clickable(enabled=enabled,role=Role.Button,onClick=onTest)
        .semantics(mergeDescendants=true){contentDescription="测试节点 $name 的延迟";stateDescription=if(testing)"正在测速" else value?:"未测试"},contentAlignment=Alignment.CenterStart) {
        when {
            testing->CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
            value==null||value=="未测试"->Icon(Icons.Outlined.Bolt,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary)
            else->Text(value,color=color,style=MaterialTheme.typography.labelMedium)
        }
    }
}
