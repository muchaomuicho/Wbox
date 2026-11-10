package com.wbox.app;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class CoreStartupTest {
    @Test public void slowResourcesMayFinishAfterOldEightSecondLimit()throws Exception {
        AtomicLong now=new AtomicLong();int[] notices={0};
        assertTrue(CoreStartup.await(120000,now::get,now::addAndGet,()->true,()->false,
            timeout->{if(now.get()<14000)throw new IOException("Connection refused");},()->notices[0]++));
        assertEquals(14000,now.get());assertEquals(1,notices[0]);
    }
    @Test public void cancellationWinsEvenWhenProbeSucceeds()throws Exception {
        AtomicLong now=new AtomicLong();
        assertFalse(CoreStartup.await(120000,now::get,now::addAndGet,()->true,()->now.get()>0,
            timeout->now.set(100),()->{}));
    }
    @Test public void timeoutIncludesProbeTimeAndUsesElapsedDeadline()throws Exception {
        AtomicLong now=new AtomicLong();
        try {CoreStartup.await(2000,now::get,now::addAndGet,()->true,()->false,
            timeout->{now.addAndGet(timeout);throw new IOException("Read timed out");},()->{});fail();}
        catch(IOException e){assertTrue(e.getMessage().contains("超过 2 秒"));assertNotNull(e.getCause());}
        assertEquals(2000,now.get());
    }
    @Test public void exitedCoreAndAuthenticationFailImmediately()throws Exception {
        for(boolean alive:new boolean[]{false,true}) {
            AtomicLong now=new AtomicLong();
            try {CoreStartup.await(120000,now::get,now::addAndGet,()->alive,()->false,
                timeout->{throw new CoreStartup.Rejected("HTTP 401");},()->{});fail();}
            catch(IOException e){assertTrue(e.getMessage().contains(alive?"401":"退出"));}
            assertEquals(0,now.get());
        }
    }
    @Test public void messageLessErrorsRetainTheirType() {
        assertEquals("IOException",ErrorMessage.describe(new IOException()));
        assertTrue(ErrorMessage.describe(new java.net.UnknownHostException()).contains("DNS"));
        assertEquals("保存文件失败",ErrorMessage.describe(new IOException("保存文件失败",new IOException())));
    }
}
