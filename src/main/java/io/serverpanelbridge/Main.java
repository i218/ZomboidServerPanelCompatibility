package io.serverpanelbridge;

import java.nio.file.*;
import java.util.logging.*;
import java.util.concurrent.*;

public final class Main {
    private static final Logger LOG = Logger.getLogger("ServerPanelBridge");
    private static boolean registered, failedGuard, bootstrapStarted, controlsInitialized;
    private static volatile boolean normalEntry;
    private static volatile boolean initialized;
    private static volatile BridgeConfig runtimeConfig;
    private static volatile Path runtimeDirectory;
    private static volatile long lastObservedTick=Long.MIN_VALUE;
    private static ScheduledExecutorService bootstrap;
    private static boolean readinessLogged;
    private static boolean optionalChecked, optionalConfirmed, optionalFailureLogged;
    private static long lastOptionalCheck;
    private static volatile PanelReporter reporter;
    private static volatile CommandService commands;
    private static OutputReporter output;
    private Main() { }
    public static synchronized void main(String[] args) {
        normalEntry=true;startEarlyReporting();
        if (registered) return;
        try {
            Class<?> exposer = Class.forName("me.zed_0xff.zombie_buddy.Exposer");
            exposer.getMethod("exposeClass", Class.class, String.class).invoke(null, LuaBridge.class, "ServerPanelBridge");
            registered = true;
            LOG.info("Java bridge registered; early reporting scheduled when dedicated-server identity is ready");
        } catch (Exception error) { LOG.log(Level.SEVERE, "ZombieBuddy bridge registration failed", error); }
    }
    /** No client writes/network; initialization itself runs outside the loader/game thread. */
    private static synchronized void startEarlyReporting() {
        if(bootstrapStarted || PreMain.pending())return;
        try {if(!PzRuntime.isDedicatedServer())return;}
        catch(ReflectiveOperationException | RuntimeException unavailable){return;}
        bootstrapStarted=true;
        bootstrap=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ServerPanelBridge-Bootstrap");t.setDaemon(true);return t;});
        bootstrap.scheduleWithFixedDelay(Main::bootstrapAttempt,0,250,TimeUnit.MILLISECONDS);
    }
    static synchronized void startPreloadedReporting(StartupContext context) {
        if(bootstrapStarted)return;
        bootstrapStarted=true;
        bootstrap=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ServerPanelBridge-Bootstrap");t.setDaemon(true);return t;});
        bootstrap.execute(()->initializeReporting(context.serverName,context.config));
    }
    static void resumeNormalBootstrap(){if(normalEntry)startEarlyReporting();}
    private static void bootstrapAttempt() {
        String serverName;Path file;
        try {
            if(!PzRuntime.isDedicatedServer())return;
            serverName=PzRuntime.serverName();
            Path defaultFile=PzRuntime.cacheDirectory().resolve("ServerPanelBridge").resolve(serverName).resolve("ServerPanelBridge.properties");
            file=Paths.get(System.getProperty("serverpanelbridge.config",defaultFile.toString())).toAbsolutePath();
            if(PreMain.invoked() && !StartupContext.enabledInProfile(PzRuntime.cacheDirectory(),serverName)) {
                PreloadConsole.discard();initialized=true;bootstrap.shutdown();return;
            }
        } catch(java.io.IOException | ReflectiveOperationException | RuntimeException unavailable) {
            if(!readinessLogged){LOG.info("Early reporting waiting for dedicated-server name/cache directory");readinessLogged=true;}
            return;
        }
        initializeReporting(serverName,file);
    }
    private static void initializeReporting(String serverName,Path file) {
        FileHandler handler=null;
        try {
            BridgeConfig config=BridgeConfig.load(file,serverName);
            if(!config.enabled || !config.outputEnabled || !config.captureConsoleOutput)PreloadConsole.discard();
            Files.createDirectories(file.getParent());
            handler=new FileHandler(file.getParent().resolve("ServerPanelBridge-%g.log").toString(),1024*1024,3,true);
            handler.setEncoding("UTF-8");handler.setFormatter(new SimpleFormatter());LOG.addHandler(handler);
            if(config.enabled) {
                reporter=new PanelReporter(config,new JvmMetrics(),()->System.nanoTime()/1_000_000,LOG);
                Runtime.getRuntime().addShutdownHook(new Thread(reporter::close,"ServerPanelBridge-Cleanup"));
                // Install capture before sending heartbeat; output waits for registration.
                if(config.outputEnabled) {
                    output=new OutputReporter(config,LOG,reporter::isRegistered);
                    Runtime.getRuntime().addShutdownHook(new Thread(output::close,"ServerPanelBridge-OutputCleanup"));
                    output.start();
                }
                long observed=lastObservedTick;if(observed!=Long.MIN_VALUE)reporter.onGameTick(observed);
                reporter.start();
            }
            runtimeDirectory=file.getParent();runtimeConfig=config;
            LOG.info("Early monitoring "+(config.enabled?"enabled":"disabled")+"; waiting for first server callback before controls; hostId="+config.hostId);
        } catch(Exception error) {
            PreloadConsole.discard();if(output!=null)output.close();if(reporter!=null)reporter.close();
            if(handler!=null){handler.close();LOG.removeHandler(handler);}
            LOG.log(Level.SEVERE,"Early reporting initialization failed; retry requires server restart",error);
        } finally {initialized=true;bootstrap.shutdown();}
    }
    static String requestModRestart(String requester,String requestId) {
        try {if(!PzRuntime.isDedicatedServer())return "REJECTED_NOT_SERVER";}
        catch(ReflectiveOperationException unavailable){return "REJECTED_NOT_SERVER";}
        CommandService active=commands;
        return active==null?"REJECTED_UNAVAILABLE":active.requestModRestart(requester,requestId);
    }
    static String modRestartStatus(String requester,String requestId) {
        CommandService active=commands;return active==null?"UNKNOWN":active.modRestartStatus(requester,requestId);
    }
    static synchronized boolean isClientOptionalReady() { return optionalConfirmed; }
    static synchronized void onTick() {
        if (!registered || failedGuard) return;
        try {
            if (!PzRuntime.isDedicatedServer()) return;
        } catch (Exception error) {
            failedGuard = true;
            LOG.log(Level.SEVERE, "Dedicated-server guard unavailable; bridge disabled", error);
            return;
        }
        lastObservedTick=System.nanoTime()/1_000_000;
        startEarlyReporting();
        if(initialized && runtimeConfig!=null && !controlsInitialized) {
            controlsInitialized=true;
            BridgeConfig config=runtimeConfig;
            try {
                if((config.enabled && config.commandsEnabled) || !config.restartRequesters.isEmpty()) {
                    commands=new CommandService(config,runtimeDirectory.resolve("commands"),new PzShutdownAdapter(config.stopWhenPlayersOnline),System::currentTimeMillis,LOG);
                    Runtime.getRuntime().addShutdownHook(new Thread(commands::close,"ServerPanelBridge-ControlCleanup"));
                    commands.start();
                }
            } catch(Exception error){LOG.log(Level.SEVERE,"Control initialization failed; retry requires server restart",error);}
        }
        long now = System.nanoTime();
        if (!optionalChecked || now - lastOptionalCheck >= 5_000_000_000L) {
            optionalChecked = true; lastOptionalCheck = now;
            try {
                OptionalClientAdapter.apply();
                if (!optionalConfirmed) LOG.info("Client optional mode enabled; server runtime retained");
                optionalConfirmed = true; optionalFailureLogged = false;
            } catch (Exception error) {
                optionalConfirmed = false;
                if (!optionalFailureLogged) LOG.log(Level.SEVERE, "Cannot exclude ServerPanelBridge from client requirements; retrying on server ticks", error);
                optionalFailureLogged = true;
            }
        }
        if (reporter != null) reporter.onGameTick(lastObservedTick);
        if (commands != null) commands.onGameTick();
    }
}
