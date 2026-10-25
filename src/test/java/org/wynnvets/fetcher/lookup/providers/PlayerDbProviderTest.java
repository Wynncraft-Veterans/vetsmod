package org.wynnvets.fetcher.lookup.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.wynnvets.fetcher.lookup.ProviderOutcome;

/**
 * Tests for {@link PlayerDbProvider#translate(int, String)}, which turns a PlayerDB reply into a
 * cascade outcome: a 404, or a 400 whose {@code code} is {@code minecraft.invalid_username} (what
 * PlayerDB answers for an unknown name), is a definite miss; any other 400 or status is a
 * transient miss; a 200 with a player is a hit. {@code lookup}'s HTTP call is not covered.
 */
class PlayerDbProviderTest {

    private static final String UUID_TEXT = "bb171c68-7080-4d02-be7b-cc2d5dcbcd82";

    /** PlayerDB's reply to an unknown name, as captured on 2026-10-25. */
    private static final String UNKNOWN_NAME_BODY =
            "{\"message\":\"No Minecraft user could be found.\","
                    + "\"code\":\"minecraft.invalid_username\","
                    + "\"data\":{},\"success\":false,\"error\":false}";

    @Test
    void unknownName400_isDefiniteMiss() {
        assertSame(ProviderOutcome.notFound(), PlayerDbProvider.translate(400, UNKNOWN_NAME_BODY));
    }

    @Test
    void other400_isTransient() {
        assertSame(
                ProviderOutcome.transientMiss(),
                PlayerDbProvider.translate(
                        400, "{\"code\":\"minecraft.api_failure\",\"success\":false}"));
        assertSame(ProviderOutcome.transientMiss(), PlayerDbProvider.translate(400, "not json"));
        assertSame(ProviderOutcome.transientMiss(), PlayerDbProvider.translate(400, ""));
    }

    @Test
    void notFound404_isDefiniteMiss() {
        assertSame(ProviderOutcome.notFound(), PlayerDbProvider.translate(404, ""));
    }

    @Test
    void serverErrorAndRateLimit_areTransient() {
        assertSame(
                ProviderOutcome.transientMiss(),
                PlayerDbProvider.translate(500, UNKNOWN_NAME_BODY));
        assertSame(ProviderOutcome.transientMiss(), PlayerDbProvider.translate(429, ""));
    }

    @Test
    void ok200WithPlayer_isHit() {
        ProviderOutcome outcome =
                PlayerDbProvider.translate(
                        200,
                        "{\"success\":true,\"data\":{\"player\":{\"id\":\""
                                + UUID_TEXT
                                + "\",\"username\":\"Wenweia\"}}}");
        assertTrue(outcome.isHit());
        assertEquals(UUID.fromString(UUID_TEXT), outcome.hit().uuid());
        assertEquals("Wenweia", outcome.hit().canonicalName());
    }

    @Test
    void ok200WithSuccessFalse_isDefiniteMiss() {
        ProviderOutcome outcome = PlayerDbProvider.translate(200, "{\"success\":false}");
        assertTrue(outcome.isDefiniteMiss());
        assertFalse(outcome.isHit());
    }
}
