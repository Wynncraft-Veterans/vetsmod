package org.wynnvets.fetcher.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MojangCooldownTest {

    @AfterEach
    void resetState() {
        MojangCooldown.clear();
    }

    @Test
    void defaultState_notCoolingDown_remainingMsZero() {
        assertFalse(MojangCooldown.isCoolingDown());
        assertEquals(0L, MojangCooldown.remainingMs());
    }

    @Test
    void record429_nullDuration_appliesDefault60s() {
        MojangCooldown.record429(null);
        assertTrue(MojangCooldown.isCoolingDown());
        long remaining = MojangCooldown.remainingMs();
        // Default is 60s. Allow a wall-clock second between record and read.
        assertTrue(remaining > 55_000L, "expected > 55_000ms, got " + remaining);
        assertTrue(remaining <= 60_000L, "expected <= 60_000ms, got " + remaining);
    }

    @Test
    void record429_explicitDuration_applies() {
        MojangCooldown.record429(Duration.ofSeconds(120));
        long remaining = MojangCooldown.remainingMs();
        assertTrue(remaining > 60_000L, "expected > 60_000ms, got " + remaining);
        assertTrue(remaining <= 120_000L, "expected <= 120_000ms, got " + remaining);
    }

    @Test
    void record429_longestWins_neverShortens() {
        MojangCooldown.record429(Duration.ofSeconds(30));
        MojangCooldown.record429(Duration.ofSeconds(5));
        long remaining = MojangCooldown.remainingMs();
        // The 5s record must not have shrunk the existing 30s cutoff.
        assertTrue(
                remaining > 25_000L,
                "expected > 25_000ms (30s window minus a tiny epsilon), got " + remaining);
    }

    @Test
    void clear_resetsState() {
        MojangCooldown.record429(Duration.ofSeconds(60));
        assertTrue(MojangCooldown.isCoolingDown());
        MojangCooldown.clear();
        assertFalse(MojangCooldown.isCoolingDown());
        assertEquals(0L, MojangCooldown.remainingMs());
    }
}
