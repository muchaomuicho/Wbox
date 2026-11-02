package com.wbox.app;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Waits for controller, listener and rule-provider initialization. */
final class CoreStartup {
    static final long TIMEOUT_MS = 120_000;
    interface Probe { void check(int timeoutMs) throws Exception; }
    interface Sleep { void pause(long millis) throws InterruptedException; }
    static final class Rejected extends IOException { Rejected(String message) { super(message); } }

    static boolean await(long timeout, LongSupplier clock, Sleep sleep, BooleanSupplier alive,
                         BooleanSupplier cancelled, Probe probe, Runnable waiting) throws Exception {
        long began = clock.getAsLong();
        Exception last = null;
        boolean explained = false;
        while (!cancelled.getAsBoolean()) {
            if (!alive.getAsBoolean()) throw new IOException("内核已退出，请查看下方内核日志");
            long elapsed = clock.getAsLong() - began;
            long remaining = timeout - elapsed;
            if (remaining <= 0) break;
            if (!explained && elapsed >= 5000) { explained = true; waiting.run(); }
            try {
                probe.check((int)Math.min(1000, remaining));
                return !cancelled.getAsBoolean();
            } catch (Rejected e) { throw e; }
            catch (IOException e) { last = e; }
            if (cancelled.getAsBoolean()) return false;
            remaining = timeout - (clock.getAsLong() - began);
            if (remaining > 0) sleep.pause(Math.min(200, remaining));
        }
        if (cancelled.getAsBoolean()) return false;
        throw new IOException("内核初始化超过 " + timeout / 1000 + " 秒，控制接口仍未就绪；请检查日志中的规则集下载、DNS 或出站错误", last);
    }
}
