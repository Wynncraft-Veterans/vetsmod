package org.wynnvets.fetcher.lookup;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Static cooldown gate for the legacy {@code api.mojang.com} provider.
 *
 * <p>Mojang's username → UUID endpoint shares an aggressive rate-limit
 * bucket across the entire client IP. When it 429s, we record a cooldown
 * and {@link org.wynnvets.fetcher.lookup.providers.MojangLegacyProvider
 * MojangLegacyProvider} self-skips for its duration so the
 * cascade can move on instead of repeatedly burning Mojang quota. The
 * cooldown is scoped to the legacy host only — Mojang Services is a
 * separate bucket.</p>
 */
public final class MojangCooldown {

    private static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(60);

    private static final AtomicLong cooldownUntilEpochMs = new AtomicLong(0L);

    /** Debug switch: when true, {@link #isCoolingDown()} returns true
     *  unconditionally. Used by the manual verification matrix to simulate
     *  a cooled-down state without an actual 429. */
    private static volatile boolean forceCooldown = false;

    private MojangCooldown() {}

    public static boolean isCoolingDown() {
        if (forceCooldown) return true;
        return System.currentTimeMillis() < cooldownUntilEpochMs.get();
    }

    public static long remainingMs() {
        if (forceCooldown) return Long.MAX_VALUE;
        long delta = cooldownUntilEpochMs.get() - System.currentTimeMillis();
        return Math.max(0L, delta);
    }

    /**
     * Records a 429. The longest-active cutoff wins (never shortens). Pass
     * null to use {@link #DEFAULT_COOLDOWN}.
     */
    public static void record429(Duration retryAfter) {
        Duration d = retryAfter == null ? DEFAULT_COOLDOWN : retryAfter;
        long candidate = System.currentTimeMillis() + d.toMillis();
        cooldownUntilEpochMs.accumulateAndGet(candidate, Math::max);
    }

    /** For diagnostics / debug commands. */
    public static void clear() {
        cooldownUntilEpochMs.set(0L);
    }

    public static void setForceCooldown(boolean force) {
        forceCooldown = force;
    }

    public static boolean isForceCooldown() {
        return forceCooldown;
    }
}
