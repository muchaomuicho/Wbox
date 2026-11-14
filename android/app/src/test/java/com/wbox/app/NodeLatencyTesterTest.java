package com.wbox.app;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class NodeLatencyTesterTest {
    @Test public void queuedTargetsArePendingBeforeFirstProbeAndCompleteIndividually()throws Exception {
        ExecutorService workers=Executors.newSingleThreadExecutor();
        Map<String,String> results=new ConcurrentHashMap<>(Map.of("A","80 ms","B","90 ms","Other","100 ms"));
        CountDownLatch firstStarted=new CountDownLatch(1),firstDone=new CountDownLatch(1);
        Semaphore release=new Semaphore(0);
        try {
            List<Future<?>> jobs=NodeLatencyTester.submitTargets(workers,List.of("A","B"),(name,value)->{
                results.put(name,value);if(name.equals("A")&&!value.equals("测试中"))firstDone.countDown();
            },name->{firstStarted.countDown();release.acquire();return name.equals("A")?"12 ms":"24 ms";});
            assertTrue(firstStarted.await(5,TimeUnit.SECONDS));
            assertEquals("测试中",results.get("A"));assertEquals("测试中",results.get("B"));assertEquals("100 ms",results.get("Other"));
            release.release();assertTrue(firstDone.await(5,TimeUnit.SECONDS));
            assertEquals("12 ms",results.get("A"));assertEquals("测试中",results.get("B"));
            release.release();for(Future<?> job:jobs)job.get(5,TimeUnit.SECONDS);
            assertEquals("24 ms",results.get("B"));assertEquals("100 ms",results.get("Other"));
        }finally{workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test public void interruptedProbeDoesNotPublishAResult()throws Exception {
        ExecutorService workers=Executors.newSingleThreadExecutor();
        Map<String,String> results=new ConcurrentHashMap<>();
        CountDownLatch started=new CountDownLatch(1),hold=new CountDownLatch(1);
        try {
            NodeLatencyTester.submitTargets(workers,List.of("A","B"),results::put,name->{started.countDown();hold.await();return "1 ms";});
            assertTrue(started.await(5,TimeUnit.SECONDS));workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));
            assertEquals(Map.of("A","测试中","B","测试中"),results);
        }finally{workers.shutdownNow();}
    }
}
