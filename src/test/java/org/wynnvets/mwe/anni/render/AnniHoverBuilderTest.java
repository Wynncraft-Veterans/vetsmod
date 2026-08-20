package org.wynnvets.mwe.anni.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Locale;
import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AnniHoverBuilder#roleColor(String)} — the seven-arm role
 * colour mapping.
 *
 * <p>This is one half of a pair. {@code AnniOutlinePalette.chatFormattingForRole}
 * carries the same table for the outline renderer, so a player's hover colour
 * and outline colour are supposed to agree. They do not agree under every
 * locale: that one folds with the no-argument {@code toUpperCase()} while this
 * one passes {@link Locale#ROOT}. See
 * {@code
 * .claude/ephemeral/bugs-found-via-mellow-rain/default-locale-case-folding-cluster.md}.
 * The equivalence cannot be asserted directly from here — touching
 * {@code AnniOutlinePalette} dies in its static initializer, which constructs a
 * Wynntils {@code CustomColor} — so the Turkish-locale case below pins this
 * side of the pair instead.</p>
 *
 * <p>NOTE: {@link AnniHoverBuilder}'s own static state is four {@code String}
 * constants. Its {@code VetsConfig} and {@code AnniSnapshot} imports are
 * resolved but never executed on the {@code roleColor} path. If a future
 * contributor adds a static initializer that loads MC or Wynntils, this test
 * starts failing at class-init time and the fix is to extract the mapping into
 * its own pure type.</p>
 */
class AnniHoverBuilderTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr");

    /** Captured once at class-init, before any test can have changed it. The
     *  suite's invariant is that whoever mutates a static restores it — two
     *  existing tests assert pristine defaults and have no setup hook. */
    private static final Locale ORIGINAL_DEFAULT = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(ORIGINAL_DEFAULT);
    }

    // ----- The seven arms -----

    @Test
    void roleColor_mapsEveryKnownRole() {
        assertSame(ChatFormatting.WHITE, AnniHoverBuilder.roleColor("FILL"));
        assertSame(ChatFormatting.AQUA, AnniHoverBuilder.roleColor("TANK"));
        assertSame(ChatFormatting.GREEN, AnniHoverBuilder.roleColor("HEAL"));
        assertSame(
                ChatFormatting.GREEN,
                AnniHoverBuilder.roleColor("HEALER"),
                "HEAL and HEALER share one arm");
        assertSame(ChatFormatting.LIGHT_PURPLE, AnniHoverBuilder.roleColor("TERTIARY"));
        assertSame(ChatFormatting.YELLOW, AnniHoverBuilder.roleColor("SECONDARY"));
        assertSame(ChatFormatting.RED, AnniHoverBuilder.roleColor("PRIMARY"));
    }

    @Test
    void roleColor_nullAndUnknownFallBackToGray() {
        assertSame(ChatFormatting.GRAY, AnniHoverBuilder.roleColor(null));
        assertSame(ChatFormatting.GRAY, AnniHoverBuilder.roleColor(""));
        assertSame(ChatFormatting.GRAY, AnniHoverBuilder.roleColor("SUPPORT"));
        assertSame(
                ChatFormatting.GRAY,
                AnniHoverBuilder.roleColor(" TANK "),
                "no trimming — the switch matches the folded string exactly");
    }

    @Test
    void roleColor_foldsCase() {
        assertSame(ChatFormatting.AQUA, AnniHoverBuilder.roleColor("tank"));
        assertSame(ChatFormatting.AQUA, AnniHoverBuilder.roleColor("Tank"));
        assertSame(ChatFormatting.LIGHT_PURPLE, AnniHoverBuilder.roleColor("tertiary"));
    }

    // ----- Locale independence -----

    @Test
    void roleColor_isUnaffectedByATurkishDefaultLocale() {
        // The three role codes containing an 'i' are exactly the ones a
        // default-locale fold would break. Under a Turkish default, 'i'
        // upper-cases to the dotted capital U+0130 rather than to ASCII 'I',
        // so "fill", "primary" and "tertiary" would match no switch arm and
        // fall through to GRAY. Locale.ROOT is what stops that.
        Locale.setDefault(TURKISH);

        assertSame(ChatFormatting.WHITE, AnniHoverBuilder.roleColor("fill"));
        assertSame(ChatFormatting.RED, AnniHoverBuilder.roleColor("primary"));
        assertSame(ChatFormatting.LIGHT_PURPLE, AnniHoverBuilder.roleColor("tertiary"));
    }

    @Test
    void roleColor_turkishFoldWouldDivergeWithoutLocaleRoot() {
        // Demonstrates that the argument to toUpperCase is load-bearing rather
        // than decorative, without asserting on the buggy sibling directly.
        Locale.setDefault(TURKISH);

        assertEquals(
                "FİLL",
                "fill".toUpperCase(),
                "a default-locale fold does not produce the switch label");
        assertEquals("FILL", "fill".toUpperCase(Locale.ROOT), "Locale.ROOT does");
    }
}
