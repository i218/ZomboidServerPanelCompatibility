package io.serverpanelbridge;
import java.util.Properties;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
/** Second JVM: old session's commands must not execute. */
public final class PanelSessionProbe {
    public static void main(String[] args)throws Exception {
        Properties p=new Properties();p.setProperty("panelUrl",args[0]);p.setProperty("hostId","pz-integration-test");p.setProperty("stopEnabled","true");p.setProperty("restartEnabled","true");
        BridgeConfig config=new BridgeConfig(p,"test");if(config.sessionId.equals(args[1]))throw new AssertionError("Session reused across JVMs");
        Logger log=Logger.getAnonymousLogger();Path root=Files.createTempDirectory("bridge-session-probe-");AtomicInteger stops=new AtomicInteger();
        try(PanelReporter reporter=new PanelReporter(config,new JvmMetrics(),()->System.nanoTime()/1_000_000,log);
            CommandService commands=new CommandService(config,root,stops::incrementAndGet,System::currentTimeMillis,log)){
            reporter.sendOnce();commands.pollOnce();commands.onGameTick();if(stops.get()!=0)throw new AssertionError("Previous session command executed");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(path);}}
        System.out.println("PASS new JVM session does not execute previous session commands");
    }
}
