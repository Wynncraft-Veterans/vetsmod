package org.wynnvets.fetcher.lookup;

/**
 * Per-provider result inside the {@link PlayerLookup} cascade.
 *
 * <p>Distinguishes <b>hit</b> (stop cascade), <b>definite miss</b> (upstream
 * said "no such player" — a 404 or {@code success: false}), and <b>transient
 * miss</b> (429/5xx/timeout/IO — we don't know). The orchestrator needs the
 * distinction to pick between {@link FailureReason#PLAYER_NOT_FOUND} (all
 * definite) and {@link FailureReason#ALL_PROVIDERS_TRANSIENT} (at least one
 * transient) when every provider misses.</p>
 */
public record ProviderOutcome(LookupResult hit, boolean isDefiniteMiss) {

    private static final ProviderOutcome NOT_FOUND = new ProviderOutcome(null, true);
    private static final ProviderOutcome TRANSIENT = new ProviderOutcome(null, false);

    public boolean isHit() {
        return hit != null;
    }

    public static ProviderOutcome hit(LookupResult result) {
        return new ProviderOutcome(result, false);
    }

    /** Upstream definitively said "no such player" (404, {@code success: false}, etc.). */
    public static ProviderOutcome notFound() {
        return NOT_FOUND;
    }

    /** Upstream returned 429, 5xx, timeout, or threw — outcome unknown. */
    public static ProviderOutcome transientMiss() {
        return TRANSIENT;
    }
}
