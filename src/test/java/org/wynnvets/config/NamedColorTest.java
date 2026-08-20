package org.wynnvets.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for all five {@link NamedColor} methods.
 *
 * <p>The class is pure {@code java.util} — four imports, one 40-entry
 * {@code LinkedHashMap}, no {@code ChatFormatting}, no {@code TextColor}, no
 * hex parsing. Its only consumer, {@code LegacyItemStyle}, is named in prose
 * below but never referenced from code here: that class builds a Wynntils
 * {@code Texture[]} in its static initializer and dies with
 * {@code NoClassDefFoundError} the moment it is touched.</p>
 *
 * <p>KNOWN BUG (pinned, not fixed): {@code isValid} and {@code getRgb} fold
 * case with the no-argument {@code toLowerCase()}, so they follow the JVM's
 * default locale. See {@code
 * .claude/ephemeral/bugs-found-via-mellow-rain/default-locale-case-folding-cluster.md}.
 * The assertions below pin today's behaviour under a Latin default locale; a
 * test asserting the locale-independent answer would be red on arrival.</p>
 */
class NamedColorTest {

    // ── getNames ──────────────────────────────────────────────────────

    @Test
    void getNames_hasFortyEntriesInInsertionOrder() {
        List<String> names = List.copyOf(NamedColor.getNames());
        assertEquals(40, names.size(), "the registry holds 40 named colours");
        // LinkedHashMap, so the set is ordered — the config screen renders it
        // in this order and the first/last entries anchor it.
        assertEquals("black", names.get(0), "insertion order starts at the first § colour");
        assertEquals("transparent", names.get(39), "the special entry is last");
    }

    @Test
    void getNames_isUnmodifiable() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> NamedColor.getNames().add("puce"),
                "the map is wrapped with Collections.unmodifiableMap");
    }

    // ── isValid ───────────────────────────────────────────────────────

    @Test
    void isValid_knownNameCaseInsensitive() {
        assertTrue(NamedColor.isValid("gold"));
        assertTrue(NamedColor.isValid("GOLD"));
        assertTrue(NamedColor.isValid("GoLd"));
    }

    @Test
    void isValid_doesNotTrim() {
        // No trim() anywhere in the lookup path — a config value with stray
        // whitespace is simply not a colour.
        assertFalse(NamedColor.isValid(" gold "), "surrounding spaces are not stripped");
        assertFalse(NamedColor.isValid("gold "), "a trailing space is not stripped");
    }

    @Test
    void isValid_nullAndUnknown() {
        assertFalse(NamedColor.isValid(null), "null short-circuits before the map lookup");
        assertFalse(NamedColor.isValid(""));
        assertFalse(NamedColor.isValid("puce"));
    }

    // ── getRgb ────────────────────────────────────────────────────────

    @Test
    void getRgb_returnsTheStoredRgb() {
        assertEquals(0xFFAA00, NamedColor.getRgb("gold"));
        assertEquals(0xFFAA00, NamedColor.getRgb("GOLD"), "lookup folds case");
        assertEquals(0xF0501E, NamedColor.getRgb("legacy_orange"), "the custom mod colour");
        assertEquals(0x4C004C, NamedColor.getRgb("mythic"));
    }

    @Test
    void getRgb_unknownIsNullNotADefault() {
        // The miss signal is null, distinguishable from a real 0x000000 entry.
        // getRgbOrDefault is the only place a fallback is applied.
        assertNull(NamedColor.getRgb("puce"));
        assertNull(NamedColor.getRgb(null));
        assertNull(NamedColor.getRgb(" gold "), "no trimming here either");
    }

    @Test
    void getRgb_transparentIsIndistinguishableFromBlack() {
        // "transparent" is stored as opaque black. Transparency is NOT a
        // property of this map — LegacyItemStyle string-compares the name
        // against "transparent" and returns 0x00000000 *before* (or instead
        // of) consulting the value asserted here. Collapsing that special
        // case into "just use the map" renders transparent as solid black.
        assertEquals(0x000000, NamedColor.getRgb("transparent"));
        assertEquals(
                NamedColor.getRgb("black"),
                NamedColor.getRgb("transparent"),
                "the two entries carry the same value; only the name distinguishes them");
    }

    // ── getRgbOrDefault ───────────────────────────────────────────────

    @Test
    void getRgbOrDefault_hitIgnoresTheDefault() {
        assertEquals(0xFFAA00, NamedColor.getRgbOrDefault("gold", 0xDC143C));
    }

    @Test
    void getRgbOrDefault_missAndNullTakeTheDefault() {
        assertEquals(0xDC143C, NamedColor.getRgbOrDefault("puce", 0xDC143C));
        assertEquals(0xDC143C, NamedColor.getRgbOrDefault(null, 0xDC143C));
    }

    @Test
    void getRgbOrDefault_returnsBlackForTransparentNotTheDefault() {
        // Follows from the map entry above: "transparent" is a hit, so the
        // caller's fallback is never reached.
        assertEquals(0x000000, NamedColor.getRgbOrDefault("transparent", 0xFFA500));
    }

    // ── withAlpha ─────────────────────────────────────────────────────

    @Test
    void withAlpha_endpointsOfTheOpacityRange() {
        assertEquals(0x00FFAA00, NamedColor.withAlpha(0xFFAA00, 0), "0% → alpha 0x00");
        assertEquals(0xFFFFAA00, NamedColor.withAlpha(0xFFAA00, 100), "100% → alpha 0xFF");
    }

    @Test
    void withAlpha_roundsHalfUp() {
        // 50 * 255 / 100 = 127.5 exactly. Math.round is floor(x + 0.5), so the
        // tie goes up to 128 (0x80), not down to 127.
        assertEquals(0x80FFAA00, NamedColor.withAlpha(0xFFAA00, 50), "50% rounds up to 0x80");
        // 1 * 2.55 = 2.55 → 3; 69 * 2.55 = 175.95 → 176 (0xB0).
        assertEquals(0x03FFAA00, NamedColor.withAlpha(0xFFAA00, 1));
        assertEquals(0xB0FFAA00, NamedColor.withAlpha(0xFFAA00, 69), "the default 69% opacity");
    }

    @Test
    void withAlpha_clampsOutOfRangeOpacity() {
        assertEquals(0x00FFAA00, NamedColor.withAlpha(0xFFAA00, -20), "negative clamps to 0%");
        assertEquals(
                0x00FFAA00,
                NamedColor.withAlpha(0xFFAA00, Integer.MIN_VALUE),
                "clamping happens before the multiply, so extremes cannot overflow");
        assertEquals(0xFFFFAA00, NamedColor.withAlpha(0xFFAA00, 250), "above 100 clamps to 100%");
        assertEquals(0xFFFFAA00, NamedColor.withAlpha(0xFFAA00, Integer.MAX_VALUE));
    }

    @Test
    void withAlpha_stripsAlphaAlreadyPresentOnTheInput() {
        // `rgb & 0x00FFFFFF` — an ARGB value fed back in loses its old alpha
        // silently rather than blending or rejecting.
        assertEquals(
                0x00FFAA00,
                NamedColor.withAlpha(0xFFFFAA00, 0),
                "an opaque input at 0% opacity becomes fully transparent");
        assertEquals(0xFF123456, NamedColor.withAlpha(0xAB123456, 100));
    }
}
