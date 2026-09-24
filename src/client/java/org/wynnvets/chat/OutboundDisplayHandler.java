package org.wynnvets.chat;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.rewriter.StaffGuildAlertRewriter;
import org.wynnvets.chat.rewriter.WarningRewriter;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.queue.QueueStateManager;
import org.wynnvets.util.Json;

/**
 * Handles outbound messages received from the v1 WebSocket and displays them
 * in the player's chat HUD.
 *
 * <p>Replaces the old polling fetchers ({@code ChatMessageFetcher} and
 * {@code BridgeMessageFetcher}) with a push-based approach. A
 * {@code "warning"} frame goes to {@link WarningRewriter} ahead of every
 * gate below. Any other frame displays only while
 * {@link VetsConfig#PRINT_BRIDGE_MESSAGES} is on, and only for (see
 * {@code shouldDisplayMessages()}):
 * <ul>
 *   <li>Returners members &mdash; except, outside a world queue, their
 *       {@code "guild"} frames, which the Wynncraft server already delivers</li>
 *   <li>guildless, waitlist-unlocked users</li>
 *   <li>honourary-unlocked users</li>
 * </ul>
 *
 * <p>Apart from that Returners exception, these gates test this client's own
 * settings and state, not the frame's {@code type}; which types arrive is
 * meant to be temporary-server's tier filter's call. Today that filter does
 * not apply, because vetsmod never authenticates this socket
 * ({@code outbound-socket-never-authenticated}): no {@code warning} frame
 * arrives, and every chat type does while the server's {@code unauth} toggle
 * is on (none with it off). So a Returner also renders {@code waitlist} and
 * {@code honourary} relays, and the other viewers above also render
 * {@code guild} and {@code queue} chat.</p>
 *
 * <p>Self-message suppression prevents echo of messages the player just sent.
 * Server-side dedup handles guild message fingerprinting.</p>
 */
public final class OutboundDisplayHandler {

    private static final int MAX_PENDING_SELF_MESSAGES = 50;
    private static final long SELF_MESSAGE_TTL_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long DEDUP_WINDOW_MS = TimeUnit.SECONDS.toMillis(10);

    private static final Deque<PendingSelfMessage> pendingSelfMessages = new ArrayDeque<>();
    private static final Object pendingSelfLock = new Object();
    private static final Deque<RecentBridgeMessage> recentBridgeMessages = new ArrayDeque<>();
    private static final Object recentBridgeLock = new Object();
    private static final int MAX_RECENT_BRIDGE_MESSAGES = 200;

    private static Consumer<JsonObject> registeredListener;

    // UUID-based dedup: prevents duplicate display when the same outbound
    // message is delivered more than once, e.g. over two overlapping outbound
    // sockets during a reconnect.
    private static final int MAX_RECENT_UUIDS = 200;
    private static final long UUID_TTL_MS = TimeUnit.SECONDS.toMillis(10);
    private static final LinkedHashMap<String, Long> recentUuids =
            new LinkedHashMap<>(MAX_RECENT_UUIDS + 1, 0.75f, false);
    private static final Object recentUuidLock = new Object();

    private OutboundDisplayHandler() {}

    /**
     * Registers the outbound listener with the V1ApiManager; a call while already
     * registered is a no-op. Called at client init and again on every server join
     * (the disconnect handler unregisters it), each time after
     * {@link V1ApiManager#connect() V1ApiManager.connect()}.
     */
    public static void register() {
        if (registeredListener != null) {
            return;
        }
        registeredListener = OutboundDisplayHandler::onOutboundMessage;
        V1ApiManager.addOutboundListener(registeredListener);
        VetsLogger.debug("Outbound display handler registered");
    }

    /**
     * Unregisters the outbound listener.
     */
    public static void unregister() {
        if (registeredListener != null) {
            V1ApiManager.removeOutboundListener(registeredListener);
            registeredListener = null;
        }
    }

    /**
     * Queues a message the local player just sent so it can be matched against
     * incoming outbound messages and suppressed as a duplicate.
     *
     * @param username the sender's username
     * @param message  the message content
     */
    public static void queuePendingSelfMessage(String username, String message) {
        if (username == null || username.isEmpty() || message == null || message.isEmpty()) {
            return;
        }
        synchronized (pendingSelfLock) {
            long now = System.currentTimeMillis();
            pruneExpiredSelfMessages(now);
            if (pendingSelfMessages.size() >= MAX_PENDING_SELF_MESSAGES) {
                pendingSelfMessages.pollFirst();
            }
            pendingSelfMessages.addLast(new PendingSelfMessage(username, message, now));
        }
    }

    /**
     * Clears all dedup caches. Called on server disconnect.
     */
    public static void clearCaches() {
        synchronized (pendingSelfLock) {
            pendingSelfMessages.clear();
        }

        synchronized (recentBridgeLock) {
            recentBridgeMessages.clear();
        }
        synchronized (recentUuidLock) {
            recentUuids.clear();
        }
    }

    private static void onOutboundMessage(JsonObject json) {
        // Staff-pushed private warning / eject frames (v1_protocol.md §2.5)
        // bypass every display gate -- the warned player must always see
        // their own warning even if PRINT_BRIDGE_MESSAGES is off or they
        // aren't a tier that normally renders outbound chat. Targeting is
        // enforced server-side by mc_uuid match before the frame is
        // pushed, so receiving the frame here implies it's for us.
        // Today this branch never runs: the server pushes warning frames only to an
        // authenticated outbound socket, and vetsmod never authenticates that socket
        // (outbound-socket-never-authenticated).
        if ("warning".equals(Json.stringOrEmpty(json, "type"))) {
            WarningRewriter.render(json);
            return;
        }

        if (!VetsConfig.get(VetsConfig.PRINT_BRIDGE_MESSAGES)) {
            return;
        }

        if (!shouldDisplayMessages()) {
            return;
        }

        // UUID dedup: skip messages already processed within the TTL window.
        // Guards against the same frame arriving twice, e.g. over two
        // overlapping outbound sockets during a reconnect.
        String uuid = Json.stringOrEmpty(json, "uuid");
        if (!uuid.isEmpty() && isDuplicateUuid(uuid)) {
            VetsLogger.debug("onOutboundMessage: duplicate UUID suppressed [{}]", uuid);
            return;
        }

        String type = Json.stringOrEmpty(json, "type");
        String rawRank = Json.stringOrEmpty(json, "rank");
        // ``pill_display`` is the 2026-07 additive field carrying the
        // client-facing label ("Steward"/"Returner"). Prefer it when the
        // server sent it; otherwise remap the raw rank locally.
        // v1_protocol.md §2.3 documents it on bridge frames only, so every
        // relayed non-bridge frame takes the local remap too, as does every
        // frame from a pre-2026-07 server.
        String pillDisplay = Json.stringOrEmpty(json, "pill_display");
        String rank;
        if (!pillDisplay.isEmpty()) {
            rank = pillDisplay;
        } else if (!rawRank.isEmpty()) {
            rank = RankDisplayMap.displayFor(rawRank);
        } else {
            rank = "";
        }
        String username = Json.stringOrEmpty(json, "username");
        String message = Json.stringOrEmpty(json, "message");

        if (username.isEmpty() || message.isEmpty()) {
            return;
        }

        // Self-message suppression: don't show messages the player just sent
        if (shouldSuppressSelfMessage(username, message, type)) {
            return;
        }

        // Returners members already receive guild chat from the Wynncraft
        // server, with its own pills and nicknames (which
        // ServerGuildChatRewriter may restyle on that path) — normally we
        // suppress outbound guild echoes to avoid double-display.  While
        // queued, however, the game-server channel is silent, so we must
        // render WS-delivered guild chat ourselves.
        if (GuildStateManager.isReturners()
                && "guild".equals(type)
                && !QueueStateManager.isInQueue()) {
            return;
        }

        // Record bridge messages for echo suppression in onGuildChat.
        // isInternalDispatch covers only work that runs synchronously inside
        // ChatUtils' own displayClientMessage call; a Wynntils build that
        // replays a line on a later tick would escape it. Recording the
        // message here lets onGuildChat catch such a replay, and any
        // Wynncraft server echo of it.
        if ("bridge".equals(type)) {
            recordBridgeOutbound(message);
        }

        // Staff alert display: rewrite ‼-prefixed guild/queue frames from
        // staff into the shout-style ALERT box. The copy a Returner reads in
        // the game's own guild channel is handled by StaffGuildAlertRewriter
        // via ChatLogMixin instead; this branch tries every guild- or
        // queue-typed frame that gets past the checks above.
        if (("guild".equals(type) || "queue".equals(type))
                && StaffGuildAlertRewriter.tryRewriteOutbound(username, message)) {
            return;
        }

        if (GuildStateManager.isHonouraryUnlocked()) {
            ChatUtils.sendHonouraryChatMessage(rank, username, message);
        } else {
            ChatUtils.sendGuildChatMessage(rank, username, message);
        }
    }

    private static boolean shouldDisplayMessages() {
        // Returners members see outbound messages (onOutboundMessage still
        // drops their "guild" frames unless they are queued)
        if (GuildStateManager.isReturners()) {
            return true;
        }
        // Waitlist-unlocked guildless users see outbound messages
        if (GuildStateManager.isGuildless() && GuildStateManager.isWaitlistUnlocked()) {
            return true;
        }
        // Honourary-unlocked users see outbound messages
        if (GuildStateManager.isHonouraryUnlocked()) {
            return true;
        }
        return false;
    }

    private static boolean shouldSuppressSelfMessage(String username, String message, String type) {
        // Only suppress game-sourced messages (sent by this client through the temp server).
        // Covers guild (Returners), queue (queued Returners), waitlist
        // (guildless relay), and honourary relay types.
        if (!"guild".equals(type)
                && !"queue".equals(type)
                && !"waitlist".equals(type)
                && !"honourary".equals(type)) {
            return false;
        }

        synchronized (pendingSelfLock) {
            long now = System.currentTimeMillis();
            pruneExpiredSelfMessages(now);

            Iterator<PendingSelfMessage> it = pendingSelfMessages.iterator();
            while (it.hasNext()) {
                PendingSelfMessage pending = it.next();
                if (pending.username.equalsIgnoreCase(username)
                        && pending.message.equals(message)) {
                    it.remove();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether a message UUID has been seen recently, and records it
     * if not.  Returns true when the UUID is a duplicate (already processed).
     */
    private static boolean isDuplicateUuid(String uuid) {
        synchronized (recentUuidLock) {
            long now = System.currentTimeMillis();
            // Prune expired entries
            Iterator<Map.Entry<String, Long>> it = recentUuids.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > UUID_TTL_MS) {
                    it.remove();
                } else {
                    break; // insertion-ordered, remaining are newer
                }
            }
            if (recentUuids.containsKey(uuid)) {
                return true;
            }
            if (recentUuids.size() >= MAX_RECENT_UUIDS) {
                // Remove eldest
                it = recentUuids.entrySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            recentUuids.put(uuid, now);
            return false;
        }
    }

    private static void pruneExpiredSelfMessages(long nowMs) {
        while (!pendingSelfMessages.isEmpty()) {
            PendingSelfMessage head = pendingSelfMessages.peekFirst();
            if (head == null || nowMs - head.createdAtMs <= SELF_MESSAGE_TTL_MS) {
                return;
            }
            pendingSelfMessages.pollFirst();
        }
    }

    // ── Bridge echo suppression ─────────────────────────────────────

    /**
     * Checks whether a guild-chat message is an echo of a recently displayed
     * bridge outbound message.  Comparison strips whitespace and every
     * codepoint {@link PillCodec#isCustomGlyph(int)} accepts, so that
     * Wynncraft line-wrap artefacts (spaces injected at wrap points) do not
     * prevent a match.
     *
     * @param message the guild-chat message text
     *     {@link org.wynnvets.listeners.WynntilsEventListener#onGuildChat
     *     WynntilsEventListener.onGuildChat} extracted from a Wynntils
     *     guild-chat event
     * @return true if the message matches a recent bridge outbound
     */
    public static boolean wasBridgeEcho(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String normalized = normalizeBridgeDedup(message);
        synchronized (recentBridgeLock) {
            long now = System.currentTimeMillis();
            pruneExpiredBridgeMessages(now);
            for (RecentBridgeMessage recent : recentBridgeMessages) {
                if (recent.normalizedMessage.equals(normalized)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void recordBridgeOutbound(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        String normalized = normalizeBridgeDedup(message);
        synchronized (recentBridgeLock) {
            long now = System.currentTimeMillis();
            pruneExpiredBridgeMessages(now);
            if (recentBridgeMessages.size() >= MAX_RECENT_BRIDGE_MESSAGES) {
                recentBridgeMessages.pollFirst();
            }
            recentBridgeMessages.addLast(new RecentBridgeMessage(normalized, now));
        }
    }

    /**
     * Normalizes a message for bridge echo dedup by stripping whitespace
     * ({@link Character#isWhitespace(int)}) and every codepoint
     * {@link PillCodec#isCustomGlyph(int)} classifies as resource-pack glyph
     * art. This allows matching despite Wynncraft line-wrap spaces and PUA
     * badge/pill differences.
     */
    private static String normalizeBridgeDedup(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            if (!Character.isWhitespace(cp)) {
                if (!PillCodec.isCustomGlyph(cp)) {
                    sb.appendCodePoint(cp);
                }
            }
            i += charCount;
        }
        return sb.toString();
    }

    private static void pruneExpiredBridgeMessages(long nowMs) {
        while (!recentBridgeMessages.isEmpty()) {
            RecentBridgeMessage head = recentBridgeMessages.peekFirst();
            if (head == null || nowMs - head.createdAtMs <= DEDUP_WINDOW_MS) {
                return;
            }
            recentBridgeMessages.pollFirst();
        }
    }

    private static final class PendingSelfMessage {
        final String username;
        final String message;
        final long createdAtMs;

        PendingSelfMessage(String username, String message, long createdAtMs) {
            this.username = username;
            this.message = message;
            this.createdAtMs = createdAtMs;
        }
    }

    private static final class RecentBridgeMessage {
        final String normalizedMessage;
        final long createdAtMs;

        RecentBridgeMessage(String normalizedMessage, long createdAtMs) {
            this.normalizedMessage = normalizedMessage;
            this.createdAtMs = createdAtMs;
        }
    }
}
