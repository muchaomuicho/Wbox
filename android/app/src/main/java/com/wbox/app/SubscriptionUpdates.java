package com.wbox.app;

import android.app.job.*;
import android.content.*;
import org.json.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class SubscriptionUpdates {
    static final int PERIODIC=2008, IMMEDIATE=2009;
    record Target(String id,String url,String revision,JSONObject options) {}
    static JSONObject defaults(JSONObject data,String url,boolean legacy)throws JSONException {
        return new JSONObject().put("name",URI.create(url).getHost()).put("auto_update",legacy&&data.optBoolean("subscription_auto_update"))
            .put("update_minutes",legacy?Math.max(15,Math.min(10080,data.optInt("subscription_update_minutes",360))):1440)
            .put("last_attempt",legacy?data.optLong("subscription_last_attempt"):0);
    }
    static void migrate(JSONObject data)throws JSONException {
        boolean legacy=!data.optBoolean("subscription_settings_migrated");
        JSONArray configs=data.optJSONArray("configs");
        if(configs!=null)for(int i=0;i<configs.length();i++){JSONObject c=configs.getJSONObject(i);if(!c.optString("url").isEmpty()&&!c.has("subscription"))c.put("subscription",defaults(data,c.getString("url"),legacy));}
        JSONObject options=data.optJSONObject("subscription_settings");if(options==null){options=new JSONObject();data.put("subscription_settings",options);}
        JSONObject subs=data.optJSONObject("subscriptions");
        if(subs!=null)for(Iterator<String> it=subs.keys();it.hasNext();){String url=it.next();if(!options.has(url))options.put(url,defaults(data,url,legacy));}
        data.put("subscription_settings_migrated",true);
    }
    static JSONObject options(Profiles p,String id,String url)throws JSONException {
        migrate(p.data);
        if(!id.isEmpty())for(int i=0;i<p.configs().length();i++){JSONObject c=p.configs().getJSONObject(i);if(c.optString("id").equals(id)&&c.optString("url").equals(url))return c.getJSONObject("subscription");}
        if(id.isEmpty())return p.data.getJSONObject("subscription_settings").optJSONObject(url);
        return null;
    }
    static String name(Profiles p,String url){try{return options(p,"",url).optString("name",URI.create(url).getHost());}catch(Exception e){return URI.create(url).getHost();}}
    static int interval(JSONObject s){return Math.max(15,Math.min(10080,s.optInt("update_minutes",1440)));}
    static boolean due(JSONObject s,long now){return s.optBoolean("auto_update")&&now-s.optLong("last_attempt")>=TimeUnit.MINUTES.toMillis(interval(s));}
    static boolean due(Profiles p,long now){try{return targets(p).stream().anyMatch(t->due(t.options,now));}catch(Exception e){return false;}}
    static List<Target> targets(Profiles p)throws Exception {
        migrate(p.data);List<Target> result=new ArrayList<>();
        for(int i=0;i<p.configs().length();i++){JSONObject c=p.configs().getJSONObject(i);String url=c.optString("url");if(!url.isEmpty())result.add(new Target(c.getString("id"),url,c.optString("content"),c.getJSONObject("subscription")));}
        JSONObject subs=p.data.optJSONObject("subscriptions");if(subs!=null)for(Iterator<String> it=subs.keys();it.hasNext();){String url=it.next();result.add(new Target("",url,revision(p,url),options(p,"",url)));}
        return result;
    }
    static void schedule(Context context) {
        try {
            Profiles p=new Profiles(context);JobScheduler jobs=context.getSystemService(JobScheduler.class);
            if(targets(p).stream().noneMatch(t->t.options.optBoolean("auto_update"))){jobs.cancel(PERIODIC);jobs.cancel(IMMEDIATE);return;}
            long period=TimeUnit.MINUTES.toMillis(15);JobInfo previous=jobs.getPendingJob(PERIODIC);
            if(previous==null||previous.getIntervalMillis()!=period)jobs.schedule(new JobInfo.Builder(PERIODIC,new ComponentName(context,SubscriptionUpdateService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(period).build());
            if(due(p,System.currentTimeMillis())&&jobs.getPendingJob(IMMEDIATE)==null)jobs.schedule(new JobInfo.Builder(IMMEDIATE,new ComponentName(context,SubscriptionUpdateService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(1000).build());
        }catch(Exception ignored){}
    }
    static String revision(Profiles p,String url)throws Exception {List<String> values=new ArrayList<>();for(Node n:p.nodes())if(n.source.equals(url))values.add(n.raw);return values.toString();}
    static void edit(Profiles p,String id,String oldUrl,String name,String url,boolean enabled,int minutes)throws Exception {
        name=name.trim();url=url.trim();if(name.isEmpty()||name.length()>100)throw new IllegalArgumentException("名称需要为 1–100 个字符");
        if(!Profiles.subscriptionAddress(url))throw new IllegalArgumentException("订阅地址需要是有效的 HTTP / HTTPS 地址");
        if(minutes<15||minutes>10080)throw new IllegalArgumentException("更新间隔需为 15–10080 分钟");
        JSONObject setting=options(p,id,oldUrl);if(setting==null)throw new IllegalArgumentException("订阅已变化，请重新打开编辑页面");
        if(id.isEmpty()){
            JSONObject subscriptions=p.data.getJSONObject("subscriptions"),all=p.data.getJSONObject("subscription_settings");
            if(!subscriptions.has(oldUrl))throw new IllegalArgumentException("订阅已移除");
            if(!oldUrl.equals(url)&&subscriptions.has(url))throw new IllegalArgumentException("该订阅地址已存在");
            Object content=subscriptions.remove(oldUrl);subscriptions.put(url,content);all.remove(oldUrl);all.put(url,setting);
            List<Node> nodes=p.nodes();for(Node n:nodes)if(n.source.equals(oldUrl))n.source=url;JSONArray stored=new JSONArray();for(Node n:nodes)stored.put(n.storage());p.data.put("nodes",stored);
        }else for(int i=0;i<p.configs().length();i++){JSONObject c=p.configs().getJSONObject(i);if(c.optString("id").equals(id)){c.put("name",name).put("url",url);}}
        setting.put("name",name).put("auto_update",enabled).put("update_minutes",minutes);
        if(!url.equals(oldUrl))setting.put("last_attempt",0).put("last_success",0).put("error","");
    }
    static void run(Context context)throws Exception {
        List<Target> targets;
        synchronized(Profiles.STORE_LOCK){Profiles p=new Profiles(context);long now=System.currentTimeMillis();targets=targets(p).stream().filter(t->due(t.options,now)).collect(java.util.stream.Collectors.toList());if(targets.isEmpty())return;for(Target t:targets)t.options.put("last_attempt",now);p.save();}
        for(Target t:targets){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            String content=null,error="";try{content=Profiles.fetch(t.url);}catch(Exception e){error="下载失败，请检查网络或订阅地址";}
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            synchronized(Profiles.STORE_LOCK){
                Profiles p=new Profiles(context);JSONObject settings=options(p,t.id,t.url);
                if(settings==null||!settings.optBoolean("auto_update"))continue;
                JSONObject profile=null;if(!t.id.isEmpty())for(int i=0;i<p.configs().length();i++){JSONObject c=p.configs().getJSONObject(i);if(c.optString("id").equals(t.id)&&c.optString("url").equals(t.url))profile=c;}
                if(t.id.isEmpty()) {if(p.data.optJSONObject("subscriptions")==null||!p.data.getJSONObject("subscriptions").has(t.url)||!revision(p,t.url).equals(t.revision))continue;}
                else if(profile==null||!profile.optString("content").equals(t.revision))continue;
                if(error.isEmpty())try {
                    Profiles next=p.copy();
                    if(t.id.isEmpty())next.importNodes(content,t.url);else next.importConfig(content,t.url,t.id,false);
                    p.data=next.data;settings=options(p,t.id,t.url);settings.put("last_success",System.currentTimeMillis());
                }catch(Exception e){error="订阅内容无效，已保留原数据";}
                settings.put("error",error);p.save();
            }
        }
    }
}
