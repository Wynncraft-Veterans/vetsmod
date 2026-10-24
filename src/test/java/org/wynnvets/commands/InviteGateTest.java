package org.wynnvets.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.wynnvets.datamodels.MembershipSnapshot;
import org.wynnvets.util.Json;

/**
 * Pins the parts of the {@code /gu invite} gate that decide from temporary-server's
 * {@code check_membership} reply: a non-staff member's invite verdict (no Discord blob) is
 * usable and a {@code may_invite: false} verdict blocks, while a staff snapshot still needs its
 * Discord blob and blocks on {@code blocklisted}; and the two lines a non-staff player sees.
 */
class InviteGateTest {

    private static MembershipSnapshot snapshot(String json) {
        return Json.GSON.fromJson(json, MembershipSnapshot.class);
    }

    private static final String VERDICT_YES =
            "{\"status\":\"ok\",\"target_uuid\":\"u\",\"target_username\":\"Steve\","
                    + "\"may_invite\":true,\"waitlist_count\":3}";
    private static final String VERDICT_NO =
            "{\"status\":\"ok\",\"target_uuid\":\"u\",\"target_username\":\"Steve\","
                    + "\"may_invite\":false,\"waitlist_count\":3}";

    @Test
    void aMembersVerdictIsUsableWithoutTheDiscordBlob() {
        assertTrue(InviteGate.snapshotUsable(snapshot(VERDICT_YES), false));
    }

    @Test
    void aStaffSnapshotStillNeedsItsDiscordBlob() {
        assertFalse(InviteGate.snapshotUsable(snapshot(VERDICT_YES), true));
        assertTrue(InviteGate.snapshotUsable(snapshot("{\"discord\":{\"linked\":true}}"), true));
    }

    @Test
    void noSnapshotIsNeverUsable() {
        assertFalse(InviteGate.snapshotUsable(null, false));
        assertFalse(InviteGate.snapshotUsable(null, true));
    }

    @Test
    void aFalseVerdictBlocksAndATrueOneDoesNot() {
        assertTrue(snapshot(VERDICT_NO).blocksInvite());
        assertFalse(snapshot(VERDICT_YES).blocksInvite());
        assertEquals(3, snapshot(VERDICT_YES).getWaitlistCount());
    }

    @Test
    void aStaffSnapshotBlocksOnTheBlocklist() {
        assertTrue(snapshot("{\"blocklisted\":true}").blocksInvite());
        assertFalse(snapshot("{\"blocklisted\":false}").blocksInvite());
        assertFalse(snapshot("{}").blocksInvite(), "no verdict and no blocklist flag");
    }

    @Test
    void onlyAStoredUnrejectedKeyIsStillBeingChecked() {
        assertTrue(InviteGate.keyStillChecking(true, false));
        assertFalse(InviteGate.keyStillChecking(true, true), "a rejected key needs a new one");
        assertFalse(InviteGate.keyStillChecking(false, false), "no key at all");
    }

    @Test
    void theNonStaffLinesAreTheUsersWording() {
        assertEquals(
                "This user is not eligible to join the guild, ask a Steward.",
                InviteGate.GENERIC_REJECTION);
        assertEquals(
                "Inviting others from unauthenticated vetsmod is not supported at this time"
                        + " (/vetsmod on Discord, then /unlock)",
                InviteGate.NEEDS_KEY);
        assertEquals(
                "We are still checking your permissions, please retry in a moment.",
                InviteGate.STILL_CHECKING);
    }
}
