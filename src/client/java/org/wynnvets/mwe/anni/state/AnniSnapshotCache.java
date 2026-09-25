package org.wynnvets.mwe.anni.state;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.wynnvets.logging.VetsLogger;

/**
 * Process-wide cache of the latest anni snapshot for the local player.
 *
 * <p>S2+ consumers subscribe via {@link #addListener(Consumer)} and re-render their
 * surfaces from {@link #latest()}. Six are registered today, all outside this
 * package: the aggressive-mode dispatcher, the boss bar's flash tracker, the mode
 * window watcher, the outline registry, the party reporter and the scroll-spot
 * marker provider.</p>
 *
 * <h2>Who writes here</h2>
 *
 * <p>Three classes call {@link #update(AnniSnapshot)} across five call sites.
 * <b>Neither of the two that handle frames writes on every frame it handles</b>,
 * and both guards are load-bearing, because {@code null} is a legal stored value
 * and "ignored" is not the same as "stored as null". The third writer handles no
 * frames at all:</p>
 *
 * <ul>
 *   <li>{@link org.wynnvets.mwe.anni.network.AnniWsHandler AnniWsHandler}'s
 *       {@code handleAnniState}, on the {@code anni_state} push. It returns early
 *       on a missing or JSON-null {@code snapshot} field, and again on a parse
 *       failure, so a push carrying no snapshot is <b>ignored</b> rather than
 *       stored.</li>
 *   <li>{@link org.wynnvets.mwe.anni.network.AnniQueryClient#onResponse
 *       AnniQueryClient#onResponse}, on the {@code anni_query_response} pull.
 *       {@link org.wynnvets.mwe.anni.network.AnniWsHandler AnniWsHandler} is the <em>route</em> to it, not the caller. It
 *       writes only when the parsed snapshot is non-null, so a successful response
 *       whose {@code snapshot} is {@code null} — which the wire contract allows —
 *       completes the caller's future with {@code null} and leaves this cache
 *       untouched.</li>
 *   <li>{@link org.wynnvets.mwe.anni.debug.AnniDebugCommands AnniDebugCommands},
 *       three sites and no frames: its shared {@code parseAndInject} helper
 *       (one call site reached from three commands — {@code snapshotInject},
 *       {@code snapshotInjectFile} and {@code snapshotInjectPreset}),
 *       {@code timeSet}, and {@code snapshotClear} — the last being the only
 *       {@code update(null)} in the tree, and therefore the only way the stored
 *       value becomes {@code null} after a session has gone warm.</li>
 * </ul>
 *
 * <p>Single-player by design: snapshots received here are always for the
 * local player (the server's per-uuid push routing guarantees this). Today no
 * {@code anni_state} push reaches vetsmod
 * ({@code outbound-socket-never-authenticated}), so snapshots arrive as pull
 * replies: the {@code anni_query} frame names no UUID, and temporary-server
 * (at ffd8c17) answers for the UUID its authenticated session carries. A
 * future fan-out that delivers snapshots for other players would live on a
 * separate cache, not here.</p>
 *
 * <p>Listener bus uses {@link CopyOnWriteArrayList} (same pattern as
 * {@link org.wynnvets.api.V1ApiManager#outboundListeners V1ApiManager#outboundListeners}) so
 * addListener never blocks reader threads; the iteration cost is irrelevant at a
 * single-digit listener count.</p>
 *
 * <p>Listeners run on whichever thread called {@link #update} — typically
 * the WebSocket reader thread. They MUST NOT block on the main game tick
 * (use {@code Minecraft.getInstance().execute(...)} to bounce work onto
 * the render thread if needed &mdash; the bounce {@link
 * org.wynnvets.chat.ChatUtils ChatUtils} performs beneath {@link
 * org.wynnvets.chat.OutboundDisplayHandler OutboundDisplayHandler}).</p>
 */
public final class AnniSnapshotCache {

    private static volatile AnniSnapshot latest;
    private static volatile long fetchedAtEpochMs = 0L;

    private static final CopyOnWriteArrayList<Consumer<AnniSnapshot>> listeners =
            new CopyOnWriteArrayList<>();

    private AnniSnapshotCache() {}

    /**
     * The most recently received snapshot, or {@code null}.
     *
     * <p>{@code null} is <b>not</b> a cold-start-only artefact. It is also what
     * {@link #update(AnniSnapshot)} stores when handed {@code null}, which
     * {@code AnniDebugCommands.snapshotClear} does — so this can return
     * {@code null} at any point in a warm session, and every caller must guard.
     * {@link AnniSnapshots} discharges that contract for the four fields the
     * diffing surfaces read; everything else null-checks its own way down.</p>
     */
    public static AnniSnapshot latest() {
        return latest;
    }

    /**
     * Update the cache and notify every listener.
     *
     * <p>{@code null} is accepted and stored as a "no snapshot available"
     * signal — listeners must tolerate the null (the branch that handles it is
     * in {@link org.wynnvets.fetcher.ondemand.StampFetcher StampFetcher}'s
     * {@code fetchStampAndCreateAnniCommandMessage}, which with {@code vetsAnniEnabled}
     * on pulls on demand before any legacy fall-back, rather than in the
     * {@code /wv anni} renderer it feeds).</p>
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
