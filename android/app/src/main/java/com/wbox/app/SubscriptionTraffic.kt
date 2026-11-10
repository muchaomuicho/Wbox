package com.wbox.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable internal fun SubscriptionTraffic(usage:JSONObject?,modifier:Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(usage==null)Text("订阅未提供流量信息",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            val used=if(usage.has("upload")&&usage.has("download"))usage.optLong("upload").toDouble()+usage.optLong("download").toDouble() else null
            val total=usage.optLong("total").takeIf{it>0}
            val expires=when { !usage.has("expire")->"到期时间未提供";usage.optLong("expire")==0L->"长期有效";else->java.text.DateFormat.getDateInstance().format(java.util.Date(usage.optLong("expire")*1000)) }
            if(used!=null&&total!=null)LinearProgressIndicator(progress={(used/total).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
            Text((used?.let{MainActivity.bytes(it.toLong())}?:"已用流量未提供")+" / "+(total?.let{MainActivity.bytes(it)}?:"总量未提供")+" · "+expires,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(used!=null&&total!=null)Text("剩余 "+MainActivity.bytes((total-used).coerceAtLeast(0.0).toLong()),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
