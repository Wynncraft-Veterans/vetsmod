package org.wynnvets.mwe.anni.state;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.wynnvets.logging.VetsLogger;

/**
 * Process-wide cache of the latest anni snapshot for the local player.
 *
 * <p>S1 owns the read/write half: {@link org.wynnvets.mwe.anni.network.AnniWsHandler}
 * calls {@link #update(AnniSnapshot)} on every {@code anni_state} push frame
 * and on every successful {@code anni_query_response}. S2+ consumers
 * subscribe via {@link #addListener(Consumer)} and re-render their surfaces
 * from {@link #latest()}.</p>
 *
 * <p>Single-player by design: snapshots received here are always for the
 * local player (the server's per-uuid push routing guarantees this). A
 * future fan-out that delivers snapshots for other players would live on a
 * separate cache, not here.</p>
 *
 * <p>Listener bus uses {@link CopyOnWriteArrayList} (same pattern as
 * {@link org.wynnvets.api.V1ApiManager#outboundListeners V1ApiManager#outboundListeners}) so
 * addListener never blocks reader threads; the iteration cost is irrelevant at the single-digit
 * listener count S2..S7 will accumulate.</p>
 *
 * <p>Listeners run on whichever thread called {@link #update} — typically
 * the WebSocket reader thread. They MUST NOT block on the main game tick
 * (use {@code MinecraftClient.getInstance().execute(...)} to bounce work
 * onto the render thread if needed, same pattern as
 * {@link org.wynnvets.chat.OutboundDisplayHandler OutboundDisplayHandler}).</p>
 */
public final class AnniSnapshotCache {

    private static volatile AnniSnapshot latest;
    private static volatile long fetchedAtEpochMs = 0L;

    private static final CopyOnWriteArrayList<Consumer<AnniSnapshot>> listeners =
            new CopyOnWriteArrayList<>();

    private AnniSnapshotCache() {}

    /** The most recently received snapshot, or {@code null} if none yet. */
    public static AnniSnapshot latest() {
        return latest;
    }

    /**
     * Update the cache and notify every listener.
     *
     * <p>{@code null} is accepted and stored as a "no snapshot available"
     * signal — listeners must tolerate the null (the legacy fall-back
     * branch in S2's {@code /wv anni} renderer triggers on it).</p>
     */
    public static void update(AnniSnapshot snapshot) {
        long now = System.currentTimeMillis();
        long gapMs = fetchedAtEpochMs == 0L ? -1L : now - fetchedAtEpochMs;
        latest = snapshot;
        fetchedAtEpochMs = now;
        VetsLogger.debug("AnniSnapshotCache.update: gap_ms={}", gapMs);
        for (Consumer<AnniSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (Exception e) {
                VetsLogger.warn("AnniSnapshotCache listener error: {}", e.getMessage());
            }
        }
    }

    /** Subscribe to snapshot-change events. */
    public static void addListener(Consumer<AnniSnapshot> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }
}
