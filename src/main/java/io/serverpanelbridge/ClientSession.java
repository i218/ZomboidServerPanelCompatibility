package io.serverpanelbridge;
import java.util.UUID;
/** One identity per loaded bridge/JVM, never persisted or configurable. */
final class ClientSession {
    static final String ID=UUID.randomUUID().toString().replace("-", "");
    private ClientSession() {}
}
