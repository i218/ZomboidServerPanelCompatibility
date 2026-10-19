package io.serverpanelbridge;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.*;

/** Bounded best-effort output; capture never waits on network or disk. */
public final class OutputReporter implements AutoCloseable {
    private final BridgeConfig config;
    private final Logger logger;
    private final BooleanSupplier registered;
    private final ArrayBlockingQueue<Line> queue=new ArrayBlockingQueue<>(500);
    private final AtomicLong dropped=new AtomicLong();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ServerPanelBridge-Output");t.setDaemon(true);return t;});
    private final HttpClient http;
    private final Handler handler;
    private ConsoleCapture console;
    private boolean started;
    private volatile boolean closed;
    private static final class Line {
        final String text,stream;
        Line(String text,String stream){this.text=text;this.stream=stream;}
    }
    public OutputReporter(BridgeConfig config,Logger logger,BooleanSupplier registered) {
        this.config=config;this.logger=logger;this.registered=registered;
        http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(config.timeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector(){
                public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
                public void connectFailed(URI uri,SocketAddress address,java.io.IOException error){}
            }).build();
        handler=new Handler(){
            public void publish(LogRecord record){if(record!=null && isLoggable(record)) offer("log",record.getLevel()+": "+record.getMessage());}
            public void flush(){}
            public void close(){}
        };
    }
    public synchronized void start() {
        if(started || closed || !config.enabled || !config.outputEnabled) return;
        started=true;logger.addHandler(handler);
        if(config.captureConsoleOutput) console=new ConsoleCapture(this::offer);
        worker.scheduleWithFixedDelay(this::cycle,1,1,TimeUnit.SECONDS);
    }
    public void offer(String stream,String text) {
        if(closed || !config.enabled || !config.outputEnabled || text==null) return;
        if(!stream.equals("stdout") && !stream.equals("stderr") && !stream.equals("log")) throw new IllegalArgumentException("Invalid output stream");
        // Bound both scan work and retained text, including a single enormous write.
        int limit=Math.min(text.length(),4000);StringBuilder clean=new StringBuilder(limit);
        for(int i=0;i<limit;i++) {char c=text.charAt(i);clean.append(c=='\r' || c=='\n' || c=='\0'?' ':c);}
        if(clean.length()>0 && Character.isHighSurrogate(clean.charAt(clean.length()-1))) clean.setLength(clean.length()-1);
        Line line=new Line(clean.toString(),stream);
        if(!queue.offer(line)) {if(queue.poll()!=null)dropped.incrementAndGet();if(!queue.offer(line))dropped.incrementAndGet();}
    }
    private void cycle(){try{sendOnce();}catch(InterruptedException error){Thread.currentThread().interrupt();}catch(Exception ignored){/* No recursive output failure logs. */}}
    void sendOnce() throws Exception {
        if(closed || !config.enabled || !config.outputEnabled || !registered.getAsBoolean()) return;
        List<Line> batch=new ArrayList<>(100);queue.drainTo(batch,100);if(batch.isEmpty())return;
        StringBuilder body=new StringBuilder("{\"sessionId\":").append(Heartbeat.quote(config.sessionId)).append(",\"lines\":[");
        for(int i=0;i<batch.size();i++) {
            if(i>0)body.append(',');Line line=batch.get(i);
            body.append("{\"stream\":").append(Heartbeat.quote(line.stream)).append(",\"text\":").append(Heartbeat.quote(line.text)).append('}');
        }
        body.append("]}");
        URI endpoint=URI.create(config.heartbeatUri.toString().replaceAll("/heartbeat$","/output"));
        HttpRequest request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(config.timeoutSeconds))
            .header("Content-Type","application/json; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(body.toString(),StandardCharsets.UTF_8)).build();
        try {
            CompletableFuture<HttpResponse<Void>> future=http.sendAsync(request,HttpResponse.BodyHandlers.discarding());
            try {if(future.get(config.timeoutSeconds,TimeUnit.SECONDS).statusCode()!=204) throw new java.io.IOException("Output rejected");}
            finally {if(!future.isDone())future.cancel(true);}
        } catch(Exception error){dropped.addAndGet(batch.size());throw error;}
        // No retry after ambiguous receipt: the panel's output endpoint does not deduplicate.
    }
    int queued(){return queue.size();}
    long dropped(){return dropped.get();}
    @Override public synchronized void close(){
        if(closed)return;
        logger.removeHandler(handler);if(console!=null)console.close();
        closed=true;worker.shutdownNow();queue.clear();
    }
}
