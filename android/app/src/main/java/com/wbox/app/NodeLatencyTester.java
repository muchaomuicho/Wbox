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
        run(context,source,names,result,snapshot,true);
    }
    static void run(Context context,Profiles source,List<String> names,BiConsumer<String,String> result,Consumer<JSONObject> snapshot,boolean expandGroup)throws Exception {
        String url=source.data.optString("test_url",Profiles.TEST_URL);validateUrl(source.engine(),url);
        ExecutorService workers=Executors.newFixedThreadPool(source.mode().equals("config")?4:1);
        LatencySession session=null;
        try {
            List<Future<?>> pending=new ArrayList<>();
            if(source.mode().equals("config")) {
                // A selection can reload the VPN while this snapshot keeps testing.
                session=new LatencySession(context,source);
                CoreApi api=session.api;
                JSONObject groups=api.proxies();snapshot.accept(groups);
                Set<String> targets=targets(groups,names,expandGroup);
                for(String target:targets)api.requireUnambiguous(target);
                pending.addAll(submitTargets(workers,targets,result,target->api.delay(target,url)+" ms"));
            } else {
                pending.addAll(submitTargets(workers,names,result,name->{
                    Profiles single=source.copy();single.data.put("selected",name);
                    try(LatencySession probe=new LatencySession(context,single)){return probe.api.delay(BundledRules.nodeTag(name),url)+" ms";}
                }));
            }
            for(Future<?> task:pending)task.get();
        } finally {workers.shutdownNow();try{workers.awaitTermination(9,TimeUnit.SECONDS);}finally{if(session!=null)session.close();}}
    }
    @FunctionalInterface interface Probe {String test(String name)throws Exception;}
    static List<Future<?>> submitTargets(ExecutorService workers,Collection<String> targets,BiConsumer<String,String> result,Probe probe) {
        // Pending covers the whole batch, including targets still waiting for a worker.
        for(String target:targets)result.accept(target,"测试中");
        List<Future<?>> pending=new ArrayList<>();
        for(String target:targets)pending.add(workers.submit(()->{
            if(Thread.currentThread().isInterrupted())return;
            String value;
            try{value=probe.test(target);}
            catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            catch(Exception e){value="Timeout";}
            if(!Thread.currentThread().isInterrupted())result.accept(target,value);
        }));
        return pending;
    }
    static Set<String> targets(JSONObject groups,List<String> names,boolean expandGroup)throws Exception {
        LinkedHashSet<String> targets=new LinkedHashSet<>();
        for(String name:names){
            JSONObject group=groups.optJSONObject(name);
            if(expandGroup&&names.size()==1&&group!=null&&group.optJSONArray("all")!=null){var all=group.getJSONArray("all");for(int i=0;i<all.length();i++)targets.add(ProxyCatalog.resolve(groups,all.getString(i)));}
            else targets.add(ProxyCatalog.resolve(groups,name));
        }
        return targets;
    }
    static void validateUrl(String engine,String url) {
        if(!url.startsWith("https://")&&!url.startsWith("http://"))throw new IllegalArgumentException("测试地址必须是 HTTP / HTTPS");
        if(engine.equals("sing-box")&&!url.startsWith("https://"))throw new IllegalArgumentException("sing-box 的测速 API 会忽略 HTTP 地址，请使用 HTTPS 测试地址。");
    }
    static String resolve(JSONObject groups,String name,Set<String> seen){return ProxyCatalog.resolve(groups,name);}
}
