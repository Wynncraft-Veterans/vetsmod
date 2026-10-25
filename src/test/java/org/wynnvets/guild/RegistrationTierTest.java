package org.wynnvets.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for the pure parts of the presence registration: which tier a player registers under
 * ({@link GuildStateManager#registrationTier}) and when a cached registration is retracted
 * ({@link GuildStateManager#shouldRetract}).
 *
 * <p>{@code sendRegistrationIfReady} and {@code reconcileRegistration} read the player, Wynntils'
 * guild model and {@code UnlockManager}, and are not exercised.</p>
 */
class RegistrationTierTest {

    // registrationTier(returners, honouraryUnlocked, guildless, waitlistUnlocked)

    @Test
    void returnersRegistersAsGuildWhateverElseHolds() {
        assertEquals("guild", GuildStateManager.registrationTier(true, false, false, false));
        assertEquals("guild", GuildStateManager.registrationTier(true, true, true, true));
    }

    @Test
    void honouraryBeatsWaitlist() {
        assertEquals("honourary", GuildStateManager.registrationTier(false, true, false, false));
        assertEquals("honourary", GuildStateManager.registrationTier(false, true, true, true));
    }

    @Test
    void waitlistNeedsBothGuildlessAndTheUnlock() {
        assertEquals("waitlist", GuildStateManager.registrationTier(false, false, true, true));
        assertNull(GuildStateManager.registrationTier(false, false, false, true), "in a guild");
        assertNull(GuildStateManager.registrationTier(false, false, true, false), "not unlocked");
    }

    @Test
    void anIneligiblePlayerHasNoTier() {
        assertNull(GuildStateManager.registrationTier(false, false, false, false));
    }

    // shouldRetract(tierNow, cachedTier, definitive)

    @Test
    void aStillEligiblePlayerIsNeverRetracted() {
        assertFalse(GuildStateManager.shouldRetract("guild", "guild", true));
        assertFalse(GuildStateManager.shouldRetract("waitlist", "waitlist", false));
        assertFalse(
                GuildStateManager.shouldRetract("guild", "waitlist", true),
                "a tier change is an overwrite, not a retraction");
    }

    @Test
    void nothingCachedMeansNothingToRetract() {
        assertFalse(GuildStateManager.shouldRetract(null, null, true));
        assertFalse(GuildStateManager.shouldRetract(null, null, false));
    }

    @Test
    void aCachedGuildRegistrationIsRetractedOnlyWhenTheGuildStateIsDefinitive() {
        assertTrue(GuildStateManager.shouldRetract(null, "guild", true));
        assertFalse(
                GuildStateManager.shouldRetract(null, "guild", false),
                "unknown guild state is not ineligibility");
    }

    @Test
    void aCachedWaitlistOrHonouraryRegistrationIsRetractedEitherWay() {
        assertTrue(GuildStateManager.shouldRetract(null, "waitlist", true));
        assertTrue(GuildStateManager.shouldRetract(null, "waitlist", false));
        assertTrue(GuildStateManager.shouldRetract(null, "honourary", true));
        assertTrue(GuildStateManager.shouldRetract(null, "honourary", false));
    }
}
