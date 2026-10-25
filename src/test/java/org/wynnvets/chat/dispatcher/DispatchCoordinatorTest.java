package org.wynnvets.chat.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tests for the cause enums behind {@link DispatchCoordinator}'s two yellow notices, the text each
 * cause prints: why a {@code /v} reached nobody ({@code NoRecipientsCause}) and why a gated staff
 * command was dropped ({@code StaffWaitCause}). Which cause each code path picks, and the staff
 * feed readers' throw on a non-200 or malformed body, are not covered: they run on the shared
 * HTTP client and the render thread.
 */
class DispatchCoordinatorTest {

    @Test
    void noRecipientsCause_messages_areTheWordedLines() {
        assertEquals(
                "Nobody saw your message (nobody here)",
                DispatchCoordinator.NoRecipientsCause.NOBODY_HERE.message());
        assertEquals(
                "Nobody saw your message (API unreachable)",
                DispatchCoordinator.NoRecipientsCause.API_UNREACHABLE.message());
    }

    @Test
    void staffWaitCause_messages_areTheWordedLines() {
        assertEquals(
                "Message not deliverable (permission verification in progress)",
                DispatchCoordinator.StaffWaitCause.IN_PROGRESS.message());
        assertEquals(
                "Message not deliverable (permission verification unsuccessful)",
                DispatchCoordinator.StaffWaitCause.UNSUCCESSFUL.message());
    }

    @Test
    void messages_areDistinct_nonBlank_andEndWithoutAPeriod() {
        String[] all =
                Stream.concat(
                                Arrays.stream(DispatchCoordinator.NoRecipientsCause.values())
                                        .map(DispatchCoordinator.NoRecipientsCause::message),
                                Arrays.stream(DispatchCoordinator.StaffWaitCause.values())
                                        .map(DispatchCoordinator.StaffWaitCause::message))
                        .toArray(String[]::new);
        assertEquals(4, all.length);
        assertEquals(4, new HashSet<>(Arrays.asList(all)).size());
        for (String message : all) {
            assertFalse(message.isBlank());
            assertFalse(message.endsWith("."), message);
        }
    }

    @Test
    void onlyTheTransportCauseBlamesTheApi() {
        for (DispatchCoordinator.NoRecipientsCause cause :
                DispatchCoordinator.NoRecipientsCause.values()) {
            assertEquals(
                    cause == DispatchCoordinator.NoRecipientsCause.API_UNREACHABLE,
                    cause.message().contains("API"),
                    cause.name());
        }
        for (DispatchCoordinator.StaffWaitCause cause :
                DispatchCoordinator.StaffWaitCause.values()) {
            assertFalse(cause.message().contains("API"), cause.name());
            assertTrue(cause.message().startsWith("Message not deliverable ("));
        }
    }
}
