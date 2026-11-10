package com.wbox.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class WboxVpnService extends VpnService {
    public static final String STOP="com.wbox.app.STOP";
    public static volatile boolean running, starting, stopping, visible, wantsGroups, wantsConnections, wantsOverview;
    public static volatile List<ConnectionRecord> connectionRows=List.of();
    public static volatile long startedAt;
    public static volatile String status="未连接", secret="", lastError="";
    public static volatile String globalTarget;
    public static volatile long upload,download,upRate,downRate,memory,appMemory,coreMemory;
    public static volatile int inbound,outbound;
    public static volatile JSONObject connections=new JSONObject(), proxies=new JSONObject();
    public static volatile List<String> groupOrder=List.of();
    public static final Deque<String> logs=new ArrayDeque<>();
    public static final class TrafficSample {
        public final long time,up,down;
        TrafficSample(long time,long up,long down){this.time=time;this.up=up;this.down=down;}
    }
    private static final Deque<TrafficSample> traffic=new ArrayDeque<>();
    public static List<TrafficSample> trafficSnapshot(){synchronized(traffic){return new ArrayList<>(traffic);}}
    static void recordTraffic(long time,long up,long down){synchronized(traffic){traffic.addLast(new TrafficSample(time,Math.max(0,up),Math.max(0,down)));while(traffic.size()>61||(!traffic.isEmpty()&&time-traffic.peekFirst().time>60000))traffic.removeFirst();}}
    static void clearTraffic(){synchronized(traffic){traffic.clear();}}
    private final ExecutorService commands=Executors.newSingleThreadExecutor();
    private ScheduledExecutorService polling;
    private ScheduledFuture<?> nextPoll;
    private static volatile WboxVpnService instance;
    static CompletableFuture<Void> refreshDetails(boolean groups){
        WboxVpnService service=instance;
        if(service==null||!running||service.polling==null)return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> result=new CompletableFuture<>();
        try{service.polling.execute(()->{try{
            if(running&&!service.shuttingDown){if(groups)proxies=api().proxies();else {List<ConnectionRecord> rows=api().snapshot(true,connectionRows,Math.max(.1,(SystemClock.elapsedRealtime()-service.lastPoll)/1000.0)).rows;ConnectionApps.enrich(service,rows);connectionRows=rows;}}
            result.complete(null);
        }catch(Exception e){result.completeExceptionally(e);}});}catch(RejectedExecutionException e){result.completeExceptionally(e);}
        return result;
    }
    static void setVisible(boolean value){visible=value;WboxVpnService service=instance;if(service!=null)service.schedulePoll(value?0:-1);}
    private synchronized void schedulePoll(long delay){
        if(!running||polling==null||polling.isShutdown())return;
        if(nextPoll!=null)nextPoll.cancel(false);
        if(delay<0||!visible)return;
        try{nextPoll=polling.schedule(this::poll,delay,TimeUnit.SECONDS);}catch(RejectedExecutionException ignored){}
    }
    private java.lang.Process core;
    private Thread tunnel;
    private ParcelFileDescriptor ownedTun;
    private volatile boolean shuttingDown;
    private volatile boolean stopRequested;
    private boolean closed=true;
    private String startStage="准备 VPN";
    private volatile String startupDiagnostic="";
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    static CompletableFuture<Void> commitNodes(Context context,JSONObject next) {
        CompletableFuture<Void> result=new CompletableFuture<>();WboxVpnService service=instance;
        Runnable apply=()->{try{
            NodeChanges.commit(new Profiles(context),next,new NodeChanges.Connection(){
                public boolean running(){return service!=null&&WboxVpnService.running;}
                public boolean cancelled(){return service==null||service.stopRequested;}
                public void stop(){if(service!=null)service.stopCore();}
                public void start()throws Exception{if(service!=null&&!service.stopRequested){service.startingForeground();service.startCore();}}
            });
            if(service!=null&&!running&&!starting)service.stopSelf();result.complete(null);
        }catch(Exception error){result.completeExceptionally(error);}};
        if(service!=null)try{service.commands.execute(apply);}catch(RejectedExecutionException error){result.completeExceptionally(error);}else apply.run();
        return result;
    }
    private void startingForeground(){closed=false;starting=true;shuttingDown=false;lastError="";status="连接中";if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFY,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(NOTIFY,notification());}
    private long corePid,previousUp,previousDown,lastPoll;
    private int pollTicks;
    private static final int NOTIFY=42;
    static void log(String text){text=LogStyle.plain(text);synchronized(logs){if(logs.size()>=350)logs.removeFirst();if(text.length()>4096)text=text.substring(0,4096);logs.addLast(LogStyle.timestamped(text)?text:new java.text.SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+"  "+text);}}
    public static List<String> logSnapshot(){synchronized(logs){return new ArrayList<>(logs);}}
    public static CoreApi api(){return new CoreApi(secret);}
    @Override public void onCreate(){super.onCreate();instance=this;NotificationManager m=getSystemService(NotificationManager.class);NotificationChannel ch=new NotificationChannel("vpn","代理连接",NotificationManager.IMPORTANCE_LOW);ch.setShowBadge(false);m.createNotificationChannel(ch);}
    private Notification notification(){PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,WboxVpnService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,"vpn").setSmallIcon(com.wbox.app.R.drawable.wbox_icon).setContentTitle("Wbox · "+status).setContentText("上传 "+MainActivity.bytes(upload)+" · 下载 "+MainActivity.bytes(download)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"断开",stop).build()).build();}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){stopRequested=true;shuttingDown=true;commands.execute(()->{stopCore();stopSelf();});return START_NOT_STICKY;}
        if(running||starting||stopping)return START_NOT_STICKY;
        stopRequested=false;startingForeground();
        commands.execute(()->{try{startCore();if(shuttingDown){stopCore();stopSelf();}}catch(Throwable ex){if(!shuttingDown){lastError=startStage+"："+(startupDiagnostic.isEmpty()?ErrorMessage.describe(ex):startupDiagnostic);log("ERROR 启动失败 · "+lastError);persistFailure();}stopCore();stopSelf();}});
        return START_NOT_STICKY;
    }
    private void startCore()throws Exception{
        startupDiagnostic="";
        long startupBegan=SystemClock.elapsedRealtime();
        startStage="加载隧道";
        NativeTunnel.prepare();if(shuttingDown)return;
        ConnectionApps.clear();
        startStage="生成配置";
        Profiles p=new Profiles(this);secret=UUID.randomUUID().toString();String profile=p.mode().equals("config")&&p.activeConfig()!=null?p.activeConfig().optString("id").replaceAll("[^a-zA-Z0-9-]",""):"nodes";File dir=new File(getFilesDir(),"runtime/"+p.engine()+"/"+profile);dir.mkdirs();File config=new File(dir,p.engine().equals("sing-box")?"config.json":"config.yaml");Files.write(config.toPath(),p.compile(secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        groupOrder=ProxyCatalog.order(new JSONObject(Profiles.yamlMap(new String(Files.readAllBytes(config.toPath()),java.nio.charset.StandardCharsets.UTF_8))),p.engine().equals("sing-box"));
        File binary=new File(getApplicationInfo().nativeLibraryDir,p.engine().equals("sing-box")?"libsingbox.so":"libmihomo.so");if(!binary.isFile())throw new IOException("安装包缺少内核 "+binary.getName());
        List<String> cmd=new ArrayList<>(List.of("/system/bin/sh","-c","echo $$; exec \"$@\"","wbox",binary.getAbsolutePath()));
        if(p.engine().equals("sing-box"))cmd.addAll(List.of("run","-c",config.getAbsolutePath(),"-D",dir.getAbsolutePath()));else cmd.addAll(List.of("-f",config.getAbsolutePath(),"-d",dir.getAbsolutePath()));
        if(p.engine().equals("mihomo")){
            startStage="准备离线规则数据库";
            Geodata.prepareRequired(dir,Profiles.yamlMap(new String(Files.readAllBytes(config.toPath()),java.nio.charset.StandardCharsets.UTF_8)),Geodata.assets(this),WboxVpnService::log);
            if(shuttingDown)return;
        }
        startStage="启动 "+p.engine();
        log("INFO ── 开始启动 "+p.engine()+" ──");
        ProcessBuilder pb=new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);pb.environment().put("GOMEMLIMIT","96MiB");pb.environment().put("GOGC","100");core=pb.start();
        BufferedReader reader=new BufferedReader(new InputStreamReader(core.getInputStream()));String pidLine=reader.readLine();try{corePid=Long.parseLong(pidLine);}catch(Exception ignored){log(pidLine==null?"内核未返回 PID":pidLine);}
        Thread logReader=new Thread(()->{try{String line;while((line=reader.readLine())!=null){String plain=LogStyle.plain(line);if(starting&&(plain.contains("level=fatal")||plain.contains("FATAL")||plain.contains("panic:")))startupDiagnostic=plain;log(line);}}catch(IOException ignored){}} ,"core-log");logReader.start();
        CoreApi controller=api();List<String> requiredRules=CoreApi.requiredRules(p.engine(),new String(Files.readAllBytes(config.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        if(!CoreStartup.await(CoreStartup.TIMEOUT_MS,SystemClock::elapsedRealtime,Thread::sleep,
                ()->{if(core.isAlive())return true;try{logReader.join(300);}catch(InterruptedException e){Thread.currentThread().interrupt();}return false;},()->shuttingDown,timeout->controller.checkReady(timeout,10808,requiredRules),()->{
                    status="正在初始化内核";
                    log("INFO 内核仍在初始化，等待远程规则集及服务就绪；可随时停止连接");
                    getSystemService(NotificationManager.class).notify(NOTIFY,notification());
                }))return;
        if(p.engine().equals("sing-box"))api().request("PATCH","/configs",new JSONObject().put("mode","rule"));
        else {globalTarget=CoreApi.globalTarget(new String(Files.readAllBytes(config.toPath()),java.nio.charset.StandardCharsets.UTF_8));if(api().request("GET","/configs",null).optString("mode").equals("global"))api().setMode("global",globalTarget);}
        ProxyCatalog.restore(p,controller);
        startStage="建立 VPN";
        if(shuttingDown)return;
        Builder b=new Builder().setSession("Wbox · "+p.engine()).setMtu(1500).addAddress("172.19.0.1",30).addRoute("0.0.0.0",0).addDnsServer("1.1.1.1");
        boolean ipv6=true;b.addAddress("fdfe:dcba:9876::1",126).addRoute("::",0).addDnsServer("2606:4700:4700::1111");
        String appMode=p.data.optString("app_mode","all");JSONArray apps=p.data.optJSONArray("apps");int count=0;
        if(appMode.equals("include")){if(apps!=null)for(int i=0;i<apps.length();i++){String pkg=apps.getString(i);if(!pkg.equals(getPackageName())){try{b.addAllowedApplication(pkg);count++;}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}}}if(count==0)throw new IOException("分应用白名单为空；请选择至少一个已安装应用");}
        else{b.addDisallowedApplication(getPackageName());if(appMode.equals("exclude")&&apps!=null)for(int i=0;i<apps.length();i++){String pkg=apps.getString(i);if(!pkg.equals(getPackageName()))try{b.addDisallowedApplication(pkg);}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}}}
        if(shuttingDown)return;
        b.setBlocking(true);if(Build.VERSION.SDK_INT>=29)b.setMetered(false);ownedTun=b.establish();if(ownedTun==null)throw new IOException("VPN 权限被撤销");
        File hev=new File(dir,"tunnel.yaml");String y="tunnel:\n  name: wbox\n  mtu: 1500\n  ipv4: 172.19.0.1\n"+(ipv6?"  ipv6: 'fdfe:dcba:9876::1'\n":"")+"socks5:\n  address: 127.0.0.1\n  port: 10808\n  udp: udp\nmisc:\n  task-stack-size: 24576\n  tcp-buffer-size: 32768\n  udp-recv-buffer-size: 131072\n  max-session-count: 1024\n  connect-timeout: 8000\n  tcp-read-write-timeout: 300000\n  udp-read-write-timeout: 60000\n  log-level: warn\n";Files.write(hev.toPath(),y.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final int fd=ownedTun.detachFd();ownedTun=null;tunnel=new Thread(()->{try{if(shuttingDown)return;int result=NativeTunnel.run(hev.getAbsolutePath(),fd);if(!shuttingDown){log("隧道结束："+result);shuttingDown=true;queueShutdown();}}catch(Throwable ex){log("隧道失败："+ex.getMessage());shuttingDown=true;queueShutdown();}finally{try{ParcelFileDescriptor.adoptFd(fd).close();}catch(IOException ignored){}}},"wbox-tun");tunnel.start();
        upload=download=upRate=downRate=previousUp=previousDown=0;clearTraffic();lastPoll=SystemClock.elapsedRealtime();startedAt=lastPoll;running=true;starting=false;status="已连接";log(p.engine()+" 已连接（"+(SystemClock.elapsedRealtime()-startupBegan)+" ms） · IPv6 "+(ipv6?"开启":"关闭"));
        monitorNetwork();polling=Executors.newSingleThreadScheduledExecutor();instance=this;schedulePoll(0);
        getSystemService(NotificationManager.class).notify(NOTIFY,notification());
        // Wait for exit instead of waking every 30 seconds in the background.
        final java.lang.Process monitored=core;
        Thread watcher=new Thread(()->{try{monitored.waitFor();if(core==monitored&&!shuttingDown){log("ERROR 内核意外退出");shuttingDown=true;queueShutdown();}}catch(InterruptedException e){Thread.currentThread().interrupt();}},"core-exit");watcher.setDaemon(true);watcher.start();
    }
    private void persistFailure(){try{Files.write(new File(getFilesDir(),"last-startup-error.log").toPath(),("Android "+Build.VERSION.RELEASE+" / "+Build.SUPPORTED_ABIS[0]+"\n"+String.join("\n",logSnapshot())).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(IOException ignored){}}
    private void monitorNetwork(){
        connectivity=getSystemService(ConnectivityManager.class);
        networkCallback=new ConnectivityManager.NetworkCallback(){
            public void onAvailable(Network network){if(!shuttingDown)setUnderlyingNetworks(new Network[]{network});}
            public void onLost(Network network){if(!shuttingDown)setUnderlyingNetworks(null);}
        };
        // Android handles Wi-Fi/mobile handover; no netlink or fixed device name.
        connectivity.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),networkCallback);
    }
    private void poll(){if(!running||!visible)return;pollTicks++;try{if(core==null||!core.isAlive()){throw new IOException("内核意外退出");}long now=SystemClock.elapsedRealtime();double seconds=Math.max(.001,(now-lastPoll)/1000.0);CoreApi.Stats j=api().snapshot(visible&&wantsConnections,connectionRows,seconds);if(visible&&wantsConnections)ConnectionApps.enrich(this,j.rows);connectionRows=j.rows;long u=j.upload,d=j.download;upRate=(long)(Math.max(0,u-previousUp)/seconds);downRate=(long)(Math.max(0,d-previousDown)/seconds);previousUp=u;previousDown=d;upload=u;download=d;lastPoll=now;recordTraffic(now,upRate,downRate);inbound=j.connections;outbound=inbound;if(visible&&(memory==0||pollTicks%5==0)){coreMemory=coreRss();memory=appMemory+coreMemory;}if(visible&&wantsGroups&&(proxies.length()==0||pollTicks%5==0))proxies=api().proxies();if(pollTicks%(visible?30:1)==0)getSystemService(NotificationManager.class).notify(NOTIFY,notification());}
        catch(Exception ex){if(core!=null&&!core.isAlive()){log(ex.getMessage());shuttingDown=true;commands.execute(()->{stopCore();stopSelf();});}}
        finally{schedulePoll(PollPolicy.delaySeconds(visible,wantsOverview||wantsConnections));}
    }
    private long coreRss(){try{for(String line:Files.readAllLines(Paths.get("/proc/"+corePid+"/status"))){if(line.startsWith("VmRSS:"))return Long.parseLong(line.replaceAll("[^0-9]",""))*1024;}}catch(Exception ignored){}return 0;}
    private void queueShutdown(){try{commands.execute(()->{stopCore();stopSelf();});}catch(RejectedExecutionException ignored){}}
    private void stopCore(){if(closed)return;closed=true;if(networkCallback!=null){try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}networkCallback=null;}stopping=true;shuttingDown=true;running=false;starting=false;status="正在关闭";if(polling!=null){polling.shutdownNow();polling=null;}try{NativeTunnel.stop();}catch(Throwable ignored){}if(tunnel!=null){while(tunnel.isAlive()){try{tunnel.join(2000);if(tunnel.isAlive())NativeTunnel.stop();}catch(InterruptedException ignored){}catch(Throwable ignored){}}tunnel=null;}if(ownedTun!=null){try{ownedTun.close();}catch(IOException ignored){}ownedTun=null;}if(core!=null){core.destroy();try{if(!core.waitFor(2,TimeUnit.SECONDS))core.destroyForcibly();}catch(InterruptedException ignored){core.destroyForcibly();}core=null;}inbound=outbound=0;upRate=downRate=memory=appMemory=coreMemory=0;connections=new JSONObject();connectionRows=List.of();proxies=new JSONObject();status=lastError.isEmpty()?"未连接":"连接失败";stopping=false;log("连接已关闭");stopForeground(STOP_FOREGROUND_REMOVE);}
    @Override public void onRevoke(){stopRequested=true;shuttingDown=true;commands.execute(()->{stopCore();stopSelf();});super.onRevoke();}
    @Override public void onDestroy(){if(instance==this)instance=null;stopRequested=true;shuttingDown=true;commands.execute(this::stopCore);commands.shutdown();super.onDestroy();}
}
