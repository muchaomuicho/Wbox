package com.wbox.app;

public final class NativeTunnel {
    static { System.loadLibrary("wboxtun"); }
    private NativeTunnel() {}
    public static native void prepare();
    public static native int run(String config, int fd);
    public static native void stop();
    public static native String[] connectionOrigins();
}
