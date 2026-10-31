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
    public static volatile boolean running, starting, stopping, visible, wantsGroups;
    public static volatile String status="未连接", secret="";
    public static volatile long upload,download,upRate,downRate,memory,appMemory,coreMemory;
    public static volatile int inbound,outbound;
    public static volatile JSONObject connections=new JSONObject(), proxies=new JSONObject();
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
    private java.lang.Process core;
    private Thread tunnel;
    private ParcelFileDescriptor ownedTun;
    private volatile boolean shuttingDown;
    private long corePid,previousUp,previousDown,lastPoll;
    private int pollTicks;
    private static final int NOTIFY=42;
    static void log(String text){synchronized(logs){if(logs.size()>=350)logs.removeFirst();if(text.length()>4096)text=text.substring(0,4096);logs.addLast(new java.text.SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+"  "+text);}}
    public static List<String> logSnapshot(){synchronized(logs){return new ArrayList<>(logs);}}
    public static CoreApi api(){return new CoreApi(secret);}
    @Override public void onCreate(){super.onCreate();NotificationManager m=getSystemService(NotificationManager.class);NotificationChannel ch=new NotificationChannel("vpn","代理连接",NotificationManager.IMPORTANCE_LOW);ch.setShowBadge(false);m.createNotificationChannel(ch);}
    private Notification notification(){PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,WboxVpnService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,"vpn").setSmallIcon(com.wbox.app.R.drawable.wbox_icon).setContentTitle("Wbox · "+status).setContentText("上传 "+MainActivity.bytes(upload)+" · 下载 "+MainActivity.bytes(download)).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"断开",stop).build()).build();}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){shuttingDown=true;commands.execute(()->{stopCore();stopSelf();});return START_NOT_STICKY;}
        if(running||starting||stopping)return START_NOT_STICKY;
        starting=true;shuttingDown=false;status="连接中";
        if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFY,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(NOTIFY,notification());
        commands.execute(()->{try{startCore();}catch(Throwable ex){log("启动失败："+ex.getMessage());status="启动失败";stopCore();stopSelf();}});
        return START_NOT_STICKY;
    }
    private void startCore()throws Exception{
        NativeTunnel.prepare();if(shuttingDown)return;
        Profiles p=new Profiles(this);secret=UUID.randomUUID().toString();File dir=new File(getFilesDir(),"runtime");dir.mkdirs();File config=new File(dir,p.engine().equals("sing-box")?"config.json":"config.yaml");Files.write(config.toPath(),p.compile(secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File binary=new File(getApplicationInfo().nativeLibraryDir,p.engine().equals("sing-box")?"libsingbox.so":"libmihomo.so");if(!binary.isFile())throw new IOException("安装包缺少内核 "+binary.getName());
        List<String> cmd=new ArrayList<>(List.of("/system/bin/sh","-c","echo $$; exec \"$@\"","wbox",binary.getAbsolutePath()));
        if(p.engine().equals("sing-box"))cmd.addAll(List.of("run","-c",config.getAbsolutePath(),"-D",dir.getAbsolutePath()));else cmd.addAll(List.of("-f",config.getAbsolutePath(),"-d",dir.getAbsolutePath()));
        ProcessBuilder pb=new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);pb.environment().put("GOMEMLIMIT","96MiB");pb.environment().put("GOGC","75");core=pb.start();
        BufferedReader reader=new BufferedReader(new InputStreamReader(core.getInputStream()));String pidLine=reader.readLine();try{corePid=Long.parseLong(pidLine);}catch(Exception ignored){log(pidLine==null?"内核未返回 PID":pidLine);}
        new Thread(()->{try{String line;while((line=reader.readLine())!=null)log(line);}catch(IOException ignored){}} ,"core-log").start();
        boolean ready=false;for(int i=0;i<80&&!shuttingDown;i++){if(!core.isAlive())throw new IOException("内核退出，请查看日志");try{proxies=api().proxies();ready=true;break;}catch(Exception ignored){Thread.sleep(100);}}
        if(shuttingDown)return;if(!ready)throw new IOException("内核控制接口启动超时");
        Builder b=new Builder().setSession("Wbox · "+p.engine()).setMtu(1500).addAddress("172.19.0.1",30).addRoute("0.0.0.0",0).addDnsServer("1.1.1.1");
        boolean ipv6=p.data.optBoolean("ipv6",true);if(ipv6)b.addAddress("fdfe:dcba:9876::1",126).addRoute("::",0).addDnsServer("2606:4700:4700::1111");
        String appMode=p.data.optString("app_mode","all");JSONArray apps=p.data.optJSONArray("apps");int count=0;
        if(appMode.equals("include")){if(apps!=null)for(int i=0;i<apps.length();i++){String pkg=apps.getString(i);if(!pkg.equals(getPackageName())){try{b.addAllowedApplication(pkg);count++;}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}}}if(count==0)throw new IOException("分应用白名单为空；请选择至少一个已安装应用");}
        else{b.addDisallowedApplication(getPackageName());if(appMode.equals("exclude")&&apps!=null)for(int i=0;i<apps.length();i++){String pkg=apps.getString(i);if(!pkg.equals(getPackageName()))try{b.addDisallowedApplication(pkg);}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}}}
        b.setBlocking(true);if(Build.VERSION.SDK_INT>=29)b.setMetered(false);ownedTun=b.establish();if(ownedTun==null)throw new IOException("VPN 权限被撤销");
        File hev=new File(dir,"tunnel.yaml");String y="tunnel:\n  name: wbox\n  mtu: 1500\n  ipv4: 172.19.0.1\n"+(ipv6?"  ipv6: 'fdfe:dcba:9876::1'\n":"")+"socks5:\n  address: 127.0.0.1\n  port: 10808\n  udp: udp\nmisc:\n  task-stack-size: 24576\n  tcp-buffer-size: 32768\n  udp-recv-buffer-size: 131072\n  max-session-count: 1024\n  connect-timeout: 8000\n  tcp-read-write-timeout: 300000\n  udp-read-write-timeout: 60000\n  log-level: warn\n";Files.write(hev.toPath(),y.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final int fd=ownedTun.detachFd();ownedTun=null;tunnel=new Thread(()->{try{if(shuttingDown)return;int result=NativeTunnel.run(hev.getAbsolutePath(),fd);if(!shuttingDown){log("隧道结束："+result);shuttingDown=true;queueShutdown();}}catch(Throwable ex){log("隧道失败："+ex.getMessage());shuttingDown=true;queueShutdown();}finally{try{ParcelFileDescriptor.adoptFd(fd).close();}catch(IOException ignored){}}},"wbox-tun");tunnel.start();
        upload=download=upRate=downRate=previousUp=previousDown=0;clearTraffic();lastPoll=SystemClock.elapsedRealtime();running=true;starting=false;status="已连接";log(p.engine()+" 已连接 · IPv6 "+(ipv6?"开启":"关闭"));
        polling=Executors.newSingleThreadScheduledExecutor();polling.schedule(this::poll,1,TimeUnit.SECONDS);
    }
    private void poll(){if(!running)return;pollTicks++;try{if(core==null||!core.isAlive()){throw new IOException("内核意外退出");}CoreApi.Stats j=api().stats();long now=SystemClock.elapsedRealtime();double seconds=Math.max(.001,(now-lastPoll)/1000.0);long u=j.upload,d=j.download;upRate=(long)(Math.max(0,u-previousUp)/seconds);downRate=(long)(Math.max(0,d-previousDown)/seconds);previousUp=u;previousDown=d;upload=u;download=d;lastPoll=now;recordTraffic(now,upRate,downRate);inbound=j.connections;outbound=inbound;if(memory==0||pollTicks%(visible?5:3)==0){appMemory=(long)Debug.getPss()*1024;coreMemory=coreRss();memory=appMemory+coreMemory;}if(visible&&wantsGroups&&pollTicks%5==0)proxies=api().proxies();if(pollTicks%(visible?30:3)==0)getSystemService(NotificationManager.class).notify(NOTIFY,notification());}
        catch(Exception ex){if(core!=null&&!core.isAlive()){log(ex.getMessage());shuttingDown=true;commands.execute(()->{stopCore();stopSelf();});}}
        finally{if(running&&polling!=null&&!polling.isShutdown())try{polling.schedule(this::poll,visible?1:10,TimeUnit.SECONDS);}catch(RejectedExecutionException ignored){}}
    }
    private long coreRss(){try{for(String line:Files.readAllLines(Paths.get("/proc/"+corePid+"/status"))){if(line.startsWith("VmRSS:"))return Long.parseLong(line.replaceAll("[^0-9]",""))*1024;}}catch(Exception ignored){}return 0;}
    private void queueShutdown(){try{commands.execute(()->{stopCore();stopSelf();});}catch(RejectedExecutionException ignored){}}
    private void stopCore(){stopping=true;shuttingDown=true;running=false;starting=false;status="正在关闭";if(polling!=null){polling.shutdownNow();polling=null;}try{NativeTunnel.stop();}catch(Throwable ignored){}if(tunnel!=null){while(tunnel.isAlive()){try{tunnel.join(2000);if(tunnel.isAlive())NativeTunnel.stop();}catch(InterruptedException ignored){}catch(Throwable ignored){}}tunnel=null;}if(ownedTun!=null){try{ownedTun.close();}catch(IOException ignored){}ownedTun=null;}if(core!=null){core.destroy();try{if(!core.waitFor(2,TimeUnit.SECONDS))core.destroyForcibly();}catch(InterruptedException ignored){core.destroyForcibly();}core=null;}inbound=outbound=0;upRate=downRate=memory=appMemory=coreMemory=0;connections=new JSONObject();proxies=new JSONObject();status="未连接";stopping=false;log("连接已关闭");stopForeground(STOP_FOREGROUND_REMOVE);}
    @Override public void onRevoke(){shuttingDown=true;commands.execute(()->{stopCore();stopSelf();});super.onRevoke();}
    @Override public void onDestroy(){shuttingDown=true;commands.execute(this::stopCore);commands.shutdown();super.onDestroy();}
}
