package org.wynnvets.fetcher.lookup.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.wynnvets.datamodels.MembershipSnapshot;
import org.wynnvets.fetcher.lookup.ProviderOutcome;
import org.wynnvets.util.Json;

/**
 * Tests for {@link VetsSnapshotProvider#translate}, which turns a {@code check_membership}
 * snapshot (or none) into a cascade outcome: a missing snapshot abstains, an unusable
 * {@code target_uuid} is a transient miss, a good one is a hit that carries the snapshot.
 * {@code lookup}, which reads the connection state from {@code V1ApiManager}, and
 * {@code requestSnapshot}, which sends the frame, are not covered.
 */
class VetsSnapshotProviderTest {

    private static final String UUID_TEXT = "bb171c68-7080-4d02-be7b-cc2d5dcbcd82";

    private static MembershipSnapshot snapshot(String json) {
        return Json.GSON.fromJson(json, MembershipSnapshot.class);
    }

    @Test
    void translate_null_abstains() {
        ProviderOutcome outcome = VetsSnapshotProvider.translate(null);
        assertSame(ProviderOutcome.abstain(), outcome);
        assertTrue(outcome.isAbstain());
        assertFalse(outcome.isDefiniteMiss());
    }

    @Test
    void translate_blankUuid_isTransient() {
        for (String json : new String[] {"{}", "{\"target_uuid\": \"  \"}"}) {
            ProviderOutcome outcome = VetsSnapshotProvider.translate(snapshot(json));
            assertSame(ProviderOutcome.transientMiss(), outcome, json);
        }
    }

    @Test
    void translate_nonUuid_isTransient() {
        ProviderOutcome outcome =
                VetsSnapshotProvider.translate(snapshot("{\"target_uuid\": \"not-a-uuid\"}"));
        assertSame(ProviderOutcome.transientMiss(), outcome);
    }

    @Test
    void translate_validSnapshot_hitCarriesSnapshotAndCanonicalName() {
        MembershipSnapshot snap =
                snapshot(
                        "{\"target_uuid\": \"%s\", \"target_username\": \"Metrafish\"}"
                                .formatted(UUID_TEXT));
        ProviderOutcome outcome = VetsSnapshotProvider.translate(snap);
        assertTrue(outcome.isHit());
        assertEquals(UUID.fromString(UUID_TEXT), outcome.hit().uuid());
        assertEquals("Metrafish", outcome.hit().canonicalName());
        assertEquals("vets", outcome.hit().source());
        assertSame(snap, outcome.hit().vetsSnapshot());
    }

    @Test
    void translate_missingUsername_fallsBackToUuidString() {
        ProviderOutcome outcome =
                VetsSnapshotProvider.translate(
                        snapshot("{\"target_uuid\": \"%s\"}".formatted(UUID_TEXT)));
        assertTrue(outcome.isHit());
        assertEquals(UUID_TEXT, outcome.hit().canonicalName());
    }
}
