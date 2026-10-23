package org.wynnvets.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Pins every constant in {@link VetsConfigKeys} to the name its setting is stored under in
 * {@code config.json}.
 *
 * <p>Those names are the file format. {@link VetsConfig#load()} reads only the names it knows and
 * the next save writes only those, so renaming a key makes every existing file's value for that
 * setting unreadable, and the old entry is dropped at the next save. The expected table below is a
 * frozen copy, not computed from the class, and the constants are read by reflection, so a key
 * added, removed or renamed fails here as well as a changed value.</p>
 *
 * <p>This pins the names only. Which keys are stored, the map each one lives in and their
 * first-run defaults are set in {@link VetsConfigDefaults#seed}, and {@code VetsConfigDefaultsTest}
 * pins the user-facing ones. The order they are written in is {@link VetsConfig}'s, which cannot
 * load in a test: its static initializer asks Fabric for the game directory.</p>
 *
 * <p>KNOWN BUG (pinned, not fixed): {@code vetsAnniEnabled} is meant to be retired
 * ({@code vets-anni-enabled-to-be-retired}). Retiring it removes its row below.</p>
 */
class VetsConfigKeysTest {

    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("VETS_AUTOMESSAGE", "vetsAutomessage");
        EXPECTED.put("VETS_IS_STAFF", "vetsIsStaff");
        EXPECTED.put("VETS_LAST_STAFF_CHECK", "vetsLastStaffCheck");
        EXPECTED.put("VETS_GUILD_CHECK_RESULT", "vetsGuildCheckResult");
        EXPECTED.put("VETS_LAST_GUILD_CHECK", "vetsLastGuildCheck");
        EXPECTED.put("VETS_DEBUG_ENABLED_AT", "vetsDebugEnabledAt");
        EXPECTED.put("VETS_ANNI_ENABLED", "vetsAnniEnabled");
        EXPECTED.put("VETS_AUTH_KEY", "vetsAuthKey");
        EXPECTED.put("VETS_AUTH_TIER", "vetsAuthTier");
        EXPECTED.put("VETS_AUTH_VERIFIED_AT", "vetsAuthVerifiedAt");
        EXPECTED.put("LEGACY_ITEM_HIGHLIGHTING", "legacyItemHighlighting");
        EXPECTED.put("LEGACY_ITEM_SHOW_ENCHANTMENTS", "legacyItemShowEnchantments");
        EXPECTED.put("PRINT_MOTD", "printMOTD");
        EXPECTED.put("PRINT_ANNI", "printANNI");
        EXPECTED.put("VETS_ANNI_SHOW_HOVER_DETAILS", "vetsAnniShowHoverDetails");
        EXPECTED.put("VETS_ANNI_PROMPT_RSVP", "vetsAnniPromptRsvp");
        EXPECTED.put("VETS_ANNI_SHOW_PREDICTION", "vetsAnniShowPrediction");
        EXPECTED.put("VETS_ANNI_MODE", "vetsAnniMode");
        EXPECTED.put("VETS_ANNI_MODE_USER_SET", "vetsAnniModeUserSet");
        EXPECTED.put("VETS_ANNI_USER_MODE", "vetsAnniUserMode");
        EXPECTED.put("VETS_ANNI_ROLE_STYLE", "vetsAnniRoleStyle");
        EXPECTED.put("VETS_ANNI_BOSSBAR_ENABLED", "vetsAnniBossbarEnabled");
        EXPECTED.put("VETS_ANNI_FLASH_INTENSITY", "vetsAnniFlashIntensity");
        EXPECTED.put("VETS_ANNI_FLASH_SOUND", "vetsAnniFlashSound");
        EXPECTED.put("VETS_ANNI_OUTLINES_ENABLED", "vetsAnniOutlinesEnabled");
        EXPECTED.put("VETS_ANNI_NAMETAGS_ENABLED", "vetsAnniNametagsEnabled");
        EXPECTED.put("VETS_ANNI_ZONE_LINES", "vetsAnniZoneLines");
        EXPECTED.put("VETS_ANNI_SCROLL_WAYPOINT", "vetsAnniScrollWaypoint");
        EXPECTED.put("VETS_ANNI_CHAT_ALERTS", "vetsAnniChatAlerts");
        EXPECTED.put("VETS_ANNI_GHOSTS_PROMPT", "vetsAnniGhostsPrompt");
        EXPECTED.put(
                "VETS_ANNI_GHOSTS_PROMPT_SHOWN_FOR_STAMP", "vetsAnniGhostsPromptShownForStamp");
        EXPECTED.put("PRINT_BRIDGE_MESSAGES", "printBridgeMessages");
        EXPECTED.put("SHOW_SUPPORTER_GLINTS", "showSupporterGlints");
        EXPECTED.put("COLOR_BLIND_MODE", "colorBlindMode");
        EXPECTED.put("HANDLE_SPOILERS", "handleSpoilers");
        EXPECTED.put("MORE_RELIABLE_GUILD_CHECK", "moreReliableGuildCheck");
        EXPECTED.put("PRINT_SUCCESSFUL_AUTH", "printSuccessfulAuth");
        EXPECTED.put("LEGACY_ITEM_BACKGROUND_GRADIENT_TOP", "legacyItemBackgroundGradientTop");
        EXPECTED.put(
                "LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM", "legacyItemBackgroundGradientBottom");
        EXPECTED.put(
                "LEGACY_ITEM_BACKGROUND_GRADIENT_TOP_OPACITY",
                "legacyItemBackgroundGradientTopOpacity");
        EXPECTED.put(
                "LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM_OPACITY",
                "legacyItemBackgroundGradientBottomOpacity");
        EXPECTED.put("LEGACY_ITEM_FOREGROUND_SPRITE", "legacyItemForegroundSprite");
        EXPECTED.put("LEGACY_ITEM_FOREGROUND_COLOR", "legacyItemForegroundColor");
    }

    private static Map<String, String> keyConstants() throws IllegalAccessException {
        Map<String, String> found = new TreeMap<>();
        for (Field f : VetsConfigKeys.class.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (Modifier.isPublic(mod)
                    && Modifier.isStatic(mod)
                    && Modifier.isFinal(mod)
                    && f.getType() == String.class) {
                found.put(f.getName(), (String) f.get(null));
            }
        }
        return found;
    }

    @Test
    void everyKeyConstant_hasItsOnDiskName() throws IllegalAccessException {
        assertEquals(new TreeMap<>(EXPECTED), keyConstants(), "constant name -> on-disk name");
    }

    @Test
    void onDiskNames_areDistinct() throws IllegalAccessException {
        Set<String> seen = new HashSet<>();
        for (String name : keyConstants().values()) {
            assertEquals(true, seen.add(name), "two constants share the on-disk name " + name);
        }
    }
}
