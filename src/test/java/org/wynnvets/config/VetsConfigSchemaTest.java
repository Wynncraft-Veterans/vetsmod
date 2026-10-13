package org.wynnvets.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the tables in {@link VetsConfigSchema}: the keys {@code /wv config} accepts and the order
 * of {@code USER_CONFIG_KEYS}, which is the order {@code configList} prints them in; the key lists
 * that decide how {@code /wv config} types a key; the valid-value lists; and the defaults
 * {@code reset} writes. Every expected table is a frozen literal, not read from the class; the
 * predicate check below is the exception.
 *
 * <p>The {@code is*Key} predicates are checked against the arrays they scan, for every key name
 * plus a debug key ({@code itemDump}) and an unknown name, so a predicate pointed at the wrong
 * array fails here.</p>
 *
 * <p>KNOWN BUG (pinned, not fixed): fixing any of these may change a table pinned below.
 * {@code vets-anni-enabled-to-be-retired} removes {@code vetsAnniEnabled} from
 * {@code USER_CONFIG_KEYS}. {@code config-commands-anni-mode-suggestion-arm-unreachable} concerns
 * {@code vetsAnniMode}'s absence from {@code USER_CONFIG_KEYS} and its place in
 * {@code STRING_CONFIG_KEYS}.
 * {@code config-set-rejects-role-style-and-flash-intensity} concerns the role-style and
 * flash-intensity value lists, which {@code /wv config} does not yet validate against.</p>
 */
class VetsConfigSchemaTest {

    private static final List<String> ALL_NAMES =
            List.of(
                    "vetsAutomessage",
                    "vetsIsStaff",
                    "vetsLastStaffCheck",
                    "vetsWaitlistUnlockTime",
                    "vetsHonouraryUnlockTime",
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
                    "vetsAnniMode",
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
                new String[] {"silent", "passive", "aggressive"},
                VetsConfigSchema.VALID_ANNI_MODES);
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
    void resetDefaults() {
        assertEquals(
                Long.valueOf(69L),
                VetsConfigSchema.getIntDefault("legacyItemBackgroundGradientTopOpacity"));
        assertEquals(
                Long.valueOf(100L),
                VetsConfigSchema.getIntDefault("legacyItemBackgroundGradientBottomOpacity"));
        assertEquals(
                "orange", VetsConfigSchema.getStringDefault("legacyItemBackgroundGradientTop"));
        assertEquals(
                "crimson", VetsConfigSchema.getStringDefault("legacyItemBackgroundGradientBottom"));
        assertEquals(
                "box_gradient_2", VetsConfigSchema.getStringDefault("legacyItemForegroundSprite"));
        assertEquals("orange", VetsConfigSchema.getStringDefault("legacyItemForegroundColor"));
        assertEquals("descriptive", VetsConfigSchema.getStringDefault("vetsAnniRoleStyle"));
        assertEquals("silent", VetsConfigSchema.getStringDefault("vetsAnniMode"));
        assertEquals("normal", VetsConfigSchema.getStringDefault("vetsAnniFlashIntensity"));
    }

    @Test
    void resetDefaults_nullForEveryOtherName() {
        Map<String, Long> ints =
                Map.of(
                        "legacyItemBackgroundGradientTopOpacity",
                        69L,
                        "legacyItemBackgroundGradientBottomOpacity",
                        100L);
        List<String> strings =
                List.of(
                        "legacyItemBackgroundGradientTop",
                        "legacyItemBackgroundGradientBottom",
                        "legacyItemForegroundSprite",
                        "legacyItemForegroundColor",
                        "vetsAnniRoleStyle",
                        "vetsAnniMode",
                        "vetsAnniFlashIntensity");
        for (String name : ALL_NAMES) {
            if (!ints.containsKey(name)) {
                assertNull(VetsConfigSchema.getIntDefault(name), name);
            }
            if (!strings.contains(name)) {
                assertNull(VetsConfigSchema.getStringDefault(name), name);
            }
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
