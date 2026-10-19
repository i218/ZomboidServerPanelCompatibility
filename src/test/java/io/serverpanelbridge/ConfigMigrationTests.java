package io.serverpanelbridge;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.stream.*;

public final class ConfigMigrationTests {
    private static int passed;
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;System.out.println("PASS "+name);}
    private static List<Path> backups(Path file)throws Exception{try(Stream<Path> files=Files.list(file.getParent())){return files.filter(p->p.getFileName().toString().startsWith(file.getFileName()+".backup-")).collect(Collectors.toList());}}
    private static boolean fails(Path file){try{BridgeConfig.load(file,"test");return false;}catch(Exception expected){return true;}}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("bridge-config-migration-");
        try {
            Path fresh=root.resolve("fresh.properties");BridgeConfig config=BridgeConfig.load(fresh,"test");
            check(config.outputEnabled && !config.captureConsoleOutput && Files.readString(fresh).contains("# Latest panel Output tab"),"new config uses canonical commented template");
            byte[] complete=Files.readAllBytes(fresh);var modified=Files.getLastModifiedTime(fresh);BridgeConfig.load(fresh,"test");
            check(Arrays.equals(complete,Files.readAllBytes(fresh)) && modified.equals(Files.getLastModifiedTime(fresh)) && backups(fresh).isEmpty(),"complete config untouched without backups or timestamp changes");
            Path old=root.resolve("old.properties");String text="# My settings\r\nenabled=false\r\npanelUrl=http://127.0.0.1:5099\r\nhostName=Сервер 😀\r\nrestartEnabled=true\r\noutputEnabled=false\r\ncaptureConsoleOutput=true\r\ncustomOption=keep me\r\nhostId=\r\n";
            Files.writeString(old,text,StandardCharsets.UTF_8);BridgeConfig migrated=BridgeConfig.load(old,"test");String result=Files.readString(old);
            check(!migrated.enabled && migrated.restartEnabled && !migrated.outputEnabled && migrated.captureConsoleOutput && migrated.hostName.equals("Сервер 😀") && migrated.heartbeatUri.getPort()==5099,"migration preserves existing runtime values including explicit enable/disable choices");
            check(result.startsWith(text) && result.contains("customOption=keep me") && result.contains("hostId=\r\n"),"comments, unknown keys, blank values and original bytes preserved");
            check(result.contains("heartbeatSeconds=5\r\n") && !result.replace("\r\n","").contains("\n"),"missing defaults appended with original CRLF style");
            List<Path> saved=backups(old);check(saved.size()==1 && Arrays.equals(Files.readAllBytes(saved.get(0)),text.getBytes(StandardCharsets.UTF_8)),"backup contains exact pre-migration bytes");
            byte[] once=Files.readAllBytes(old);BridgeConfig.load(old,"test");check(Arrays.equals(once,Files.readAllBytes(old)) && backups(old).size()==1,"repeat startup does not append keys or create another backup");
            Path bom=root.resolve("bom.properties");String bomText="\uFEFFenabled=false\nhostName=Тест\n";Files.writeString(bom,bomText,StandardCharsets.UTF_8);BridgeConfig bomConfig=BridgeConfig.load(bom,"test");
            check(!bomConfig.enabled && bomConfig.hostName.equals("Тест") && Files.readString(bom).startsWith(bomText),"UTF-8 BOM parsed correctly and preserved in migrated file");
            Path escaped=root.resolve("escaped.properties");String escapedText="enabl\\u0065d=false\noutputEnabled:false\nhostName=First\\\n  second\nunknown=value\\";Files.writeString(escaped,escapedText,StandardCharsets.UTF_8);
            BridgeConfig escapeConfig=BridgeConfig.load(escaped,"test");
            check(!escapeConfig.enabled && !escapeConfig.outputEnabled && escapeConfig.hostName.equals("Firstsecond") && Files.readString(escaped).startsWith(escapedText),"escaped keys, colon syntax, multiline values and trailing continuation preserved");
            Properties properties=new Properties();try(var reader=Files.newBufferedReader(escaped,StandardCharsets.UTF_8)){properties.load(reader);}
            check(properties.getProperty("unknown").equals("value") && properties.getProperty("captureConsoleOutput").equals("false"),"trailing backslash cannot consume an appended setting");
            Path duplicate=root.resolve("duplicate.properties");String duplicates="enabled=true\nenabled=false\n";Files.writeString(duplicate,duplicates,StandardCharsets.UTF_8);BridgeConfig duplicateConfig=BridgeConfig.load(duplicate,"test");
            check(!duplicateConfig.enabled && Files.readString(duplicate).startsWith(duplicates),"duplicate existing keys retain Java properties last-value semantics");
            Path invalid=root.resolve("invalid.properties");String invalidText="# wrong value must stay visible\nheartbeatSeconds=999\n";Files.writeString(invalid,invalidText,StandardCharsets.UTF_8);
            check(fails(invalid) && Files.readString(invalid).equals(invalidText) && backups(invalid).isEmpty(),"invalid existing values refused without rewrite or reset");
            Path badUtf=root.resolve("bad-utf.properties");byte[] invalidBytes={(byte)0xc3,(byte)0x28};Files.write(badUtf,invalidBytes);
            check(fails(badUtf) && Arrays.equals(invalidBytes,Files.readAllBytes(badUtf)) && backups(badUtf).isEmpty(),"invalid UTF-8 refused without changing bytes");
            if(System.getProperty("os.name","").startsWith("Windows")) {
                Path readonly=root.resolve("readonly.properties");byte[] readonlyBytes="enabled=false\n".getBytes(StandardCharsets.UTF_8);Files.write(readonly,readonlyBytes);
                var dos=Files.getFileAttributeView(readonly,java.nio.file.attribute.DosFileAttributeView.class);dos.setReadOnly(true);
                try{check(fails(readonly) && Arrays.equals(readonlyBytes,Files.readAllBytes(readonly)) && backups(readonly).isEmpty(),"read-only old config refused without writes or backups");}finally{dos.setReadOnly(false);}
                var fullDos=Files.getFileAttributeView(fresh,java.nio.file.attribute.DosFileAttributeView.class);fullDos.setReadOnly(true);
                try{BridgeConfig.load(fresh,"test");check(Arrays.equals(complete,Files.readAllBytes(fresh)) && backups(fresh).isEmpty(),"complete read-only config remains usable without migration");}finally{fullDos.setReadOnly(false);}
                Path locked=root.resolve("locked.properties");byte[] original="enabled=false\n".getBytes(StandardCharsets.UTF_8);Files.write(locked,original);
                try(FileChannel hold=FileChannel.open(locked,StandardOpenOption.READ,com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)){
                    check(fails(locked) && Arrays.equals(original,Files.readAllBytes(locked)),"failed atomic replacement leaves original config intact");
                }
                check(backups(locked).size()==1 && Arrays.equals(original,Files.readAllBytes(backups(locked).get(0))),"failed replacement retains a complete original backup");
                BridgeConfig.load(locked,"test");check(Files.readString(locked).contains("captureConsoleOutput=false"),"migration can recover on next startup after file is unlocked");
            }
        } finally {try(Stream<Path> paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path);}}
        System.out.println("RESULT: "+passed+" config migration assertions passed");
    }
}
