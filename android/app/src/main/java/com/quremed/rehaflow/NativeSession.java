package com.quremed.rehaflow;

/** Shared only inside this application process; never written to disk or Intent extras. */
public final class NativeSession {
    private NativeSession() {}
    private static android.content.Context context;
    public static void initialize(android.content.Context c){context=c.getApplicationContext();}
    public static volatile String server = "";
    public static volatile String token = "";
    public static volatile String userId = "";
    public static synchronized void clear() { token = ""; userId = ""; if(context!=null)SessionStore.clear(context); }
}
