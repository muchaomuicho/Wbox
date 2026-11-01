package com.wbox.app;

import android.content.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Emulator-only diagnostic entry point. Never included in release APKs. */
public final class RuntimeProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        PendingResult pending=goAsync();new Thread(()->{try{
            String action=intent.getStringExtra("operation");
            if("fetch".equals(action)) {
                JSONObject result=new JSONObject();
                try {
                    result.put("stage","download");
                    String content=Profiles.fetch(intent.getStringExtra("url"));
                    result.put("stage","parse").put("length",content.length());
                    Profiles copy=new Profiles(context).copy();copy.importConfig(content,null,null,false);
                    result.put("ok",true).put("length",content.length()).put("core",copy.configs().getJSONObject(copy.configs().length()-1).getString("core"));
                }catch(Exception e){StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));result.put("ok",false).put("type",e.getClass().getName()).put("message",ErrorMessage.describe(e)).put("trace",trace.toString());}
                Files.write(new File(context.getFilesDir(),"probe-fetch.json").toPath(),result.toString().getBytes());return;
            }
            if("stop".equals(action)){context.startService(new Intent(context,WboxVpnService.class).setAction(WboxVpnService.STOP));return;}
            if("connect".equals(action)){if(android.net.VpnService.prepare(context)!=null)throw new IllegalStateException("Test VPN approval missing");context.startForegroundService(new Intent(context,WboxVpnService.class));return;}
            if("status".equals(action)){
                JSONObject state=new JSONObject().put("running",WboxVpnService.running).put("starting",WboxVpnService.starting).put("status",WboxVpnService.status).put("error",WboxVpnService.lastError).put("upload",WboxVpnService.upload).put("download",WboxVpnService.download).put("logs",new JSONArray(WboxVpnService.logSnapshot()));
                Files.write(new File(context.getFilesDir(),"probe-state.json").toPath(),state.toString().getBytes());return;
            }
            String core=intent.getStringExtra("engine");if(core==null)core="sing-box";
            String json="{\"log\":{\"level\":\"info\"},\"inbounds\":[{\"type\":\"tun\",\"tag\":\"tun-in\",\"address\":[\"172.18.0.1/30\"],\"auto_route\":true}],\"route\":{\"auto_detect_interface\":true,\"final\":\"Proxy\"},\"outbounds\":[{\"type\":\"selector\",\"tag\":\"Proxy\",\"outbounds\":[\"🇯🇵 Tokyo\",\"🇭🇰 Hong Kong\"]},{\"type\":\"direct\",\"tag\":\"🇯🇵 Tokyo\"},{\"type\":\"direct\",\"tag\":\"🇭🇰 Hong Kong\"},{\"type\":\"selector\",\"tag\":\"Media\",\"outbounds\":[\"🇯🇵 Tokyo\",\"🇭🇰 Hong Kong\"]}]}";
            String yaml="log-level: info\nproxies: []\nproxy-groups:\n - {name: Proxy, type: select, proxies: [DIRECT]}\nrules: ['MATCH,Proxy']\n";
            Profiles p=new Profiles(context);p.data=new JSONObject().put("mode","config").put("theme","dark");p.importConfig(core.equals("sing-box")?new JSONObject(json).toString(2):yaml,null,null);p.setWorkspace("config");p.activeConfig().put("name","我的配置");p.save();
            if("seed".equals(action))return;
            if("original".equals(action)){
                JSONObject compiled=new JSONObject(p.compile("probe"));compiled.getJSONObject("route").put("auto_detect_interface",true);
                File cfg=new File(context.getCacheDir(),"original.json");Files.write(cfg.toPath(),compiled.toString().getBytes());
                Process proc=new ProcessBuilder(new File(context.getApplicationInfo().nativeLibraryDir,"libsingbox.so").getAbsolutePath(),"run","-c",cfg.getAbsolutePath()).redirectErrorStream(true).start();
                proc.waitFor(10,TimeUnit.SECONDS);if(proc.isAlive())proc.destroy();String log=Profiles.readLimited(proc.getInputStream());
                Files.write(new File(context.getFilesDir(),"probe-original.log").toPath(),log.getBytes());
            }else context.startForegroundService(new Intent(context,WboxVpnService.class));
        }catch(Exception e){try{Files.write(new File(context.getFilesDir(),"probe-error.txt").toPath(),e.toString().getBytes());}catch(Exception ignored){}}
        finally{pending.finish();}}).start();
    }
}
