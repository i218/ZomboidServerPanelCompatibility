package io.serverpanelbridge;

import java.util.Locale;

public final class Heartbeat {
    private Heartbeat() { }
    public static String json(BridgeConfig c, double cpu, double memory, long uptime, boolean tickFresh) {
        return "{\"name\":" + quote(c.hostName) +
            ",\"environment\":" + quote(c.environment) +
            ",\"address\":" + quote(c.address) +
            ",\"cpuPercent\":" + number(cpu, 100) +
            ",\"memoryMb\":" + number(memory, Double.MAX_VALUE) +
            ",\"requestsPerMinute\":0,\"errorRatePercent\":0" +
            ",\"uptimeSeconds\":" + Math.max(0, uptime) +
            ",\"version\":\"ServerPanelBridge 0.5.1\"" +
            ",\"health\":" + quote(tickFresh ? "Healthy" : "Degraded") +
            ",\"category\":" + quote(c.category) + ",\"sessionId\":" + quote(c.sessionId) + "}";
    }
    static String number(double value, double max) {
        if (!Double.isFinite(value) || value < 0) value = 0;
        return String.format(Locale.ROOT, "%.3f", Math.min(value, max));
    }
    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (ch < 32 || Character.isSurrogate(ch)) out.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    else out.append(ch);
            }
        }
        return out.append('"').toString();
    }
}
