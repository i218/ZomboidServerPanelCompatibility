package io.serverpanelbridge;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Properties;

public final class BridgeConfig {
    public final boolean enabled, commandsEnabled, stopEnabled, stopWhenPlayersOnline, restartEnabled, outputEnabled, captureConsoleOutput;
    public final URI heartbeatUri;
    public final String hostId, hostName, category, environment, address;
    public final String sessionId=ClientSession.ID;
    public final int heartbeatSeconds, timeoutSeconds, staleTickSeconds, maxRetrySeconds, restartExitTimeoutSeconds;

    public BridgeConfig(Properties values, String serverName) {
        enabled = bool(values, "enabled", true);
        outputEnabled = bool(values, "outputEnabled", true);
        captureConsoleOutput = bool(values, "captureConsoleOutput", false);
        commandsEnabled = bool(values, "commandsEnabled", true);
        stopEnabled = bool(values, "stopEnabled", false);
        restartEnabled = bool(values, "restartEnabled", false);
        restartExitTimeoutSeconds = integer(values, "restartExitTimeoutSeconds", 180, 10, 600);
        stopWhenPlayersOnline = bool(values, "stopWhenPlayersOnline", false);
        String base = values.getProperty("panelUrl", "http://127.0.0.1:5088").trim();
        URI uri = URI.create(base);
        String host = uri.getHost();
        if (!"http".equals(uri.getScheme()) || host == null ||
            !(host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1")) ||
            uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
            !(uri.getPath().isEmpty() || uri.getPath().equals("/")) ||
            uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IllegalArgumentException("panelUrl must be an HTTP loopback address with no credentials, query or path");
        hostId = text(values, "hostId", "pz-" + serverName, 120);
        if (!hostId.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("hostId contains invalid characters");
        hostName = text(values, "hostName", "Project Zomboid: " + serverName, 200);
        category = text(values, "category", "Game servers", 64);
        environment = text(values, "environment", "Production", 100);
        address = text(values, "address", "Local dedicated server", 200);
        heartbeatSeconds = integer(values, "heartbeatSeconds", 5, 5, 10);
        timeoutSeconds = integer(values, "requestTimeoutSeconds", 2, 1, 5);
        staleTickSeconds = integer(values, "staleTickSeconds", 20, 10, 300);
        maxRetrySeconds = integer(values, "maxRetrySeconds", 30, heartbeatSeconds, 300);
        heartbeatUri = URI.create(base.replaceAll("/$", "") + "/api/hosts/" + hostId + "/heartbeat");
    }
    public static BridgeConfig load(Path path, String serverName) throws IOException {
        return ConfigFile.load(path,serverName);
    }
    private static boolean bool(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key, Boolean.toString(fallback)).trim();
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid " + key);
        return Boolean.parseBoolean(value);
    }
    private static int integer(Properties p, String key, int fallback, int min, int max) {
        int value = Integer.parseInt(p.getProperty(key, Integer.toString(fallback)).trim());
        if (value < min || value > max) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
    private static String text(Properties p, String key, String fallback, int max) {
        String value = p.getProperty(key, "").trim();
        if (value.isEmpty()) value = fallback;
        if (value.length() > max || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
}
