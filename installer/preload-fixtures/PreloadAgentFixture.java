import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.Map;
/** Actual ZombieBuddy PREMAIN loader in an isolated javaagent harness; no Agent.premain patches/UI. */
public final class PreloadAgentFixture {
    public static void premain(String root,Instrumentation instrumentation) throws Exception {
        Class<?> agent=Class.forName("me.zed_0xff.zombie_buddy.Agent");
        ((Map<String,String>)agent.getField("arguments").get(null)).put("config_dir",Paths.get(root,"agent").toString());
        Class<?> loader=Class.forName("me.zed_0xff.zombie_buddy.Loader");
        Field inst=loader.getDeclaredField("g_instrumentation");inst.setAccessible(true);inst.set(null,instrumentation);
        Method init=loader.getDeclaredMethod("initConfig");init.setAccessible(true);init.invoke(null);
        Method preload=loader.getDeclaredMethod("preloadMods");preload.setAccessible(true);preload.invoke(null);
        System.out.println("PRELOAD-WORKSHOP-BUFFER");
        System.setProperty("fixture.agent.returned","true");
    }
}
