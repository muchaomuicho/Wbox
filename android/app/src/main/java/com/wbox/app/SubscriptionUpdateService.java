package com.wbox.app;

import android.app.job.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SubscriptionUpdateService extends JobService {
    private static final AtomicBoolean updating=new AtomicBoolean();
    private final ConcurrentHashMap<Integer,Thread> tasks=new ConcurrentHashMap<>();
    @Override public boolean onStartJob(JobParameters params) {
        if(!updating.compareAndSet(false,true))return false;
        Thread task=new Thread(()->{
            boolean retry=false;
            try{SubscriptionUpdates.run(this);}catch(InterruptedException e){Thread.currentThread().interrupt();retry=true;}catch(Exception e){retry=true;}
            finally{updating.set(false);if(tasks.remove(params.getJobId())!=null)jobFinished(params,retry);}
        },"subscription-update");
        tasks.put(params.getJobId(),task);task.start();return true;
    }
    @Override public boolean onStopJob(JobParameters params){Thread task=tasks.remove(params.getJobId());if(task!=null)task.interrupt();return true;}
}
