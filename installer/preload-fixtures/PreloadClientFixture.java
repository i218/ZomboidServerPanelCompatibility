public final class PreloadClientFixture {
    public static void main(String[] args)throws Exception {
        Class<?> entry=Class.forName("io.serverpanelbridge.PreMain");var method=entry.getDeclaredMethod("invoked");method.setAccessible(true);
        if((boolean)method.invoke(null))throw new AssertionError("Client activated preload capture");
        System.out.println("PASS native preload does nothing on a client main");
    }
}
