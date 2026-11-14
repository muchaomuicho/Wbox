package com.wbox.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal data class ConnectionAppIcon(val bitmap:Bitmap,val label:String)
private val icons=LruCache<String,ConnectionAppIcon>(64)
private val iconLoads=Semaphore(4)
internal fun trimConnectionIcons(level:Int) {
    if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)icons.evictAll()
}
private fun loadIcon(context:Context,name:String):ConnectionAppIcon? {
    icons.get(name)?.let{return it}
    return try {
        val pm=context.packageManager;val app=pm.getApplicationInfo(name,0)
        val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        app.loadIcon(pm).apply{setBounds(0,0,128,128);draw(Canvas(bitmap))}
        ConnectionAppIcon(bitmap,app.loadLabel(pm).toString()).also{icons.put(name,it)}
    }catch(_:Exception){null}
}
/** Icons load only for composed rows, with four workers and a bounded 4 MiB bitmap cache. */
@Composable internal fun ConnectionIcon(packageName:String,modifier:Modifier=Modifier,size:Dp=30.dp,description:String?=null){
    val context=LocalContext.current.applicationContext
    val app by produceState<ConnectionAppIcon?>(null,packageName){value=if(packageName.isEmpty())null else iconLoads.withPermit{withContext(Dispatchers.IO){loadIcon(context,packageName)}}}
    val info=app
    if(info==null)Icon(Icons.Outlined.Apps,description?:"未识别应用",modifier.size(size))
    else Image(info.bitmap.asImageBitmap(),description?:info.label,modifier.size(size))
}
