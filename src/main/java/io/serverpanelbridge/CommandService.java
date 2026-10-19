package io.serverpanelbridge;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** Network/journal work is isolated from game ticks. Shutdown is game-thread only. */
public final class CommandService implements AutoCloseable {
    private final BridgeConfig config; private final HttpClient http; private final URI endpoint;
    private final RestartAdapter restart; private final ShutdownAdapter shutdown; private final CommandJournal journal;
    private final LongSupplier now; private final Logger log;
    private final ConcurrentLinkedQueue<Command> pending=new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Result> completed=new ConcurrentLinkedQueue<>();
    private final Set<String> inFlight=new HashSet<>();
    private final ScheduledExecutorService worker;
    private volatile boolean closed; private boolean started; private boolean stopRequested;
    private static final class Command {
        final String id, action, token, targetSession; final long expires, executeBefore; RestartAdapter.Prepared restartTicket;
        Command(String id,String action,String token,long expires,long executeBefore,String targetSession) { this.id=id;this.action=action;this.token=token;this.expires=expires;this.executeBefore=executeBefore;this.targetSession=targetSession; }
    }
    private static final class Result {
        final Command command; final boolean success; final String message;
        Result(Command command,boolean success,String message) {this.command=command;this.success=success;this.message=message;}
    }
    public CommandService(BridgeConfig config, Path journalDir, ShutdownAdapter shutdown, LongSupplier now, Logger log) {
        this(config,journalDir,shutdown,new JvmRestartAdapter(journalDir.getParent(),config.restartExitTimeoutSeconds),now,log);
    }
    public CommandService(BridgeConfig config, Path journalDir, ShutdownAdapter shutdown, RestartAdapter restart, LongSupplier now, Logger log) {
        this.restart=restart; this.config=config; this.shutdown=shutdown; this.now=now; this.log=log;
        journal=new CommandJournal(journalDir);
        endpoint=URI.create(config.heartbeatUri.toString().replaceAll("/heartbeat$","/commands"));
        http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(config.timeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
                public void connectFailed(URI uri,SocketAddress address,java.io.IOException error){}
            }).build();
        worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ServerPanelBridge-Control");t.setDaemon(true);return t;});
    }
    public synchronized void start() {
        if(!started && !closed && config.commandsEnabled) {started=true;worker.scheduleWithFixedDelay(this::cycle,0,5,TimeUnit.SECONDS);}
    }
    private void cycle() {
        try { pollOnce(); }
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(Exception error){log.warning("Command polling unavailable: "+error.getClass().getSimpleName());}
    }
    synchronized void pollOnce() throws Exception {
        if(closed || !config.commandsEnabled) return;
        Result result;
        while((result=completed.poll())!=null) {
            try { journal.write(result.command.id,"done",result.success,result.message); }
            catch(java.io.IOException failure) { completed.add(result); throw failure; }
            inFlight.remove(result.command.id);
            try { acknowledge(result.command,result.success,result.message); }
            catch(java.io.IOException error) {log.warning("Command acknowledgement pending; outcome retained in journal");}
        }
        HttpRequest request=HttpRequest.newBuilder(URI.create(endpoint+"?sessionId="+config.sessionId)).timeout(Duration.ofSeconds(config.timeoutSeconds)).GET().build();
        HttpResponse<byte[]> response=boundedSend(request, info -> new LimitedBody());
        String body=new String(response.body(),StandardCharsets.UTF_8);
        if(response.statusCode()!=200) throw new java.io.IOException("Command HTTP "+response.statusCode());
        Object parsed=Json.parse(body);
        if(!(parsed instanceof List<?>) || ((List<?>)parsed).size()>100) throw new java.io.IOException("Invalid command list");
        int count=0;
        for(Object item:(List<?>)parsed) {
            if(closed || pending.size()>=8 || ++count>8) break; // bound work; remaining leases may be delivered again
            if(!(item instanceof Map<?,?>)) throw new java.io.IOException("Invalid command");
            Map<?,?> values=(Map<?,?>)item;
            Command command=new Command(field(values,"id"),field(values,"action"),field(values,"leaseToken"),
                    Instant.parse(field(values,"leaseExpiresAtUtc")).toEpochMilli(),
                    values.get("expiresAtUtc")==null?Long.MAX_VALUE:Instant.parse(field(values,"expiresAtUtc")).toEpochMilli(),
                    values.get("targetSessionId")==null?null:field(values,"targetSessionId"));
            if(!command.id.matches("[A-Za-z0-9_-]{1,128}") || !command.token.matches("[A-Za-z0-9_-]{1,128}"))
                throw new java.io.IOException("Invalid command identity");
            if(command.expires<=now.getAsLong() || command.executeBefore<=now.getAsLong() ||
                (command.targetSession!=null && !command.targetSession.equals(config.sessionId)) || inFlight.contains(command.id)) continue;
            Properties previous=journal.read(command.id);
            if(previous!=null) {
                boolean succeeded="done".equals(previous.getProperty("state")) && Boolean.parseBoolean(previous.getProperty("succeeded"));
                String message="done".equals(previous.getProperty("state"))?previous.getProperty("result","Recorded outcome"):
                    "Previous shutdown delivery has an uncertain outcome; automatic replay refused";
                acknowledge(command,succeeded,message); continue;
            }
            String refusal=null;
            if(!command.action.equals("stop") && !command.action.equals("restart")) refusal="Unsupported action: "+command.action+"; process start belongs to the supervisor";
            else if(command.action.equals("stop") && !config.stopEnabled) refusal="stopEnabled=false; graceful stop is disabled by configuration";
            else if(command.action.equals("restart") && !config.restartEnabled) refusal="restartEnabled=false; restart is disabled by configuration";
            if(refusal!=null) {
                journal.write(command.id,"done",false,refusal); acknowledge(command,false,refusal); continue;
            }
            journal.write(command.id,"reserved",false,"Shutdown reserved before game-thread execution");
            if(command.action.equals("restart")) {
                try {command.restartTicket=restart.prepare();}
                catch(Exception error) {
                    String message="Restart preparation failed: "+error.getClass().getSimpleName()+"; server was not stopped";
                    journal.write(command.id,"done",false,message);acknowledge(command,false,message);continue;
                }
            }
            inFlight.add(command.id); pending.add(command);
        }
    }
    private static String field(Map<?,?> values,String name) throws java.io.IOException {
        Object value=values.get(name);
        if(!(value instanceof String) || ((String)value).length()>256) throw new java.io.IOException("Invalid "+name);
        return (String)value;
    }
    /** Call only from the verified dedicated-server game tick. No HTTP or journal I/O. */
    public void onGameTick() {
        Command command=pending.poll(); if(command==null) return;
        if(closed) {if(command.restartTicket!=null) command.restartTicket.close();return;}
        Result result;
        try {
            if(command.expires<=now.getAsLong()) throw new IllegalStateException("Lease expired before game tick");
            if(command.executeBefore<=now.getAsLong()) throw new IllegalStateException("Command TTL expired before game tick");
            if(command.targetSession!=null && !command.targetSession.equals(config.sessionId)) throw new IllegalStateException("Command belongs to another session");
            if(stopRequested) throw new IllegalStateException("Shutdown already requested");
            if(command.restartTicket!=null) command.restartTicket.validate();
            shutdown.requestStop(); stopRequested=true;
            if(command.restartTicket!=null) command.restartTicket.arm();
            result=new Result(command,true,command.restartTicket==null ? "Graceful shutdown requested through QueueQuit; process exit and save completion are not confirmed" : "Restart armed after QueueQuit; replacement launch and save completion are not confirmed");
            log.info("Graceful "+command.action+" requested by ServerPanel command "+command.id);
        } catch(Exception error) {
            result=new Result(command,false,"Shutdown/restart rejected: "+(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage())+ (stopRequested ? "; shutdown was already queued; restart is not confirmed" : "; server was not stopped"));
            log.warning("Shutdown/restart rejected: "+error.getClass().getSimpleName());
        }
        if(command.restartTicket!=null) command.restartTicket.close();
        completed.add(result);
        try {worker.execute(this::cycle);} catch(RejectedExecutionException stopping){}
    }
    private <T> HttpResponse<T> boundedSend(HttpRequest request,HttpResponse.BodyHandler<T> handler) throws Exception {
        CompletableFuture<HttpResponse<T>> future=http.sendAsync(request,handler);
        try {return future.get(config.timeoutSeconds,TimeUnit.SECONDS);}
        catch(TimeoutException timeout){future.cancel(true);throw new java.io.IOException("Command response timeout",timeout);}
        catch(InterruptedException interrupted){future.cancel(true);throw interrupted;}
    }
    private void acknowledge(Command command,boolean success,String message) throws Exception {
        if(command.expires<=now.getAsLong()) return;
        String text=message==null?"Unknown result":message;
        if(text.length()>1000) text=text.substring(0,1000);
        String body="{\"leaseToken\":"+Heartbeat.quote(command.token)+",\"sessionId\":"+Heartbeat.quote(config.sessionId)+",\"succeeded\":"+success+",\"result\":"+Heartbeat.quote(text)+"}";
        HttpRequest request=HttpRequest.newBuilder(URI.create(endpoint+"/"+command.id+"/ack"))
            .timeout(Duration.ofSeconds(config.timeoutSeconds)).header("Content-Type","application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
        HttpResponse<Void> response=boundedSend(request,HttpResponse.BodyHandlers.discarding());
        if(response.statusCode()!=204) throw new java.io.IOException("Command acknowledgement HTTP "+response.statusCode());
    }
    @Override public synchronized void close() {closed=true;worker.shutdownNow();Command command;while((command=pending.poll())!=null) if(command.restartTicket!=null) command.restartTicket.close();}
}
