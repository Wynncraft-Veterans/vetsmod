package org.wynnvets.api;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.wynnvets.Vetsmod;
import org.wynnvets.chat.dispatcher.CommandDispatcher;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.fetcher.polling.StaffRanksPoller;
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

    /** Server-confirmed staff status from the most recent successful auth.
     *  Cleared on disconnect. The value is "modern verification token +
     *  WAPI-confirmed staff" -- not the local /gu rank cache. Used to gate
     *  /caution, /warn, /eject, and /wv check (caution view). */
    private static volatile boolean confirmedStaff = false;
    /** In-game guild rank from the staff roster ("chief"/"strategist"/
     *  "captain"/"owner"), populated alongside {@link #confirmedStaff}.
     *  Empty when not staff. */
    private static volatile String confirmedStaffRank = "";

    private static final CopyOnWriteArrayList<Consumer<JsonObject>> outboundListeners = new CopyOnWriteArrayList<>();

    /** Listeners that receive every typed inbound frame. V1ApiManager's own
     *  inbound handler only processes ``{"status":...}``-shaped acks (auth,
     *  staff-action, chat); anything carrying a ``type`` field is a typed
     *  response and gets fanned out here so consumers (currently:
     *  {@link org.wynnvets.mwe.anni.network.AnniWsHandler}) can route by
     *  type. Symmetric to {@link #outboundListeners}. */
    private static final CopyOnWriteArrayList<Consumer<JsonObject>> inboundListeners = new CopyOnWriteArrayList<>();

    /** FIFO queue of callbacks awaiting a staff-action ack frame. Each
     *  outgoing caution_check / caution_add / warn_add / eject_add frame
     *  enqueues one callback; each incoming ack with a staff-action
     *  shape ({@code kind}, {@code triggered}, or {@code would_trigger})
     *  pops the head callback and invokes it. The protocol has no
     *  per-frame correlation ID, so this is a strict-order queue --
     *  rapidly-fired commands without an interleaving wait may receive
     *  reordered callbacks, but the alternative (correlation IDs) was
     *  judged not worth the wire-protocol churn for the current scale. */
    private static final java.util.concurrent.ConcurrentLinkedDeque<Consumer<JsonObject>>
        staffActionCallbacks = new java.util.concurrent.ConcurrentLinkedDeque<>();

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
            // Typed inbound frames (carrying a `type` field, no `status`)
            // are server replies whose contract has a dedicated response
            // shape rather than the generic {"status":...} ack. Fan them
            // out to registered inbound listeners — V1ApiManager doesn't
            // know about specific MWE/feature types here. Symmetric to
            // the outbound listener fan-out below.
            if (json.has("type")) {
                for (Consumer<JsonObject> listener : inboundListeners) {
                    try {
                        listener.accept(json);
                    } catch (Exception e) {
                        VetsLogger.warn("Inbound listener error: {}",
                                e.getMessage());
                    }
                }
                return;
            }

            // Acknowledgements from the server. Auth-frame responses share
            // the {"status":...} shape with chat acks; we route them based on
            // whether an auth was the most recent frame we sent (and on the
            // tier/ws_tier hint that auth-success responses include).
            if (!json.has("status")) return;
            String status = json.get("status").getAsString();
            boolean wasAuthAck = expectingAuthAck;

            // Staff-action ack shape: commits carry `kind`+`triggered`,
            // checks carry `total_points`, preflight carries
            // `status:"would_trigger"`. We pop ONE pending callback per
            // matching ack so multiple in-flight staff actions resolve
            // in send order. Errors (status=="error") are also routed to
            // the callback queue when one is pending and we aren't
            // currently waiting on an auth ack -- the alternative would
            // strand the caller's UI in "loading" forever on a
            // server-side validation failure.
            //
            // Auth responses are explicitly excluded by the `tier` key
            // (only auth acks carry it) so a future `total_points`
            // collision can't misroute auth into the staff queue.
            boolean isAuthShaped = json.has("tier");
            boolean staffShaped = !isAuthShaped && (
                json.has("kind")
                || json.has("triggered")
                || json.has("total_points")
                || json.has("target_uuid")
                || "would_trigger".equals(status));
            boolean staffOrphanError = "error".equals(status)
                && !wasAuthAck
                && !staffActionCallbacks.isEmpty();
            if (staffShaped || staffOrphanError) {
                Consumer<JsonObject> cb = staffActionCallbacks.pollFirst();
                if (cb != null) {
                    try {
                        cb.accept(json);
                    } catch (Exception e) {
                        VetsLogger.warn("Staff-action callback error: {}", e.getMessage());
                    }
                    return;
                }
                VetsLogger.debug("Staff-action ack with empty callback queue: {}", json);
                // Without a callback the response is unactionable; swallow it
                // so it doesn't leak into the auth/chat error path below.
                return;
            }

            // Auth success responses carry a `tier` key — chat acks don't.
            // This double-check protects against ack reordering on lossy nets.
            if ("ok".equals(status) && json.has("tier")) {
                expectingAuthAck = false;
                String tier = json.get("tier").isJsonNull() ? "" : json.get("tier").getAsString();
                long now = System.currentTimeMillis();
                VetsConfig.setString(VetsConfig.VETS_AUTH_TIER, tier);
                VetsConfig.setLong(VetsConfig.VETS_AUTH_VERIFIED_AT, now);
                // Capture server-confirmed staff status from the auth ack.
                // The roster lookup happens server-side; the client just
                // reads whatever the server resolved.
                boolean wasStaff = confirmedStaff;
                confirmedStaff = json.has("is_staff")
                    && !json.get("is_staff").isJsonNull()
                    && json.get("is_staff").getAsBoolean();
                if (confirmedStaff && json.has("staff_rank")
                    && !json.get("staff_rank").isJsonNull()) {
                    confirmedStaffRank = json.get("staff_rank").getAsString();
                } else {
                    confirmedStaffRank = "";
                }
                // Same-world demotion clears the eligibility cache so the
                // fast-path bypass in CommandDispatcher doesn't keep treating
                // us as staff. World change already resets it via
                // GuildStateManager.onEnteredWorld -> resetStaffChatEligibilityCache.
                if (wasStaff && !confirmedStaff) {
                    CommandDispatcher.resetStaffChatEligibilityCache();
                }
                // Close the cold-start gap: the next scheduled poll may be up
                // to two minutes away, so trigger an immediate refresh of the
                // receiver-side staff cache as soon as auth completes.
                StaffRanksPoller.refreshNow();
                GuildStateManager.onAuthSuccess(tier);
                // MWE auto-enable: a tier-vets user (member/waitlist/
                // honourary) gets the anni subsystem on for free. Don't
                // disable on tier downgrade — the user may have toggled
                // it on manually and we don't want to override that. The
                // tier downgrade itself will gate eligibility server-side.
                if (("member".equals(tier) || "waitlist".equals(tier)
                        || "honourary".equals(tier))
                        && !VetsConfig.get(VetsConfig.VETS_ANNI_ENABLED)) {
                    VetsConfig.set(VetsConfig.VETS_ANNI_ENABLED, true);
                    VetsLogger.debug(
                            "vetsAnniEnabled auto-set on tier={} auth ack",
                            tier);
                }
                return;
            }
            if (!"ok".equals(status)) {
                String detail = json.has("detail") ? json.get("detail").getAsString() : "unknown";
                if (wasAuthAck || detail.startsWith("auth rejected")
                        || detail.startsWith("Authentication required")) {
                    expectingAuthAck = false;
                    boolean wasStaff = confirmedStaff;
                    confirmedStaff = false;
                    confirmedStaffRank = "";
                    if (wasStaff) {
                        CommandDispatcher.resetStaffChatEligibilityCache();
                    }
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
            if (json.has("type")) {
                String frameType = json.get("type").getAsString();
                if ("server_info".equals(frameType)) {
                    boolean unauthEnabled = json.has("unauth_enabled")
                        && json.get("unauth_enabled").getAsBoolean();
                    org.wynnvets.guild.SessionAuthWarning.onServerInfo(unauthEnabled);
                    return;
                }
                if ("staff_online".equals(frameType) || "staff_offline".equals(frameType)) {
                    handleStaffPresenceFrame(frameType, json);
                    return;
                }
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
        confirmedStaff = false;
        confirmedStaffRank = "";
        // Drain any in-flight staff-action callbacks with synthetic
        // errors so callers don't hang waiting on responses that will
        // never arrive.
        Consumer<JsonObject> pending;
        while ((pending = staffActionCallbacks.pollFirst()) != null) {
            JsonObject err = new JsonObject();
            err.addProperty("status", "error");
            err.addProperty("detail", "Connection closed before reply.");
            try {
                pending.accept(err);
            } catch (Exception e) {
                VetsLogger.warn("Staff-action drain callback error: {}", e.getMessage());
            }
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
     * Sends a {@code rank_change} control frame describing a guild rank-change
     * broadcast observed in chat. The server deduplicates across reporting
     * clients and dispatches the alert (BAN/KICK to dazebot, MOTE to the
     * bridge channel). See v1_protocol.md §1.9.
     *
     * @param actor          the captain/chief who issued the rank change
     * @param target         the player whose rank was changed
     * @param fromRank       previous rank (Recruit, Recruiter, Captain, Strategist, Chief, Owner)
     * @param toRank         new rank
     * @param classification one of {@code "ban"}, {@code "kick"}, {@code "mote"}
     */
    public static void sendRankChange(String actor, String target, String fromRank,
                                      String toRank, String classification) {
        if (inboundClient == null || !inboundClient.isConnected()) {
            VetsLogger.debug("Inbound WebSocket not connected, dropping rank_change");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "rank_change");
        payload.addProperty("uuid", UUID.randomUUID().toString());
        payload.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        payload.addProperty("actor", actor);
        payload.addProperty("target", target);
        payload.addProperty("from_rank", fromRank);
        payload.addProperty("to_rank", toRank);
        payload.addProperty("classification", classification);
        inboundClient.send(payload);
        VetsLogger.debug("Sent rank_change: {} set {} {} -> {} ({})",
                actor, target, fromRank, toRank, classification);
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
     * Sends an {@code anni_query} control frame to the server.
     *
     * <p>The frame body omits {@code mc_uuid} — the server falls back to
     * the authenticated session's identity, so a key-authed vetsmod
     * client always asks "give me my own snapshot." (Unauthenticated
     * sessions get an {@code error: "mc_uuid required"} response, which
     * surfaces as a null snapshot via {@link org.wynnvets.mwe.anni.network.AnniQueryClient#query()}.)</p>
     *
     * <p>The actual single-flight queue + response routing lives in
     * {@link org.wynnvets.mwe.anni.network.AnniQueryClient}; this method
     * just dispatches the frame and reports whether it went out. The
     * dedicated frame type ({@code anni_query_response}) means we don't
     * need to share the staff-action callback queue or expose extra
     * state-shaping hooks here — the V1 outbound listener fans the ack
     * straight to {@code AnniQueryClient} via {@code AnniWsHandler}.</p>
     *
     * @return true iff the frame was actually sent (inbound connection up);
     *         false when the caller must fall back to a null snapshot.
     */
    public static boolean sendAnniQuery() {
        if (inboundClient == null || !inboundClient.isConnected()) {
            VetsLogger.debug("sendAnniQuery: inbound not connected");
            return false;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "anni_query");
        inboundClient.send(payload);
        VetsLogger.debug("Sent anni_query");
        return true;
    }

    /**
     * S5 — Sends an {@code anni_scrollspot_set} inbound control frame: the
     * local user (who must be a party host) pins or clears their party's
     * scroll-spot coord. Pass {@code null} for the triplet to clear the spot.
     *
     * <p>Authenticated only — temp-server reads the session's MC UUID and
     * forwards it to vets-anni as {@code actor_mc_uuid}. The client cannot
     * impersonate a different host; vets-anni double-checks host status
     * against {@code Party.host} before persisting.</p>
     *
     * <p>The response arrives as an {@code anni_scrollspot_response} frame
     * routed by {@link org.wynnvets.mwe.anni.network.AnniWsHandler} to the
     * {@link org.wynnvets.mwe.anni.network.AnniScrollspotClient}'s pending
     * {@link java.util.concurrent.CompletableFuture}.</p>
     *
     * @param x  block-X (nullable triplet = clear)
     * @param y  block-Y
     * @param z  block-Z
     * @return true iff the frame was actually sent (inbound connection up).
     */
    public static boolean sendAnniScrollspotSet(Integer x, Integer y, Integer z) {
        if (inboundClient == null || !inboundClient.isConnected()) {
            VetsLogger.debug("sendAnniScrollspotSet: inbound not connected");
            return false;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "anni_scrollspot_set");
        if (x == null || y == null || z == null) {
            payload.add("scroll_spot", com.google.gson.JsonNull.INSTANCE);
            VetsLogger.debug("Sent anni_scrollspot_set: clear");
        } else {
            JsonObject spot = new JsonObject();
            spot.addProperty("x", x);
            spot.addProperty("y", y);
            spot.addProperty("z", z);
            payload.add("scroll_spot", spot);
            VetsLogger.debug("Sent anni_scrollspot_set: {} {} {}", x, y, z);
        }
        inboundClient.send(payload);
        return true;
    }

    /**
     * Sends a {@code party_status} control frame describing the local player's
     * current Wynncraft party roster (sourced from Wynntils' {@code PartyModel}).
     * The server aggregates these reports across connected clients and exposes
     * them via {@code /v1/outbound/party_status} so vets-anni can flip the
     * dashboard from {@code ONLINE_WORLD} to {@code ONLINE_PARTY} once a
     * party-assigned player has actually joined the host's MC party.
     *
     * <p>Names are sent verbatim — server-side resolves to UUIDs via the
     * roster cache (same path the {@code !list} discord command uses for
     * tab-only entries). An empty leader + empty members signals "I am not
     * in a party right now," which is how disband / self-leave propagates
     * (Wynntils has no dedicated event for those — see
     * {@code PartyRosterListener}).</p>
     *
     * @param leaderName   the party leader's Minecraft username, or {@code ""} if not in a party
     * @param memberNames  every party member's username (including the leader
     *                     and the local player — Wynntils' convention), or empty list
     */
    public static void sendPartyStatus(String leaderName, List<String> memberNames) {
        if (inboundClient == null || !inboundClient.isConnected()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "party_status");
        payload.addProperty("leader_name", leaderName != null ? leaderName : "");
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        if (memberNames != null) {
            for (String name : memberNames) {
                if (name != null && !name.isEmpty()) {
                    arr.add(name);
                }
            }
        }
        payload.add("member_names", arr);
        inboundClient.send(payload);
        VetsLogger.debug("Sent party_status: leader={}, members={}",
                leaderName, arr.size());
    }

    /**
     * Routes a {@code staff_online} / {@code staff_offline} frame to
     * {@link StaffRanksPoller}. These deltas keep the receiver-side
     * staff-rank cache fresh between scheduled polls so the chat
     * rewriters recognise a newly-online staff member's whispers
     * within milliseconds rather than waiting up to two minutes.
     */
    private static void handleStaffPresenceFrame(String type, JsonObject json) {
        String username = json.has("username") && !json.get("username").isJsonNull()
            ? json.get("username").getAsString()
            : "";
        if (username.isEmpty()) {
            return;
        }
        if ("staff_online".equals(type)) {
            String rank = json.has("rank") && !json.get("rank").isJsonNull()
                ? json.get("rank").getAsString()
                : "";
            StaffRanksPoller.applyLiveStaffEvent(username, rank, true);
        } else {
            StaffRanksPoller.applyLiveStaffEvent(username, null, false);
        }
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

    /**
     * Registers a listener that receives every typed inbound frame (any
     * frame carrying a {@code type} field). Listeners are invoked on the
     * WebSocket reader thread; bounce to the main thread via
     * {@code Minecraft.getInstance().execute(...)} for any work that
     * touches game state.
     *
     * <p>V1ApiManager's own inbound routing handles only
     * {@code {"status":...}}-shaped acks (auth, staff-action, chat).
     * Anything with a {@code type} field is fanned out to these
     * listeners. Listeners MUST filter by type — they will see every
     * typed inbound frame, including frames from features they don't
     * own.</p>
     */
    public static void addInboundListener(Consumer<JsonObject> listener) {
        inboundListeners.add(listener);
    }

    /** Removes a previously registered inbound listener. */
    public static void removeInboundListener(Consumer<JsonObject> listener) {
        inboundListeners.remove(listener);
    }

    /**
     * Sends a staff-action control frame (caution_check / caution_add /
     * warn_add / eject_add) and registers a callback for the matching
     * server ack.
     *
     * <p>Callers MUST verify {@link #isConfirmedStaff()} before calling --
     * the server will reject the frame otherwise, and the rejection
     * burns a callback slot. The {@code fields} map is merged into the
     * outgoing JSON; do NOT include {@code "type"} (this method sets
     * it). The callback runs on the WebSocket reader thread and is
     * single-use.</p>
     *
     * @param type     "caution_check" / "caution_add" / "warn_add" / "eject_add"
     * @param fields   frame-specific fields (e.g. target_username, message, confirm)
     * @param callback invoked with the server's ack JSON. May be a
     *                 {"status":"ok",...} (success), {"status":"would_trigger",...}
     *                 (caution preflight), or {"status":"error","detail":...}.
     */
    public static void sendStaffActionFrame(
            String type, JsonObject fields, Consumer<JsonObject> callback) {
        if (inboundClient == null || !inboundClient.isConnected()) {
            JsonObject err = new JsonObject();
            err.addProperty("status", "error");
            err.addProperty("detail", "Inbound WebSocket not connected.");
            if (callback != null) callback.accept(err);
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", type);
        if (fields != null) {
            for (var entry : fields.entrySet()) {
                payload.add(entry.getKey(), entry.getValue());
            }
        }
        if (callback != null) {
            staffActionCallbacks.addLast(callback);
        }
        inboundClient.send(payload);
        VetsLogger.debug("Sent staff-action frame: {}", type);
    }

    /** @return whether the most recent successful auth ack reported the
     *  user as confirmed staff. False until auth completes; cleared on
     *  auth failure or disconnect. This is the *only* gate that should
     *  be used for /caution, /warn, /eject -- {@code GuildStateManager.isStaff()}
     *  reads the spoofable client-side /gu rank cache. */
    public static boolean isConfirmedStaff() {
        return confirmedStaff;
    }

    /** @return the server-confirmed in-game guild rank (one of "chief",
     *  "strategist", "captain", "owner") for the authenticated user, or
     *  empty string when not confirmed-staff. Used to map /eject
     *  outcomes to the right /gu kick vs /gu rank dispatch. */
    public static String confirmedStaffRank() {
        return confirmedStaffRank;
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
