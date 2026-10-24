package org.wynnvets.fetcher.lookup;

/**
 * Per-provider result inside the {@link PlayerLookup} cascade.
 *
 * <p>Distinguishes <b>hit</b> (stop cascade), <b>definite miss</b> (upstream
 * said "no such player" — a 404 or {@code success: false}), <b>transient
 * miss</b> (429/5xx/timeout/IO — we don't know) and <b>abstain</b> (this
 * provider could not be asked, or cannot speak to whether the name exists:
 * socket down, not confirmed staff. It says nothing about the name and, unlike
 * a transient miss, does not stop the cascade ending in
 * {@link FailureReason#PLAYER_NOT_FOUND}). The orchestrator needs the
 * distinction to pick between {@link FailureReason#PLAYER_NOT_FOUND} (the
 * answering providers all definite) and
 * {@link FailureReason#ALL_PROVIDERS_TRANSIENT} (at least one transient, or no
 * provider gave any evidence) when every provider misses.</p>
 */
public record ProviderOutcome(LookupResult hit, boolean isDefiniteMiss, boolean isAbstain) {

    private static final ProviderOutcome NOT_FOUND = new ProviderOutcome(null, true, false);
    private static final ProviderOutcome TRANSIENT = new ProviderOutcome(null, false, false);
    private static final ProviderOutcome ABSTAIN = new ProviderOutcome(null, false, true);

    public boolean isHit() {
        return hit != null;
    }

    public static ProviderOutcome hit(LookupResult result) {
        return new ProviderOutcome(result, false, false);
    }

    /** Upstream definitively said "no such player" (404, {@code success: false}, etc.). */
    public static ProviderOutcome notFound() {
        return NOT_FOUND;
    }

    /** Upstream returned 429, 5xx, timeout, or threw — outcome unknown. */
    public static ProviderOutcome transientMiss() {
        return TRANSIENT;
    }

    /**
     * This provider could not be asked, or cannot say whether the name exists (socket down, not
     * confirmed staff). It counts as neither a definite nor a transient miss, so the other
     * providers decide the cascade's ending; if every provider abstains the cascade ends
     * {@link FailureReason#ALL_PROVIDERS_TRANSIENT}, having learned nothing.
     */
    public static ProviderOutcome abstain() {
        return ABSTAIN;
    }
}
