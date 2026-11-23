package io.serverpanelbridge;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class PreloadTests {
    static int passed;
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);passed++;System.out.println("PASS "+why);}
    static void refuses(String...args)throws Exception{try{StartupContext.parse(args);throw new AssertionError("Ambiguous launch accepted");}catch(IOException expected){}}
    public static void main(String[] args)throws Exception {
        String home=System.getProperty("user.home");StartupContext defaults=StartupContext.parse(new String[]{"-Dprivate.fixture=do-not-log","-cp","classpath","zombie.network.GameServer"});
        check(defaults.serverName.equals("servertest") && defaults.cache.equals(Paths.get(home,"Zomboid").toAbsolutePath()),"stock dedicated defaults parsed without game classes");
        Path root=Files.createTempDirectory("bridge-preload-tests-");
        try {
            StartupContext context=StartupContext.parse(new String[]{"-cp","zombie.network.GameServer","zombie.network.GameServer","-cachedir="+root,"-servername","actual","-adminpassword","-servername"});
            check(context.serverName.equals("actual") && context.cache.equals(root),"JVM operands and password values cannot masquerade as profile options");
            StartupContext early=StartupContext.parse(new String[]{"zombie.network.GameServer","-cachedir="+root.resolve("earlier"),"-adminpassword","-cachedir="+root});
            check(early.cache.equals(root),"cache preprocessing matches the game's independent early pass");
            refuses("-jar","server.jar");refuses("@args");refuses("other.Main");refuses("zombie.network.GameServer","-custom-unknown");refuses("zombie.network.GameServer","-servername","../bad");refuses("zombie.network.GameServer","-coop");refuses("zombie.network.GameServer","-cachedir=relative");
            check(true,"unknown/indirect/client/co-op/ambiguous launches fail closed");
            Files.createDirectories(root.resolve("Server"));Path ini=root.resolve("Server/actual.ini");Files.writeString(ini,"Mods=Other\n");
            check(!StartupContext.enabledInProfile(root,"actual"),"inactive profile cannot inherit global preload activation");
            Files.writeString(ini,"\uFEFFMods=\\Other;\\ServerPanelBridge\r\n");check(StartupContext.enabledInProfile(root,"actual"),"BOM and backslash mod IDs supported");
            PrintStream oldOut=System.out,oldErr=System.err;ByteArrayOutputStream preserved=new ByteArrayOutputStream();PrintStream local=new PrintStream(preserved,true,StandardCharsets.UTF_8);
            List<String> received=new ArrayList<>();boolean unchanged=false,partial=false,disabled=false;
            System.setOut(local);System.setErr(local);
            try {
                PreloadConsole.install();PrintStream cached=System.out;
                cached.print("partial-");ConsoleCapture adopted=PreloadConsole.claim((stream,text)->received.add(stream+":"+text));
                cached.println("line");cached.println("cached-game-stream");System.setOut(new PrintStream(cached,true,StandardCharsets.UTF_8));System.out.println("wrapped-output");
                partial=received.equals(List.of("stdout:partial-line","stdout:cached-game-stream","stdout:wrapped-output"));
                adopted.close();PreloadConsole.discard();cached.println("not-captured");unchanged=preserved.toString(StandardCharsets.UTF_8).equals("partial-line\ncached-game-stream\nwrapped-output\nnot-captured\n".replace("\n",System.lineSeparator()));
                disabled=received.size()==3;
            }finally{System.setOut(oldOut);System.setErr(oldErr);PreloadConsole.discard();}
            check(partial,"handoff keeps incomplete lines and cached/replaced game streams without duplicates");
            check(unchanged && disabled,"closing a cached tee preserves original console and stops capture");
            AtomicInteger count=new AtomicInteger();System.setOut(local);System.setErr(local);
            try{PreloadConsole.install();for(int i=0;i<650;i++)System.out.println("line-"+i);ConsoleCapture adopted=PreloadConsole.claim((stream,text)->count.incrementAndGet());adopted.close();PreloadConsole.discard();}finally{System.setOut(oldOut);System.setErr(oldErr);}
            check(count.get()==501,"pre-profile buffer is bounded to 500 records plus a loss notice");
            Properties disabledConfig=new Properties();disabledConfig.setProperty("captureConsoleOutput","false");
            System.setOut(local);System.setErr(local);
            try{PreloadConsole.install();System.out.println("private-unapproved-output");try(OutputReporter output=new OutputReporter(new BridgeConfig(disabledConfig,"test"),Logger.getAnonymousLogger(),()->false)){output.start();check(output.queued()==0 && !PreloadConsole.installed(),"capture opt-out discards provisional logs without sending them");}}finally{System.setOut(oldOut);System.setErr(oldErr);PreloadConsole.discard();}
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path);}}
        System.out.println("RESULT: "+passed+" preload parsing/capture assertions passed");
    }
}
