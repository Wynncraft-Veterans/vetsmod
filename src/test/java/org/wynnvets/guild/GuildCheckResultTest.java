package org.wynnvets.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.wynnvets.guild.GuildChecker.GuildCheckResult;

/**
 * Tests for {@link GuildChecker.GuildCheckResult} — the persisted-value
 * mapping, not the surrounding checker.
 *
 * <p>The numbers are a storage contract, not an implementation detail:
 * {@code VetsConfig.VETS_GUILD_CHECK_RESULT} ({@code "vetsGuildCheckResult"})
 * writes {@code persistedValue} into the player's config as a raw long and
 * reads it back through {@code fromPersistedValue}. Changing a number
 * silently re-interprets every existing user's stored result. The assertions
 * below therefore compare against the four literals and never against
 * {@code ordinal()} — the two coincide today, which is exactly what would
 * make a later "just use ordinal()" simplification look safe and be a time
 * bomb.</p>
 *
 * <p>NOTE: only the nested enum is exercised. {@link GuildChecker}'s own
 * methods reach {@code VetsConfig}, and {@code completeCheck} spawns a thread
 * and calls {@code GuildStateManager}, which loads Wynntils. The enum has no
 * such dependency: initializing a nested type does not initialize its
 * enclosing class, and the enum's own static state is four longs. If a future
 * contributor gives {@code GuildCheckResult} a static initializer that loads
 * MC/Wynntils, this test starts failing at class-init time — the fix is to
 * move the mapping into its own pure type.</p>
 */
class GuildCheckResultTest {

    // ── The persisted numbers ─────────────────────────────────────────

    @Test
    void persistedValues_areTheFourStorageLiterals() {
        assertEquals(0L, GuildCheckResult.UNKNOWN.persistedValue, "UNKNOWN is stored as 0");
        assertEquals(1L, GuildCheckResult.RETURNERS.persistedValue, "RETURNERS is stored as 1");
        assertEquals(2L, GuildCheckResult.OTHER_GUILD.persistedValue, "OTHER_GUILD is stored as 2");
        assertEquals(3L, GuildCheckResult.GUILDLESS.persistedValue, "GUILDLESS is stored as 3");
    }

    // ── fromPersistedValue ────────────────────────────────────────────

    @Test
    void fromPersistedValue_roundTripsEveryConstant() {
        assertSame(GuildCheckResult.UNKNOWN, GuildCheckResult.fromPersistedValue(0L));
        assertSame(GuildCheckResult.RETURNERS, GuildCheckResult.fromPersistedValue(1L));
        assertSame(GuildCheckResult.OTHER_GUILD, GuildCheckResult.fromPersistedValue(2L));
        assertSame(GuildCheckResult.GUILDLESS, GuildCheckResult.fromPersistedValue(3L));
    }

    @Test
    void fromPersistedValue_unrecognisedFallsBackToUnknown() {
        // The loop returns UNKNOWN rather than throwing, so a config written by
        // a newer build degrades to "not checked yet" instead of crashing.
        assertSame(GuildCheckResult.UNKNOWN, GuildCheckResult.fromPersistedValue(4L));
        assertSame(GuildCheckResult.UNKNOWN, GuildCheckResult.fromPersistedValue(-1L));
        assertSame(GuildCheckResult.UNKNOWN, GuildCheckResult.fromPersistedValue(Long.MAX_VALUE));
        assertSame(GuildCheckResult.UNKNOWN, GuildCheckResult.fromPersistedValue(Long.MIN_VALUE));
    }

    // The parameter is a primitive long, so there is no null or empty case to
    // cover — and no toPersistedValue() inverse exists; readers use the bare
    // field asserted above.
}
