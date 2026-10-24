package org.wynnvets.chat.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.wynnvets.util.Json;

/**
 * Tests for {@link GuildChatDispatcher#argsAfter}, the word match the staff intercepts use so a
 * bare command, which typed chat delivers trimmed, still reaches its handler's usage line, and
 * for {@link GuildChatDispatcher#relayRefusalLine}, the line a relay the server refused gets.
 *
 * <p>Only the pure helper runs here: {@code intercept} itself reaches Wynntils' connection
 * state and the guild state. Loading the class is safe: its static state is plain fields.</p>
 */
class GuildChatDispatcherTest {

    @Test
    void theBareWordHasNoArguments() {
        assertEquals("", GuildChatDispatcher.argsAfter("v", "v"));
        assertEquals("", GuildChatDispatcher.argsAfter("wv check", "wv check"));
    }

    @Test
    void theRestFollowsOneSpace() {
        assertEquals("hello there", GuildChatDispatcher.argsAfter("v hello there", "v"));
        assertEquals("Metrafish", GuildChatDispatcher.argsAfter("wv check Metrafish", "wv check"));
    }

    @Test
    void matchingIgnoresCase() {
        assertEquals("Holidaze late", GuildChatDispatcher.argsAfter("WARN Holidaze late", "warn"));
        assertEquals("", GuildChatDispatcher.argsAfter("Caution", "caution"));
    }

    @Test
    void aLongerWordIsAnotherCommand() {
        assertNull(GuildChatDispatcher.argsAfter("caution-go Nazzae", "caution"));
        assertNull(GuildChatDispatcher.argsAfter("vote", "v"));
        assertNull(GuildChatDispatcher.argsAfter("g hello", "v"));
    }

    // ----- a relay's ack -----

    private static JsonObject ack(String json) {
        return Json.GSON.fromJson(json, JsonObject.class);
    }

    private static String refusalFor(String detail) {
        JsonObject error = new JsonObject();
        error.addProperty("status", "error");
        error.addProperty("detail", detail);
        return GuildChatDispatcher.relayRefusalLine(error);
    }

    @Test
    void anOkOrOutcomeUnknownAckPrintsNothing() {
        assertNull(GuildChatDispatcher.relayRefusalLine(ack("{\"status\":\"ok\",\"req\":\"R\"}")));
        assertNull(
                GuildChatDispatcher.relayRefusalLine(
                        ack(
                                "{\"status\":\"error\",\"detail\":\"No reply from the vets"
                                        + " server.\",\"outcome_unknown\":\"no reply\"}")));
    }

    @Test
    void theKnownRefusalsGetShortReasons() {
        assertEquals("Not delivered (relay paused)", refusalFor("Inbound processing is disabled"));
        assertEquals(
                "Not delivered (not authenticated)",
                refusalFor("Authentication required: send an `auth` frame first."));
        assertEquals(
                "Not delivered (not allowed for your tier)",
                refusalFor("Your tier (other) is not permitted to send 'honourary' messages."));
        assertEquals("Not delivered (server busy)", refusalFor("Server busy"));
        assertEquals(
                "Not delivered (connection closed)", refusalFor("Connection closed before reply."));
        assertEquals(
                "Not delivered (connection closed)",
                refusalFor("Inbound WebSocket not connected."));
    }

    @Test
    void anyOtherDetailIsShownWithoutItsPeriodAndCut() {
        assertEquals(
                "Not delivered (Message content is empty after sanitization)",
                refusalFor("Message content is empty after sanitization."));
        String longDetail = "x".repeat(100);
        assertEquals("Not delivered (" + "x".repeat(79) + "…)", refusalFor(longDetail));
        assertEquals("Not delivered (refused)", refusalFor(""));
    }
}
