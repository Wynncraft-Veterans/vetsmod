package org.wynnvets.chat.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GuildChatDispatcher#argsAfter}, the word match the staff intercepts use so a
 * bare command, which typed chat delivers trimmed, still reaches its handler's usage line.
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
}
