package com.wbox.app;

import android.app.job.*;
import android.content.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class SubscriptionUpdates {
    static final int PERIODIC=2008, IMMEDIATE=2009;
    static boolean due(Profiles p,long now) {
        return p.data.optBoolean("subscription_auto_update")&&now-p.data.optLong("subscription_last_attempt")>=TimeUnit.MINUTES.toMillis(interval(p));
    }
    static int interval(Profiles p){return Math.max(15,Math.min(10080,p.data.optInt("subscription_update_minutes",360)));}
    static void schedule(Context context) {
        Profiles p=new Profiles(context);JobScheduler jobs=context.getSystemService(JobScheduler.class);
        if(!p.data.optBoolean("subscription_auto_update")){jobs.cancel(PERIODIC);jobs.cancel(IMMEDIATE);return;}
        long period=TimeUnit.MINUTES.toMillis(interval(p));JobInfo previous=jobs.getPendingJob(PERIODIC);
        if(previous==null||previous.getIntervalMillis()!=period)jobs.schedule(new JobInfo.Builder(PERIODIC,new ComponentName(context,SubscriptionUpdateService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(period).build());
        if(due(p,System.currentTimeMillis())&&jobs.getPendingJob(IMMEDIATE)==null)jobs.schedule(new JobInfo.Builder(IMMEDIATE,new ComponentName(context,SubscriptionUpdateService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(1000).build());
    }
    static String revision(Profiles p,String url)throws Exception {List<String> values=new ArrayList<>();for(Node n:p.nodes())if(n.source.equals(url))values.add(n.raw);return values.toString();}
    static void run(Context context)throws Exception {
        Profiles snapshot;
        synchronized(Profiles.STORE_LOCK){snapshot=new Profiles(context);if(!due(snapshot,System.currentTimeMillis()))return;snapshot.data.put("subscription_last_attempt",System.currentTimeMillis());snapshot.save();}
        List<String[]> targets=new ArrayList<>();
        for(int i=0;i<snapshot.configs().length();i++){JSONObject p=snapshot.configs().getJSONObject(i);if(!p.optString("url").isEmpty())targets.add(new String[]{p.optString("id"),p.optString("url"),p.optString("content")});}
        JSONObject subscriptions=snapshot.data.optJSONObject("subscriptions");if(subscriptions!=null)for(Iterator<String> it=subscriptions.keys();it.hasNext();){String url=it.next();targets.add(new String[]{"",url,revision(snapshot,url)});}
        boolean failed=false;
        for(String[] t:targets){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            String content;try{content=Profiles.fetch(t[1]);}catch(Exception e){failed=true;continue;}
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            synchronized(Profiles.STORE_LOCK){
                Profiles p=new Profiles(context);if(!p.data.optBoolean("subscription_auto_update"))return;
                try {
                    if(t[0].isEmpty()) {
                        if(p.data.optJSONObject("subscriptions")==null||!p.data.getJSONObject("subscriptions").has(t[1])||!revision(p,t[1]).equals(t[2]))continue;
                        Profiles next=p.copy();next.importNodes(content,t[1]);
                        p.data.put("nodes",next.data.getJSONArray("nodes")).put("subscriptions",next.data.getJSONObject("subscriptions"));p.save();
                    }else {
                        JSONObject target=null;for(int i=0;i<p.configs().length();i++){JSONObject c=p.configs().getJSONObject(i);if(c.optString("id").equals(t[0])&&c.optString("url").equals(t[1]))target=c;}
                        if(target==null||!target.optString("content").equals(t[2]))continue;
                        p.importConfig(content,t[1],t[0],false);
                    }
                }catch(Exception e){failed=true;}
            }
        }
        synchronized(Profiles.STORE_LOCK){Profiles p=new Profiles(context);p.data.put("subscription_update_error",failed?"部分订阅更新失败，已保留原数据":"");if(!failed)p.data.put("subscription_last_success",System.currentTimeMillis());p.save();}
    }
}
