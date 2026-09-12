package com.quremed.rehaflow;

/** Shared only inside this application process; never written to disk or Intent extras. */
public final class NativeSession {
    private NativeSession() {}
    public static volatile String server = "";
    public static volatile String token = "";
    public static volatile String userId = "";
    public static synchronized void clear() { token = ""; userId = ""; }
}
