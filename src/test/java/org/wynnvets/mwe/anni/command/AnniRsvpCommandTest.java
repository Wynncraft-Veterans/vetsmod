package org.wynnvets.mwe.anni.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;
import org.wynnvets.mwe.anni.network.AnniRsvpClient;

/**
 * Tests for {@link AnniRsvpCommand#successComponent}, the line {@code /wv anni rsvp} prints on an
 * ok ack: a revoke that the server says found nothing to withdraw, a revoke that withdrew one
 * (or whose server predates the {@code result} field), and the hard and soft lines. The
 * dispatch, the authentication guard and the follow-up snapshot pull are not covered: they need
 * the WebSocket, {@code GuildStateManager} and the render thread.
 */
class AnniRsvpCommandTest {

    private static final AnniRsvpClient.Ack NOTHING =
            new AnniRsvpClient.Ack(true, null, "nothing_to_withdraw");

    private static void assertGray(String expectedText, Component actual) {
        assertEquals(expectedText, actual.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), actual.getStyle().getColor());
    }

    @Test
    void successComponent_revoke_nothingToWithdraw_saysNoActiveRsvp() {
        assertGray(
                "You had no active RSVP to withdraw.",
                AnniRsvpCommand.successComponent("revoke", NOTHING));
    }

    @Test
    void successComponent_revoke_withdrawn_saysWithdrawn() {
        assertGray(
                "Your RSVP has been withdrawn.",
                AnniRsvpCommand.successComponent(
                        "revoke", new AnniRsvpClient.Ack(true, null, "withdrawn")));
    }

    @Test
    void successComponent_revoke_nullResult_saysWithdrawn() {
        // An older server sends no result; it keeps today's line.
        assertGray(
                "Your RSVP has been withdrawn.",
                AnniRsvpCommand.successComponent("revoke", new AnniRsvpClient.Ack(true, null)));
    }

    @Test
    void successComponent_hard_unchanged() {
        assertEquals(
                "You have HARD RSVP'd for the next anni.",
                AnniRsvpCommand.successComponent(
                                "hard", new AnniRsvpClient.Ack(true, null, "recorded"))
                        .getString());
    }

    @Test
    void successComponent_soft_unchanged() {
        assertEquals(
                "You have SOFT RSVP'd for the next anni.",
                AnniRsvpCommand.successComponent("soft", new AnniRsvpClient.Ack(true, null))
                        .getString());
    }

    @Test
    void successComponent_nothingToWithdrawOnAHardRsvp_isIgnored() {
        // The result only changes a revoke's line.
        assertEquals(
                "You have HARD RSVP'd for the next anni.",
                AnniRsvpCommand.successComponent("hard", NOTHING).getString());
    }
}
