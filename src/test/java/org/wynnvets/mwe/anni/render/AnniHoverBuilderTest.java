package org.wynnvets.mwe.anni.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Locale;
import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wynnvets.mwe.anni.outline.AnniOutlinePalette;

/**
 * Tests for {@link AnniHoverBuilder#roleColor(String)} — the chat surfaces'
 * entry point to the seven-arm role colour mapping.
 *
 * <p>This is one half of a pair, and since Phase 5d it is the half that does not
 * carry the table: {@code roleColor} delegates to
 * {@link org.wynnvets.mwe.anni.outline.AnniOutlinePalette#chatFormattingForRole
 * AnniOutlinePalette#chatFormattingForRole}, which the outline renderer and
 * {@code NametagMixin} read through {@code AnniOutlineRegistry}. The seven arms
 * below therefore now pin the palette through one hop; they were written against
 * an independent copy and are kept unmodified so the delegation had to reproduce
 * every answer exactly.</p>
 *
 * <p>The two copies disagreed under a Turkish or Azeri default until Phase 5d:
 * the palette folded with the no-argument {@code toUpperCase()} while this side
 * has always passed {@link Locale#ROOT}, so {@code fill} / {@code primary} /
 * {@code tertiary} produced one colour in chat and another in the outline. See
 * {@code
 * .claude/ephemeral/bugs-found-via-mellow-rain/default-locale-case-folding-cluster.md},
 * where that row is struck and the remaining 64 sites are not.</p>
 *
 * <p>{@code rolesAgreeWithTheOutlinePalette} is the equivalence case. It could
 * not be written before the palette was made loadable — its static initializer
 * built a Wynntils {@code CustomColor} and any touch died with
 * {@code NoClassDefFoundError} — and asserting it before the fold was fixed
 * would have documented a bug rather than a property. After the delegation it
 * holds by construction; it is here to fail the day somebody re-inlines the
 * table.</p>
 *
 * <p>NOTE: {@link AnniHoverBuilder}'s own static state is four {@code String}
 * constants, and {@code AnniOutlinePalette} has no static state at all. Neither
 * class's {@code VetsConfig} / {@code AnniSnapshot} / {@code CustomColor}
 * imports are executed on the {@code roleColor} path. If a future contributor
 * adds a static initializer that loads MC or Wynntils to either, this test
 * starts failing at class-init time.</p>
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
        // than decorative. Written when the sibling still folded with the
        // default locale; it asserts on String.toUpperCase directly rather than
        // on either subject, so the Phase 5d fix left it green — which is what
        // its bug file predicted.
        Locale.setDefault(TURKISH);

        assertEquals(
                "FİLL",
                "fill".toUpperCase(),
                "a default-locale fold does not produce the switch label");
        assertEquals("FILL", "fill".toUpperCase(Locale.ROOT), "Locale.ROOT does");
    }

    // ----- Equivalence with the outline palette (item 9c) -----

    /** Every key the two surfaces can disagree on, including the lower-case
     *  forms — those are the only ones a fold can break — plus the three
     *  fall-through inputs. */
    private static final String[] EQUIVALENCE_KEYS = {
        "FILL",
        "TANK",
        "HEAL",
        "HEALER",
        "TERTIARY",
        "SECONDARY",
        "PRIMARY",
        "fill",
        "tank",
        "heal",
        "healer",
        "tertiary",
        "secondary",
        "primary",
        null,
        "",
        "SUPPORT",
        " TANK ",
    };

    @Test
    void roleColor_agreesWithTheOutlinePaletteUnderLocaleRoot() {
        Locale.setDefault(Locale.ROOT);

        for (String key : EQUIVALENCE_KEYS) {
            assertSame(
                    AnniOutlinePalette.chatFormattingForRole(key),
                    AnniHoverBuilder.roleColor(key),
                    "chat and outline disagree on role " + key);
        }
    }

    @Test
    void roleColor_agreesWithTheOutlinePaletteUnderATurkishDefaultLocale() {
        // The half that discriminates. Against the pre-fix palette this failed
        // on fill / primary / tertiary and passed on everything else; a fixture
        // that only walked the upper-case forms would have missed it entirely.
        Locale.setDefault(TURKISH);

        for (String key : EQUIVALENCE_KEYS) {
            assertSame(
                    AnniOutlinePalette.chatFormattingForRole(key),
                    AnniHoverBuilder.roleColor(key),
                    "chat and outline disagree on role " + key);
        }
    }
}
