package org.wynnvets.chat;

import com.google.gson.JsonObject;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Handles outbound messages received from the v1 WebSocket and displays them
 * in the player's chat HUD.
 *
 * <p>Replaces the old polling fetchers ({@code ChatMessageFetcher} and
 * {@code BridgeMessageFetcher}) with a push-based approach. Messages are
 * displayed based on the player's current state:
 * <ul>
 *   <li>Returners members: all guild and bridge messages</li>
 *   <li>Waitlist-unlocked users: all guild and bridge messages</li>
 *   <li>Honourary-unlocked users: all guild and bridge messages</li>
 * </ul>
 *
 * <p>Self-message suppression prevents echo of messages the player just sent.
 * Server-side dedup handles guild message fingerprinting.</p>
 */
public final class OutboundDisplayHandler {

    private static final int MAX_PENDING_SELF_MESSAGES = 50;
    private static final long SELF_MESSAGE_TTL_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long SERVER_MESSAGE_DEDUP_WINDOW_MS = TimeUnit.SECONDS.toMillis(10);
    private static final int MAX_RECENT_SERVER_MESSAGES = 200;

    private static final Deque<PendingSelfMessage> pendingSelfMessages = new ArrayDeque<>();
    private static final Object pendingSelfLock = new Object();
    private static final Deque<RecentServerMessage> recentServerMessages = new ArrayDeque<>();
    private static final Object recentServerLock = new Object();
    private static final Deque<RecentOutboundMessage> recentOutboundMessages = new ArrayDeque<>();
    private static final Object recentOutboundLock = new Object();
    private static final int MAX_RECENT_OUTBOUND_MESSAGES = 200;

    private static Consumer<JsonObject> registeredListener;
    private static volatile boolean frumaModeEnabled = false;

    private OutboundDisplayHandler() {
    }

    /**
     * Registers the outbound listener with the V1ApiManager.
     * Should be called once during initialization, after V1ApiManager.connect().
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
     * Records a guild chat message received from the Minecraft server for
     * cross-source deduplication with outbound bridge messages.
     *
     * @param displayName the sender's display name
     * @param message     the message content
     */
    public static void recordServerGuildMessage(String displayName, String message) {
        if (displayName == null || displayName.isEmpty() || message == null || message.isEmpty()) {
            return;
        }
        synchronized (recentServerLock) {
            long now = System.currentTimeMillis();
            pruneExpiredServerMessages(now);
            if (recentServerMessages.size() >= MAX_RECENT_SERVER_MESSAGES) {
                recentServerMessages.pollFirst();
            }
            recentServerMessages.addLast(new RecentServerMessage(displayName, message, now));
        }
    }

    /**
     * Clears all dedup caches. Called on server disconnect.
     */
    public static void clearCaches() {
        synchronized (pendingSelfLock) {
            pendingSelfMessages.clear();
        }
        synchronized (recentServerLock) {
            recentServerMessages.clear();
        }
        synchronized (recentOutboundLock) {
            recentOutboundMessages.clear();
        }
    }

    /** Enable or disable Fruma mode bridge mirroring. */
    public static void setFrumaModeEnabled(boolean enabled) {
        frumaModeEnabled = enabled;
    }

    /** Returns whether Fruma mode bridge mirroring is enabled. */
    public static boolean isFrumaModeEnabled() {
        return frumaModeEnabled;
    }

    /**
     * Checks whether a message was recently displayed from the outbound WebSocket.
     * Used by Fruma mode to suppress the corresponding server guild message.
     *
     * @param displayName the sender's display name
     * @param message     the message content
     * @return true if a matching outbound message was recently shown
     */
    public static boolean wasOutboundMessageRecentlyDisplayed(String displayName, String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String normalized = normalizeForDedup(message);
        synchronized (recentOutboundLock) {
            long now = System.currentTimeMillis();
            pruneExpiredOutboundMessages(now);
            for (RecentOutboundMessage recent : recentOutboundMessages) {
                if (normalizeForDedup(recent.message).equals(normalized)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void onOutboundMessage(JsonObject json) {
        if (!VetsConfig.get(VetsConfig.PRINT_BRIDGE_MESSAGES)) {
            return;
        }

        if (!shouldDisplayMessages()) {
            return;
        }

        String type = getStringOrEmpty(json, "type");
        String rank = getStringOrEmpty(json, "rank");
        String username = getStringOrEmpty(json, "username");
        String message = getStringOrEmpty(json, "message");

        if (username.isEmpty() || message.isEmpty()) {
            return;
        }

        // Self-message suppression: don't show messages the player just sent
        if (shouldSuppressSelfMessage(username, message, type)) {
            return;
        }

        // For Returners members, suppress outbound guild messages that were
        // already displayed by the server's native guild chat
        if (GuildStateManager.isReturners() && "guild".equals(type)) {
            if (wasServerMessageRecentlySeen(message)) {
                return;
            }
        }

        // Record this message for Fruma mode cross-source dedup
        synchronized (recentOutboundLock) {
            long now = System.currentTimeMillis();
            pruneExpiredOutboundMessages(now);
            if (recentOutboundMessages.size() >= MAX_RECENT_OUTBOUND_MESSAGES) {
                recentOutboundMessages.pollFirst();
            }
            recentOutboundMessages.addLast(new RecentOutboundMessage(username, message, now));
        }

        ChatUtils.sendGuildChatMessage(rank, username, message);
    }

    private static boolean shouldDisplayMessages() {
        // Returners members see outbound messages (bridge/Fruma support)
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
        // Only suppress game-sourced messages (sent by this client through the server)
        if (!"guild".equals(type)) {
            return false;
        }

        synchronized (pendingSelfLock) {
            long now = System.currentTimeMillis();
            pruneExpiredSelfMessages(now);

            Iterator<PendingSelfMessage> it = pendingSelfMessages.iterator();
            while (it.hasNext()) {
                PendingSelfMessage pending = it.next();
                if (pending.username.equalsIgnoreCase(username) && pending.message.equals(message)) {
                    it.remove();
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean wasServerMessageRecentlySeen(String message) {
        String normalized = normalizeForDedup(message);
        synchronized (recentServerLock) {
            long now = System.currentTimeMillis();
            pruneExpiredServerMessages(now);
            for (RecentServerMessage recent : recentServerMessages) {
                if (normalizeForDedup(recent.message).equals(normalized)) {
                    return true;
                }
            }
        }
        return false;
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

    private static void pruneExpiredServerMessages(long nowMs) {
        while (!recentServerMessages.isEmpty()) {
            RecentServerMessage head = recentServerMessages.peekFirst();
            if (head == null || nowMs - head.createdAtMs <= SERVER_MESSAGE_DEDUP_WINDOW_MS) {
                return;
            }
            recentServerMessages.pollFirst();
        }
    }

    private static void pruneExpiredOutboundMessages(long nowMs) {
        while (!recentOutboundMessages.isEmpty()) {
            RecentOutboundMessage head = recentOutboundMessages.peekFirst();
            if (head == null || nowMs - head.createdAtMs <= SERVER_MESSAGE_DEDUP_WINDOW_MS) {
                return;
            }
            recentOutboundMessages.pollFirst();
        }
    }

    private static String normalizeForDedup(String message) {
        if (message == null) return "";
        StringBuilder sb = new StringBuilder(message.length());
        int i = 0;
        while (i < message.length()) {
            int cp = message.codePointAt(i);
            int charCount = Character.charCount(cp);
            if (cp == '\n') {
                sb.append(' ');
            } else {
                int type = Character.getType(cp);
                boolean isCustomGlyph = type == Character.PRIVATE_USE
                        || (type == Character.UNASSIGNED && cp > 0xFFFF);
                if (!isCustomGlyph) {
                    sb.appendCodePoint(cp);
                }
            }
            i += charCount;
        }
        return sb.toString().replaceAll("  +", " ").trim();
    }

    private static String getStringOrEmpty(JsonObject json, String key) {
        if (json.has(key) && !json.get(key).isJsonNull()) {
            return json.get(key).getAsString();
        }
        return "";
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

    private static final class RecentServerMessage {
        final String displayName;
        final String message;
        final long createdAtMs;

        RecentServerMessage(String displayName, String message, long createdAtMs) {
            this.displayName = displayName;
            this.message = message;
            this.createdAtMs = createdAtMs;
        }
    }

    private static final class RecentOutboundMessage {
        final String displayName;
        final String message;
        final long createdAtMs;

        RecentOutboundMessage(String displayName, String message, long createdAtMs) {
            this.displayName = displayName;
            this.message = message;
            this.createdAtMs = createdAtMs;
        }
    }
}
