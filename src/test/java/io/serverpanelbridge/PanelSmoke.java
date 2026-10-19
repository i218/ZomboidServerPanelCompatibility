package io.serverpanelbridge;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.nio.file.*;
public final class PanelSmoke {
    public static void main(String[] args) throws Exception {
        Properties p=new Properties();
        p.setProperty("panelUrl",args[0]);p.setProperty("hostId","pz-integration-test");p.setProperty("hostName","Dedicated integration test");
        p.setProperty("stopEnabled","true");p.setProperty("requestTimeoutSeconds","5");
        BridgeConfig config=new BridgeConfig(p,"test");
        Logger logger=Logger.getAnonymousLogger();
        try(PanelReporter reporter=new PanelReporter(config,new JvmMetrics(),()->System.nanoTime()/1_000_000,logger);
            OutputReporter output=new OutputReporter(config,logger,reporter::isRegistered)){
            reporter.sendOnce();output.offer("stdout","fixture stdout");output.offer("stderr","fixture stderr");output.offer("log","fixture log");output.sendOnce();
        }
        AtomicInteger requested=new AtomicInteger(),armed=new AtomicInteger();
        Path directory=Files.createTempDirectory("bridge-panel-command-");
        try(CommandService commands=new CommandService(config,directory,requested::incrementAndGet,()->new RestartAdapter.Prepared(){public void arm(){armed.incrementAndGet();}public void close(){}},System::currentTimeMillis,logger)){
            commands.pollOnce();commands.onGameTick();commands.pollOnce();
            if(requested.get()!=1) throw new AssertionError("Expected one simulated graceful stop");
        }
        p.setProperty("hostId","pz-restart-integration-test");p.setProperty("restartEnabled","true");
        BridgeConfig restartConfig=new BridgeConfig(p,"test");
        try(PanelReporter reporter=new PanelReporter(restartConfig,new JvmMetrics(),()->System.nanoTime()/1_000_000,logger)){reporter.sendOnce();}
        try(CommandService commands=new CommandService(restartConfig,directory.resolve("restart"),requested::incrementAndGet,()->new RestartAdapter.Prepared(){public void arm(){armed.incrementAndGet();}public void close(){}},System::currentTimeMillis,logger)) {
            commands.pollOnce();commands.onGameTick();commands.pollOnce();
            if(requested.get()!=2 || armed.get()!=1) throw new AssertionError("Expected one simulated restart");
        } finally {
            try(var paths=Files.walk(directory)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(path);}
        }
        System.out.println("PASS actual panel leases/acknowledgements; simulated game action only");
    }
}
