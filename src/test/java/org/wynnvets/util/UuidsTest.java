package org.wynnvets.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.UUID;
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
}
