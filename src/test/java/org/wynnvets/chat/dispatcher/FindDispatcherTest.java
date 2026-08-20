package org.wynnvets.chat.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FindDispatcher}'s response sanitiser — the one pure leaf of
 * the {@code /find} path.
 *
 * <p>Its strip is <b>deliberately wider</b> than
 * {@code org.wynnvets.chat.PillCodec#isCustomGlyph}: it adds {@code SURROGATE}
 * and {@code FORMAT}, and its {@code UNASSIGNED} clause carries no
 * supplementary bound. Phase 4 flagged the difference as unpinned and left the
 * decision to Phase 5; the decision is to <b>keep the wider strip</b>. This
 * method exists to normalise a Wynncraft {@code /find} response for prose
 * comparison ({@code "currently on server WC12"}), where stripping more is the
 * point — nothing downstream reads a codepoint, only the words. Narrowing it to
 * the canonical predicate would change what {@code /find} recognises, with no
 * motivation for the change.</p>
 *
 * <p>{@link #strip_alsoRemovesBmpUnassignedFormatAndSurrogates()} is the only
 * case that discriminates against the canonical predicate, and all three of its
 * assertions do so — the BMP-unassigned probe, the FORMAT probe and the
 * SURROGATE probe, one per added clause. A <em>supplementary</em> unassigned
 * probe would not: it passes under either form, which is the trap
 * {@code MessageFanoutDispatcherTest} documents for the sibling strip.</p>
 *
 * <p>NOTE: {@link FindDispatcher} imports Minecraft and Wynntils, but its
 * static state is a String, a long, a queue, a lock object and a null field, so
 * nothing loads during class-init. If a future contributor adds a static
 * initializer that loads MC or Wynntils, this test starts failing at class-init
 * time and the fix is to extract the pure leaf.</p>
 */
class FindDispatcherTest {

    /** A rank pill glyph — PRIVATE_USE, which the canonical predicate also strips. */
    private static final String PUA = String.valueOf((char) 0xE062);

    /** Unassigned and inside the BMP. The canonical predicate keeps this one. */
    private static final String BMP_UNASSIGNED = String.valueOf((char) 0x0378);

    /** Unassigned and above the BMP. Both predicates strip this one. */
    private static final String SUPPLEMENTARY_UNASSIGNED =
            new StringBuilder().appendCodePoint(0xD0002).toString();

    /** Zero-width space — category FORMAT, which the canonical predicate keeps. */
    private static final String FORMAT_CHAR = String.valueOf((char) 0x200B);

    /** An unpaired high surrogate — category SURROGATE, likewise kept by the canonical. */
    private static final String LONE_SURROGATE = String.valueOf((char) 0xD800);

    @Test
    void strip_nullYieldsEmptyStringNotNull() {
        assertEquals("", FindDispatcher.stripFormattingAndPua(null));
    }

    @Test
    void strip_leavesOrdinaryProseAlone() {
        assertEquals(
                "Alice is currently on server WC12",
                FindDispatcher.stripFormattingAndPua("Alice is currently on server WC12"));
    }

    @Test
    void strip_removesSectionSignCodesAsAPair() {
        // The scan consumes the section sign and the character after it, so the
        // colour code goes and the text either side survives.
        assertEquals("green text", FindDispatcher.stripFormattingAndPua("§agreen §rtext"));
    }

    @Test
    void strip_keepsATrailingSectionSignBecauseThereIsNoCodeAfterIt() {
        // The pair skip needs a following character. A lone trailing section
        // sign falls through to the category test, and U+00A7 is
        // OTHER_PUNCTUATION — none of the four categories stripped — so it
        // survives into the comparison.
        assertEquals("text§", FindDispatcher.stripFormattingAndPua("text§"));
    }

    @Test
    void strip_removesPrivateUseAndSupplementaryUnassigned() {
        // The overlap with the canonical predicate: both would strip these.
        assertEquals("ab", FindDispatcher.stripFormattingAndPua("a" + PUA + "b"));
        assertEquals(
                "ab", FindDispatcher.stripFormattingAndPua("a" + SUPPLEMENTARY_UNASSIGNED + "b"));
    }

    @Test
    void strip_alsoRemovesBmpUnassignedFormatAndSurrogates() {
        // The three cells that make this strip wider, and the decision Phase 5
        // was asked to take explicitly. Each of these is kept by
        // PillCodec.isCustomGlyph and dropped here.
        assertEquals(
                "ab",
                FindDispatcher.stripFormattingAndPua("a" + BMP_UNASSIGNED + "b"),
                "unbounded UNASSIGNED — the canonical predicate's cp > 0xFFFF would keep it");
        assertEquals(
                "ab",
                FindDispatcher.stripFormattingAndPua("a" + FORMAT_CHAR + "b"),
                "FORMAT is not in the canonical predicate at all");
        assertEquals(
                "ab",
                FindDispatcher.stripFormattingAndPua("a" + LONE_SURROGATE + "b"),
                "SURROGATE is not in the canonical predicate at all");
    }
}
