package io.serverpanelbridge;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

public final class BridgeTests {
    private static int passed;
    interface Checked { void run() throws Exception; }
    private static void test(String name, Checked action) throws Exception {
        action.run(); passed++; System.out.println("PASS " + name);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static Properties property(String key, String value) {
        Properties p = new Properties(); p.setProperty(key, value); return p;
    }
    private static void invalid(String key, String value) {
        try { new BridgeConfig(property(key, value), "test"); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Accepted invalid " + key + ": " + value);
    }
    public static void main(String[] args) throws Exception {
        test("configuration defaults and distinct server identities", () -> {
            BridgeConfig c = new BridgeConfig(new Properties(), "dedicated1");
            check(c.hostId.equals("pz-dedicated1") && c.heartbeatSeconds == 5 && c.enabled, "defaults");
            check(c.heartbeatUri.toString().equals("http://127.0.0.1:5088/api/hosts/pz-dedicated1/heartbeat"), "endpoint");
            check(!c.hostId.equals(new BridgeConfig(new Properties(), "dedicated2").hostId), "identity");
        });
        test("reject external destinations and invalid configuration", () -> {
            for (String url : List.of("https://127.0.0.1:5088", "http://example.com", "http://localhost:5088",
                    "http://user:secret@127.0.0.1:5088", "http://127.0.0.1:5088/path",
                    "http://127.0.0.1:5088?x=1", "http://127.0.0.1:5088#x", "http://127.0.0.1:0"))
                invalid("panelUrl", url);
            invalid("hostId", "../host"); invalid("heartbeatSeconds", "11"); invalid("requestTimeoutSeconds", "0");
            invalid("enabled", "yes"); invalid("hostName", "hello\u0001world");
            check(new BridgeConfig(property("panelUrl", "http://[::1]:5088/"), "test").heartbeatUri.getHost().equals("[::1]"), "IPv6");
        });
        test("UTF-8 configuration creation and preservation", () -> {
            Path dir = Files.createTempDirectory("bridge-config-");
            Path file = dir.resolve("nested/config.properties");
            try {
                BridgeConfig.load(file, "test");
                check(Files.exists(file), "created");
                String text = "hostName=Сервер Зомбоид\nenabled=false\n";
                Files.writeString(file, text, StandardCharsets.UTF_8);
                BridgeConfig c = BridgeConfig.load(file, "test");
                check(c.hostName.equals("Сервер Зомбоид") && !c.enabled, "UTF-8");
                check(Files.readString(file).startsWith(text), "original bytes preserved before appended defaults");
            } finally { try(var paths=Files.walk(dir)){for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path);} }
        });
        test("JSON encoding and finite locale-independent metrics", () -> {
            check(Heartbeat.quote("a\"\\\n\r\t\u0001").equals("\"a\\\"\\\\\\n\\r\\t\\u0001\""), "escaping");
            check(Heartbeat.quote("😀").equals("\"\\ud83d\\ude00\""), "surrogates");
            Locale old = Locale.getDefault();
            try {
                Locale.setDefault(Locale.GERMANY);
                String body = Heartbeat.json(new BridgeConfig(new Properties(), "test"), 125, Double.NaN, -1, false);
                check(body.contains("\"cpuPercent\":100.000") && body.contains("\"memoryMb\":0.000"), "metrics");
                check(body.contains("\"uptimeSeconds\":0") && body.contains("\"health\":\"Degraded\""), "health");
            } finally { Locale.setDefault(old); }
        });
        test("CPU normalization and bounded retry delays", () -> {
            check(JvmMetrics.normalizeCpu(100, 100, 4) == 25, "normalization");
            check(JvmMetrics.normalizeCpu(1000, 100, 1) == 100, "cap");
            check(JvmMetrics.normalizeCpu(-1, 100, 1) == 0 && JvmMetrics.normalizeCpu(1, 0, 1) == 0, "invalid");
            check(PanelReporter.retrySeconds(5, 30, 1) == 5 && PanelReporter.retrySeconds(5, 30, 3) == 20
                && PanelReporter.retrySeconds(5, 30, 30) == 30, "bounded backoff");
        });
        test("optional adapter preserves runtime and other mods", () -> {
            List<String> loaded = new ArrayList<>(List.of("Other", "ServerPanelBridge"));
            List<String> advertised = new ArrayList<>(List.of("Other", "ServerPanelBridge", "\\ServerPanelBridge"));
            OptionalClientAdapter.exclude(advertised, loaded);
            check(advertised.equals(List.of("Other")) && loaded.contains("ServerPanelBridge"), "own entry only");
            OptionalClientAdapter.exclude(advertised, loaded);
            check(advertised.equals(List.of("Other")), "idempotent");
        });
        test("optional adapter refuses shared or unloaded lists", () -> {
            List<String> same = new ArrayList<>(List.of("ServerPanelBridge"));
            try { OptionalClientAdapter.exclude(same, same); throw new AssertionError("shared list accepted"); }
            catch (IllegalStateException expected) { }
            List<String> advertised = new ArrayList<>(List.of("Other", "ServerPanelBridge"));
            try { OptionalClientAdapter.exclude(advertised, List.of("Other")); throw new AssertionError("unloaded mod accepted"); }
            catch (IllegalStateException expected) { }
            check(same.size() == 1 && advertised.size() == 2, "lists unchanged");
        });
        httpTests();
        test("client bootstrap performs no configuration or reporting", () -> {
            Path temp = Files.createTempDirectory("bridge-client-");
            System.setProperty("serverpanelbridge.testCache", temp.toString());
            try {
                zombie.network.GameServer.server = false;
                Main.main(new String[0]); Main.onTick();
                check(me.zed_0xff.zombie_buddy.Exposer.alias.equals("ServerPanelBridge"), "bridge exposed");
                try (var entries = Files.list(temp)) { check(entries.findAny().isEmpty(), "client wrote config"); }
                var field = Main.class.getDeclaredField("reporter"); field.setAccessible(true);
                check(field.get(null) == null, "client started reporting");
                Path configDir = temp.resolve("ServerPanelBridge/test-server");
                Files.createDirectories(configDir);
                Files.writeString(configDir.resolve("ServerPanelBridge.properties"), "enabled=false\n");
                zombie.network.GameServer.server = true;
                Main.onTick();
                check(field.get(null) == null, "disabled reporting");
                check(!zombie.network.GameServer.ServerMods.contains("ServerPanelBridge"), "optional even with reporting disabled");
                check(Files.exists(configDir.resolve("ServerPanelBridge.properties")), "server config");
            } finally {
                for (Handler h : Logger.getLogger("ServerPanelBridge").getHandlers()) {
                    h.close(); Logger.getLogger("ServerPanelBridge").removeHandler(h);
                }
                try (var paths = Files.walk(temp)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
                }
                System.clearProperty("serverpanelbridge.testCache");
            }
        });
        System.out.println("RESULT: " + passed + " passed, 0 failed");
    }
    private static void httpTests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        AtomicReference<String> body = new AtomicReference<>(), path = new AtomicReference<>(), method = new AtomicReference<>();
        AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger();
        AtomicLong delay = new AtomicLong();
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().getPath()); method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delay.get());
                if (status.get() == 302) exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/redirected");
                exchange.sendResponseHeaders(status.get(), -1);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        Properties values = property("panelUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        values.setProperty("requestTimeoutSeconds", "5");
        AtomicLong clock = new AtomicLong(100);
        Logger logger = Logger.getAnonymousLogger(); logger.setLevel(Level.OFF);
        try (PanelReporter reporter = new PanelReporter(new BridgeConfig(values, "http-test"), new JvmMetrics(), clock::get, logger)) {
            test("HTTP heartbeat contract and game tick health", () -> {
                reporter.sendOnce();
                check(method.get().equals("POST") && path.get().equals("/api/hosts/pz-http-test/heartbeat"), "request");
                check(body.get().contains("\"health\":\"Healthy\"") && body.get().contains("\"category\":\"Game servers\""), "payload");
                clock.addAndGet(21000); reporter.sendOnce();
                check(body.get().contains("\"health\":\"Degraded\""), "stale tick");
                reporter.onGameTick(); reporter.sendOnce();
                check(body.get().contains("\"health\":\"Healthy\""), "tick recovered");
            });
            test("HTTP rejection and redirects are not accepted", () -> {
                for (int code : new int[]{500, 302}) {
                    status.set(code); int before = calls.get();
                    try { reporter.sendOnce(); throw new AssertionError("accepted " + code); }
                    catch (java.io.IOException expected) { }
                    check(calls.get() == before + 1 && !path.get().equals("/redirected"), "redirect followed");
                }
                status.set(200);
            });
            test("HTTP timeout is bounded", () -> {
                delay.set(1700);
                Properties shortTimeout = new Properties(); shortTimeout.putAll(values);
                shortTimeout.setProperty("requestTimeoutSeconds", "1");
                try (PanelReporter shortReporter = new PanelReporter(new BridgeConfig(shortTimeout, "timeout"), new JvmMetrics(), clock::get, logger)) {
                    shortReporter.sendOnce(); throw new AssertionError("no timeout");
                }
                catch (HttpTimeoutException expected) { }
                delay.set(0);
            });
            test("scheduler starts once and game ticks remain nonblocking", () -> {
                int before = calls.get();
                delay.set(500);
                reporter.start(); reporter.start();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (calls.get() == before && System.nanoTime() < until) Thread.sleep(10);
                check(calls.get() == before + 1, "duplicate or missing worker");
                long start = System.nanoTime();
                reporter.onGameTick();
                check(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(100), "tick blocked");
                reporter.close(); reporter.start();
                Thread.sleep(150);
                check(calls.get() == before + 1, "work after close");
            });
        } finally { server.stop(0); executor.shutdownNow(); }
    }
}
