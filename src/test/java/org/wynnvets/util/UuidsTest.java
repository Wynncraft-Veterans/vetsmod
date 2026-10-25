package org.wynnvets.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import org.junit.jupiter.api.Test;

class UuidsTest {

    // Notch's account UUID — a stable real-world value with no
    // ambiguity between dashed and undashed forms.
    private static final String DASHED = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private static final String UNDASHED = "069a79f444e94726a5befca90e38aaf5";
    private static final UUID EXPECTED = UUID.fromString(DASHED);

    @Test
    void parse_null_returnsNull() {
        assertNull(Uuids.parse(null));
    }

    @Test
    void parse_empty_returnsNull() {
        assertNull(Uuids.parse(""));
    }

    @Test
    void parse_blankWhitespace_returnsNull() {
        assertNull(Uuids.parse("   "));
        assertNull(Uuids.parse("\t\n"));
    }

    @Test
    void parse_dashedUuid_parses() {
        assertEquals(EXPECTED, Uuids.parse(DASHED));
    }

    @Test
    void parse_undashedUuid_parses() {
        assertEquals(EXPECTED, Uuids.parse(UNDASHED));
    }

    @Test
    void parse_undashedUuid_trimsWhitespace() {
        assertEquals(EXPECTED, Uuids.parse("  " + UNDASHED + "\n"));
    }

    @Test
    void parse_invalidHex_returnsNull() {
        // 32 chars but not valid hex — must not throw, must return null.
        assertNull(Uuids.parse("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"));
    }

    @Test
    void parse_wrongLength_returnsNull() {
        // Neither 32 nor 36 chars; UUID.fromString on these throws and we
        // swallow the RuntimeException.
        assertNull(Uuids.parse("069a79f444e9")); // 12
        assertNull(Uuids.parse("069a79f444e94726a5befca90e38aa")); // 30
        assertNull(Uuids.parse("069a79f444e94726a5befca90e38aaf500")); // 34
    }

    // ----- normalizeUndashed -----

    @Test
    void normalizeUndashed_stripsDashesAndLowercases() {
        assertEquals(
                "aaaaaaaabbbbccccddddeeeeeeeeeeee",
                Uuids.normalizeUndashed("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"));
        assertEquals(
                "aaaaaaaabbbbccccddddeeeeeeeeeeee",
                Uuids.normalizeUndashed("aaaaaaaabbbbccccddddeeeeeeeeeeee"),
                "an already-normalised uuid is idempotent");
    }

    @Test
    void normalizeUndashed_nullBecomesEmptyStringNotNull() {
        // A caller may key a map or set by the result, where a null would poison the
        // index rather than simply missing.
        assertEquals("", Uuids.normalizeUndashed(null));
    }

    @Test
    void normalizeUndashed_lowercasesWithLocaleRoot() {
        // Under a Turkish default, a default-locale fold would turn I into a dotless i.
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));
        try {
            assertEquals("ii", Uuids.normalizeUndashed("I-I"));
        } finally {
            Locale.setDefault(saved);
        }
    }

    // ----- isUndashedLowercase -----

    @Test
    void isUndashedLowercase_acceptsOnlyThirtyTwoLowercaseHexDigits() {
        assertTrue(Uuids.isUndashedLowercase(UNDASHED));
        assertFalse(Uuids.isUndashedLowercase(DASHED));
        assertFalse(Uuids.isUndashedLowercase(UNDASHED.toUpperCase(Locale.ROOT)));
        assertFalse(Uuids.isUndashedLowercase(UNDASHED.substring(1)));
        assertFalse(Uuids.isUndashedLowercase("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"));
        assertFalse(Uuids.isUndashedLowercase(null));
    }

    // ----- toUndashed -----

    @Test
    void toUndashed_dropsTheDashes() {
        assertEquals(UNDASHED, Uuids.toUndashed(EXPECTED));
    }

    // ----- int arrays -----

    // A UUID with every int non-zero, its int array, and the array as Tag.toString spells it.
    private static final UUID GOLDEN = UUID.fromString("085d0e58-29d4-44aa-8379-92a4568a59d6");
    private static final int[] GOLDEN_INTS = {140316248, 701777066, -2089184604, 1451907542};
    private static final String GOLDEN_SNBT = "[I;140316248,701777066,-2089184604,1451907542]";

    // A negative low word under a high word that is not -1: this is what a dropped
    // 0xFFFFFFFFL mask gets wrong (the golden vector's low words are both positive).
    private static final UUID SIGN = UUID.fromString("00000000-8000-0000-0000-000080000000");
    private static final int[] SIGN_INTS = {0, -2147483648, 0, -2147483648};

    @Test
    void intArray_goldenVector_bothDirections() {
        assertEquals(GOLDEN, Uuids.fromIntArray(GOLDEN_INTS));
        assertArrayEquals(GOLDEN_INTS, Uuids.toIntArray(GOLDEN));
        assertEquals(GOLDEN_SNBT, Uuids.toIntArrayString(GOLDEN));
        assertEquals(GOLDEN, Uuids.parseIntArray(GOLDEN_SNBT));
        assertEquals("085d0e5829d444aa837992a4568a59d6", Uuids.toUndashed(GOLDEN));
    }

    @Test
    void intArray_signBoundaryVector_keepsTheLowWords() {
        assertEquals(SIGN, Uuids.fromIntArray(SIGN_INTS));
        assertArrayEquals(SIGN_INTS, Uuids.toIntArray(SIGN));
        assertEquals("[I;0,-2147483648,0,-2147483648]", Uuids.toIntArrayString(SIGN));
    }

    @Test
    void intArray_roundTrips() {
        for (UUID u : new UUID[] {new UUID(0L, 0L), new UUID(-1L, -1L), SIGN, GOLDEN, EXPECTED}) {
            assertEquals(u, Uuids.fromIntArray(Uuids.toIntArray(u)));
            assertEquals(u, Uuids.parseIntArray(Uuids.toIntArrayString(u)));
        }
    }

    @Test
    void intArray_nullAndLengthRules() {
        assertNull(Uuids.fromIntArray(null));
        assertNull(Uuids.fromIntArray(new int[3]));
        assertNull(Uuids.fromIntArray(new int[5]));
        assertNull(Uuids.toIntArray(null));
        assertNull(Uuids.toIntArrayString(null));
    }

    @Test
    void parseIntArray_acceptsBothMinecraftSpellings() {
        assertEquals(GOLDEN, Uuids.parseIntArray(GOLDEN_SNBT));
        assertEquals(
                GOLDEN, Uuids.parseIntArray("[I; 140316248, 701777066, -2089184604, 1451907542]"));
        assertEquals(GOLDEN, Uuids.parseIntArray("  " + GOLDEN_SNBT + "\n"));
        assertEquals(
                Uuids.fromIntArray(new int[] {1, 0, -1, 0}),
                Uuids.parseIntArray("[I;00000000001,0,-00000000001,0]"));
    }

    @Test
    void parseIntArray_rejectsEverythingElse() {
        for (String bad :
                new String[] {
                    null,
                    "",
                    "   ",
                    "junk",
                    "[i;1,2,3,4]",
                    "[L;1,2,3,4]",
                    "[I;1,2,3]",
                    "[I;1,2,3,4,5]",
                    "[I;1,2,3,4",
                    "I;1,2,3,4]",
                    "[I;1,,3,4]",
                    "[I;-,2,3,4]",
                    "[I;+1,2,3,4]",
                    "[I;  1,2,3,4]",
                    "[I;1 ,2,3,4]",
                    "[I; 1,2,3,4]",
                    "[I;1, 2,3,4]",
                    "[I; 1, 2,3, 4]",
                    "[I;1,2,3,4,]",
                    "[I; 1, 2, 3, 4, ]",
                    "[I;2147483648,0,0,0]",
                    "[I;-2147483649,0,0,0]",
                    "[I;99999999999,0,0,0]",
                    "[I;\u0661,2,3,4]",
                    DASHED
                }) {
            assertNull(Uuids.parseIntArray(bad), String.valueOf(bad));
        }
    }

    @Test
    void parseAny_readsEverySpelling() {
        assertEquals(EXPECTED, Uuids.parseAny(DASHED));
        assertEquals(EXPECTED, Uuids.parseAny(UNDASHED));
        assertEquals(GOLDEN, Uuids.parseAny(GOLDEN_SNBT));
        assertEquals(GOLDEN, Uuids.parseAny("[I; 140316248, 701777066, -2089184604, 1451907542]"));
        assertNull(Uuids.parseAny("junk"));
        assertNull(Uuids.parseAny(null));
    }

    @Test
    void intArray_agreesWithMinecraftsUuidUtil() {
        for (UUID u : new UUID[] {new UUID(0L, 0L), new UUID(-1L, -1L), SIGN, GOLDEN, EXPECTED}) {
            assertEquals(u, UUIDUtil.uuidFromIntArray(Uuids.toIntArray(u)));
            assertEquals(u, Uuids.fromIntArray(UUIDUtil.uuidToIntArray(u)));
            assertArrayEquals(UUIDUtil.uuidToIntArray(u), Uuids.toIntArray(u));
        }
    }
}
