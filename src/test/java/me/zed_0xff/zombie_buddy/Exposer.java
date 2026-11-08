package me.zed_0xff.zombie_buddy;
public final class Exposer {
    public static boolean fail;
    public static Class<?> exposed;
    public static String alias;
    public static void exposeClass(Class<?> type, String name) { if(fail)throw new IllegalStateException("Fixture exposer unavailable");exposed = type; alias = name; }
}
