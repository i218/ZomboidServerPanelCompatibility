import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class PreloadRuntimeTests {
    static String quote(String text){return "\""+text.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    public static void main(String[] args)throws Exception {
        for(String mode:List.of("active","no-capture","inactive","fallback","client")){
            Path root=Files.createTempDirectory("bridge-native-preload-");HttpServer panel=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            List<String> batches=Collections.synchronizedList(new ArrayList<>());AtomicInteger beats=new AtomicInteger();
            try {
                Files.createDirectories(root.resolve("agent"));Files.createDirectories(root.resolve("Server"));Files.createDirectories(root.resolve("ServerPanelBridge/preload-test"));
                Files.writeString(root.resolve("Server/preload-test.ini"),"Mods="+(mode.equals("inactive")?"Other":"\\ServerPanelBridge")+"\n");
                String agent="{\"trusted_authors\":[],\"auto_fix_mod_order\":true,\"fix_approval_dialog_cursor\":true,\"preload_mods\":{\"ServerPanelBridge\":{\"infPath\":"+quote(args[4])+",\"jarPath\":"+quote(args[3])+"}}}";
                Files.writeString(root.resolve("agent/config.json"),agent);
                Files.writeString(root.resolve("ServerPanelBridge/preload-test/ServerPanelBridge.properties"),"panelUrl=http://127.0.0.1:"+panel.getAddress().getPort()+"\ncaptureConsoleOutput="+!mode.equals("no-capture")+"\n");
                panel.createContext("/",exchange->{try {
                    String path=exchange.getRequestURI().getPath(),body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                    if(path.endsWith("/heartbeat")){beats.incrementAndGet();if(!body.contains("\"health\":\"Starting\""))throw new AssertionError("Ready before callback");if(mode.equals("no-capture"))Files.writeString(root.resolve("registered"),"yes");}
                    if(path.endsWith("/output")){batches.add(body);if(body.contains("PRELOAD-WORKSHOP-BUFFER"))Files.writeString(root.resolve("registered"),"yes");}
                    exchange.sendResponseHeaders(204,-1);
                }finally{exchange.close();}});panel.start();
                List<String> command=new ArrayList<>(List.of(args[0],"--enable-native-access=ALL-UNNAMED","-XX:-CreateCoredumpOnCrash","-Dserverpanelbridge.testCache="+root,"-javaagent:"+args[1]+"="+root,"-Dfixture.root="+root,"-Dfixture.mode="+mode,"-Dfixture.bridge="+args[3],"-cp",args[2],mode.equals("client")?"PreloadClientFixture":"zombie.network.GameServer","-servername","preload-test","-cachedir="+root,"-adminpassword","fixture-private-value"));
                if(mode.equals("fallback"))command.add("-unsupported-fixture-option");
                Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve("console.log").toFile()).start();
                if(!process.waitFor(25,TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Native preload timeout");}
                if(process.exitValue()!=0)throw new AssertionError("Native preload fixture failed: "+Files.readString(root.resolve("console.log")));
                String combined=String.join("\n",batches);
                if(mode.equals("active")){
                    if(!combined.contains("PRELOAD-WORKSHOP-BUFFER") || !combined.contains("WORKSHOP-DOWNLOAD-PHASE") || !combined.contains("CACHED-WORKSHOP-LOG") || !combined.contains("AFTER-NORMAL-MOD"))throw new AssertionError("Startup tail/handoff missing: "+combined+"; "+Files.readString(root.resolve("console.log")));
                    if(combined.split("CACHED-WORKSHOP-LOG",-1).length!=2)throw new AssertionError("Cached stream duplicated");
                }
                if(mode.equals("no-capture") && combined.contains("WORKSHOP-"))throw new AssertionError("Opt-out leaked provisional output");
                if((mode.equals("inactive") || mode.equals("client")) && beats.get()!=0)throw new AssertionError("Inactive/client reported");
                if(mode.equals("fallback") && beats.get()==0)throw new AssertionError("Normal fallback failed");
                if(combined.contains("fixture-private-value"))throw new AssertionError("Command-line credentials leaked");
                System.out.println("PASS actual ZombieBuddy preload lifecycle: "+mode);
            }finally{panel.stop(0);try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path);}}
        }
    }
}
