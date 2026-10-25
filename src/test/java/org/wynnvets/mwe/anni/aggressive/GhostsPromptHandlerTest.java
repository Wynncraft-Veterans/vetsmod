package org.wynnvets.mwe.anni.aggressive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GhostsPromptHandler#promptLead}, the ghosts prompt's opening words. The
 * aggressive gate's hot window runs past the anni's start, so a zone entry after it must not say
 * the anni is starting soon. Only this pure leaf runs here: the rest of the handler needs a
 * client, a level and Wynntils.
 */
class GhostsPromptHandlerTest {

    private static final long STAMP = 1_800_000_000L;

    @Test
    void beforeTheStampTheAnniIsStartingSoon() {
        assertEquals("Anni starting soon - ", GhostsPromptHandler.promptLead(STAMP, STAMP - 600));
        assertEquals("Anni starting soon - ", GhostsPromptHandler.promptLead(STAMP, STAMP - 1));
    }

    @Test
    void fromTheStampOnTheAnniIsUnderway() {
        assertEquals("Anni underway - ", GhostsPromptHandler.promptLead(STAMP, STAMP));
        assertEquals("Anni underway - ", GhostsPromptHandler.promptLead(STAMP, STAMP + 1200));
    }

    @Test
    void anUnknownStampSaysStartingSoon() {
        assertEquals("Anni starting soon - ", GhostsPromptHandler.promptLead(0L, STAMP));
    }
}
