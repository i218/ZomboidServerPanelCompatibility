package io.serverpanelbridge;

public final class LuaBridge {
    private LuaBridge() { }
    public static boolean isClientOptionalReady() { return Main.isClientOptionalReady(); }
    public static double restartApiVersion() {return ModRestartApi.apiVersion();}
    public static String requestRestart(String requester,String requestId) {return ModRestartApi.requestRestart(requester,requestId);}
    public static String restartStatus(String requester,String requestId) {return ModRestartApi.status(requester,requestId);}
    public static void onTick() { Main.onTick(); }
}
