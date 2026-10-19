package io.serverpanelbridge;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Additive migration: preserve original bytes and validate before atomic replacement. */
final class ConfigFile {
    private ConfigFile() {}
    static BridgeConfig load(Path requested,String serverName) throws IOException {
        Path path=requested.toAbsolutePath().normalize();
        String template;
        try(InputStream input=ConfigFile.class.getResourceAsStream("/defaults/ServerPanelBridge.properties")) {
            if(input==null)throw new IOException("Bundled configuration defaults unavailable");
            template=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        Properties defaults=parse(template);
        if(Files.isSymbolicLink(path))throw new IOException("Configuration must be a regular file, not a symbolic link");
        if(!Files.exists(path)) {
            BridgeConfig config=new BridgeConfig(defaults,serverName);
            Files.createDirectories(path.getParent());
            Files.writeString(path,template,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            return config;
        }
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Configuration is not a regular file");
        byte[] original=Files.readAllBytes(path);
        String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(original)).toString();
        Properties values=parse(text),merged=new Properties();merged.putAll(values);
        Set<String> missing=new HashSet<>(defaults.stringPropertyNames());missing.removeAll(values.stringPropertyNames());
        for(String key:missing)merged.setProperty(key,defaults.getProperty(key));
        // Invalid user values are never reset or rewritten silently.
        BridgeConfig config=new BridgeConfig(merged,serverName);
        if(missing.isEmpty())return config;
        if(!Files.isWritable(path))throw new AccessDeniedException("Configuration requires missing settings but is not writable");
        Matcher newline=Pattern.compile("\\r\\n|\\n|\\r").matcher(text);
        String eol=newline.find()?newline.group():System.lineSeparator();
        // Two newlines terminate a possible trailing properties continuation safely.
        StringBuilder suffix=new StringBuilder(eol).append(eol).append("# Added missing settings by ServerPanelBridge.").append(eol);
        List<String> comments=new ArrayList<>();
        for(String line:template.split("\\r\\n|\\n|\\r")) {
            String trimmed=line.trim();
            if(trimmed.isEmpty()){comments.clear();continue;}
            if(trimmed.startsWith("#") || trimmed.startsWith("!")){comments.add(line);continue;}
            Properties entry=parse(line);
            if(entry.size()!=1)throw new IOException("Bundled default must contain one property per line");
            String key=entry.stringPropertyNames().iterator().next();
            if(missing.remove(key)) {
                for(String comment:comments)suffix.append(comment).append(eol);
                suffix.append(line).append(eol);
            }
            comments.clear();
        }
        if(!missing.isEmpty() || !parse(text+suffix).equals(merged))throw new IOException("Configuration migration would change existing values");
        byte[] tail=suffix.toString().getBytes(StandardCharsets.UTF_8);
        byte[] updated=Arrays.copyOf(original,original.length+tail.length);System.arraycopy(tail,0,updated,original.length,tail.length);
        Path temporary=null,backup=null;boolean backupComplete=false;
        try {
            temporary=Files.createTempFile(path.getParent(),path.getFileName()+".update-",".tmp");
            // Preserve the original file's supported permissions/attributes on the replacement.
            Files.copy(path,temporary,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);
            write(temporary,updated);
            backup=Files.createTempFile(path.getParent(),path.getFileName()+".backup-",".bak");
            Files.copy(path,backup,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);
            try(FileChannel saved=FileChannel.open(backup,StandardOpenOption.WRITE)){saved.force(true);}
            backupComplete=Arrays.equals(Files.readAllBytes(backup),original);
            if(!backupComplete || !Arrays.equals(Files.readAllBytes(path),original))
                throw new IOException("Configuration changed during migration; original left untouched");
            // No non-atomic fallback: a failed replace must leave the old file usable.
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if(temporary!=null)Files.deleteIfExists(temporary);
            if(backup!=null && !backupComplete)Files.deleteIfExists(backup);
            // Keep completed backups, including when replacement fails.
        }
        return config;
    }
    private static void write(Path path,byte[] bytes) throws IOException {
        try(FileChannel output=FileChannel.open(path,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);
        }
    }
    private static Properties parse(String text) throws IOException {
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        Properties values=new Properties();try(Reader reader=new StringReader(text)){values.load(reader);}return values;
    }
}
