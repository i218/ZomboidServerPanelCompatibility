package io.serverpanelbridge;
/** Optional JVM-local API. Requester names are an administrative allowlist, not authentication. */
public final class ModRestartApi {
    private ModRestartApi() {}
    public static int apiVersion(){return 1;}
    public static String requestRestart(String requester,String requestId){return Main.requestModRestart(requester,requestId);}
    public static String status(String requester,String requestId){return Main.modRestartStatus(requester,requestId);}
}
