package io.serverpanelbridge;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
public final class ModRestartTests {
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean() && System.nanoTime()<until)Thread.sleep(10);
        check(condition.getAsBoolean(),"Timed out waiting for worker");
    }
    public static void main(String[] args)throws Exception {
        Path folder=Files.createTempDirectory("bridge-local-api-");Logger log=Logger.getAnonymousLogger();log.setLevel(Level.OFF);
        Properties p=new Properties();p.setProperty("enabled","false");p.setProperty("commandsEnabled","false");p.setProperty("restartEnabled","true");p.setProperty("restartRequesters","WorkshopSentinel");
        AtomicInteger stops=new AtomicInteger(),arms=new AtomicInteger(),prepared=new AtomicInteger();AtomicBoolean ready=new AtomicBoolean();
        RestartAdapter restart=()->{prepared.incrementAndGet();ready.set(true);return new RestartAdapter.Prepared(){public void arm(){arms.incrementAndGet();}public void close(){}};};
        try(CommandService service=new CommandService(new BridgeConfig(p,"test"),folder,stops::incrementAndGet,restart,System::currentTimeMillis,log)) {
            service.start();check(service.requestModRestart("OtherMod","r").equals("REJECTED_DISABLED"),"Allowlist required");
            check(service.requestModRestart("WorkshopSentinel","one").equals("QUEUED"),"Local request accepted without panel polling");
            check(service.requestModRestart("WorkshopSentinel","one").equals("QUEUED"),"Duplicate request is idempotent");
            check(service.requestModRestart("WorkshopSentinel","two").equals("REJECTED_BUSY"),"Bound one active local request");
            waitFor(ready::get);
            waitFor(()->{service.onGameTick();return stops.get()==1;});
            waitFor(()->service.modRestartStatus("WorkshopSentinel","one").equals("ARMED"));
            check(arms.get()==1 && prepared.get()==1,"Exactly one helper and shutdown");
            check(service.requestModRestart("WorkshopSentinel","one").equals("ARMED"),"Completed duplicate cannot relaunch");
            check(service.requestModRestart("WorkshopSentinel","three").equals("REJECTED_BUSY"),"Shared shutdown gate");
        }
        p.setProperty("restartEnabled","false");
        try(CommandService disabled=new CommandService(new BridgeConfig(p,"test"),folder.resolve("disabled"),stops::incrementAndGet,restart,System::currentTimeMillis,log)){
            disabled.start();check(disabled.requestModRestart("WorkshopSentinel","one").equals("REJECTED_DISABLED"),"Bridge restart permission required");
        }
        p.setProperty("restartEnabled","true");
        try(CommandService broken=new CommandService(new BridgeConfig(p,"test"),folder.resolve("broken"),stops::incrementAndGet,()->{throw new java.io.IOException("unavailable");},System::currentTimeMillis,log)){
            broken.start();broken.requestModRestart("WorkshopSentinel","one");waitFor(()->broken.modRestartStatus("WorkshopSentinel","one").equals("FAILED_PREPARATION"));check(stops.get()==1,"Failed helper cannot stop server");
        }
        if(args.length>0) {
            AtomicInteger crossed=new AtomicInteger();
            try(CommandService live=new CommandService(new BridgeConfig(p,"test"),folder.resolve("cross"),crossed::incrementAndGet,restart,System::currentTimeMillis,log)) {
                live.start();
                java.lang.reflect.Field field=Main.class.getDeclaredField("commands");field.setAccessible(true);
                Object previous=field.get(null);field.set(null,live);
                try {
                    Class<?> adapter=Class.forName("io.workshopsentinel.ServerPanelBridgeAdapter");
                    Object sentinel=adapter.getConstructor(Logger.class).newInstance(log);
                    boolean wasServer=zombie.network.GameServer.server;
                    zombie.network.GameServer.server=false;
                    try {adapter.getMethod("requestShutdown").invoke(sentinel);throw new AssertionError("Client must be refused");}
                    catch(java.lang.reflect.InvocationTargetException expected){check(expected.getCause() instanceof IllegalStateException,"Explicit client refusal");}
                    zombie.network.GameServer.server=true;
                    adapter.getMethod("requestShutdown").invoke(sentinel);
                    waitFor(()->{live.onGameTick();return crossed.get()==1;});
                    check(crossed.get()==1,"Real Sentinel adapter reaches real Bridge API without panel");
                } finally {field.set(null,previous);zombie.network.GameServer.server=false;}
            }
            Class<?> sentinelType=Class.forName("io.workshopsentinel.ServerPanelBridgeAdapter");
            java.net.URL jar=sentinelType.getProtectionDomain().getCodeSource().getLocation();
            try(java.net.URLClassLoader standalone=new java.net.URLClassLoader(new java.net.URL[]{jar},ClassLoader.getPlatformClassLoader())) {
                Class<?> type=standalone.loadClass("io.workshopsentinel.ServerPanelBridgeAdapter");
                Object adapter=type.getConstructor(Logger.class).newInstance(log);
                try {type.getMethod("requestShutdown").invoke(adapter);throw new AssertionError("Missing Bridge must fail closed");}
                catch(java.lang.reflect.InvocationTargetException expected){check(expected.getCause() instanceof ClassNotFoundException,"No hidden Bridge dependency or process fallback");}
            }
            System.out.println("PASS real Sentinel JAR -> Bridge public API -> shared restart queue; simulated game action only");
        }
        System.out.println("PASS optional mod restart API: disabled/allowlist/idempotency/busy/preparation failure; simulated shutdown only; no panel required");
    }
}
