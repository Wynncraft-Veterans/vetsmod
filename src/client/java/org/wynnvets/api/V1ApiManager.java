package org.wynnvets.api;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.wynnvets.Vetsmod;
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

    private static final String INBOUND_BASE = "wss://api.wynnvets.org/v1/inbound";
    private static final URI OUTBOUND_URI = URI.create("wss://api.wynnvets.org/v1/outbound");

    private static volatile WsClient inboundClient;
    private static volatile WsClient outboundClient;
    private static volatile JsonObject pendingRegistration;

    private static final CopyOnWriteArrayList<Consumer<JsonObject>> outboundListeners = new CopyOnWriteArrayList<>();

    private V1ApiManager() {
    }

    private static String getModVersion() {
        return FabricLoader.getInstance()
                .getModContainer(Vetsmod.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    /** Starts both inbound and outbound WebSocket connections. */
    public static void connect() {
        if (inboundClient != null) return;

        URI inboundUri = URI.create(INBOUND_BASE + "?version=" + getModVersion());
        inboundClient = new WsClient(inboundUri, "inbound", json -> {
            // Acknowledgements from the server — log errors
            if (json.has("status")) {
                String status = json.get("status").getAsString();
                if (!"ok".equals(status) && json.has("detail")) {
                    VetsLogger.warn("Inbound API error: {}", json.get("detail").getAsString());
                }
            }
        });

        // Re-send registration after every (re)connect so the server's
        // presence list stays accurate across network hiccups.
        inboundClient.setOnConnectCallback(() -> {
            JsonObject reg = pendingRegistration;
            if (reg != null && inboundClient != null) {
                inboundClient.send(reg);
                VetsLogger.debug("Re-sent pending registration on inbound reconnect");
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
     * Sends a registration frame to identify this client for presence tracking.
     *
     * <p>The payload is cached so it is automatically re-sent on reconnect.
     * Old clients that never call this method are unaffected — they simply
     * won’t appear in the connected-users list.</p>
     *
     * @param uuid     the player’s Minecraft UUID (with dashes)
     * @param username the player’s current username
     * @param tier     one of "guild", "waitlist", "honourary"
     */
    public static void sendRegistration(String uuid, String username, String tier) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "register");
        payload.addProperty("uuid", uuid);
        payload.addProperty("username", username);
        payload.addProperty("tier", tier);
        pendingRegistration = payload;

        if (inboundClient != null && inboundClient.isConnected()) {
            inboundClient.send(payload);
            VetsLogger.debug("Sent registration: {} ({}…, tier={})", username,
                uuid.length() >= 8 ? uuid.substring(0, 8) : uuid, tier);
        }
    }

    /** Clears cached registration (e.g. on disconnect / reset). */
    public static void clearRegistration() {
        pendingRegistration = null;
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
