package io.serverpanelbridge;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

/** Separate JVM modes exercise Main.main before any game callback. */
public final class EarlyStartupTests {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);System.out.println("PASS "+why);}
    static Object field(String name)throws Exception {var f=Main.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    static void waitFor(java.util.function.BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(9);while(!condition.getAsBoolean() && System.nanoTime()<deadline)Thread.sleep(20);if(!condition.getAsBoolean())throw new AssertionError("Early startup condition timed out");}
    static void cleanup()throws Exception {
        ScheduledExecutorService boot=(ScheduledExecutorService)field("bootstrap");if(boot!=null){boot.shutdownNow();boot.awaitTermination(5,TimeUnit.SECONDS);}
        for(String name:List.of("commands","output","reporter")){Object value=field(name);if(value!=null)((AutoCloseable)value).close();}
        for(Handler h:Logger.getLogger("ServerPanelBridge").getHandlers()){h.close();Logger.getLogger("ServerPanelBridge").removeHandler(h);}
    }
    public static void main(String[] args)throws Exception {
        if(args.length==0){
            for(String mode:List.of("client","early","late-identity","no-exposer")){
                Process child=new ProcessBuilder(ProcessHandle.current().info().command().get(),"-Dfile.encoding=UTF-8","-cp",System.getProperty("java.class.path"),EarlyStartupTests.class.getName(),mode).inheritIO().start();
                if(!child.waitFor(25,TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("Fixture timed out: "+mode);}
                if(child.exitValue()!=0)throw new AssertionError("Early startup fixture failed: "+mode);
            }
            return;
        }
        String mode=args[0];Path root=Files.createTempDirectory("bridge-early-startup-");
        HttpServer panel=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService handlers=Executors.newCachedThreadPool();panel.setExecutor(handlers);
        AtomicInteger beats=new AtomicInteger(),polls=new AtomicInteger();AtomicBoolean marker=new AtomicBoolean();AtomicReference<String> health=new AtomicReference<>("");
        panel.createContext("/",exchange->{try{
            String path=exchange.getRequestURI().getPath(),body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            if(path.endsWith("/heartbeat")){health.set((String)((Map<?,?>)Json.parse(body)).get("health"));beats.incrementAndGet();exchange.sendResponseHeaders(204,-1);}
            else if(path.endsWith("/output")){if(body.contains("EARLY-CONSOLE-MARKER"))marker.set(true);exchange.sendResponseHeaders(204,-1);}
            else {polls.incrementAndGet();byte[] bytes="[]".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}
        }finally{exchange.close();}});panel.start();
        Path config=root.resolve("ServerPanelBridge/test-server/ServerPanelBridge.properties");Files.createDirectories(config.getParent());
        Files.writeString(config,"panelUrl=http://127.0.0.1:"+panel.getAddress().getPort()+"\nheartbeatSeconds=5\ncaptureConsoleOutput=true\nrestartEnabled=true\nrestartRequesters=FixtureMod\n",StandardCharsets.UTF_8);
        System.setProperty("serverpanelbridge.testCache",root.toString());
        try {
            if(mode.equals("client")){
                zombie.network.GameServer.server=false;Main.main(new String[0]);Main.onTick();Thread.sleep(500);
                check(field("bootstrap")==null && field("reporter")==null && beats.get()==0,"client starts no reporting worker or HTTP");
                check(!Files.exists(config.getParent().resolve("ServerPanelBridge-0.log")),"client creates no runtime log");return;
            }
            zombie.network.GameServer.server=true;
            if(mode.equals("late-identity"))zombie.network.GameServer.serverName=null;
            me.zed_0xff.zombie_buddy.Exposer.fail=mode.equals("no-exposer");
            long start=System.nanoTime();Main.main(new String[0]);
            check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(2),"Java entry returns while reporting initializes in background");
            if(mode.equals("late-identity")){
                Thread.sleep(400);check(beats.get()==0 && field("reporter")==null,"unready identity is not guessed or registered");zombie.network.GameServer.serverName="test-server";
            }
            waitFor(()->beats.get()>0);check(health.get().equals("Starting"),"heartbeat is sent before any game callback with Starting health");
            check(field("commands")==null && polls.get()==0 && ModRestartApi.requestRestart("FixtureMod","early-id").equals("REJECTED_UNAVAILABLE"),"controls stay unavailable throughout startup");
            System.out.println("EARLY-CONSOLE-MARKER");waitFor(marker::get);check(marker.get(),"Java console reaches panel Output before first game callback");
            Object reporter=field("reporter"),output=field("output");Main.main(new String[0]);Thread.sleep(300);
            check(field("reporter")==reporter && field("output")==output,"repeated entry does not install duplicate workers or console capture");
            if(!mode.equals("no-exposer")){
                Main.onTick();waitFor(()->polls.get()>0);waitFor(()->health.get().equals("Healthy"));
                check(field("commands")!=null && zombie.network.ServerMap.quitRequests==0,"first callback enables controls without performing a shutdown");
                check(field("reporter")==reporter && field("output")==output,"game callback reuses early reporter/capture and advances health");
            } else check(field("commands")==null,"reporting survives Lua exposure failure without enabling controls");
        }finally{cleanup();panel.stop(0);handlers.shutdownNow();try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p);}}
    }
}
