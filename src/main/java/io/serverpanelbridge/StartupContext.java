package io.serverpanelbridge;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
/** Stock dedicated launch identity without loading any game classes during premain. */
final class StartupContext {
    final String serverName;final Path cache,config;
    StartupContext(String name,Path cache){this.serverName=name;this.cache=cache;this.config=Paths.get(System.getProperty("serverpanelbridge.config",cache.resolve("ServerPanelBridge").resolve(name).resolve("ServerPanelBridge.properties").toString())).toAbsolutePath();}
    static boolean directServer(){String command=System.getProperty("sun.java.command","");return command.equals("zombie.network.GameServer") || command.startsWith("zombie.network.GameServer ");}
    static StartupContext capture()throws Exception {
        ProcessHandle process=ProcessHandle.current();String[] args=process.info().arguments().orElse(null);
        if(args==null && System.getProperty("os.name","").startsWith("Windows"))args=WindowsLaunch.capture(process.pid(),Paths.get(process.info().command().orElseThrow(()->new IOException("Executable unavailable"))));
        if(args==null)throw new IOException("Launch arguments unavailable");return parse(args);
    }
    static StartupContext parse(String[] args)throws IOException {
        int main=-1;
        // Find an actual main-class token, not a JVM option's operand.
        for(int i=0;i<args.length;i++) {
            String arg=args[i];
            if(Set.of("-cp","-classpath","--class-path","-p","--module-path","--upgrade-module-path","--add-modules","--limit-modules","--add-exports","--add-opens","--add-reads","--patch-module","--enable-native-access").contains(arg)){if(++i>=args.length)throw new IOException("Incomplete JVM option");continue;}
            if(arg.startsWith("@") || arg.equals("-jar") || arg.equals("-m") || arg.equals("--module"))throw new IOException("Unsupported indirect launcher");
            if(arg.startsWith("-"))continue;
            if(!arg.equals("zombie.network.GameServer"))throw new IOException("Not the dedicated main class");main=i;break;
        }
        if(main<0)throw new IOException("Dedicated main class unavailable");
        String name="servertest";Path cache=Paths.get(System.getProperty("user.home"),"Zomboid");
        // GameServer's early pass scans cache options even before its value-option parser.
        for(int i=main+1;i<args.length;i++)if(args[i].startsWith("-cachedir=")){
            cache=Paths.get(args[i].substring(10));if(!cache.isAbsolute())throw new IOException("Relative cache directory unsupported");
        }
        Set<String> values=Set.of("-adminusername","-adminpassword","-ip","-port","-udpport","-steamvac","-servername");
        Set<String> flags=Set.of("-gui","-nosteam","-anti-cheats","-no-worldgen","-no-foraging","-no-attachment");
        for(int i=main+1;i<args.length;i++) {
            String arg=args[i];
            if(values.contains(arg)){if(++i>=args.length)throw new IOException("Incomplete server option");if(arg.equals("-servername"))name=args[i];continue;}
            if(arg.startsWith("-cachedir="))continue; // Already resolved by the independent early pass.
            if(flags.contains(arg) || arg.startsWith("-console_dot_txt_size_kb=") || arg.startsWith("-useTimeStampedLogFileNames=") || arg.startsWith("-debugLogFile=") || arg.startsWith("-disablelog=") || arg.startsWith("-debuglog=") || arg.startsWith("-seed="))continue;
            throw new IOException("Unsupported server option; normal bootstrap retained");
        }
        if(name.isEmpty() || !name.matches("[A-Za-z0-9_.-]+") || name.equals(".") || name.equals(".."))throw new IOException("Invalid server identity");
        String override=System.getProperty("serverpanelbridge.cachedir");if(override!=null)cache=Paths.get(override).toAbsolutePath();
        return new StartupContext(name,cache.toAbsolutePath().normalize());
    }
    static boolean enabledInProfile(Path cache,String name)throws IOException {
        Path ini=cache.resolve("Server").resolve(name+".ini");if(!Files.isRegularFile(ini))return false;
        String text=Files.readString(ini);if(text.startsWith("\uFEFF"))text=text.substring(1);
        for(String line:text.split("\\R"))if(line.startsWith("Mods="))for(String id:line.substring(5).split(";"))if(id.trim().replaceFirst("^\\\\","").equals("ServerPanelBridge"))return true;
        return false;
    }
}
