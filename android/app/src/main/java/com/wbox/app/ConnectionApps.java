package com.wbox.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.util.LruCache;
import java.net.*;
import java.util.*;

/** Uses the VPN owner's public Android API, never private /proc or packages.xml. */
final class ConnectionApps {
    private static final LruCache<String,String> owners=new LruCache<>(1024);
    static void clear(){owners.evictAll();}
    static void enrich(Context context,List<ConnectionRecord> rows){
        if(Build.VERSION.SDK_INT<29)return;
        String[] origins;
        try{origins=NativeTunnel.connectionOrigins();}catch(UnsatisfiedLinkError unavailable){return;}
        Map<String,String> packages=new HashMap<>();
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        for(String origin:origins){
            String[] part=origin.split(";",-1);if(part.length!=6)continue;
            String name=owners.get(origin);
            if(name==null){
                name="";
                try{
                    int uid=manager.getConnectionOwnerUid(Integer.parseInt(part[0]),
                        new InetSocketAddress(InetAddress.getByName(part[2]),Integer.parseInt(part[3])),
                        new InetSocketAddress(InetAddress.getByName(part[4]),Integer.parseInt(part[5])));
                    String[] candidates=uid<0?null:context.getPackageManager().getPackagesForUid(uid);
                    // Shared UIDs cannot identify a unique app. Leave their icon generic.
                    if(candidates!=null&&candidates.length==1)name=candidates[0];
                }catch(Exception ignored){}
                owners.put(origin,name);
            }
            packages.put(part[0]+":"+part[1],name);
        }
        for(ConnectionRecord row:rows){
            String port=row.source.substring(row.source.lastIndexOf(':')+1);
            row.packageName=packages.getOrDefault((row.network.equals("UDP")?"17":"6")+":"+port,"");
        }
    }
}
