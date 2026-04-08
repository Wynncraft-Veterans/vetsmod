package org.wynnvets.guild;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks recently-seen online guild members and keeps them visible for a
 * grace period after they disappear from all data sources.
 *
 * <p>Players frequently vanish from the tab list, the Wynncraft API, and
 * the vetsmod server while switching worlds or sitting in a queue.  This
 * cache prevents them from flickering out of {@code /wv list} during
 * those brief gaps.</p>
 *
 * <p>Thread-safe — may be called from any thread.</p>
 */
public final class OnlineGuildCache {

    /** How long (ms) to keep a player visible after they were last seen. */
    private static final long GRACE_PERIOD_MS = 30_000L;

    /**
     * Username (case-preserved) → epoch millis when last confirmed online.
     * Uses case-insensitive keys via {@link ConcurrentHashMap} with
     * lowercased keys, but we store the original-case name separately.
     */
    private static final ConcurrentHashMap<String, Entry> SEEN = new ConcurrentHashMap<>();

    private record Entry(String displayName, long lastSeenMs) {}

    private OnlineGuildCache() {}

    /**
     * Record that a set of usernames were just confirmed online.
     * Call this every time fresh data arrives from any source.
     */
    public static void markSeen(Set<String> usernames) {
        long now = System.currentTimeMillis();
        for (String name : usernames) {
            SEEN.put(name.toLowerCase(), new Entry(name, now));
        }
    }

    /**
     * Returns usernames that were seen within the grace period but are
     * <b>not</b> in the given {@code currentlyOnline} set.
     *
     * @param currentlyOnline the usernames confirmed online right now
     *                        (case-insensitive comparison)
     * @return additional usernames to include, with their original casing
     */
    public static Set<String> getGracePeriodNames(Set<String> currentlyOnline) {
        long now = System.currentTimeMillis();
        Set<String> currentLower = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        currentLower.addAll(currentlyOnline);

        Set<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        SEEN.entrySet().removeIf(e -> now - e.getValue().lastSeenMs > GRACE_PERIOD_MS);

        for (Entry entry : SEEN.values()) {
            if (!currentLower.contains(entry.displayName)) {
                result.add(entry.displayName);
            }
        }

        return result;
    }

    /**
     * Clears all cached entries (e.g. on world disconnect).
     */
    public static void clear() {
        SEEN.clear();
    }
}
