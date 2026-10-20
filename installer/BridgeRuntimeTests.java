import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/** Real ZombieBuddy/Kahlua and native connection serializer; isolated game-state fixtures. */
public final class BridgeRuntimeTests {
    private static Object env, thread;
    private static Method compile, call;
    private static Object run(String code) throws Exception {
        return call.invoke(thread, compile.invoke(null, code, "bridge-runtime-test", env), new Object[0]);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static int advertisedCount() throws Exception {
        Class<?> writer = Class.forName("zombie.core.network.ByteBufferWriter");
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        Object output = writer.getConstructor(ByteBuffer.class).newInstance(buffer);
        Method serialize = Class.forName("zombie.network.ConnectionDetails").getDeclaredMethod("writeMods", writer);
        serialize.setAccessible(true);
        serialize.invoke(null, output);
        buffer.flip();
        return buffer.getInt();
    }
    public static void main(String[] args) throws Exception {
        Path fixture = Files.createTempDirectory("serverpanelbridge-runtime-");
        try {
            Path config = fixture.resolve("ServerPanelBridge.properties");
            Files.writeString(config, "enabled=false\n");
            System.setProperty("serverpanelbridge.cachedir", fixture.toString());
            System.setProperty("serverpanelbridge.config", config.toString());
            Class<?> table=Class.forName("se.krka.kahlua.vm.KahluaTable"), platformType=Class.forName("se.krka.kahlua.vm.Platform");
            Class<?> platformClass=Class.forName("se.krka.kahlua.j2se.J2SEPlatform");
            Object platform=platformClass.getConstructor().newInstance();
            env=platformClass.getMethod("newTable").invoke(platform);
            table.getMethod("rawset",Object.class,Object.class).invoke(env,"_G",env);
            Class.forName("se.krka.kahlua.stdlib.BaseLib").getMethod("register",table).invoke(null,env);
            Class<?> converters=Class.forName("se.krka.kahlua.converter.KahluaConverterManager");
            Class<?> lua=Class.forName("zombie.Lua.LuaManager");
            lua.getField("env").set(null,env);
            lua.getField("exposer").set(null,Class.forName("zombie.Lua.LuaManager$Exposer")
                .getConstructor(converters,platformType,table).newInstance(converters.getConstructor().newInstance(),platform,env));
            Class.forName("io.serverpanelbridge.Main").getMethod("main",String[].class).invoke(null,(Object)new String[0]);
            require(table.getMethod("rawget",Object.class).invoke(env,"ServerPanelBridge")!=null, "Missing exposed bridge");
            Class<?> threadClass=Class.forName("se.krka.kahlua.vm.KahluaThread");
            thread=threadClass.getConstructor(platformType,table).newInstance(platform,env);
            threadClass.getField("debugOwnerThread").set(thread,Thread.currentThread());
            compile=Class.forName("se.krka.kahlua.luaj.compiler.LuaCompiler").getMethod("loadstring",String.class,String.class,table);
            call=threadClass.getMethod("call",Object.class,Object[].class);
            run("isServer = function() return false end; started = nil; paused = nil; Events = { OnServerStarted = { Add = function(f) started = f end }, OnTickEvenPaused = { Add = function(f) paused = f end } }");
            String source=Files.readString(Paths.get(args[0]).resolve("42/media/lua/server/ServerPanelBridge.lua"));
            run(source);
            require(Boolean.TRUE.equals(run("return started == nil and paused == nil")), "Client registered server callbacks");
            run("ServerPanelBridge.onTick()");
            require(Boolean.TRUE.equals(run("return ServerPanelBridge.restartApiVersion() == 1 and ServerPanelBridge.requestRestart('SchedulerAlpha', 'probe') == 'REJECTED_NOT_SERVER'")), "Lua restart API client guard unavailable");
            require(!Files.exists(fixture.resolve("ServerPanelBridge-0.log")), "Client created runtime files");
            require(advertisedCount()==2, "Before startup core should be advertised in fixture");
            zombie.network.GameServer.server=true;
            run("isServer = function() return true end");
            run(source);
            require(Boolean.TRUE.equals(run("return type(started) == 'function' and type(paused) == 'function'")), "Missing unpaused startup/paused callbacks");
            run("started()");
            require(Boolean.TRUE.equals(run("return ServerPanelBridge.requestRestart('SchedulerAlpha', 'probe') == 'REJECTED_UNAVAILABLE' and ServerPanelBridge.restartStatus('SchedulerAlpha', 'probe') == 'UNKNOWN'")), "Lua restart API bypassed disabled local service");
            require(Boolean.TRUE.equals(run("return ServerPanelBridge.isClientOptionalReady()")), "Startup optional mode not confirmed");
            require(advertisedCount()==1, "Native handshake still requires core");
            require(zombie.network.GameServer.ServerMods.equals(List.of("Other")), "Other mods altered");
            require(zombie.ZomboidFileSystem.instance.getModIDs().contains("ServerPanelBridge"), "Server runtime removed");
            zombie.network.GameServer.ServerMods.add("ServerPanelBridge");
            Field last=Class.forName("io.serverpanelbridge.Main").getDeclaredField("lastOptionalCheck");
            last.setAccessible(true); last.setLong(null,System.nanoTime()-6_000_000_000L);
            run("paused()");
            require(advertisedCount()==1, "Paused callback did not repair required list");
            System.out.println("PASS real ZombieBuddy exposure, client guard, startup/paused callbacks, native handshake exclusion and runtime retention");
        } finally {
            zombie.network.GameServer.server=false;
            for(java.util.logging.Handler handler : java.util.logging.Logger.getLogger("ServerPanelBridge").getHandlers()) {
                handler.close(); java.util.logging.Logger.getLogger("ServerPanelBridge").removeHandler(handler);
            }
            System.clearProperty("serverpanelbridge.cachedir"); System.clearProperty("serverpanelbridge.config");
            try(java.util.stream.Stream<Path> paths=Files.walk(fixture)) {
                for(Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }
}
