package org.wynnvets.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Prepend}'s badge dedup: what {@link Prepend#get()} returns after earlier
 * calls, that {@link Prepend#blockIndicatorStyle()} leaves that state alone, and that
 * {@link Prepend#STAFF_CHANNEL} dedups apart from {@link Prepend#GUILD}.
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

    @Test
    void staffChannel_afterAGuildLine_getsItsFullRedBadge() {
        Style guildBadge = Prepend.GUILD.get().getStyle();
        MutableComponent staff = Prepend.STAFF_CHANNEL.get();
        assertEquals(GUILD_FULL, staff.getString());
        // GUILD's font and shadow, in red: a /v line looks as it did when it recoloured GUILD.
        assertEquals(guildBadge.withColor(ChatFormatting.RED), staff.getStyle());
    }

    @Test
    void staffChannel_dedupsAgainstItself_andAGuildLineAfterItIsFull() {
        Prepend.STAFF_CHANNEL.get();
        MutableComponent second = Prepend.STAFF_CHANNEL.get();
        assertEquals(GUILD_INDICATOR, second.getString());
        assertEquals(
                Prepend.GUILD.blockIndicatorStyle().withColor(ChatFormatting.RED),
                second.getStyle());
        MutableComponent guild = Prepend.GUILD.get();
        assertEquals(GUILD_FULL, guild.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.AQUA), guild.getStyle().getColor());
    }
}
