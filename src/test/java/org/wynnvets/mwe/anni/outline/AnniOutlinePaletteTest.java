package org.wynnvets.mwe.anni.outline;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Locale;
import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AnniOutlinePalette#chatFormattingForRole(String)} — the
 * seven-arm role colour table the highlight overlay renders from, and the chat
 * surfaces too, through
 * {@link org.wynnvets.mwe.anni.render.AnniHoverBuilder#roleColor AnniHoverBuilder#roleColor},
 * which delegates here.
 *
 * <p>The palette declares no fields and imports nothing from Wynntils, so it loads in
 * a test. {@link AnniOutlineRegistry}, which reads it, does not: it builds a Wynntils
 * {@code CustomColor} both in {@code <clinit>} and in {@code ownPartyEntry}, and
 * Wynntils is {@code modCompileOnly}, so it is absent at test runtime.</p>
 */
class AnniOutlinePaletteTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr");

    /** Captured at class-init, before any test can have changed it. The suite's
     *  invariant is that whoever mutates a static restores it. */
    private static final Locale ORIGINAL_DEFAULT = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(ORIGINAL_DEFAULT);
    }

    // ----- The seven arms -----

    @Test
    void chatFormattingForRole_mapsEveryKnownRole() {
        assertSame(ChatFormatting.WHITE, AnniOutlinePalette.chatFormattingForRole("FILL"));
        assertSame(ChatFormatting.AQUA, AnniOutlinePalette.chatFormattingForRole("TANK"));
        assertSame(ChatFormatting.GREEN, AnniOutlinePalette.chatFormattingForRole("HEAL"));
        assertSame(
                ChatFormatting.GREEN,
                AnniOutlinePalette.chatFormattingForRole("HEALER"),
                "HEAL and HEALER share one arm");
        assertSame(
                ChatFormatting.LIGHT_PURPLE, AnniOutlinePalette.chatFormattingForRole("TERTIARY"));
        assertSame(ChatFormatting.YELLOW, AnniOutlinePalette.chatFormattingForRole("SECONDARY"));
        assertSame(ChatFormatting.RED, AnniOutlinePalette.chatFormattingForRole("PRIMARY"));
    }

    @Test
    void chatFormattingForRole_nullAndUnknownFallBackToGray() {
        assertSame(ChatFormatting.GRAY, AnniOutlinePalette.chatFormattingForRole(null));
        assertSame(ChatFormatting.GRAY, AnniOutlinePalette.chatFormattingForRole(""));
        assertSame(ChatFormatting.GRAY, AnniOutlinePalette.chatFormattingForRole("SUPPORT"));
        assertSame(
                ChatFormatting.GRAY,
                AnniOutlinePalette.chatFormattingForRole(" TANK "),
                "no trimming — the switch matches the folded string exactly");
    }

    @Test
    void chatFormattingForRole_foldsCase() {
        assertSame(ChatFormatting.AQUA, AnniOutlinePalette.chatFormattingForRole("tank"));
        assertSame(ChatFormatting.AQUA, AnniOutlinePalette.chatFormattingForRole("Tank"));
        assertSame(
                ChatFormatting.LIGHT_PURPLE, AnniOutlinePalette.chatFormattingForRole("tertiary"));
    }

    // ----- Locale independence -----

    @Test
    void chatFormattingForRole_isUnaffectedByATurkishDefaultLocale() {
        // The three role codes containing an 'i' are exactly the ones a
        // default-locale fold breaks. Under a Turkish default, 'i' upper-cases
        // to the dotted capital U+0130 rather than to ASCII 'I', so "fill",
        // "primary" and "tertiary" match no switch arm and fall through to
        // GRAY — an own-party member outlined, and coloured in chat, as an
        // outsider. Locale.ROOT here is what stops that.
        Locale.setDefault(TURKISH);

        assertSame(ChatFormatting.WHITE, AnniOutlinePalette.chatFormattingForRole("fill"));
        assertSame(ChatFormatting.RED, AnniOutlinePalette.chatFormattingForRole("primary"));
        assertSame(
                ChatFormatting.LIGHT_PURPLE, AnniOutlinePalette.chatFormattingForRole("tertiary"));
    }
}
