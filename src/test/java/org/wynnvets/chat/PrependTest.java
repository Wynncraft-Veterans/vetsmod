package org.wynnvets.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Prepend}'s badge dedup: what {@link Prepend#get()} returns after earlier
 * calls, and that {@link Prepend#blockIndicatorStyle()} leaves that state alone.
 *
 * <p>The dedup state is static, so every test starts and ends with
 * {@link Prepend#resetDedup()}.</p>
 *
 * <p>NOTE: {@link Prepend}'s class-init builds only {@code Component}, {@code Style},
 * {@code FontDescription.Resource} and {@code Identifier} values, which load under JUnit as they
 * do for {@code MessageBodyFormatterTest}. If a constant ever builds something that needs a
 * running client, this test starts failing at class-init time.</p>
 */
class PrependTest {

    /** {@link Prepend#GUILD}'s full badge text, its trailing space included. */
    private static final String GUILD_FULL = "\uDAFF\uDFFC\uE006\uDAFF\uDFFF\uE002\uDAFF\uDFFE ";

    /** {@link Prepend#GUILD}'s compact block indicator text, its trailing space included. */
    private static final String GUILD_INDICATOR = "\uDAFF\uDFFC\uE001\uDB00\uDC06 ";

    @BeforeEach
    @AfterEach
    void resetDedup() {
        Prepend.resetDedup();
    }

    @Test
    void blockIndicatorStyle_leavesTheNextGetAFullBadge() {
        Prepend.GUILD.blockIndicatorStyle();
        assertEquals(GUILD_FULL, Prepend.GUILD.get().getString());
    }

    @Test
    void blockIndicatorStyle_isTheStyleOfTheIndicatorGetReturns() {
        Prepend.GUILD.get();
        MutableComponent indicator = Prepend.GUILD.get();
        assertEquals(GUILD_INDICATOR, indicator.getString());
        assertEquals(indicator.getStyle(), Prepend.GUILD.blockIndicatorStyle());
    }

    @Test
    void blockIndicatorStyle_isTheIndicatorsStyleNotTheFullBadges() {
        // DEFAULT's full badge has no font on its root style and its indicator has one, so this
        // tells the two apart, which GUILD's equal styles cannot.
        Prepend.DEFAULT.get();
        MutableComponent indicator = Prepend.DEFAULT.get();
        assertEquals(indicator.getStyle(), Prepend.DEFAULT.blockIndicatorStyle());
    }

    @Test
    void get_returnsTheFullBadgeAgainAfter18Lines() {
        Prepend.GUILD.get();
        Prepend.addRenderedLines(17);
        assertEquals(GUILD_INDICATOR, Prepend.GUILD.get().getString());
        Prepend.addRenderedLines(1);
        assertEquals(GUILD_FULL, Prepend.GUILD.get().getString());
    }
}
