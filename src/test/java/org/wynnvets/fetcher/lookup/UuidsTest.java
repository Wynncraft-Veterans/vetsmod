package org.wynnvets.fetcher.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
