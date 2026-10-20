package io.serverpanelbridge;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

/** Independent requesters; no Sentinel classes or JAR on this test's classpath. */
public final class GenericModApiTests {
    private static int passed;
    private static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);passed++;System.out.println("PASS "+reason);}
    private static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean() && System.nanoTime()<deadline)Thread.sleep(10);
        if(!condition.getAsBoolean())throw new AssertionError("Worker did not complete");
    }
    private static ScheduledExecutorService worker(CommandService service)throws Exception{Field f=CommandService.class.getDeclaredField("worker");f.setAccessible(true);return (ScheduledExecutorService)f.get(service);}
    private static RestartAdapter restart(AtomicInteger prepared,AtomicInteger closed){return ()->{prepared.incrementAndGet();return new RestartAdapter.Prepared(){public void arm(){}public void close(){closed.incrementAndGet();}};};}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("bridge-generic-mods-");Logger log=Logger.getAnonymousLogger();log.setLevel(Level.OFF);
        Properties p=new Properties();p.setProperty("enabled","false");p.setProperty("commandsEnabled","false");p.setProperty("restartEnabled","true");p.setProperty("restartRequesters","SchedulerAlpha; MaintenanceMod,Third.Mod");
        BridgeConfig config=new BridgeConfig(p,"test");AtomicInteger prepared=new AtomicInteger(),cancelled=new AtomicInteger(),stops=new AtomicInteger();
        AtomicLong now=new AtomicLong(System.currentTimeMillis());
        try {
            check(config.restartRequesters.equals(Set.of("SchedulerAlpha","MaintenanceMod","Third.Mod")),"multiple independent requester names supported without plugin-specific registration");
            Field active=Main.class.getDeclaredField("commands");active.setAccessible(true);Object previous=active.get(null);boolean wasServer=zombie.network.GameServer.server;
            try(CommandService service=new CommandService(config,root.resolve("multiple"),()->{throw new IllegalStateException("Players online");},restart(prepared,cancelled),now::get,log)){
                service.start();active.set(null,service);zombie.network.GameServer.server=false;
                check(ModRestartApi.apiVersion()==1 && LuaBridge.restartApiVersion()==1,"Java and Lua expose the same stable API version");
                check(ModRestartApi.requestRestart("SchedulerAlpha","same-id").equals("REJECTED_NOT_SERVER"),"public Java entry refuses clients");
                zombie.network.GameServer.server=true;
                check(LuaBridge.requestRestart("SchedulerAlpha","same-id").equals("QUEUED"),"generic Lua entry accepts an authorized independent mod");
                await(()->{service.onGameTick();return service.modRestartStatus("SchedulerAlpha","same-id").equals("FAILED_EXECUTION");});
                check(ModRestartApi.status("SchedulerAlpha","same-id").equals("FAILED_EXECUTION") && cancelled.get()==1,"public status reports shared game-thread policy refusal and cancels helper");
                check(ModRestartApi.requestRestart("MaintenanceMod","same-id").equals("QUEUED"),"equal request IDs from different authorized senders are independent");
                await(()->{service.onGameTick();return LuaBridge.restartStatus("MaintenanceMod","same-id").equals("FAILED_EXECUTION");});
                check(prepared.get()==2 && cancelled.get()==2,"both generic requesters use the existing restart adapter and shutdown gate");
                check(ModRestartApi.requestRestart("DeniedMod","id").equals("REJECTED_DISABLED"),"unlisted requester refused");
                check(ModRestartApi.requestRestart("SchedulerAlpha","bad:id").equals("REJECTED_ID") && ModRestartApi.requestRestart(null,null).equals("REJECTED_ID"),"malformed/null identifiers return defined refusal");
                check(ModRestartApi.status(null,null).equals("UNKNOWN") && LuaBridge.restartStatus("SchedulerAlpha","missing").equals("UNKNOWN"),"unknown or invalid status lookup returns UNKNOWN");
            } finally {active.set(null,previous);zombie.network.GameServer.server=wasServer;}
            CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
            int before=prepared.get();
            try(CommandService service=new CommandService(config,root.resolve("expiry"),stops::incrementAndGet,restart(prepared,cancelled),now::get,log)){
                service.start();worker(service).execute(()->{blocked.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
                if(!blocked.await(5,TimeUnit.SECONDS))throw new AssertionError("Worker fixture not ready");
                check(service.requestModRestart("SchedulerAlpha","queued-expiry").equals("QUEUED"),"request acceptance does not wait for an occupied worker");
                now.addAndGet(60_000);release.countDown();await(()->service.modRestartStatus("SchedulerAlpha","queued-expiry").equals("FAILED_PREPARATION"));service.onGameTick();
                check(prepared.get()==before && stops.get()==0,"60-second TTL starts on acceptance and expires before helper creation");
            }finally{release.countDown();}
            AtomicInteger concurrentPrepared=new AtomicInteger();CountDownLatch preparation=new CountDownLatch(1),finishPreparation=new CountDownLatch(1);
            ExecutorService callers=Executors.newFixedThreadPool(12);
            try(CommandService service=new CommandService(config,root.resolve("concurrent"),stops::incrementAndGet,()->{
                concurrentPrepared.incrementAndGet();preparation.countDown();finishPreparation.await();return new RestartAdapter.Prepared(){public void arm(){}public void close(){}};
            },now::get,log)){
                service.start();CountDownLatch start=new CountDownLatch(1);List<Future<String>> results=new ArrayList<>();
                for(int i=0;i<12;i++)results.add(callers.submit(()->{start.await();return service.requestModRestart("Third.Mod","concurrent-id");}));start.countDown();
                for(Future<String> result:results)if(!result.get(5,TimeUnit.SECONDS).equals("QUEUED"))throw new AssertionError("Concurrent duplicate did not reuse outcome");
                if(!preparation.await(5,TimeUnit.SECONDS))throw new AssertionError("Helper fixture not ready");
                check(concurrentPrepared.get()==1,"concurrent identical requests all reuse one queued outcome/helper");
                check(service.requestModRestart("MaintenanceMod","different-id").equals("REJECTED_BUSY"),"different mod cannot overlap an active request");
                finishPreparation.countDown();await(()->{service.onGameTick();return service.modRestartStatus("Third.Mod","concurrent-id").equals("ARMED");});
                check(stops.get()==1 && service.requestModRestart("Third.Mod","concurrent-id").equals("ARMED"),"duplicate after arming never starts another shutdown");
            }finally{finishPreparation.countDown();callers.shutdownNow();}
            int closeBefore=cancelled.get();
            CommandService closing=new CommandService(config,root.resolve("close"),stops::incrementAndGet,restart(prepared,cancelled),now::get,log);
            closing.start();closing.requestModRestart("MaintenanceMod","close-id");
            // A worker barrier establishes that the prepared ticket has entered the pending queue.
            worker(closing).submit(()->{}).get(5,TimeUnit.SECONDS);closing.close();
            check(closing.modRestartStatus("MaintenanceMod","close-id").equals("FAILED_CLOSED") && cancelled.get()==closeBefore+1,"bridge close cancels pending helper and publishes a terminal status");
            check(closing.requestModRestart("MaintenanceMod","new-id").equals("REJECTED_UNAVAILABLE"),"closed bridge refuses new requests");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path);}}
        System.out.println("RESULT: "+passed+" generic mod API assertions passed; no real shutdown or panel dependency");
    }
}
