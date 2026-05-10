package org.wynnvets.api;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.wynnvets.Vetsmod;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

import java.net.URI;
import java.util.List;
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
    /** Tracks whether the *next* inbound ack frame should be routed to
     *  {@link GuildStateManager} as an auth response. Set whenever we send
     *  an auth frame; cleared on the corresponding ack. Without this we
     *  can't tell apart a chat-frame ack from an auth-frame ack — both
     *  arrive as `{"status": "ok"}` from the server. */
    private static volatile boolean expectingAuthAck = false;

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
            // Acknowledgements from the server. Auth-frame responses share
            // the {"status":...} shape with chat acks; we route them based on
            // whether an auth was the most recent frame we sent (and on the
            // tier/ws_tier hint that auth-success responses include).
            if (!json.has("status")) return;
            String status = json.get("status").getAsString();
            boolean wasAuthAck = expectingAuthAck;
            // Auth success responses carry a `tier` key — chat acks don't.
            // This double-check protects against ack reordering on lossy nets.
            if ("ok".equals(status) && json.has("tier")) {
                expectingAuthAck = false;
                String tier = json.get("tier").isJsonNull() ? "" : json.get("tier").getAsString();
                long now = System.currentTimeMillis();
                VetsConfig.setString(VetsConfig.VETS_AUTH_TIER, tier);
                VetsConfig.setLong(VetsConfig.VETS_AUTH_VERIFIED_AT, now);
                GuildStateManager.onAuthSuccess(tier);
                return;
            }
            if (!"ok".equals(status)) {
                String detail = json.has("detail") ? json.get("detail").getAsString() : "unknown";
                if (wasAuthAck || detail.startsWith("auth rejected")
                        || detail.startsWith("Authentication required")) {
                    expectingAuthAck = false;
                    GuildStateManager.onAuthFailure(detail);
                } else {
                    VetsLogger.warn("Inbound API error: {}", detail);
                }
            }
        });

        // Re-send registration AND re-authenticate on every reconnect so the
        // server's presence + auth state stays accurate across network hiccups.
        inboundClient.setOnConnectCallback(() -> {
            String storedKey = VetsConfig.getString(VetsConfig.VETS_AUTH_KEY);
            if (storedKey != null && !storedKey.isEmpty() && inboundClient != null) {
                expectingAuthAck = true;
                JsonObject auth = new JsonObject();
                auth.addProperty("type", "auth");
                auth.addProperty("key", storedKey);
                inboundClient.send(auth);
                VetsLogger.debug("Re-sent auth frame on inbound (re)connect");
            }
            JsonObject reg = pendingRegistration;
            if (reg != null && inboundClient != null) {
                inboundClient.send(reg);
                VetsLogger.debug("Re-sent pending registration on inbound reconnect");
            }
        });

        outboundClient = new WsClient(OUTBOUND_URI, "outbound", json -> {
            // Server pushes a `server_info` hello frame on connect to tell
            // us the current `unauth` toggle state. Routed straight to
            // SessionAuthWarning so its session-start warning can pick the
            // right copy. Other frames are real chat — fan out to listeners.
            if (json.has("type") && "server_info".equals(json.get("type").getAsString())) {
                boolean unauthEnabled = json.has("unauth_enabled")
                    && json.get("unauth_enabled").getAsBoolean();
                org.wynnvets.guild.SessionAuthWarning.onServerInfo(unauthEnabled);
                return;
            }
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
     * Sends a vetsmod ``auth`` frame containing the user's bearer key.
     *
     * <p>The server validates the key against dazebot, stores the resolved
     * identity/tier on the WebSocket connection, and replies with either
     * {@code {"status":"ok","tier":"...","ws_tier":"..."}} or
     * {@code {"status":"error","detail":"auth rejected: ..."}}. The reply
     * is routed to {@link GuildStateManager#onAuthSuccess(String)} /
     * {@link GuildStateManager#onAuthFailure(String)} by the inbound
     * message handler.</p>
     *
     * <p>If the inbound WebSocket is not yet connected the frame is dropped
     * silently — the {@code onConnect} callback registered in {@link #connect()}
     * already re-sends the persisted key on every reconnect, so this is
     * harmless: the user will be authenticated as soon as the connection
     * comes up.</p>
     *
     * @param key the URL-safe base64 bearer key issued by dazebot's /vetsmod
     */
    public static void sendAuth(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        if (inboundClient == null || !inboundClient.isConnected()) {
            VetsLogger.debug("sendAuth: inbound not connected; key will be sent on reconnect");
            return;
        }
        expectingAuthAck = true;
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "auth");
        payload.addProperty("key", key);
        inboundClient.send(payload);
        VetsLogger.debug("Sent auth frame ({}…)",
            key.length() >= 6 ? key.substring(0, 6) : key);
    }

    /**
     * Sends a queue status update so the server can track which users are
     * currently sitting in a Wynncraft world queue.
     *
     * @param queued true if the client just entered a queue, false if exited
     * @param world  the target world name (e.g. "NA30"), or empty
     */
    public static void sendQueueStatus(boolean queued, String world) {
        if (inboundClient == null || !inboundClient.isConnected()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "queue_status");
        payload.addProperty("queued", queued);
        payload.addProperty("world", world != null ? world : "");
        inboundClient.send(payload);
        VetsLogger.debug("Sent queue_status: queued={}, world={}", queued, world);
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
     * Sends the current tab list guild entries to the server so its {@code !list}
     * command can include players not connected via VetsMod.
     *
     * @param entries list of {@code {server, username}} pairs parsed from the tab list
     */
    public static void sendTabList(List<TabListEntry> entries) {
        if (inboundClient == null || !inboundClient.isConnected()) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "tablist");
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (TabListEntry e : entries) {
            JsonObject obj = new JsonObject();
            obj.addProperty("server", e.server());
            obj.addProperty("username", e.username());
            arr.add(obj);
        }
        payload.add("entries", arr);
        inboundClient.send(payload);
        VetsLogger.debug("Sent tablist with {} guild entries", entries.size());
    }

    /** Lightweight record for tab list entries sent to the server. */
    public record TabListEntry(String server, String username) {}

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
