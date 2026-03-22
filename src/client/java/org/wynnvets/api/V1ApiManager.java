package org.wynnvets.api;

import com.google.gson.JsonObject;
import org.wynnvets.logging.VetsLogger;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Central manager for the v1 WebSocket API connections (inbound and outbound).
 *
 * <p>Inbound is used to send messages to the server. Outbound is used to
 * receive messages (guild, waitlist, honourary, bridge) pushed by the server.</p>
 */
public final class V1ApiManager {

    private static final URI INBOUND_URI = URI.create("wss://api.wynnvets.org/v1/inbound");
    private static final URI OUTBOUND_URI = URI.create("wss://api.wynnvets.org/v1/outbound");

    private static WsClient inboundClient;
    private static WsClient outboundClient;

    private static final CopyOnWriteArrayList<Consumer<JsonObject>> outboundListeners = new CopyOnWriteArrayList<>();

    private V1ApiManager() {
    }

    /** Starts both inbound and outbound WebSocket connections. */
    public static void connect() {
        if (inboundClient != null) return;

        inboundClient = new WsClient(INBOUND_URI, "inbound", json -> {
            // Acknowledgements from the server — log errors
            if (json.has("status")) {
                String status = json.get("status").getAsString();
                if (!"ok".equals(status) && json.has("detail")) {
                    VetsLogger.warn("Inbound API error: {}", json.get("detail").getAsString());
                }
            }
        });

        outboundClient = new WsClient(OUTBOUND_URI, "outbound", json -> {
            for (Consumer<JsonObject> listener : outboundListeners) {
                try {
                    listener.accept(json);
                } catch (Exception e) {
                    VetsLogger.warn("Outbound listener error: {}", e.getMessage());
                }
            }
        });

        inboundClient.connect();
        outboundClient.connect();
        VetsLogger.debug("V1 API connections initiated");
    }

    /** Closes both WebSocket connections permanently. */
    public static void disconnect() {
        if (inboundClient != null) {
            inboundClient.close();
            inboundClient = null;
        }
        if (outboundClient != null) {
            outboundClient.close();
            outboundClient = null;
        }
        VetsLogger.debug("V1 API connections closed");
    }

    /**
     * Sends a message to the v1/inbound endpoint.
     *
     * @param type     one of "guild", "waitlist", "honourary"
     * @param rank     the sender's guild rank (may be empty)
     * @param username the sender's true Minecraft username (never a nickname)
     * @param message  the message content
     */
    public static void sendInbound(String type, String rank, String username, String message) {
        if (inboundClient == null || !inboundClient.isConnected()) {
            VetsLogger.debug("Inbound WebSocket not connected, dropping message");
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", UUID.randomUUID().toString());
        payload.addProperty("type", type);
        payload.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        payload.addProperty("rank", rank != null ? rank : "");
        payload.addProperty("username", username);
        payload.addProperty("message", message);

        inboundClient.send(payload);
    }

    /**
     * Registers a listener that receives every outbound message from the server.
     * Listeners are invoked on the WebSocket reader thread.
     */
    public static void addOutboundListener(Consumer<JsonObject> listener) {
        outboundListeners.add(listener);
    }

    /** Removes a previously registered outbound listener. */
    public static void removeOutboundListener(Consumer<JsonObject> listener) {
        outboundListeners.remove(listener);
    }

    /** Returns true if the inbound connection is active. */
    public static boolean isInboundConnected() {
        return inboundClient != null && inboundClient.isConnected();
    }

    /** Returns true if the outbound connection is active. */
    public static boolean isOutboundConnected() {
        return outboundClient != null && outboundClient.isConnected();
    }
}
