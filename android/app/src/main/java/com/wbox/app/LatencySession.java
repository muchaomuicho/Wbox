package com.wbox.app;

import android.content.Context;
import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** A loopback-only core; it never establishes a VPN or changes system proxy state. */
final class LatencySession implements AutoCloseable {
    Process process;
    File directory;
    CoreApi api;
    LatencySession(Context context,Profiles source)throws Exception {
        try {
            String secret=UUID.randomUUID().toString();int mixed,controller;
            try(ServerSocket one=new ServerSocket(0);ServerSocket two=new ServerSocket(0)){mixed=one.getLocalPort();controller=two.getLocalPort();}
            directory=new File(context.getCacheDir(),"latency/"+UUID.randomUUID());directory.mkdirs();
            File config=new File(directory,"config.json");String compiled=source.compile(secret,mixed,controller,true,directory);
            Files.write(config.toPath(),compiled.getBytes(StandardCharsets.UTF_8));
            if(source.engine().equals("mihomo"))Geodata.prepareRequired(directory,Profiles.yamlMap(compiled),Geodata.assets(context),message->{});
            File binary=new File(context.getApplicationInfo().nativeLibraryDir,source.engine().equals("sing-box")?"libsingbox.so":"libmihomo.so");
            List<String> command=new ArrayList<>(List.of(binary.getAbsolutePath()));
            if(source.engine().equals("sing-box"))command.addAll(List.of("run","-c",config.getAbsolutePath(),"-D",directory.getAbsolutePath()));else command.addAll(List.of("-f",config.getAbsolutePath(),"-d",directory.getAbsolutePath()));
            File log=new File(directory,"core.log");
            ProcessBuilder builder=new ProcessBuilder(command).directory(directory).redirectErrorStream(true).redirectOutput(log);
            builder.environment().put("GOMEMLIMIT","96MiB");process=builder.start();api=new CoreApi(secret,controller);
            List<String> rules=CoreApi.requiredRules(source.engine(),compiled);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
            while(System.nanoTime()<deadline){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(!process.isAlive())throw new IOException("测速内核启动失败："+new String(Files.readAllBytes(log.toPath()),StandardCharsets.UTF_8).replaceAll("\\x1b\\[[0-9;]*m", "").trim());
                try{api.checkReady(1000,mixed,rules);return;}catch(IOException ignored){Thread.sleep(100);}
            }
            throw new IOException("测速内核启动超时");
        }catch(Exception e){close();throw e;}
    }
    public void close(){
        if(process!=null){process.destroy();try{if(!process.waitFor(1,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException e){process.destroyForcibly();Thread.currentThread().interrupt();}}
        if(directory!=null)try(var paths=Files.walk(directory.toPath())){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}catch(IOException ignored){}
    }
}
