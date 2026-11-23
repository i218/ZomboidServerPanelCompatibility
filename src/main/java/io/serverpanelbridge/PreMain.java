package io.serverpanelbridge;
import java.util.logging.Logger;
/** Called by ZombieBuddy PREMAIN, not a separate Java agent. */
public final class PreMain {
    private static volatile boolean invoked,pending;
    private PreMain(){}
    public static synchronized void premain(String[] ignored){
        if(invoked || !StartupContext.directServer())return;
        invoked=true;pending=true;
        try {
        PreloadConsole.install();
        Thread worker=new Thread(()->{
            try {
                StartupContext context=StartupContext.capture();
                if(StartupContext.enabledInProfile(context.cache,context.serverName))Main.startPreloadedReporting(context);
                else PreloadConsole.discard();
            }catch(Exception failure){
                PreloadConsole.discard();
                Logger.getLogger("ServerPanelBridge").warning("Preload identity unavailable ("+failure.getClass().getSimpleName()+"); normal mod bootstrap remains available");
            }finally{pending=false;Main.resumeNormalBootstrap();}
        },"ServerPanelBridge-Preload");worker.setDaemon(true);worker.start();
        } catch(RuntimeException failure) {
            pending=false;PreloadConsole.discard();
            Logger.getLogger("ServerPanelBridge").warning("Preload capture unavailable ("+failure.getClass().getSimpleName()+"); normal bootstrap retained");
            Main.resumeNormalBootstrap();
        }
    }
    static boolean pending(){return pending;}
    static boolean invoked(){return invoked;}
}
