package io.serverpanelbridge;

import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

public final class PanelReporter implements AutoCloseable {
    private final BridgeConfig config;
    private final JvmMetrics metrics;
    private final LongSupplier clock;
    private final Logger log;
    private final HttpClient http;
    private final ScheduledExecutorService worker;
    private volatile long lastTick;
    private volatile boolean closed, registered;
    public boolean isRegistered() {return registered && !closed;}
    private boolean started;
    private int failures;
    public PanelReporter(BridgeConfig config, JvmMetrics metrics, LongSupplier clock, Logger log) {
        this.config = config; this.metrics = metrics; this.clock = clock; this.log = log;
        lastTick = clock.getAsLong();
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(config.timeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(new java.net.ProxySelector() {
                public java.util.List<java.net.Proxy> select(java.net.URI uri) { return java.util.List.of(java.net.Proxy.NO_PROXY); }
                public void connectFailed(java.net.URI uri, java.net.SocketAddress address, java.io.IOException e) { }
            }).build();
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ServerPanelBridge-Reporter"); t.setDaemon(true); return t;
        });
    }
    public synchronized void start() {
        if (config.enabled && !closed && !started) {
            started = true; worker.schedule(this::run, 0, TimeUnit.SECONDS);
        }
    }
    public void onGameTick() { lastTick = clock.getAsLong(); }
    private void run() {
        if (closed) return;
        long delay = config.heartbeatSeconds;
        try {
            sendOnce();
            if (failures != 0) log.info("ServerPanel connection restored");
            failures = 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return;
        } catch (Exception failure) {
            registered = false;
            failures = Math.min(30, failures + 1);
            delay = retrySeconds(config.heartbeatSeconds, config.maxRetrySeconds, failures);
            if (failures == 1 || failures % 12 == 0)
                log.warning("ServerPanel heartbeat unavailable (" + failure.getClass().getSimpleName() + "); retry in " + delay + " seconds");
        }
        if (!closed) try { worker.schedule(this::run, delay, TimeUnit.SECONDS); } catch (RejectedExecutionException stopping) { }
    }
    void sendOnce() throws Exception {
        long age = clock.getAsLong() - lastTick;
        String payload = Heartbeat.json(config, metrics.cpuPercent(), metrics.memoryMb(), metrics.uptimeSeconds(),
            age >= 0 && age <= config.staleTickSeconds * 1000L);
        HttpRequest request = HttpRequest.newBuilder(config.heartbeatUri)
            .timeout(Duration.ofSeconds(config.timeoutSeconds))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new java.io.IOException("Panel returned HTTP " + response.statusCode());
        registered = true;
    }
    static long retrySeconds(int interval, int maximum, int failures) {
        return Math.min(maximum, interval * (1L << Math.min(20, Math.max(0, failures - 1))));
    }
    @Override public synchronized void close() { closed = true; worker.shutdownNow(); }
}
