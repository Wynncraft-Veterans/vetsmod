package org.wynnvets.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.wynnvets.logging.VetsLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Manages a single WebSocket connection with automatic reconnection.
 *
 * <p>Used for both the inbound and outbound v1 API endpoints. The inbound
 * connection sends messages and receives acknowledgements; the outbound
 * connection passively receives messages from the server.</p>
 */
public class WsClient implements WebSocket.Listener {

    private static final Gson GSON = new Gson();
    private static final long RECONNECT_DELAY_MS = 3000;
    private static final long PING_INTERVAL_MS = 30000;

    private final URI uri;
    private final String label;
    private final Consumer<JsonObject> messageHandler;
    private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final StringBuilder textBuffer = new StringBuilder();

    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private volatile Runnable onConnectCallback;

    /**
     * @param uri            the WebSocket endpoint URI
     * @param label          a human-readable label for logging
     * @param messageHandler callback invoked on each complete JSON text frame
     */
    public WsClient(URI uri, String label, Consumer<JsonObject> messageHandler) {
        this.uri = uri;
        this.label = label;
        this.messageHandler = messageHandler;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VetsMod-WS-" + label);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Sets a callback to be invoked every time the WebSocket (re)connects.
     * Used by {@link V1ApiManager} to re-send registration after reconnects.
     */
    public void setOnConnectCallback(Runnable callback) {
        this.onConnectCallback = callback;
    }

    /** Opens the WebSocket connection. Safe to call multiple times. */
    public void connect() {
        if (closed.get()) return;
        if (!connecting.compareAndSet(false, true)) return;

        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, this)
                .whenComplete((ws, ex) -> {
                    connecting.set(false);
                    if (ex != null) {
                        VetsLogger.debug("[{}] WebSocket connect failed: {}", label, ex.getMessage());
                        scheduleReconnect();
                    } else {
                        WebSocket old = wsRef.getAndSet(ws);
                        if (old != null) {
                            VetsLogger.warn("[{}] Replacing existing WebSocket connection (possible dual-connection)", label);
                            try {
                                old.abort();
                            } catch (Exception ignored) {
                                // Previous connection may already be closed
                            }
                        }
                        VetsLogger.debug("[{}] WebSocket connected", label);
                        schedulePing();
                        Runnable cb = onConnectCallback;
                        if (cb != null) {
                            try {
                                cb.run();
                            } catch (Exception e) {
                                VetsLogger.debug("[{}] onConnect callback error: {}", label, e.getMessage());
                            }
                        }
                    }
                });
    }

    /** Sends a JSON text frame. Silently drops the message if not connected. */
    public void send(JsonObject json) {
        WebSocket ws = wsRef.get();
        if (ws == null) return;
        try {
            ws.sendText(GSON.toJson(json), true);
        } catch (Exception e) {
            VetsLogger.debug("[{}] WebSocket send failed: {}", label, e.getMessage());
        }
    }

    /** Returns true if a WebSocket connection is currently open. */
    public boolean isConnected() {
        WebSocket ws = wsRef.get();
        return ws != null && !ws.isOutputClosed();
    }

    /** Permanently closes the connection and stops the reconnect scheduler. */
    public void close() {
        closed.set(true);
        scheduler.shutdownNow();
        WebSocket ws = wsRef.getAndSet(null);
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client closing");
            } catch (Exception ignored) {
                // already closed or error
            }
        }
    }

    // ── WebSocket.Listener ────────────────────────────────────────────

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        textBuffer.append(data);
        if (last) {
            String text = textBuffer.toString();
            textBuffer.setLength(0);
            handleText(text);
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        VetsLogger.debug("[{}] WebSocket closed: {} {}", label, statusCode, reason);
        wsRef.compareAndSet(webSocket, null);
        scheduleReconnect();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        VetsLogger.debug("[{}] WebSocket error: {}", label, error.getMessage());
        wsRef.compareAndSet(webSocket, null);
        try {
            webSocket.abort();
        } catch (Exception ignored) {
            // WebSocket may already be closed
        }
        scheduleReconnect();
    }

    // ── Internal ──────────────────────────────────────────────────────

    private void handleText(String text) {
        try {
            JsonObject json = GSON.fromJson(text, JsonObject.class);
            if (json != null && messageHandler != null) {
                messageHandler.accept(json);
            }
        } catch (JsonSyntaxException e) {
            VetsLogger.debug("[{}] Invalid JSON from server: {}", label, e.getMessage());
        }
    }

    private void scheduleReconnect() {
        if (closed.get()) return;
        scheduler.schedule(this::connect, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void schedulePing() {
        if (closed.get()) return;
        scheduler.scheduleAtFixedRate(() -> {
            WebSocket ws = wsRef.get();
            if (ws != null && !ws.isOutputClosed()) {
                try {
                    ws.sendPing(ByteBuffer.allocate(0));
                } catch (Exception ignored) {
                    // will trigger onError → reconnect
                }
            }
        }, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
}
