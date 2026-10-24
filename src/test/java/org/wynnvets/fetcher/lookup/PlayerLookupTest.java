package org.wynnvets.fetcher.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PlayerLookup}'s cascade ending, run through {@code runCascade(List, String)}
 * with fake providers: which {@link FailureReason} an exhausted cascade reports for each mix of
 * definite misses, transient misses and abstentions, and that a hit stops it. The real
 * providers' HTTP calls and {@code resolve}'s single-flight map are not covered.
 */
class PlayerLookupTest {

    private static final UUID ID = UUID.fromString("bb171c68-7080-4d02-be7b-cc2d5dcbcd82");

    private static final ProviderOutcome NOT_FOUND = ProviderOutcome.notFound();
    private static final ProviderOutcome TRANSIENT = ProviderOutcome.transientMiss();
    private static final ProviderOutcome ABSTAIN = ProviderOutcome.abstain();

    private static LookupProvider fake(ProviderOutcome outcome, List<String> asked, String id) {
        return name -> {
            asked.add(id);
            return CompletableFuture.completedFuture(outcome);
        };
    }

    private static LookupProvider fake(ProviderOutcome outcome) {
        return fake(outcome, new ArrayList<>(), "fake");
    }

    private static LookupResult run(LookupProvider... providers) {
        return PlayerLookup.runCascade(List.of(providers), "Name").join();
    }

    @Test
    void allDefiniteMisses_endsPlayerNotFound() {
        LookupResult r = run(fake(NOT_FOUND), fake(NOT_FOUND), fake(NOT_FOUND));
        assertFalse(r.isSuccess());
        assertEquals(FailureReason.PLAYER_NOT_FOUND, r.failureReason());
    }

    @Test
    void vetsAbstains_othersDefinite_endsPlayerNotFound() {
        // The cascade's real shape: Wynncraft misses, Vets abstains, the rest miss definitely.
        LookupResult r = run(fake(NOT_FOUND), fake(ABSTAIN), fake(NOT_FOUND), fake(NOT_FOUND));
        assertEquals(FailureReason.PLAYER_NOT_FOUND, r.failureReason());
    }

    @Test
    void vetsTransient_othersDefinite_endsTransient() {
        LookupResult r = run(fake(NOT_FOUND), fake(TRANSIENT), fake(NOT_FOUND));
        assertEquals(FailureReason.ALL_PROVIDERS_TRANSIENT, r.failureReason());
    }

    @Test
    void anyTransientAmongOthers_endsTransient() {
        // The legacy Mojang provider's cooldown miss is transient and forces fail-open.
        LookupResult r = run(fake(NOT_FOUND), fake(ABSTAIN), fake(NOT_FOUND), fake(TRANSIENT));
        assertEquals(FailureReason.ALL_PROVIDERS_TRANSIENT, r.failureReason());
    }

    @Test
    void allAbstain_endsTransient() {
        // No provider gave any evidence, so the name is not known to be missing.
        LookupResult r = run(fake(ABSTAIN), fake(ABSTAIN));
        assertEquals(FailureReason.ALL_PROVIDERS_TRANSIENT, r.failureReason());
    }

    @Test
    void emptyCascade_endsTransient() {
        assertEquals(FailureReason.ALL_PROVIDERS_TRANSIENT, run().failureReason());
    }

    @Test
    void hitStopsCascade_andSkipsLaterProviders() {
        List<String> asked = new ArrayList<>();
        LookupResult hit = LookupResult.ofGeneric("fake", "Name", ID);
        LookupResult r =
                run(
                        fake(NOT_FOUND, asked, "first"),
                        fake(ProviderOutcome.hit(hit), asked, "second"),
                        fake(NOT_FOUND, asked, "third"));
        assertTrue(r.isSuccess());
        assertEquals(ID, r.uuid());
        assertNull(r.failureReason());
        assertEquals(List.of("first", "second"), asked);
    }

    @Test
    void providerThrowing_countsTransient() {
        LookupProvider throwing =
                name -> CompletableFuture.failedFuture(new IllegalStateException("boom"));
        LookupResult r = run(fake(NOT_FOUND), throwing, fake(NOT_FOUND));
        assertEquals(FailureReason.ALL_PROVIDERS_TRANSIENT, r.failureReason());
    }

    @Test
    void outcomeFlags_areMutuallyExclusive() {
        assertTrue(NOT_FOUND.isDefiniteMiss());
        assertFalse(NOT_FOUND.isAbstain());
        assertFalse(TRANSIENT.isDefiniteMiss());
        assertFalse(TRANSIENT.isAbstain());
        assertFalse(ABSTAIN.isDefiniteMiss());
        assertTrue(ABSTAIN.isAbstain());
        assertFalse(ABSTAIN.isHit());
    }
}
