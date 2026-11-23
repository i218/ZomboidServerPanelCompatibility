package zombie.network;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
/** No actual game: the early Workshop phase precedes normal mod entry. */
public final class GameServer {
    public static boolean server;
    public static String serverName;
    public static void main(String[] args)throws Exception {
        if(!"true".equals(System.getProperty("fixture.agent.returned")))throw new AssertionError("Agent did not run first");
        Path root=Paths.get(System.getProperty("fixture.root"));String mode=System.getProperty("fixture.mode");
        server=true;serverName="preload-test";System.setProperty("serverpanelbridge.testCache",root.toString());
        PrintStream cached=System.out;cached.println("WORKSHOP-DOWNLOAD-PHASE");
        Class<?> entry=Class.forName("io.serverpanelbridge.PreMain");Method pending=entry.getDeclaredMethod("pending");pending.setAccessible(true);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        if(mode.equals("active") || mode.equals("no-capture")){
            while(!Files.exists(root.resolve("registered")) && System.nanoTime()<deadline)Thread.sleep(20);
            if(!Files.exists(root.resolve("registered")))throw new AssertionError("No heartbeat/output before normal mod entry");
        }else while((boolean)pending.invoke(null) && System.nanoTime()<deadline)Thread.sleep(20);
        Class<?> main=Class.forName("io.serverpanelbridge.Main");Field reporter=main.getDeclaredField("reporter");reporter.setAccessible(true);Object before=reporter.get(null);
        Class<?> loader=Class.forName("me.zed_0xff.zombie_buddy.Loader");Class<?> phase=Class.forName("me.zed_0xff.zombie_buddy.Loader$Phase");
        Object normal=Enum.valueOf((Class)phase,"MAIN");Method load=loader.getDeclaredMethod("loadJar",Path.class,String.class,String.class,phase);load.setAccessible(true);
        load.invoke(null,Paths.get(System.getProperty("fixture.bridge")),"io.serverpanelbridge",null,normal);
        if((mode.equals("active") || mode.equals("no-capture")) && reporter.get(null)!=before)throw new AssertionError("Normal phase replaced early reporter");
        System.setOut(new PrintStream(cached,true,StandardCharsets.UTF_8));cached.println("CACHED-WORKSHOP-LOG");System.out.println("AFTER-NORMAL-MOD");
        Thread.sleep(1600);
        Field controls=main.getDeclaredField("commands");controls.setAccessible(true);if(controls.get(null)!=null)throw new AssertionError("Preload enabled controls");
        if(mode.equals("inactive") && reporter.get(null)!=null)throw new AssertionError("Disabled server profile reported");
        for(String name:new String[]{"output","reporter"}){Field field=main.getDeclaredField(name);field.setAccessible(true);Object value=field.get(null);if(value!=null)((AutoCloseable)value).close();}
        for(var handler:java.util.logging.Logger.getLogger("ServerPanelBridge").getHandlers()){handler.close();java.util.logging.Logger.getLogger("ServerPanelBridge").removeHandler(handler);}
        System.out.println("PASS native ZombieBuddy PREMAIN -> Workshop phase -> MAIN: "+mode);
    }
}
