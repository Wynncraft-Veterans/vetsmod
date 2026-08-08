package org.wynnvets.queue;

import java.util.concurrent.CopyOnWriteArrayList;
import org.wynnvets.logging.VetsLogger;

/**
 * Tracks whether the client is currently sitting in a Wynncraft world queue.
 *
 * <p>While queued, the Wynncraft game server silently drops outbound
 * {@code /g} commands and does not relay inbound guild chat to the client.
 * Features that need to work around this (e.g. the WebSocket-backed guild
 * chat relay in {@link org.wynnvets.chat.OutboundDisplayHandler} and
 * {@link org.wynnvets.commands.GuildChatDispatcher}) query this manager to
 * decide whether to take the queue-aware path.</p>
 *
 * <p>State is driven by {@link QueueDetector}, which subscribes to the
 * relevant Wynntils and Fabric events.  This class is a plain state store and
 * does not know about detection signals — detection logic lives in
 * {@link QueueDetector} so the two can evolve independently.</p>
 *
 * <p>The class is thread-safe: all public mutators synchronize on the
 * singleton instance, and the listener list is a {@link CopyOnWriteArrayList}
 * so callbacks can be dispatched without holding the lock.</p>
 */
public final class QueueStateManager {

    private static volatile boolean inQueue;
    private static volatile String queuedWorld = "";
    private static volatile long enteredAtMs;

    private static final CopyOnWriteArrayList<QueueStateListener> listeners =
            new CopyOnWriteArrayList<>();

    private QueueStateManager() {}

    // ── Query ──────────────────────────────────────────────────────────

    /**
     * @return {@code true} if the client is currently in a world queue.
     */
    public static boolean isInQueue() {
        return inQueue;
    }

    /**
     * @return epoch millis when the current queue state was entered, or
     *         {@code 0} if not queued.
     */
    public static long getEnteredAtMs() {
        return enteredAtMs;
    }

    // ── State transitions (called by QueueDetector) ────────────────────

    /**
     * Marks the client as having entered a world queue.  Idempotent: repeated
     * calls with the same world name are ignored, so detection can fire
     * freely from tick-rate signals like the queue title packet.
     *
     * @param worldName the target world (e.g. {@code "NA30"}); may be empty
     */
    public static synchronized void enter(String worldName) {
        String normalized = worldName == null ? "" : worldName;
        if (inQueue && normalized.equals(queuedWorld)) {
            return;
        }
        inQueue = true;
        queuedWorld = normalized;
        enteredAtMs = System.currentTimeMillis();
        VetsLogger.debug("QueueStateManager: entered queue (world={})", normalized);
        for (QueueStateListener listener : listeners) {
            try {
                listener.onQueueEntered(normalized);
            } catch (Throwable t) {
                VetsLogger.debug("QueueStateListener threw in onQueueEntered: {}", t);
            }
        }
    }

    /**
     * Marks the client as having exited the queue.  No-op if not currently
     * queued.
     *
     * @param reason short machine-readable tag for diagnostics
     */
    public static synchronized void exit(String reason) {
        if (!inQueue) {
            return;
        }
        inQueue = false;
        queuedWorld = "";
        enteredAtMs = 0L;
        VetsLogger.debug("QueueStateManager: exited queue (reason={})", reason);
        for (QueueStateListener listener : listeners) {
            try {
                listener.onQueueExited(reason);
            } catch (Throwable t) {
                VetsLogger.debug("QueueStateListener threw in onQueueExited: {}", t);
            }
        }
    }

    // ── Listener registration ──────────────────────────────────────────

    /**
     * Subscribes a listener to queue state transitions.  Listeners are
     * invoked on the thread that triggered the state change (typically the
     * render/netty thread).
     */
    public static void addListener(QueueStateListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }
}
