package org.wynnvets.mwe.anni.outline;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Locale;
import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AnniOutlinePalette#chatFormattingForRole(String)} — the
 * seven-arm role colour table the highlight overlay renders from.
 *
 * <p>This class was untouchable from a test until the other-vets-party
 * {@code CustomColor} moved to {@link AnniOutlineRegistry}: constructing a
 * Wynntils type in a static initializer kills the load, and Wynntils is
 * {@code modCompileOnly} so it is absent at test runtime. The palette now
 * declares no fields at all. Its {@code CustomColor} import resolves a Javadoc
 * link and is never executed, which costs nothing — {@code invokestatic} is
 * resolved lazily.</p>
 *
 * <p>{@link AnniOutlineRegistry} itself is still unloadable here: it builds a
 * {@code CustomColor} both in {@code <clinit>} and in {@code ownPartyEntry}.
 * The relocation moved the blocker into a class that already had one; it did
 * not remove it.</p>
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
        // GRAY — an own-party member rendered as an outsider in the outline
        // while the chat hover, which passes Locale.ROOT, shows the right
        // colour. Locale.ROOT here is what stops that.
        Locale.setDefault(TURKISH);

        assertSame(ChatFormatting.WHITE, AnniOutlinePalette.chatFormattingForRole("fill"));
        assertSame(ChatFormatting.RED, AnniOutlinePalette.chatFormattingForRole("primary"));
        assertSame(
                ChatFormatting.LIGHT_PURPLE, AnniOutlinePalette.chatFormattingForRole("tertiary"));
    }
}
