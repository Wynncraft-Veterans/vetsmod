package org.wynnvets.mwe.anni.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AnniMode}'s string conversions. Both fold with {@link Locale#ROOT}. Folding with
 * the JVM's default locale, a Turkish or Azeri default wrote {@code passıve}, which read back as
 * {@link AnniMode#SILENT}, so the mode could never be set.
 *
 * <p>Only the pure conversions run here: {@code fromConfig} reaches {@code VetsConfig}, whose
 * static initializer asks Fabric for the game directory.</p>
 */
class AnniModeTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr");

    private static final Locale ORIGINAL_DEFAULT = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(ORIGINAL_DEFAULT);
    }

    @Test
    void toConfigValue_isPlainLowercaseUnderATurkishDefaultLocale() {
        Locale.setDefault(TURKISH);
        assertEquals("silent", AnniMode.SILENT.toConfigValue());
        assertEquals("passive", AnniMode.PASSIVE.toConfigValue());
        assertEquals("aggressive", AnniMode.AGGRESSIVE.toConfigValue());
    }

    @Test
    void fromString_roundTripsEveryModeUnderATurkishDefaultLocale() {
        Locale.setDefault(TURKISH);
        for (AnniMode mode : AnniMode.values()) {
            assertEquals(mode, AnniMode.fromString(mode.toConfigValue()));
            assertEquals(mode, AnniMode.fromString(mode.name()), "upper-case input");
        }
    }

    @Test
    void fromString_readsTheDotlessSpellingAnOlderBuildWrote() {
        assertEquals(AnniMode.PASSIVE, AnniMode.fromString("pass\u0131ve"));
        assertEquals(AnniMode.AGGRESSIVE, AnniMode.fromString("aggress\u0131ve"));
        assertEquals(AnniMode.SILENT, AnniMode.fromString("s\u0131lent"));
    }

    @Test
    void fromString_nullAndUnknownAreSilent() {
        assertEquals(AnniMode.SILENT, AnniMode.fromString(null));
        assertEquals(AnniMode.SILENT, AnniMode.fromString("loud"));
    }
}
