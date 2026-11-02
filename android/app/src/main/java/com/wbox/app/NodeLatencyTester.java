package com.wbox.app;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Each library node is measured in isolation without changing the active VPN. */
final class NodeLatencyTester {
    static void run(Context context,Profiles source,List<String> names,BiConsumer<String,String> result)throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(4);
        Semaphore starts=new Semaphore(1);
        try {
            List<Future<?>> pending=new ArrayList<>();
            if(source.mode().equals("config")) {
                if(!WboxVpnService.running)throw new IllegalStateException("请先连接配置工作区，再测试代理组。");
                CoreApi api=new CoreApi(WboxVpnService.secret);JSONObject groups=api.proxies();
                Map<String,List<String>> targets=new LinkedHashMap<>();
                for(String name:names)targets.computeIfAbsent(resolve(groups,name,new HashSet<>()),k->new ArrayList<>()).add(name);
                for(var entry:targets.entrySet())pending.add(workers.submit(()->{String value;try{value=api.delay(entry.getKey(),source.data.optString("test_url",Profiles.TEST_URL))+" ms";}catch(Exception e){value="失败";}for(String name:entry.getValue())result.accept(name,value);}));
            } else {
                for(String name:names)pending.add(workers.submit(()->{String value;try{value=measure(context,source,name,starts)+" ms";}catch(InterruptedException e){Thread.currentThread().interrupt();return;}catch(Exception e){value="失败";}result.accept(name,value);}));
            }
            for(Future<?> task:pending)task.get();
        } finally {workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS);}
    }
    private static long measure(Context context,Profiles source,String name,Semaphore starts)throws Exception {
        Process process=null;File dir=null;CoreApi api;
        try {
            starts.acquire();
            try {
                Profiles p=source.copy();p.data.put("selected",name);
                String secret=UUID.randomUUID().toString();
                int mixed,controller;try(ServerSocket one=new ServerSocket(0);ServerSocket two=new ServerSocket(0)){mixed=one.getLocalPort();controller=two.getLocalPort();}
                dir=new File(context.getCacheDir(),"latency/"+UUID.randomUUID());dir.mkdirs();
                File config=new File(dir,"config.json");String compiled=p.compile(secret,mixed,controller,true,dir);Files.write(config.toPath(),compiled.getBytes(StandardCharsets.UTF_8));
                if(p.engine().equals("mihomo"))Geodata.prepare(dir,Profiles.yamlMap(compiled),context.getAssets()::open,message->{});
                File binary=new File(context.getApplicationInfo().nativeLibraryDir,p.engine().equals("sing-box")?"libsingbox.so":"libmihomo.so");
                List<String> command=new ArrayList<>(List.of(binary.getAbsolutePath()));
                if(p.engine().equals("sing-box"))command.addAll(List.of("run","-c",config.getAbsolutePath(),"-D",dir.getAbsolutePath()));else command.addAll(List.of("-f",config.getAbsolutePath(),"-d",dir.getAbsolutePath()));
                ProcessBuilder builder=new ProcessBuilder(command).directory(dir).redirectErrorStream(true).redirectOutput(new File("/dev/null"));
                builder.environment().put("GOMEMLIMIT","48MiB");process=builder.start();api=new CoreApi(secret,controller);
                List<String> requiredRules=CoreApi.requiredRules(p.engine(),compiled);boolean ready=false;for(int i=0;i<60;i++){if(!process.isAlive())throw new IOException("测速内核启动失败");try{api.checkReady(1000,mixed,requiredRules);ready=true;break;}catch(IOException ignored){Thread.sleep(100);}}
                if(!ready)throw new IOException("测速内核启动超时");
            } finally {starts.release();}
            return api.delay(BundledRules.nodeTag(name),source.data.optString("test_url",Profiles.TEST_URL));
        } finally {
            if(process!=null){process.destroy();try{if(!process.waitFor(1,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException e){process.destroyForcibly();Thread.currentThread().interrupt();}}
            if(dir!=null){File[] files=dir.listFiles();if(files!=null)try(var paths=Files.walk(dir.toPath())){paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}}
        }
    }
    static String resolve(JSONObject groups,String name,Set<String> seen){if(!seen.add(name))return name;JSONObject group=groups.optJSONObject(name);String selected=group==null?"":group.optString("now");return selected.isEmpty()?name:resolve(groups,selected,seen);}
}
