package com.wbox.app;

import android.content.Context;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

final class NodeLatencyTester {
    static void run(Context context,Profiles source,List<String> names,BiConsumer<String,String> result)throws Exception {
        run(context,source,names,result,snapshot->{});
    }
    static void run(Context context,Profiles source,List<String> names,BiConsumer<String,String> result,Consumer<JSONObject> snapshot)throws Exception {
        String url=source.data.optString("test_url",Profiles.TEST_URL);validateUrl(source.engine(),url);
        ExecutorService workers=Executors.newFixedThreadPool(source.mode().equals("config")?4:1);
        LatencySession session=null;
        try {
            List<Future<?>> pending=new ArrayList<>();
            if(source.mode().equals("config")) {
                boolean running=WboxVpnService.running;String secret=WboxVpnService.secret;
                if(!running)session=new LatencySession(context,source);
                CoreApi api=running?new CoreApi(secret):session.api;
                JSONObject groups=api.proxies();snapshot.accept(groups);
                LinkedHashSet<String> targets=new LinkedHashSet<>();
                for(String name:names){
                    JSONObject group=groups.optJSONObject(name);
                    if(names.size()==1&&group!=null&&group.optJSONArray("all")!=null){var all=group.getJSONArray("all");for(int i=0;i<all.length();i++)targets.add(ProxyCatalog.resolve(groups,all.getString(i)));}
                    else targets.add(ProxyCatalog.resolve(groups,name));
                }
                for(String target:targets)pending.add(workers.submit(()->{
                    if(Thread.currentThread().isInterrupted())return;
                    if(running&&(!WboxVpnService.running||!secret.equals(WboxVpnService.secret)))return;
                    result.accept(target,"测试中");String value;
                    try{value=api.delay(target,url)+" ms";}catch(Exception e){value="Timeout";}
                    if(!Thread.currentThread().isInterrupted()&&(!running||(WboxVpnService.running&&secret.equals(WboxVpnService.secret))))result.accept(target,value);
                }));
            } else {
                for(String name:names)pending.add(workers.submit(()->{
                    if(Thread.currentThread().isInterrupted())return;
                    result.accept(name,"测试中");String value;
                    try{Profiles single=source.copy();single.data.put("selected",name);try(LatencySession probe=new LatencySession(context,single)){value=probe.api.delay(BundledRules.nodeTag(name),url)+" ms";}}
                    catch(InterruptedException e){Thread.currentThread().interrupt();return;}catch(Exception e){value="Timeout";}
                    if(!Thread.currentThread().isInterrupted())result.accept(name,value);
                }));
            }
            for(Future<?> task:pending)task.get();
        } finally {workers.shutdownNow();try{workers.awaitTermination(9,TimeUnit.SECONDS);}finally{if(session!=null)session.close();}}
    }
    static void validateUrl(String engine,String url) {
        if(!url.startsWith("https://")&&!url.startsWith("http://"))throw new IllegalArgumentException("测试地址必须是 HTTP / HTTPS");
        if(engine.equals("sing-box")&&!url.startsWith("https://"))throw new IllegalArgumentException("sing-box 的测速 API 会忽略 HTTP 地址，请使用 HTTPS 测试地址。");
    }
    static String resolve(JSONObject groups,String name,Set<String> seen){return ProxyCatalog.resolve(groups,name);}
}
