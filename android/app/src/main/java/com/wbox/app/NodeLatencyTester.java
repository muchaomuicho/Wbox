package com.wbox.app;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Runs a temporary node core without establishing a VPN. Always closes its process. */
final class NodeLatencyTester {
    static void run(Context context, Profiles source, List<String> names, BiConsumer<String,String> result)throws Exception {
        java.lang.Process process=null;
        File config=null;
        ExecutorService tests=Executors.newFixedThreadPool(3);
        try {
            String secret=WboxVpnService.secret;
            if(!WboxVpnService.running) {
                if(!source.mode().equals("nodes"))throw new IllegalStateException("请先连接配置工作区，再测试代理组。");
                Profiles p=new Profiles(context);p.data=new JSONObject(source.data.toString());
                secret=UUID.randomUUID().toString();
                File dir=new File(context.getCacheDir(),"latency");dir.mkdirs();
                config=new File(dir,"latency.json");Files.write(config.toPath(),p.compile(secret).getBytes(StandardCharsets.UTF_8));
                File binary=new File(context.getApplicationInfo().nativeLibraryDir,p.engine().equals("sing-box")?"libsingbox.so":"libmihomo.so");
                List<String> command=new ArrayList<>(List.of(binary.getAbsolutePath()));
                if(p.engine().equals("sing-box"))command.addAll(List.of("run","-c",config.getAbsolutePath(),"-D",dir.getAbsolutePath()));else command.addAll(List.of("-f",config.getAbsolutePath(),"-d",dir.getAbsolutePath()));
                ProcessBuilder builder=new ProcessBuilder(command).directory(dir).redirectErrorStream(true).redirectOutput(new File("/dev/null"));
                builder.environment().put("GOMEMLIMIT","96MiB");process=builder.start();
                boolean ready=false;for(int i=0;i<60;i++){if(!process.isAlive())throw new IOException("测速内核启动失败，请检查节点协议。");try{new CoreApi(secret).proxies();ready=true;break;}catch(Exception ignored){Thread.sleep(100);}}
                if(!ready)throw new IOException("测速内核启动超时。");
            }
            CoreApi api=new CoreApi(secret);String url=source.data.optString("test_url",Profiles.TEST_URL);
            List<Future<?>> futures=new ArrayList<>();
            for(String name:names)futures.add(tests.submit(()->{try{result.accept(name,api.delay(name,url)+" ms");}catch(Exception e){result.accept(name,"超时");}}));
            for(Future<?> future:futures)future.get();
        } finally {
            tests.shutdownNow();
            if(process!=null){process.destroy();try{if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException ignored){process.destroyForcibly();Thread.currentThread().interrupt();}}
            if(config!=null)Files.deleteIfExists(config.toPath());
        }
    }
}
