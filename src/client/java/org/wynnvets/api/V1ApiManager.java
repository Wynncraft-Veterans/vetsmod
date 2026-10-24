package org.wynnvets.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import org.wynnvets.Vetsmod;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.fetcher.polling.StaffRanksPoller;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.Json;

/**
 * Central manager for the v1 WebSocket API connections (inbound and outbound).
 *
 * <p>Inbound carries the frames vetsmod sends and the server's replies to them. Outbound
 * carries what the server pushes: relayed chat (guild, queue, waitlist, honourary,
 * bridge), the {@code server_info} hello, and frames the server sends only to an
 * authenticated socket ({@code staff_online} / {@code staff_offline}, {@code anni_state},
 * and the targeted {@code warning} frame).</p>
 *
 * <p>temporary-server keeps its authenticated session per socket, so the outbound socket
 * sends its own {@code auth} frame ({@link #sendOutboundAuthIfReady()}), but only once the
 * inbound ack has confirmed a member tier (member, waitlist or honourary) and only against a
 * server whose {@code server_info} carries {@code chat_tiers: "all"}. Such a server gives every
 * member tier every chat type; an older one would hide chat from an authenticated socket that
 * an unauthenticated one receives. A keyholder of tier {@code other} stays unauthenticated on
 * this socket, like a player with no key: relayed chat while the server's {@code unauth}
 * toggle is on, none with it off; a session made under an earlier member tier is logged out
 * when the inbound ack stops confirming one ({@link #endOutboundSession()}). The outbound ack
 * goes to {@link OutboundAckRouter}, never through the inbound ack's side effects. The mechanism is in {@code vetsmod_networking.md}
 * §1.</p>
 */
public final class V1ApiManager {

    private static final String INBOUND_BASE = "wss://api.wynnvets.org/v1/inbound";
    private static final URI OUTBOUND_URI = URI.create("wss://api.wynnvets.org/v1/outbound");

    // Package-private for unit tests. See V1ApiManagerFramesTest and
    // V1ApiManagerOutboundAuthTest.
    static volatile WsClient inboundClient;
    static volatile WsClient outboundClient;

    /** Whether the current outbound socket's {@code server_info} advertised {@code chat_tiers:
     *  "all"}, the receive map that gives every member tier every chat type. Reset by
     *  {@link #connect()}, {@link #disconnect()} and a lost outbound socket
     *  ({@link #onOutboundSocketLost}), so a reconnect waits for its own hello. */
    static volatile boolean outboundAuthOffered = false;

    /** Whether the latest inbound auth ack on the current inbound socket confirmed a member tier
     *  (member, waitlist or honourary). Set by {@link #onInboundAuthOk(String)}; cleared by
     *  {@link #onInboundAuthFailed(String)}, by each inbound (re)connect before it re-sends the
     *  key (so a server restart waits for the fresh ack), and by {@link #connect()} and
     *  {@link #disconnect()}. */
    static volatile boolean inboundMemberTier = false;

    /** Whether the outbound socket's latest auth ack was ok with a chat channel, as recorded by
     *  {@link OutboundAckRouter}. {@link #endOutboundSession()} reads it so it logs out only a
     *  session that exists. Reset by {@link #connect()}, {@link #disconnect()} and a lost
     *  outbound socket ({@link #onOutboundSocketLost}), whose server session died with it. */
    static volatile boolean outboundAuthenticated = false;

    /** Whether this connection's outbound {@code server_info} carried {@code unregister: true},
     *  the capability to retract a registration ({@link #sendUnregistration(String)}). Reset by
     *  {@link #connect()} and {@link #disconnect()}. */
    static volatile boolean serverSupportsUnregister = false;

    /** The register frame that is re-sent on every inbound (re)connect, or {@code null} when the
     *  player is not registered: set by {@link #sendRegistration}, cleared by
     *  {@link #clearRegistration()} and by {@link #sendUnregistration(String)}. */
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

    /** The callbacks awaiting a staff-action ack, and, against a server that echoes ids, a chat
     *  relay's ack ({@link #sendInbound(String, String, String, String, Consumer)}).
     *  {@link #sendStaffActionFrame} sends every frame with a fresh request id ({@code req}) and
     *  registers its callback under it.
     *  temporary-server echoes the id in its reply, so {@link InboundAckRouter} answers exactly
     *  the callback whose frame the ack belongs to, and an error ack to some other frame (a
     *  forwarded guild line refused while inbound processing is disabled, say) can no longer
     *  reach a staff command. Against a server that does not echo ({@link #serverEchoesReq()}
     *  false) the router answers the eldest callback instead, the strict-order pairing the
     *  protocol had before ids. Every callback is answered in the end: by its ack, by
     *  {@link #onInboundSocketLost} when its socket dies, by its deadline ({@link #expire}), or
     *  by {@link #disconnect()}. */
    // Package-private for InboundAckRouter, which answers it.
    static final PendingAcks pendingAcks = new PendingAcks();

    /** Whether this connection's outbound {@code server_info} advertised {@code req_echo}: the
     *  server echoes a frame's {@code req} in its reply. Reset by {@link #connect()} and
     *  {@link #disconnect()}, and by {@link InboundAckRouter} when an id-less staff-action ack
     *  proves the server no longer echoes (a rollback before the next hello). */
    static volatile boolean helloEchoesReq = false;

    /** Whether an inbound ack on the current inbound socket has carried a {@code req}, which
     *  proves the server echoes ids even if its hello was missed. Set by
     *  {@link InboundAckRouter}; cleared by each inbound (re)connect, {@link #connect()} and
     *  {@link #disconnect()}. */
    static volatile boolean ackEchoedReq = false;

    /** Held while a staff-action callback is registered and its frame handed to the socket, so
     *  the registration order is the order the frames go out, which the eldest-first pairing
     *  against a server that does not echo ids relies on. */
    private static final Object SEND_LOCK = new Object();

    /** Runs each pending ack's deadline ({@link #expire}). One daemon thread for the session,
     *  never shut down, like {@code WsClient}'s shared HTTP client. */
    private static final ScheduledExecutorService ACK_DEADLINES =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "VetsMod-AckDeadline");
                        t.setDaemon(true);
                        return t;
                    });

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
        outboundAuthOffered = false;
        serverSupportsUnregister = false;
        inboundMemberTier = false;
        outboundAuthenticated = false;
        helloEchoesReq = false;
        ackEchoedReq = false;
        OutboundAckRouter.reset();

        URI inboundUri = URI.create(INBOUND_BASE + "?version=" + getModVersion());
        // Built into a local, which the on-connect callback below uses, so a disconnect() that
        // nulls the field while the callback runs cannot make it dereference null.
        WsClient inbound =
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
        inbound.setOnConnectCallback(
                () -> {
                    // A reconnect is a new server session: the previous ack's tier no longer
                    // holds, so the outbound socket waits for this socket's fresh ack, and the
                    // server may not be the one that echoed request ids.
                    inboundMemberTier = false;
                    ackEchoedReq = false;
                    String storedKey = VetsConfig.getString(VetsConfigKeys.VETS_AUTH_KEY);
                    if (storedKey != null && !storedKey.isEmpty()) {
                        SessionAuthState.expectingAuthAck = true;
                        JsonObject auth = V1Frames.auth(storedKey);
                        inbound.send(auth);
                        VetsLogger.debug("Re-sent auth frame on inbound (re)connect");
                    }
                    JsonObject reg = pendingRegistration;
                    if (reg != null) {
                        inbound.send(reg);
                        VetsLogger.debug("Re-sent pending registration on inbound reconnect");
                    }
                    // Notify post-connect listeners after any auth and registration
                    // frames have been handed to the socket. WsClient writes one socket's
                    // frames in submit order and the server handles them in that order, so a
                    // frame a listener emits here is handled after the auth frame, provided
                    // that one was sent (a key is stored) and its write did not fail.
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

        // A socket that dies takes its unanswered acks with it: fail them now rather than leave
        // their callers waiting for the next disconnect().
        inbound.setOnLostCallback(V1ApiManager::onInboundSocketLost);

        WsClient outbound =
                new WsClient(OUTBOUND_URI, "outbound", V1ApiManager::handleOutboundFrame);
        // The server sends server_info right after accepting, so the hello usually arrives before
        // the socket is installed and its own sendOutboundAuthIfReady finds no socket to send on.
        // Authenticate once the socket is installed too; the flags make the two sends exclusive.
        outbound.setOnConnectCallback(V1ApiManager::sendOutboundAuthIfReady);
        outbound.setOnLostCallback(V1ApiManager::onOutboundSocketLost);
        outboundClient = outbound;

        inboundClient = inbound;
        inbound.connect();
        outboundClient.connect();
        VetsLogger.debug("V1 API connections initiated");
    }

    /** Closes both WebSocket connections for good: the closed {@link WsClient}s do not
     *  reconnect, and the next Wynncraft join's {@link #connect()} builds a new pair. A
     *  handshake still in flight is aborted when it completes ({@link WsClient#close()}).
     *  Also clears the confirmed-staff state and fails any pending staff-action callbacks. */
    public static void disconnect() {
        outboundAuthOffered = false;
        serverSupportsUnregister = false;
        inboundMemberTier = false;
        outboundAuthenticated = false;
        helloEchoesReq = false;
        ackEchoedReq = false;
        OutboundAckRouter.reset();
        StaffRanksPoller.clearLiveOverlay();
        WsClient inbound = inboundClient;
        if (inbound != null) {
            inboundClient = null;
            inbound.close();
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
        // A relay's line was already echoed and may have been delivered, so it gets the
        // outcome-unknown ack, which its callback keeps quiet about.
        for (PendingAcks.Entry entry : pendingAcks.drainAll()) {
            answer(entry, entry.kind() == PendingAcks.Kind.RELAY ? lostAck() : drainError());
        }
        VetsLogger.debug("V1 API connections closed");
    }

    /** Whether the server echoes request ids: its outbound hello said so, or an inbound ack on
     *  the current socket carried one. While false, {@link InboundAckRouter} pairs staff-action
     *  acks with callbacks eldest first, and only callbacks registered while it was true have
     *  their deadline enforced ({@link #expire}). */
    static boolean serverEchoesReq() {
        return helloEchoesReq || ackEchoedReq;
    }

    /**
     * Called by the inbound {@link WsClient} when the socket of {@code generation} is lost (it
     * closed or failed, or a newer socket replaced it). No ack to a frame sent on it can arrive on
     * another socket, so every callback still waiting on it, or on an older one, is answered now
     * with {@link #lostAck()}. Safe against a server that does not echo ids too. The next
     * socket may reach a different server, so {@link #ackEchoedReq} is cleared ({@link
     * #helloEchoesReq} stays: the next outbound hello rewrites it), and so is the
     * awaited-auth-ack flag, whose ack cannot come either.
     */
    // Package-private for unit tests. See V1ApiManagerFramesTest.
    static void onInboundSocketLost(long generation) {
        ackEchoedReq = false;
        SessionAuthState.expectingAuthAck = false;
        List<PendingAcks.Entry> lost = pendingAcks.failGeneration(generation);
        if (!lost.isEmpty()) {
            VetsLogger.debug(
                    "Inbound socket {} lost with {} ack(s) unanswered", generation, lost.size());
        }
        for (PendingAcks.Entry entry : lost) {
            answer(entry, lostAck());
        }
    }

    /**
     * The deadline of the callback registered under {@code req}, run {@link PendingAcks.Kind}'s
     * timeout after its frame was sent: if it is still waiting, it is answered with
     * {@link #timeoutAck()}, and an ack that arrives later finds nothing and is dropped. It
     * applies to a callback registered while {@link #serverEchoesReq()} held, or to any callback
     * if it holds when the deadline runs (checked once). A callback sent to a server that does
     * not echo ids is left to its ack, which would otherwise be paired with the next caller's.
     */
    // Package-private for unit tests. See V1ApiManagerFramesTest.
    static void expire(String req) {
        answer(pendingAcks.expire(req, serverEchoesReq()), timeoutAck());
    }

    /** Answers {@code entry}'s callback with {@code ack}. Call it only with an entry just removed
     *  from {@link #pendingAcks}, which makes the removing path the only one that answers; a
     *  {@code null} entry, which another path took first, is ignored. */
    static void answer(PendingAcks.Entry entry, JsonObject ack) {
        if (entry == null) {
            return;
        }
        try {
            entry.callback().accept(ack);
        } catch (Exception e) {
            VetsLogger.warn("Staff-action callback error: {}", e.getMessage());
        }
    }

    /**
     * Handles one frame from the outbound socket.
     *
     * <ul>
     *   <li>An untyped {@code {"status":...}} frame is the reply to this socket's own
     *       {@code auth} frame and goes to {@link OutboundAckRouter}, never to the listeners or
     *       the inbound ack logic. Chat frames never carry {@code status}.</li>
     *   <li>{@code server_info}: its {@code unauth_enabled} goes to
     *       {@code SessionAuthWarning.onServerInfo} so the session-start warning picks the right
     *       copy, and its {@code chat_tiers} decides whether this socket may authenticate
     *       ({@link #sendOutboundAuthIfReady()}, called here because the hello opens every
     *       outbound (re)connect).</li>
     *   <li>{@code staff_online} / {@code staff_offline} go to {@link StaffRanksPoller}.</li>
     *   <li>Everything else (chat, and the typed pushes the listeners pick out by type) fans out
     *       to the outbound listeners.</li>
     * </ul>
     */
    // Package-private for unit tests. See V1ApiManagerOutboundAuthTest.
    static void handleOutboundFrame(JsonObject json) {
        if (!json.has("type") && json.has("status")) {
            OutboundAckRouter.route(json);
            return;
        }
        if (json.has("type")) {
            String frameType = json.get("type").getAsString();
            if ("server_info".equals(frameType)) {
                boolean unauthEnabled =
                        json.has("unauth_enabled") && json.get("unauth_enabled").getAsBoolean();
                org.wynnvets.guild.SessionAuthWarning.onServerInfo(unauthEnabled);
                helloEchoesReq = hasTrueFlag(json, "req_echo");
                outboundAuthOffered = offersAllChatTiers(json);
                serverSupportsUnregister = offersUnregister(json);
                sendOutboundAuthIfReady();
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
    }

    /** Whether a {@code server_info} hello advertises the all-tiers receive map
     *  ({@code chat_tiers: "all"}). A server without the key runs the old per-tier map. */
    static boolean offersAllChatTiers(JsonObject serverInfo) {
        return "all".equals(Json.stringOrNull(serverInfo, "chat_tiers"));
    }

    /**
     * Called by the outbound {@link WsClient} when its socket is lost. The server's session for
     * that socket, and the {@code chat_tiers} offer its hello made, die with it: the next socket
     * starts unauthenticated, and authenticates once its own hello offers the map again (from
     * that hello, or from the on-connect callback if the hello came first). The request-id and
     * unregister capabilities are left alone: they gate inbound behaviour, and an outbound-only
     * drop says nothing about the server the inbound socket talks to.
     */
    // Package-private for unit tests. See V1ApiManagerOutboundAuthTest.
    static void onOutboundSocketLost(long generation) {
        outboundAuthOffered = false;
        outboundAuthenticated = false;
    }

    /** Whether {@code frame}'s {@code key} is the JSON boolean {@code true}: the form of a
     *  {@code server_info} capability flag. A server without the key lacks the capability. */
    static boolean hasTrueFlag(JsonObject frame, String key) {
        JsonElement value = frame.get(key);
        return value != null
                && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }

    /** Whether a {@code server_info} hello carries {@code unregister: true}: the server knows the
     *  {@code unregister} frame. A server without the key does not. */
    static boolean offersUnregister(JsonObject serverInfo) {
        return hasTrueFlag(serverInfo, "unregister");
    }

    /** Whether an auth ack's {@code tier} is a member tier, one the server gives a chat
     *  channel: {@code member}, {@code waitlist} or {@code honourary} (not {@code other}). */
    static boolean isMemberTier(String tier) {
        return "member".equals(tier) || "waitlist".equals(tier) || "honourary".equals(tier);
    }

    /**
     * Called by {@link InboundAckRouter} on every ok inbound auth ack: records whether the tier
     * is a member tier and, if it is, authenticates the outbound socket too. Covers the
     * connect-time auth, {@code /unlock} and the mid-session Returners re-auth, since each of
     * them ends in an inbound ack. A non-member tier ends an outbound session made under an
     * earlier member tier ({@link #endOutboundSession()}).
     */
    static void onInboundAuthOk(String tier) {
        inboundMemberTier = isMemberTier(tier);
        if (inboundMemberTier) {
            sendOutboundAuthIfReady();
        } else {
            endOutboundSession();
        }
    }

    /**
     * Called by {@link InboundAckRouter} on an inbound auth failure. A verdict on the key ends the
     * outbound session too; a transient one (temporary-server could not reach dazebot) leaves it,
     * since it says nothing about the key.
     */
    static void onInboundAuthFailed(String detail) {
        inboundMemberTier = false;
        if (!OutboundAckRouter.isTransient(detail)) {
            endOutboundSession();
        }
    }

    /**
     * Logs the outbound socket out by sending an {@code auth} frame with an empty key, which
     * temporary-server refuses and treats as a logout (protocol §1.8), so the socket falls back to
     * the unauthenticated feed and the server stops counting it as a staff session. Does nothing
     * unless the outbound socket holds an authenticated session.
     */
    // Package-private for unit tests. See V1ApiManagerOutboundAuthTest.
    static void endOutboundSession() {
        if (!outboundAuthenticated) {
            return;
        }
        WsClient client = outboundClient;
        if (client == null || !client.isConnected()) {
            return;
        }
        outboundAuthenticated = false;
        OutboundAckRouter.expectLogout();
        client.send(V1Frames.auth(""));
        VetsLogger.debug("Logged the outbound socket out: no member tier confirmed");
    }

    /** Sends the stored key on the outbound socket when {@link #sendOutboundAuth(String)}'s
     *  conditions hold. Called on every {@code server_info} hello, on every ok inbound auth ack
     *  and by {@link OutboundAckRouter}'s retry. */
    static void sendOutboundAuthIfReady() {
        // The flags first: most calls stop here, and only then is the stored key read.
        if (!outboundAuthOffered || !inboundMemberTier) {
            return;
        }
        sendOutboundAuth(VetsConfig.getString(VetsConfigKeys.VETS_AUTH_KEY));
    }

    /**
     * Sends {@code {type:"auth", key}} on the outbound socket if the server offered the
     * all-tiers map, the inbound ack confirmed a member tier, there is a key and the outbound
     * socket is up. Unlike {@link #sendAuth(String)} it never sets {@code expectingAuthAck}:
     * the reply arrives on the outbound socket and goes to {@link OutboundAckRouter}.
     */
    // Package-private for unit tests. See V1ApiManagerOutboundAuthTest.
    static void sendOutboundAuth(String key) {
        if (!outboundAuthOffered || !inboundMemberTier || key == null || key.isEmpty()) {
            return;
        }
        WsClient client = outboundClient;
        if (client == null || !client.isConnected()) {
            return;
        }
        client.send(V1Frames.auth(key));
        VetsLogger.debug("Sent auth frame on outbound");
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

        WsClient client = connectedInbound();
        if (client != null) {
            client.send(payload);
            VetsLogger.debug(
                    "Sent registration: {} ({}…, tier={})",
                    username,
                    uuid.length() >= 8 ? uuid.substring(0, 8) : uuid,
                    tier);
        }
    }

    /** Clears cached registration (e.g. on disconnect / reset). Sends nothing: the server drops
     *  the entry itself when the socket closes. */
    public static void clearRegistration() {
        pendingRegistration = null;
    }

    /** @return the tier of the cached registration ({@code guild}, {@code waitlist} or
     *  {@code honourary}), or {@code null} when none is cached */
    public static String registeredTier() {
        JsonObject reg = pendingRegistration;
        return reg == null ? null : tierOf(reg);
    }

    private static String tierOf(JsonObject registration) {
        com.google.gson.JsonElement tier = registration.get("tier");
        return tier != null && tier.isJsonPrimitive() ? tier.getAsString() : null;
    }

    /**
     * Retracts this client's presence registration, for a player who is no longer eligible to be
     * registered (left the guild, joined another, lost the waitlist or honourary auth tier).
     *
     * <p>The cached registration is cleared first, whatever else happens, so a later inbound
     * reconnect does not re-send it. The {@code unregister} frame itself goes out only when the
     * inbound socket is connected <b>and</b> {@link #serverSupportsUnregister} says the server
     * knows it. It is deliberately not sent to an older server: that server answers an unknown
     * frame type with a {@code Missing required fields} error that carries no {@code req}, which
     * can be taken for the reply to a pending staff-action callback or read as an auth failure.
     * Against such a server (or with the socket down) the entry disappears when the socket next
     * reconnects, since the cache no longer re-registers.</p>
     *
     * @param reason why, for the debug log ({@code left}, {@code joined} or {@code auth})
     */
    public static void sendUnregistration(String reason) {
        JsonObject cached = pendingRegistration;
        if (cached == null) {
            return;
        }
        pendingRegistration = null;
        boolean sent = false;
        WsClient client = connectedInbound();
        if (client != null && serverSupportsUnregister) {
            client.send(V1Frames.unregister());
            sent = true;
        }
        VetsLogger.debug(
                "Retracted presence registration (tier={}, reason={}, sent={})",
                tierOf(cached),
                reason,
                sent);
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
        WsClient client = connectedInbound();
        if (client == null) {
            VetsLogger.debug("sendAuth: inbound not connected; key will be sent on reconnect");
            return;
        }
        SessionAuthState.expectingAuthAck = true;
        JsonObject payload = V1Frames.auth(key);
        client.send(payload);
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
        WsClient client = connectedInbound();
        if (client == null) {
            return;
        }
        JsonObject payload = V1Frames.queueStatus(queued, world);
        client.send(payload);
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
        WsClient client = connectedInbound();
        if (client == null) {
            VetsLogger.debug("Inbound WebSocket not connected, dropping message");
            return;
        }

        JsonObject payload = V1Frames.inbound(type, rank, username, message);

        client.send(payload);
    }

    /**
     * Sends a chat relay ({@code waitlist}, {@code queue} or {@code honourary}) like
     * {@link #sendInbound(String, String, String, String)}, and reports the server's verdict to
     * {@code onAck}. Against a server that echoes request ids the frame carries one and
     * {@code onAck} gets the server's ack, or a {@code outcome_unknown} ack when the socket is
     * lost or no reply comes within 15 s. Against one that does not, the frame carries no
     * {@code req} (such a server would broadcast the key to every outbound client) and
     * {@code onAck} hears only a frame that could not be written. A socket that is down answers
     * at once with the {@code Inbound WebSocket not connected.} error. Not for {@code guild}
     * forwards, which have no one to tell.
     */
    public static void sendInbound(
            String type, String rank, String username, String message, Consumer<JsonObject> onAck) {
        WsClient client = connectedInbound();
        if (client == null) {
            VetsLogger.debug("Inbound WebSocket not connected, dropping message");
            answerQuietly(onAck, notConnectedError());
            return;
        }
        JsonObject payload = V1Frames.inbound(type, rank, username, message);
        if (serverEchoesReq()) {
            sendTracked(client, payload, onAck, PendingAcks.Kind.RELAY);
            return;
        }
        client.sendAsync(payload)
                .thenAccept(
                        written -> {
                            if (!written) {
                                answerQuietly(onAck, drainError());
                            }
                        });
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
        WsClient client = connectedInbound();
        if (client == null) {
            VetsLogger.debug("Inbound WebSocket not connected, dropping rank_change");
            return;
        }
        JsonObject payload = V1Frames.rankChange(actor, target, fromRank, toRank, classification);
        client.send(payload);
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
        WsClient client = connectedInbound();
        if (client == null) {
            return;
        }

        JsonObject payload = V1Frames.tabList(entries);
        client.send(payload);
        VetsLogger.debug("Sent tablist with {} guild entries", entries.size());
    }

    /** Lightweight record for tab list entries sent to the server. */
    public record TabListEntry(String server, String username) {}

    /**
     * Hands {@code frame} to the inbound socket if it is connected, reading the socket once, so a
     * disconnect on another thread cannot land between the check and the send. It does none of
     * the bookkeeping some other senders do around their sends: it caches no registration,
     * enqueues no staff-action callback and sets no {@code expectingAuthAck}, so it is not for
     * register, auth or staff-action frames.
     *
     * @return {@code true} if the socket was up and took the frame (which still does not confirm
     *     delivery), {@code false} if it was down and nothing was sent
     */
    public static boolean sendInboundIfConnected(JsonObject frame) {
        WsClient client = connectedInbound();
        if (client == null) {
            return false;
        }
        client.send(frame);
        return true;
    }

    /** The inbound client if it is installed and connected, else {@code null}. Every sender reads
     *  the static field through this, once, and then uses the local: {@link #disconnect()} on
     *  another thread may null the field between a check and a send. */
    private static WsClient connectedInbound() {
        WsClient client = inboundClient;
        return client != null && client.isConnected() ? client : null;
    }

    /**
     * Routes a {@code staff_online} / {@code staff_offline} frame to
     * {@link StaffRanksPoller}. These deltas keep the receiver-side
     * staff-rank cache fresh between scheduled polls so the chat
     * rewriters recognise a newly-online staff member's whispers
     * within milliseconds rather than waiting up to two minutes.
     *
     * <p>The server sends these frames only to an authenticated outbound socket
     * ({@link #sendOutboundAuthIfReady()}). A {@code staff_offline} may carry a null
     * {@code username}; its {@code uuid} is passed along so the poller can resolve it.</p>
     */
    // Package-private for unit tests. See V1ApiManagerOutboundAuthTest.
    static void handleStaffPresenceFrame(String type, JsonObject json) {
        String username = Json.stringOrEmpty(json, "username");
        String uuid = Json.stringOrNull(json, "uuid");
        if ("staff_online".equals(type)) {
            StaffRanksPoller.applyLiveStaffEvent(
                    username, uuid, Json.stringOrEmpty(json, "rank"), true);
        } else {
            StaffRanksPoller.applyLiveStaffEvent(username, uuid, null, false);
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
     * registers a callback for the matching server ack. The frame carries a fresh request id
     * ({@code req}), which the server echoes, so the ack reaches this callback and no other; a
     * server that does not echo has its staff-action acks paired with callbacks eldest first
     * ({@link #pendingAcks}).
     *
     * <p>A session the server does not treat as staff gets an error ack, which normally
     * resolves this frame's callback like any other ack; callers that want to spare
     * non-staff the round trip check {@link #isConfirmedStaff()} first. The one exception is
     * {@code check_membership}, which answers an authenticated non-staff Returners member with
     * an invite verdict ({@code MembershipSnapshot.getMayInvite}). The {@code fields} map
     * is merged into the outgoing JSON after {@code "type"} is set, so do NOT include
     * {@code "type"} -- it would replace the frame type. The callback is single-use and always
     * runs: if the socket that carried the frame is lost first, it gets {@link #lostAck()}, and
     * against a server that echoes ids it gets {@link #timeoutAck()} when no reply comes within
     * {@link PendingAcks.Kind}'s timeout (15 s for a read, 45 s for a commit). Both carry
     * {@code outcome_unknown}, since the server may have acted. It normally runs on the thread
     * that delivers inbound frames, but runs on the caller's thread when the socket is down, on
     * the thread that completed the write when the frame could not be sent, on the thread that
     * reports a lost socket, on the deadline thread, and on the disconnecting thread when
     * {@link #disconnect()} drains it -- bounce to the main thread for game-state work.</p>
     *
     * @param type     the staff-action frame type (e.g. "caution_check", "eject_add",
     *                 "check_membership")
     * @param fields   frame-specific fields (e.g. target_username, message, confirm)
     * @param callback invoked once, normally with the server's ack JSON and otherwise
     *                 with a client-built error ack (socket down, socket lost, no reply in
     *                 time, or drained by {@link #disconnect()}). May be a
     *                 {"status":"ok",...} (success), {"status":"would_trigger",...} (caution
     *                 preflight), or {"status":"error","detail":...}.
     */
    public static void sendStaffActionFrame(
            String type, JsonObject fields, Consumer<JsonObject> callback) {
        WsClient client = connectedInbound();
        if (client == null) {
            if (callback != null) callback.accept(notConnectedError());
            return;
        }
        // Every staff frame carries a req, even one with no callback, so its ack is dropped as
        // unknown instead of being paired with another caller's callback.
        sendTracked(
                client, V1Frames.staffAction(type, fields), callback, PendingAcks.kindFor(type));
    }

    /**
     * Sends {@code frame} on {@code client} with a fresh request id, registering {@code callback}
     * under it as {@code kind} (or nothing for a {@code null} callback) and scheduling its
     * deadline. The callback is taken back and answered with {@link #drainError()} when the frame
     * is never written, or when a {@link #disconnect()} raced the send and drained the callbacks
     * before this one was registered.
     */
    private static void sendTracked(
            WsClient client,
            JsonObject frame,
            Consumer<JsonObject> callback,
            PendingAcks.Kind kind) {
        String req;
        CompletableFuture<Boolean> sent;
        synchronized (SEND_LOCK) {
            req =
                    callback != null
                            ? pendingAcks.register(
                                    callback, client.generation(), kind, serverEchoesReq())
                            : PendingAcks.newReq();
            sent = client.sendAsync(V1Frames.withReq(frame, req));
        }
        VetsLogger.debug("Sent {} frame (req {})", frame.get("type").getAsString(), req);
        if (callback == null) {
            return;
        }
        ACK_DEADLINES.schedule(() -> expire(req), kind.timeoutMs, TimeUnit.MILLISECONDS);
        // A frame that never went out gets no ack: take its callback back and answer it now. If an
        // ack or a drain took it first, complete() returns null and it has already been answered.
        sent.thenAccept(
                written -> {
                    if (!written) {
                        answer(pendingAcks.complete(req), drainError());
                    }
                });
        // A disconnect() that ran after the check drained the callbacks before this one was
        // registered, so nothing would ever answer it. If the socket changed, take it back and
        // answer it with the drain's error.
        if (inboundClient != client) {
            answer(pendingAcks.complete(req), drainError());
        }
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
        return connectedInbound() != null;
    }

    /** The synthetic error ack a callback gets at once when the inbound socket is down. */
    private static JsonObject notConnectedError() {
        JsonObject err = new JsonObject();
        err.addProperty("status", "error");
        err.addProperty("detail", "Inbound WebSocket not connected.");
        return err;
    }

    /** Answers a callback that was never registered in {@link #pendingAcks}, so no other path
     *  can answer it, with the same guard as {@link #answer}. */
    private static void answerQuietly(Consumer<JsonObject> callback, JsonObject ack) {
        try {
            callback.accept(ack);
        } catch (Exception e) {
            VetsLogger.warn("Ack callback error: {}", e.getMessage());
        }
    }

    /** The synthetic error ack a staff-action callback gets when {@link #disconnect()} closed the
     *  connection first, or its frame was never written: either way the server never acted on
     *  it, or the player has left. */
    private static JsonObject drainError() {
        JsonObject err = new JsonObject();
        err.addProperty("status", "error");
        err.addProperty("detail", "Connection closed before reply.");
        return err;
    }

    /** The synthetic error ack for a frame whose socket was lost after it was written
     *  ({@link #onInboundSocketLost}). The server may have acted on it, so it carries
     *  {@code outcome_unknown}, whose value is the cause a staff line shows. */
    static JsonObject lostAck() {
        return unknownOutcomeAck("Connection lost before reply.", "connection lost");
    }

    /** The synthetic error ack for a frame whose deadline passed with no reply
     *  ({@link #expire}). Like {@link #lostAck()}, it carries {@code outcome_unknown}. */
    static JsonObject timeoutAck() {
        return unknownOutcomeAck("No reply from the vets server.", "no reply");
    }

    private static JsonObject unknownOutcomeAck(String detail, String cause) {
        JsonObject err = new JsonObject();
        err.addProperty("status", "error");
        err.addProperty("detail", detail);
        err.addProperty("outcome_unknown", cause);
        return err;
    }
}
