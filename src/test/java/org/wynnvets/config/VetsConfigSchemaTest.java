package org.wynnvets.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the tables in {@link VetsConfigSchema}: the keys {@code /wv config} accepts and the order
 * of {@code USER_CONFIG_KEYS}, which is the order {@code configList} prints them in; the key lists
 * that decide how {@code /wv config} types a key; the valid-value lists and which keys
 * {@code fixedValuesOf} maps to them. The defaults are pinned in
 * {@code VetsConfigDefaultsTest}. Every expected table
 * is a frozen literal, not read from the class; the predicate check and the string-key guard
 * below are the exceptions.
 *
 * <p>The {@code is*Key} predicates are checked against the arrays they scan, for every key name
 * plus a debug key ({@code itemDump}) and an unknown name, so a predicate pointed at the wrong
 * array fails here. The string-key guard reads {@code STRING_CONFIG_KEYS} so that a new string
 * key that is neither a colour key nor the sprite key fails until it declares a fixed value list,
 * instead of being validated as a colour name.</p>
 *
 * <p>KNOWN BUG (pinned, not fixed): fixing it changes a table pinned below.
 * {@code vets-anni-enabled-to-be-retired} removes {@code vetsAnniEnabled} from
 * {@code USER_CONFIG_KEYS}.</p>
 */
class VetsConfigSchemaTest {

    private static final List<String> ALL_NAMES =
            List.of(
                    "vetsAutomessage",
                    "vetsIsStaff",
                    "vetsLastStaffCheck",
                    "vetsGuildCheckResult",
                    "vetsLastGuildCheck",
                    "vetsDebugEnabledAt",
                    "vetsAnniEnabled",
                    "vetsAuthKey",
                    "vetsAuthTier",
                    "vetsAuthVerifiedAt",
                    "legacyItemHighlighting",
                    "legacyItemShowEnchantments",
                    "printMOTD",
                    "printANNI",
                    "vetsAnniShowHoverDetails",
                    "vetsAnniPromptRsvp",
                    "vetsAnniShowPrediction",
                    "vetsAnniMode",
                    "vetsAnniModeUserSet",
                    "vetsAnniUserMode",
                    "vetsAnniRoleStyle",
                    "vetsAnniBossbarEnabled",
                    "vetsAnniFlashIntensity",
                    "vetsAnniFlashSound",
                    "vetsAnniOutlinesEnabled",
                    "vetsAnniNametagsEnabled",
                    "vetsAnniZoneLines",
                    "vetsAnniScrollWaypoint",
                    "vetsAnniChatAlerts",
                    "vetsAnniGhostsPrompt",
                    "vetsAnniGhostsPromptShownForStamp",
                    "printBridgeMessages",
                    "showSupporterGlints",
                    "colorBlindMode",
                    "handleSpoilers",
                    "moreReliableGuildCheck",
                    "printSuccessfulAuth",
                    "legacyItemBackgroundGradientTop",
                    "legacyItemBackgroundGradientBottom",
                    "legacyItemBackgroundGradientTopOpacity",
                    "legacyItemBackgroundGradientBottomOpacity",
                    "legacyItemForegroundSprite",
                    "legacyItemForegroundColor",
                    "itemDump",
                    "noSuchKey");

    @Test
    void userConfigKeys_inPrintOrder() {
        assertArrayEquals(
                new String[] {
                    "legacyItemHighlighting",
                    "legacyItemShowEnchantments",
                    "legacyItemBackgroundGradientTop",
                    "legacyItemBackgroundGradientTopOpacity",
                    "legacyItemBackgroundGradientBottom",
                    "legacyItemBackgroundGradientBottomOpacity",
                    "legacyItemForegroundSprite",
                    "legacyItemForegroundColor",
                    "printMOTD",
                    "printANNI",
                    "vetsAnniEnabled",
                    "vetsAnniShowHoverDetails",
                    "vetsAnniPromptRsvp",
                    "vetsAnniShowPrediction",
                    "vetsAnniRoleStyle",
                    "vetsAnniBossbarEnabled",
                    "vetsAnniFlashIntensity",
                    "vetsAnniFlashSound",
                    "vetsAnniOutlinesEnabled",
                    "vetsAnniNametagsEnabled",
                    "vetsAnniZoneLines",
                    "vetsAnniScrollWaypoint",
                    "vetsAnniChatAlerts",
                    "vetsAnniGhostsPrompt",
                    "printBridgeMessages",
                    "printSuccessfulAuth",
                    "showSupporterGlints",
                    "colorBlindMode",
                    "handleSpoilers",
                    "moreReliableGuildCheck"
                },
                VetsConfigSchema.USER_CONFIG_KEYS);
    }

    @Test
    void typeLists() {
        assertArrayEquals(new String[] {"handleSpoilers"}, VetsConfigSchema.TRISTATE_KEYS);
        assertArrayEquals(
                new String[] {
                    "legacyItemBackgroundGradientTop",
                    "legacyItemBackgroundGradientBottom",
                    "legacyItemForegroundSprite",
                    "legacyItemForegroundColor",
                    "vetsAnniRoleStyle",
                    "vetsAnniFlashIntensity"
                },
                VetsConfigSchema.STRING_CONFIG_KEYS);
        assertArrayEquals(
                new String[] {
                    "legacyItemBackgroundGradientTopOpacity",
                    "legacyItemBackgroundGradientBottomOpacity"
                },
                VetsConfigSchema.INT_CONFIG_KEYS);
    }

    @Test
    void validValueLists() {
        assertArrayEquals(
                new String[] {"descriptive", "short", "formal"},
                VetsConfigSchema.VALID_ROLE_STYLES);
        assertArrayEquals(
                new String[] {"subtle", "normal", "strong"},
                VetsConfigSchema.VALID_FLASH_INTENSITIES);
        assertArrayEquals(
                new String[] {
                    "wynn",
                    "tag",
                    "circle_transparent",
                    "circle_opaque",
                    "circle_outline_large",
                    "circle_outline_small",
                    "box_transparent",
                    "box_opaque",
                    "box_gradient_1",
                    "box_gradient_2"
                },
                VetsConfigSchema.VALID_SPRITES);
    }

    @Test
    void fixedValuesOf_mapsOnlyRoleStyleAndFlashIntensity() {
        for (String name : ALL_NAMES) {
            String[] fixed = VetsConfigSchema.fixedValuesOf(name);
            if (name.equals("vetsAnniRoleStyle")) {
                assertArrayEquals(new String[] {"descriptive", "short", "formal"}, fixed);
            } else if (name.equals("vetsAnniFlashIntensity")) {
                assertArrayEquals(new String[] {"subtle", "normal", "strong"}, fixed);
            } else {
                assertNull(fixed, name);
            }
        }
    }

    @Test
    void isFixedValue_acceptsTheListedLowercaseValuesOnly() {
        for (String v : new String[] {"descriptive", "short", "formal"}) {
            assertTrue(VetsConfigSchema.isFixedValue("vetsAnniRoleStyle", v), v);
        }
        for (String v : new String[] {"subtle", "normal", "strong"}) {
            assertTrue(VetsConfigSchema.isFixedValue("vetsAnniFlashIntensity", v), v);
        }
        assertFalse(VetsConfigSchema.isFixedValue("vetsAnniRoleStyle", "red"));
        assertFalse(VetsConfigSchema.isFixedValue("vetsAnniRoleStyle", "SHORT"));
        assertFalse(VetsConfigSchema.isFixedValue("vetsAnniRoleStyle", ""));
        assertFalse(VetsConfigSchema.isFixedValue("vetsAnniRoleStyle", null));
        assertFalse(VetsConfigSchema.isFixedValue("vetsAnniFlashIntensity", "short"));
        assertFalse(VetsConfigSchema.isFixedValue("legacyItemForegroundColor", "orange"));
        assertFalse(VetsConfigSchema.isFixedValue("noSuchKey", "normal"));
    }

    @Test
    void everyStringKey_isAColourKeyTheSpriteKeyOrHasAFixedList() {
        List<String> colourOrSprite =
                List.of(
                        "legacyItemBackgroundGradientTop",
                        "legacyItemBackgroundGradientBottom",
                        "legacyItemForegroundColor",
                        "legacyItemForegroundSprite");
        for (String key : VetsConfigSchema.STRING_CONFIG_KEYS) {
            assertTrue(
                    colourOrSprite.contains(key) || VetsConfigSchema.fixedValuesOf(key) != null,
                    key);
        }
    }

    @Test
    void predicates_agreeWithTheirArrays() {
        for (String name : ALL_NAMES) {
            assertEquals(
                    Arrays.asList(VetsConfigSchema.USER_CONFIG_KEYS).contains(name),
                    VetsConfigSchema.isUserConfigKey(name),
                    "isUserConfigKey " + name);
            assertEquals(
                    Arrays.asList(VetsConfigSchema.TRISTATE_KEYS).contains(name),
                    VetsConfigSchema.isTriStateKey(name),
                    "isTriStateKey " + name);
            assertEquals(
                    Arrays.asList(VetsConfigSchema.STRING_CONFIG_KEYS).contains(name),
                    VetsConfigSchema.isStringKey(name),
                    "isStringKey " + name);
            assertEquals(
                    Arrays.asList(VetsConfigSchema.INT_CONFIG_KEYS).contains(name),
                    VetsConfigSchema.isIntKey(name),
                    "isIntKey " + name);
        }
    }
}
