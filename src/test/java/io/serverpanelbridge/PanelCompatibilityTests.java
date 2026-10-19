package io.serverpanelbridge;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

public final class PanelCompatibilityTests {
    private static int passed;
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;System.out.println("PASS "+name);}
    private static String command(BridgeConfig c,String id,long lease,long ttl,String session){return "[{\"id\":\""+id+"\",\"action\":\"restart\",\"leaseToken\":\"lease\",\"leaseExpiresAtUtc\":"+Heartbeat.quote(Instant.ofEpochMilli(lease).toString())+",\"expiresAtUtc\":"+Heartbeat.quote(Instant.ofEpochMilli(ttl).toString())+",\"targetSessionId\":"+Heartbeat.quote(session)+"}]";}
    public static void main(String[] args)throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService handlers=Executors.newCachedThreadPool();server.setExecutor(handlers);
        AtomicReference<String> commands=new AtomicReference<>("[]"),query=new AtomicReference<>(""),ack=new AtomicReference<>(""),output=new AtomicReference<>("");
        AtomicInteger outputCode=new AtomicInteger(204),outputPosts=new AtomicInteger();AtomicLong outputDelay=new AtomicLong();
        server.createContext("/",exchange->{try{
            String path=exchange.getRequestURI().getPath();
            if(path.endsWith("/commands")){query.set(exchange.getRequestURI().getQuery());byte[] bytes=commands.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}
            else if(path.endsWith("/output")){output.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));outputPosts.incrementAndGet();try{Thread.sleep(outputDelay.get());}catch(InterruptedException e){Thread.currentThread().interrupt();}exchange.sendResponseHeaders(outputCode.get(),-1);}
            else {ack.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));exchange.sendResponseHeaders(204,-1);}
        }finally{exchange.close();}});server.start();
        Path root=Files.createTempDirectory("bridge-panel-compat-");
        Logger logger=Logger.getAnonymousLogger();logger.setUseParentHandlers(false);
        Properties p=new Properties();p.setProperty("panelUrl","http://127.0.0.1:"+server.getAddress().getPort());p.setProperty("restartEnabled","true");p.setProperty("requestTimeoutSeconds","1");
        BridgeConfig config=new BridgeConfig(p,"test");AtomicLong now=new AtomicLong(System.currentTimeMillis());
        AtomicInteger stops=new AtomicInteger(),prepared=new AtomicInteger(),cancelled=new AtomicInteger();
        RestartAdapter restart=()->{prepared.incrementAndGet();return new RestartAdapter.Prepared(){boolean armed;public void arm(){armed=true;}public void close(){if(!armed)cancelled.incrementAndGet();}};};
        try {
            check(config.sessionId.matches("[a-f0-9]{32}") && config.sessionId.equals(new BridgeConfig(new Properties(),"other").sessionId),"one GUID-N session shared by heartbeat and services for the JVM");
            check(((Map<?,?>)Json.parse(Heartbeat.json(config,0,0,0,true))).get("sessionId").equals(config.sessionId),"heartbeat includes current session");
            try(CommandService service=new CommandService(config,root.resolve("foreign"),stops::incrementAndGet,restart,now::get,logger)){
                commands.set(command(config,"foreign",now.get()+30000,now.get()+10000,"00000000000000000000000000000000"));service.pollOnce();service.onGameTick();
                check(stops.get()==0 && prepared.get()==0 && !Files.exists(root.resolve("foreign/foreign.properties")),"foreign-session command refused before reservation or preparation");
                check(query.get().equals("sessionId="+config.sessionId),"command polling supplies current session");
                commands.set(command(config,"expired",now.get()+30000,now.get()-1,config.sessionId));service.pollOnce();service.onGameTick();
                check(prepared.get()==0 && stops.get()==0,"expired command TTL refused while lease is still valid");
            }
            long ttl=now.get()+1000,lease=now.get()+30000;
            try(CommandService service=new CommandService(config,root.resolve("tick-ttl"),stops::incrementAndGet,restart,now::get,logger)){
                commands.set(command(config,"tick-ttl",lease,ttl,config.sessionId));service.pollOnce();now.set(ttl);service.onGameTick();service.pollOnce();
                check(stops.get()==0 && cancelled.get()==1 && ack.get().contains("TTL expired"),"TTL rechecked on game tick and prepared helper cancelled");
                check(ack.get().contains("\"sessionId\":\""+config.sessionId+"\"") && ack.get().contains("\"succeeded\":false"),"refusal ACK retains session and valid lease after command TTL");
            }
            final long nextTtl=now.get()+1000;
            try(CommandService service=new CommandService(config,root.resolve("ack-ttl"),()->{stops.incrementAndGet();now.set(nextTtl+1);},restart,now::get,logger)){
                commands.set(command(config,"ack-ttl",lease,nextTtl,config.sessionId));service.pollOnce();service.onGameTick();service.pollOnce();
                check(stops.get()==1 && ack.get().contains("\"succeeded\":true"),"action begun before TTL can ACK after TTL within original lease");
            }
            AtomicBoolean registered=new AtomicBoolean(false);
            try(OutputReporter reporter=new OutputReporter(config,logger,registered::get)){
                for(int i=0;i<600;i++)reporter.offer("log","line "+i);
                reporter.sendOnce();check(reporter.queued()==500 && reporter.dropped()==100 && outputPosts.get()==0,"output queue bounded at 500 and waits for heartbeat registration");
                registered.set(true);reporter.sendOnce();Map<?,?> body=(Map<?,?>)Json.parse(output.get());
                check(body.get("sessionId").equals(config.sessionId) && ((List<?>)body.get("lines")).size()==100 && reporter.queued()==400,"output batches contain at most 100 lines and current session");
            }
            try(OutputReporter reporter=new OutputReporter(config,logger,()->true)){
                reporter.offer("stderr","a\r\nb\0"+"x".repeat(5000));reporter.sendOnce();
                Map<?,?> line=(Map<?,?>)((List<?>)((Map<?,?>)Json.parse(output.get())).get("lines")).get(0);String text=(String)line.get("text");
                check(text.length()==4000 && !text.contains("\r") && !text.contains("\n") && !text.contains("\0") && line.get("stream").equals("stderr"),"output records are capped and comply with panel text/stream rules");
                outputCode.set(409);reporter.offer("log","wrong session");try{reporter.sendOnce();throw new AssertionError("Expected output rejection");}catch(IOException expected){}
                check(reporter.queued()==0 && reporter.dropped()==1,"rejected output batch discarded without duplicate replay");
                outputCode.set(204);outputDelay.set(1800);reporter.offer("log","timeout");long start=System.nanoTime();
                try{reporter.sendOnce();throw new AssertionError("Expected output timeout");}catch(Exception expected){}
                check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(3) && reporter.dropped()==2,"output timeout bounded without blocking game thread");outputDelay.set(0);
            }
            int before=logger.getHandlers().length;
            try(OutputReporter reporter=new OutputReporter(config,logger,()->false)){reporter.start();logger.info("bridge event");check(reporter.queued()==1,"bridge log events captured automatically");}
            check(logger.getHandlers().length==before,"output handler removed on shutdown");
            p.setProperty("outputEnabled","false");p.setProperty("captureConsoleOutput","true");PrintStream original=System.out;
            try(OutputReporter reporter=new OutputReporter(new BridgeConfig(p,"test"),logger,()->true)){reporter.start();reporter.offer("log","ignored");check(reporter.queued()==0 && System.out==original,"disabled output does not capture or replace console");}
            List<String> captured=new ArrayList<>();ByteArrayOutputStream preserved=new ByteArrayOutputStream();PrintStream oldOut=System.out,oldErr=System.err;
            PrintStream local=new PrintStream(preserved,true,StandardCharsets.UTF_8);
            System.setOut(local);System.setErr(local);boolean restored=false,bytesPreserved=false;
            try {
                ConsoleCapture capture=new ConsoleCapture((stream,text)->captured.add(stream+":"+text));
                byte[] utf="Привет 😀\r\npartial".getBytes(StandardCharsets.UTF_8);
                for(byte value:utf)System.out.write(value);System.err.print("error\n");capture.close();
                restored=System.out==local && System.err==local && captured.equals(List.of("stdout:Привет 😀","stderr:error","stdout:partial"));
                bytesPreserved=preserved.toString(StandardCharsets.UTF_8).equals("Привет 😀\r\npartialerror\n");
            } finally {System.setOut(oldOut);System.setErr(oldErr);}
            check(restored,"console tee handles fragmented UTF-8, CRLF, partial lines and stream restoration");
            check(bytesPreserved,"original console bytes remain unchanged");
        } finally {server.stop(0);handlers.shutdownNow();try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path);}}
        System.out.println("RESULT: "+passed+" panel compatibility assertions passed");
    }
}
