package io.serverpanelbridge;

import java.nio.file.*;
import java.util.logging.*;

public final class Main {
    private static final Logger LOG = Logger.getLogger("ServerPanelBridge");
    private static boolean registered, initialized, failedGuard;
    private static boolean optionalChecked, optionalConfirmed, optionalFailureLogged;
    private static long lastOptionalCheck;
    private static PanelReporter reporter;
    private static volatile CommandService commands;
    private static OutputReporter output;
    private Main() { }
    public static synchronized void main(String[] args) {
        if (registered) return;
        try {
            Class<?> exposer = Class.forName("me.zed_0xff.zombie_buddy.Exposer");
            exposer.getMethod("exposeClass", Class.class, String.class).invoke(null, LuaBridge.class, "ServerPanelBridge");
            registered = true;
            LOG.info("Java bridge registered; awaiting dedicated-server tick");
        } catch (Exception error) { LOG.log(Level.SEVERE, "ZombieBuddy bridge registration failed", error); }
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
        if (!initialized) {
            initialized = true;

            try {
                String serverName = PzRuntime.serverName();
                Path defaultFile = PzRuntime.cacheDirectory().resolve("ServerPanelBridge").resolve(serverName).resolve("ServerPanelBridge.properties");
                Path file = Paths.get(System.getProperty("serverpanelbridge.config", defaultFile.toString())).toAbsolutePath();
                BridgeConfig config = BridgeConfig.load(file, serverName);
                Files.createDirectories(file.getParent());
                FileHandler handler = new FileHandler(file.getParent().resolve("ServerPanelBridge-%g.log").toString(), 1024 * 1024, 3, true);
                handler.setEncoding("UTF-8"); handler.setFormatter(new SimpleFormatter()); LOG.addHandler(handler);
                if (config.enabled) {
                    reporter = new PanelReporter(config, new JvmMetrics(), () -> System.nanoTime() / 1_000_000, LOG);
                    Runtime.getRuntime().addShutdownHook(new Thread(reporter::close, "ServerPanelBridge-Cleanup"));
                    reporter.start();
                    if(config.outputEnabled) {
                        output=new OutputReporter(config,LOG,reporter::isRegistered);
                        Runtime.getRuntime().addShutdownHook(new Thread(output::close,"ServerPanelBridge-OutputCleanup"));
                        output.start();
                    }
                }
                if((config.enabled && config.commandsEnabled) || !config.restartRequesters.isEmpty()) {
                    commands = new CommandService(config, file.getParent().resolve("commands"), new PzShutdownAdapter(config.stopWhenPlayersOnline), System::currentTimeMillis, LOG);
                    Runtime.getRuntime().addShutdownHook(new Thread(commands::close,"ServerPanelBridge-ControlCleanup"));
                    commands.start();
                }
                LOG.info("Monitoring " + (config.enabled ? "enabled" : "disabled") + "; hostId=" + config.hostId + "; commandsEnabled=" + config.commandsEnabled + "; stopEnabled=" + config.stopEnabled + "; restartEnabled=" + config.restartEnabled);
            } catch (Exception error) { LOG.log(Level.SEVERE, "Monitoring initialization failed; retry requires server restart", error); }
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
        if (reporter != null) reporter.onGameTick();
        if (commands != null) commands.onGameTick();
    }
}
