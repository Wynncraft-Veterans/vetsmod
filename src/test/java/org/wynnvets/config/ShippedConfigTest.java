package org.wynnvets.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ShippedConfig}: its YAML parse, its typed reads, and that the
 * {@code config.yml} the build puts on the classpath holds exactly
 * {@link VetsConfigDefaults#firstRunValues()}. The last case is the one that fails if the build's
 * {@code generateConfigYml} wiring breaks.
 *
 * <p>NOTE: {@link ShippedConfig} names {@code FabricLoader} only inside {@code load()}, which these
 * tests never reach: they fill its package-private {@code values} instead.</p>
 */
class ShippedConfigTest {

    @AfterEach
    void forgetValues() {
        ShippedConfig.values = null;
    }

    @Test
    void parse_keepsEachValuesType() {
        Map<String, Object> parsed = parse("flag: true\nopacity: 69\nempty: ''\nname: orange\n");
        assertEquals(Boolean.TRUE, parsed.get("flag"));
        assertEquals(69L, ((Number) parsed.get("opacity")).longValue());
        assertEquals("", parsed.get("empty"));
        assertEquals("orange", parsed.get("name"));
    }

    @Test
    void parse_readsNothingFromADocumentThatIsNotAMapping() {
        assertEquals(Map.of(), parse("- a\n- b\n"));
        assertEquals(Map.of(), parse(""));
    }

    @Test
    void typedReads_returnNullForAMissingKeyOrTheWrongType() {
        ShippedConfig.values = parse("flag: true\nopacity: 69\nname: orange\nquoted: 'true'\n");
        assertEquals(Boolean.TRUE, ShippedConfig.bool("flag"));
        assertEquals(69L, ShippedConfig.number("opacity"));
        assertEquals("orange", ShippedConfig.string("name"));
        // A quoted 'true' is a string, as the dumper writes a string that looks like a boolean.
        assertEquals("true", ShippedConfig.string("quoted"));
        assertNull(ShippedConfig.bool("quoted"));
        assertNull(ShippedConfig.bool("name"));
        assertNull(ShippedConfig.number("flag"));
        assertNull(ShippedConfig.string("opacity"));
        assertNull(ShippedConfig.bool("missing"));
    }

    @Test
    void theShippedFileHoldsTheFirstRunValues() throws IOException {
        String text;
        try (InputStream in = ShippedConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(
                    in, "config.yml is not on the test classpath: generateConfigYml did not run");
            text = new String(in.readAllBytes(), UTF_8);
        }
        assertTrue(text.startsWith("# Written by the build from VetsConfigDefaults"), text);
        Map<String, Object> shipped = parse(text);
        Map<String, Object> expected = VetsConfigDefaults.firstRunValues();
        assertEquals(expected.keySet(), shipped.keySet());
        expected.forEach(
                (key, value) -> {
                    Object actual = shipped.get(key);
                    if (value instanceof Long number) {
                        // YAML reads a small integer back as an Integer.
                        assertEquals(number.longValue(), ((Number) actual).longValue(), key);
                    } else {
                        assertEquals(value, actual, key);
                    }
                });
    }

    private static Map<String, Object> parse(String yaml) {
        return ShippedConfig.parse(new ByteArrayInputStream(yaml.getBytes(UTF_8)));
    }
}
