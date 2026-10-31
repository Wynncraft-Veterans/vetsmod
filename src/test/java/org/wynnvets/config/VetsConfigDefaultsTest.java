package org.wynnvets.config;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link VetsConfigDefaults}: the values {@code config.json} starts from, and so what the
 * shipped {@code config.yml} and {@code /wv config <key> default} restore. The user-facing
 * defaults are a frozen literal, not read from the class. The per-kind guard reads
 * {@code VetsConfigSchema.USER_CONFIG_KEYS}, so a new user key without a default fails here.
 * Three internal defaults are pinned too: {@code retroTextures} starts off, and
 * {@code retroWynntilsSnapshot} and {@code retroPartsOff} start empty.
 *
 * <p>NOTE: {@link VetsConfigDefaults} has no static state, and the one other class it loads,
 * {@code DebugConfigManager}, holds only strings and an immutable map, so nothing here needs a
 * running client. {@link VetsConfig} itself cannot load in a test: its {@code CONFIG_FILE} asks
 * {@code FabricLoader} for the game directory.</p>
 */
class VetsConfigDefaultsTest {

    /** Every user-facing key's default, except the tri-state handleSpoilers, which is unset. */
    private static final Map<String, Object> USER_DEFAULTS =
            Map.ofEntries(
                    entry("legacyItemHighlighting", true),
                    entry("legacyItemShowEnchantments", true),
                    entry("legacyItemUnderrolling", true),
                    entry("legacyItemBackgroundGradientTop", "orange"),
                    entry("legacyItemBackgroundGradientTopOpacity", 69L),
                    entry("legacyItemBackgroundGradientBottom", "crimson"),
                    entry("legacyItemBackgroundGradientBottomOpacity", 100L),
                    entry("legacyItemForegroundSprite", "box_gradient_2"),
                    entry("legacyItemForegroundColor", "orange"),
                    entry("printMOTD", true),
                    entry("printANNI", true),
                    entry("vetsAnniShowHoverDetails", true),
                    entry("vetsAnniPromptRsvp", true),
                    entry("vetsAnniShowPrediction", true),
                    entry("vetsAnniRoleStyle", "descriptive"),
                    entry("vetsAnniBossbarEnabled", true),
                    entry("vetsAnniFlashIntensity", "normal"),
                    entry("vetsAnniFlashSound", true),
                    entry("vetsAnniOutlinesEnabled", true),
                    entry("vetsAnniNametagsEnabled", true),
                    entry("vetsAnniZoneLines", true),
                    entry("vetsAnniScrollWaypoint", true),
                    entry("vetsAnniChatAlerts", true),
                    entry("vetsAnniGhostsPrompt", true),
                    entry("printBridgeMessages", true),
                    entry("printSuccessfulAuth", true),
                    entry("showSupporterGlints", true),
                    entry("colorBlindMode", false),
                    entry("moreReliableGuildCheck", true));

    @Test
    void userDefaults_areTheFirstRunValues() {
        Map<String, Object> values = VetsConfigDefaults.firstRunValues();
        USER_DEFAULTS.forEach((key, expected) -> assertEquals(expected, values.get(key), key));
    }

    @Test
    void everyUserKeyHasADefaultOfItsKind_andTheTriStateIsLeftOut() {
        Map<String, Object> values = VetsConfigDefaults.firstRunValues();
        Set<String> pinned = new HashSet<>(USER_DEFAULTS.keySet());
        pinned.add("handleSpoilers");
        assertEquals(pinned, Set.copyOf(List.of(VetsConfigSchema.USER_CONFIG_KEYS)));
        for (String key : VetsConfigSchema.USER_CONFIG_KEYS) {
            if (VetsConfigSchema.isTriStateKey(key)) {
                // save() leaves an unset tri-state out, so config.yml has no line for it.
                assertFalse(values.containsKey(key), key);
            } else if (VetsConfigSchema.isIntKey(key)) {
                assertInstanceOf(Long.class, values.get(key), key);
            } else if (VetsConfigSchema.isStringKey(key)) {
                assertInstanceOf(String.class, values.get(key), key);
            } else {
                assertInstanceOf(Boolean.class, values.get(key), key);
            }
        }
    }

    @Test
    void retroTextures_isOffOnAFirstRun() {
        // Installing vetsmod must not change how Wynncraft looks until /wv retro on.
        assertEquals(false, VetsConfigDefaults.firstRunValues().get("retroTextures"));
    }

    @Test
    void retroWynntilsSnapshot_isEmptyOnAFirstRun() {
        // Empty means retro has changed no Wynntils setting, so there is nothing to put back.
        assertEquals("", VetsConfigDefaults.firstRunValues().get("retroWynntilsSnapshot"));
    }

    @Test
    void retroPartsOff_isEmptyOnAFirstRun() {
        // Empty means every part of retro is on.
        assertEquals("", VetsConfigDefaults.firstRunValues().get("retroPartsOff"));
    }

    @Test
    void firstRunValues_includeTheDebugKeys() {
        // config.json gets them too: DebugConfigManager registers them before load().
        assertEquals(false, VetsConfigDefaults.firstRunValues().get("itemDump"));
    }
}
