package com.wbox.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

open class UpdateReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action!=AppUpdate.INSTALL_STATUS&&intent.action!=Intent.ACTION_MY_PACKAGE_REPLACED&&intent.action!=android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)return
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch {try{AppUpdate.get(context).receive(intent)}finally{pending.finish()}}
    }
}

class UpdateDownloadReceiver:UpdateReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        // Downloads can originate from the system's separate provider UID.
        if(intent.action==android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)super.onReceive(context,intent)
    }
}
