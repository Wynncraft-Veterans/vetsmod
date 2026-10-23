package org.wynnvets.api;

import com.google.gson.JsonObject;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import org.wynnvets.Vetsmod;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.fetcher.polling.StaffRanksPoller;
import org.wynnvets.logging.VetsLogger;

/**
 * Central manager for the v1 WebSocket API connections (inbound and outbound).
 *
 * <p>Inbound carries the frames vetsmod sends and the server's replies to them. Outbound
 * carries what the server pushes: relayed chat (guild, queue, waitlist, honourary,
 * bridge), the {@code server_info} hello, and frames the server sends only to an
 * authenticated socket ({@code staff_online} / {@code staff_offline}, {@code anni_state},
 * and the targeted {@code warning} frame).</p>
 *
 * <p>temporary-server keeps its authenticated session per socket, and {@link #connect()}
 * sends the {@code auth} frame on the inbound socket only. So today the outbound socket is
 * never authenticated: none of those authenticated-only frames arrives, and relayed chat
 * arrives without the server's tier filter while its {@code unauth} toggle is on (and not
 * at all with it off). See {@code outbound-socket-never-authenticated}; the mechanism is in
 * {@code vetsmod_networking.md} §1.</p>
 */
public final class V1ApiManager {

    private static final String INBOUND_BASE = "wss://api.wynnvets.org/v1/inbound";
    private static final URI OUTBOUND_URI = URI.create("wss://api.wynnvets.org/v1/outbound");

    // Package-private for unit tests. See V1ApiManagerFramesTest.
    static volatile WsClient inboundClient;
    private static volatile WsClient outboundClient;
    private static volatile JsonObject pendingRegistration;

    private static final CopyOnWriteArrayList<Consumer<JsonObject>> outboundListeners =
            new CopyOnWriteArrayList<>();

    /** Listeners that receive every typed inbound frame. The inbound handler that
     *  {@link #connect()} installs hands the ``{"status":...}``-shaped acks (auth,
     *  staff-action, chat, and the plain ok/error acks to the other frames V1ApiManager
     *  sends) to {@link InboundAckRouter};
     *  anything carrying a ``type`` field is a typed
     *  response and gets fanned out here so consumers (currently:
     *  {@link org.wynnvets.mwe.anni.network.AnniWsHandler}) can route by
     *  type. Symmetric to {@link #outboundListeners}. */
    private static final CopyOnWriteArrayList<Consumer<JsonObject>> inboundListeners =
            new CopyOnWriteArrayList<>();

    /** Listeners that fire after the inbound socket (re)connects and any auth and
     *  registration frames have been handed to it. Use this when a feature needs to
     *  refresh its server-side state after a reconnect — e.g. anni snapshots, which
     *  otherwise go stale silently across a socket drop. Listeners run off the render
     *  thread, on whichever thread completes the socket's connect (not necessarily the
     *  thread that delivers frames); defer any game-state-touching work via
     *  {@code McUtils.mc().execute}. */
    private static final CopyOnWriteArrayList<Runnable> inboundPostConnectListeners =
            new CopyOnWriteArrayList<>();

    /** FIFO queue of callbacks awaiting a staff-action ack frame. Each frame sent
     *  through {@link #sendStaffActionFrame} with a callback enqueues one callback (the
     *  caution/warn/eject family, and {@code check_membership}). Each incoming ack that
     *  {@link InboundAckRouter} classifies as staff-action-shaped (see the
     *  {@code staffShaped} test in {@link InboundAckRouter#route}), or an error ack while
     *  a callback is pending and no auth ack is outstanding, pops the head callback and
     *  invokes it. The protocol has no
     *  per-frame correlation ID, so this is a strict-order queue: it stays aligned only
     *  while each enqueued frame's ack, and no other ack, reaches it in enqueue order.
     *  An ack that never arrives shifts every later ack onto the previous caller's
     *  callback until {@link #disconnect()} drains the queue
     *  ({@code staff-action-queue-not-drained-on-socket-reconnect}). Correlation IDs were
     *  judged not worth the wire-protocol churn for the current scale. */
    // Package-private for InboundAckRouter, which pops it.
    static final java.util.concurrent.ConcurrentLinkedDeque<Consumer<JsonObject>>
            staffActionCallbacks = new java.util.concurrent.ConcurrentLinkedDeque<>();

    private V1ApiManager() {}

    private static String getModVersion() {
        return FabricLoader.getInstance()
                .getModContainer(Vetsmod.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    /** Starts both inbound and outbound WebSocket connections. Called only on a Wynncraft join,
     *  by {@link org.wynnvets.listeners.ServerConnectionListener ServerConnectionListener}. */
    public static void connect() {
        if (inboundClient != null) return;

        URI inboundUri = URI.create(INBOUND_BASE + "?version=" + getModVersion());
        inboundClient =
                new WsClient(
                        inboundUri,
                        "inbound",
                        json -> {
                            // Typed inbound frames (carrying a `type` field; every one but
                            // anni_query_response also carries `status`, which is why
                            // `type` is tested first) are server replies whose contract has
                            // a dedicated response shape rather than the generic
                            // {"status":...} ack. Fan them
                            // out to registered inbound listeners — V1ApiManager doesn't
                            // know about specific MWE/feature types here. Symmetric to
                            // the outbound listener fan-out below.
                            if (json.has("type")) {
                                for (Consumer<JsonObject> listener : inboundListeners) {
                                    try {
                                        listener.accept(json);
                                    } catch (Exception e) {
                                        VetsLogger.warn(
                                                "Inbound listener error: {}", e.getMessage());
                                    }
                                }
                                return;
                            }

                            InboundAckRouter.route(json);
                        });

        // Re-send registration AND re-authenticate on every reconnect so the
        // server's presence + auth state stays accurate across network hiccups.
        inboundClient.setOnConnectCallback(
                () -> {
                    String storedKey = VetsConfig.getString(VetsConfigKeys.VETS_AUTH_KEY);
                    if (storedKey != null && !storedKey.isEmpty() && inboundClient != null) {
                        SessionAuthState.expectingAuthAck = true;
                        JsonObject auth = V1Frames.auth(storedKey);
                        inboundClient.send(auth);
                        VetsLogger.debug("Re-sent auth frame on inbound (re)connect");
                    }
                    JsonObject reg = pendingRegistration;
                    if (reg != null && inboundClient != null) {
                        inboundClient.send(reg);
                        VetsLogger.debug("Re-sent pending registration on inbound reconnect");
                    }
                    // Notify post-connect listeners after any auth and registration
                    // frames have been handed to the socket. The server handles frames
                    // in send order on the single inbound socket, so a frame a listener
                    // emits here is handled after the auth frame, provided that one was
                    // sent and not dropped (ws-client-send-ignores-send-pending-failure).
                    // Used by AnniWsHandler to re-pull a fresh snapshot on every
                    // reconnect — without this the cache silently goes stale across a
                    // socket drop.
                    for (Runnable listener : inboundPostConnectListeners) {
                        try {
                            listener.run();
                        } catch (Exception e) {
                            VetsLogger.warn(
                                    "Inbound post-connect listener error: {}", e.getMessage());
                        }
                    }
                });

        outboundClient =
                new WsClient(
                        OUTBOUND_URI,
                        "outbound",
                        json -> {
                            // Server pushes a `server_info` hello frame on connect to tell
                            // us the current `unauth` toggle state. Routed straight to
                            // SessionAuthWarning so its session-start warning can pick the
                            // right copy. staff_online / staff_offline go to StaffRanksPoller
                            // below; everything else (chat, and the typed pushes the listeners
                            // pick out by type) fans out to the listeners.
                            if (json.has("type")) {
                                String frameType = json.get("type").getAsString();
                                if ("server_info".equals(frameType)) {
                                    boolean unauthEnabled =
                                            json.has("unauth_enabled")
                                                    && json.get("unauth_enabled").getAsBoolean();
                                    org.wynnvets.guild.SessionAuthWarning.onServerInfo(
                                            unauthEnabled);
                                    return;
                                }
                                if ("staff_online".equals(frameType)
                                        || "staff_offline".equals(frameType)) {
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

    /** Closes both WebSocket connections for good: the closed {@link WsClient}s do not
     *  reconnect, and the next Wynncraft join's {@link #connect()} builds a new pair. A
     *  handshake still in flight is aborted when it completes ({@link WsClient#close()}).
     *  Also clears the confirmed-staff state and fails any pending staff-action callbacks. */
    public static void disconnect() {
        if (inboundClient != null) {
            inboundClient.close();
            inboundClient = null;
        }
        if (outboundClient != null) {
            outboundClient.close();
            outboundClient = null;
        }
        SessionAuthState.confirmedStaff = false;
        SessionAuthState.confirmedStaffRank = "";
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
        JsonObject payload = V1Frames.register(uuid, username, tier);
        pendingRegistration = payload;

        if (inboundClient != null && inboundClient.isConnected()) {
            inboundClient.send(payload);
            VetsLogger.debug(
                    "Sent registration: {} ({}…, tier={})",
                    username,
                    uuid.length() >= 8 ? uuid.substring(0, 8) : uuid,
                    tier);
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
     * {@code {"status":"ok","tier":"...","ws_tier":"...",...}} (the full success
     * shape, including the {@code is_staff} and {@code staff_rank} that
     * {@link InboundAckRouter} reads, is in {@code vetsmod_networking.md} §1) or
     * {@code {"status":"error","detail":"auth rejected: ..."}}. The reply
     * is routed by {@link InboundAckRouter} to
     * {@link org.wynnvets.guild.GuildStateManager#onAuthSuccess(String)
     * GuildStateManager#onAuthSuccess(String)} /
     * {@link org.wynnvets.guild.GuildStateManager#onAuthFailure(String)
     * GuildStateManager#onAuthFailure(String)}.</p>
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
        SessionAuthState.expectingAuthAck = true;
        JsonObject payload = V1Frames.auth(key);
        inboundClient.send(payload);
        VetsLogger.debug("Sent auth frame ({}…)", key.length() >= 6 ? key.substring(0, 6) : key);
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
        JsonObject payload = V1Frames.queueStatus(queued, world);
        inboundClient.send(payload);
        VetsLogger.debug("Sent queue_status: queued={}, world={}", queued, world);
    }

    /**
     * Sends a message to the v1/inbound endpoint.
     *
     * @param type     one of "guild", "queue", "waitlist", "honourary"
     * @param rank     the sender's guild rank; temporary-server rejects a {@code guild} or
     *                 {@code queue} frame whose rank is empty or not a Wynncraft guild
     *                 rank, so it may be empty only for the other two types
     * @param username the sender's username. True for every type but {@code guild}, where the
     *                 sender may be a nickname if Wynncraft's hover was absent when the line was
     *                 parsed.
     * @param message  the message content
     */
    public static void sendInbound(String type, String rank, String username, String message) {
        if (inboundClient == null || !inboundClient.isConnected()) {
            VetsLogger.debug("Inbound WebSocket not connected, dropping message");
            return;
        }

        JsonObject payload = V1Frames.inbound(type, rank, username, message);

        inboundClient.send(payload);
    }

    /**
     * Sends a {@code rank_change} control frame describing a guild rank-change
     * broadcast observed in chat. The server deduplicates across reporting
     * clients and dispatches the alert (BAN/KICK to dazebot, MOTE to the
     * bridge channel). See v1_protocol.md §1.9.
     *
     * @param actor          the player the broadcast names as having set the rank
     * @param target         the player whose rank was changed
     * @param fromRank       previous rank (Recruit, Recruiter, Captain, Strategist, Chief, Owner)
     * @param toRank         new rank
     * @param classification one of {@code "ban"}, {@code "kick"}, {@code "mote"}
     */
    public static void sendRankChange(
            String actor, String target, String fromRank, String toRank, String classification) {
        if (inboundClient == null || !inboundClient.isConnected()) {
            VetsLogger.debug("Inbound WebSocket not connected, dropping rank_change");
            return;
        }
        JsonObject payload = V1Frames.rankChange(actor, target, fromRank, toRank, classification);
        inboundClient.send(payload);
        VetsLogger.debug(
                "Sent rank_change: {} set {} {} -> {} ({})",
                actor,
                target,
                fromRank,
                toRank,
                classification);
    }

    /**
     * Sends the current tab list guild entries to the server, which uses them in its
     * {@code !list} command (to include players not connected via VetsMod) among other
     * things.
     *
     * @param entries list of {@code {server, username}} pairs parsed from the tab list
     */
    public static void sendTabList(List<TabListEntry> entries) {
        if (inboundClient == null || !inboundClient.isConnected()) {
            return;
        }

        JsonObject payload = V1Frames.tabList(entries);
        inboundClient.send(payload);
        VetsLogger.debug("Sent tablist with {} guild entries", entries.size());
    }

    /** Lightweight record for tab list entries sent to the server. */
    public record TabListEntry(String server, String username) {}

    /**
     * Hands {@code frame} to the inbound socket's {@link WsClient#send(JsonObject)} with no check
     * of its own. The caller must have just checked {@link #isInboundConnected()}. It does none of
     * the bookkeeping some other senders do around their sends: it caches no registration, enqueues
     * no staff-action callback and sets no {@code expectingAuthAck}, so it is not for register,
     * auth or staff-action frames. Today a disconnect between the caller's check and this call
     * throws {@link NullPointerException}
     * ({@code v1-check-then-send-npe-on-concurrent-disconnect}).
     */
    public static void sendInboundUnchecked(JsonObject frame) {
        inboundClient.send(frame);
    }

    /**
     * Routes a {@code staff_online} / {@code staff_offline} frame to
     * {@link StaffRanksPoller}. These deltas keep the receiver-side
     * staff-rank cache fresh between scheduled polls so the chat
     * rewriters recognise a newly-online staff member's whispers
     * within milliseconds rather than waiting up to two minutes.
     *
     * <p>Never reached today: the server sends these frames only to an authenticated
     * outbound socket, and vetsmod never authenticates that socket
     * ({@code outbound-socket-never-authenticated}).</p>
     */
    private static void handleStaffPresenceFrame(String type, JsonObject json) {
        String username =
                json.has("username") && !json.get("username").isJsonNull()
                        ? json.get("username").getAsString()
                        : "";
        if (username.isEmpty()) {
            return;
        }
        if ("staff_online".equals(type)) {
            String rank =
                    json.has("rank") && !json.get("rank").isJsonNull()
                            ? json.get("rank").getAsString()
                            : "";
            StaffRanksPoller.applyLiveStaffEvent(username, rank, true);
        } else {
            StaffRanksPoller.applyLiveStaffEvent(username, null, false);
        }
    }

    /**
     * Registers a listener that receives every outbound message from the server except
     * the {@code server_info} and staff-presence frames this class routes itself.
     * Listeners are invoked on the thread that delivers the socket's frames, not the
     * render thread.
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
     * thread that delivers the socket's frames; bounce to the main thread via
     * {@code McUtils.mc().execute(...)} for any work that
     * touches game state.
     *
     * <p>The inbound handler that {@link #connect()} installs hands every frame without a
     * {@code type} to {@link InboundAckRouter}, which routes the
     * {@code {"status":...}}-shaped acks (auth, staff-action, chat, and the plain
     * ok/error acks to the other untyped frames).
     * Anything with a {@code type} field is fanned out to these
     * listeners. Listeners MUST filter by type — they will see every
     * typed inbound frame, including frames from features they don't
     * own.</p>
     */
    public static void addInboundListener(Consumer<JsonObject> listener) {
        inboundListeners.add(listener);
    }

    /**
     * Registers a callback that fires after the inbound WebSocket
     * (re)connects and any auth and registration frames have been handed to it.
     * Use this when a feature needs to re-pull server-side state on every
     * reconnect — e.g. the anni snapshot, which otherwise goes stale silently
     * across a socket drop.
     *
     * <p>Listeners run off the render thread, on whichever thread completes the
     * socket's connect; defer game-state-touching work via
     * {@code McUtils.mc().execute(...)}. Listeners MUST be
     * idempotent — the first Wynncraft join fires this exactly once,
     * but every subsequent reconnect (a network blip, a temporary-server restart, or
     * a Wynncraft leave and rejoin) fires it again.</p>
     */
    public static void addInboundPostConnectListener(Runnable listener) {
        inboundPostConnectListeners.add(listener);
    }

    /**
     * Sends a staff-action control frame (the caution/warn/eject family --
     * caution_check / caution_add / warn_add / eject_add -- or check_membership) and
     * registers a callback for the matching server ack.
     *
     * <p>A session the server does not treat as staff gets an error ack, which normally
     * resolves this frame's callback like any other ack; callers that want to spare
     * non-staff the round trip check {@link #isConfirmedStaff()} first
     * ({@link org.wynnvets.fetcher.lookup.providers.VetsSnapshotProvider VetsSnapshotProvider}'s
     * {@code check_membership} does not:
     * {@code vets-snapshot-provider-skips-confirmed-staff-check}). The {@code fields} map
     * is merged into the outgoing JSON after {@code "type"} is set, so do NOT include
     * {@code "type"} -- it would replace the frame type. The callback is single-use. It
     * normally runs on the thread that delivers inbound frames, but runs on the caller's
     * thread when the socket is down and on the disconnecting thread when
     * {@link #disconnect()} drains it -- bounce to the main thread for game-state
     * work.</p>
     *
     * @param type     the staff-action frame type (e.g. "caution_check", "eject_add",
     *                 "check_membership")
     * @param fields   frame-specific fields (e.g. target_username, message, confirm)
     * @param callback invoked once, normally with the server's ack JSON and otherwise
     *                 with a client-built error ack (socket down, or drained by
     *                 {@link #disconnect()}). May be a {"status":"ok",...} (success),
     *                 {"status":"would_trigger",...} (caution preflight), or
     *                 {"status":"error","detail":...}.
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
        JsonObject payload = V1Frames.staffAction(type, fields);
        if (callback != null) {
            staffActionCallbacks.addLast(callback);
        }
        inboundClient.send(payload);
        VetsLogger.debug("Sent staff-action frame: {}", type);
    }

    /** @return whether the most recent successful auth ack reported the
     *  user as confirmed staff. False until auth completes; cleared on
     *  auth failure or disconnect. This is the *only* gate that should
     *  be used for /caution, /warn, /eject --
     *  {@link org.wynnvets.guild.GuildStateManager#isStaff()
     *  GuildStateManager#isStaff()} reads the spoofable
     *  client-side /gu rank cache. */
    public static boolean isConfirmedStaff() {
        return SessionAuthState.confirmedStaff;
    }

    /** @return the server-confirmed in-game guild rank (one of
     *  "strategist", "chief", "owner") for the authenticated user, or
     *  empty string when not confirmed-staff. One consumer of this
     *  accessor, reached through
     *  {@link org.wynnvets.guild.GuildStateManager GuildStateManager}'s delegate of the same name:
     *  {@link org.wynnvets.guild.GuildStateManager#isChiefOfAnyGuild()
     *  GuildStateManager#isChiefOfAnyGuild()},
     *  which gates
     *  execution of {@code /wv distribute}. Captain was retired in the
     *  2026-07 permission restructure. */
    public static String confirmedStaffRank() {
        return SessionAuthState.confirmedStaffRank;
    }

    /** Returns true if the inbound connection is active. */
    public static boolean isInboundConnected() {
        return inboundClient != null && inboundClient.isConnected();
    }
}
