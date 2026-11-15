package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.ScreenSections.SlotFill;

/**
 * Tests for the shipped screen rows ({@link ScreenSections}): the titles, the slot fills and
 * their labels, the hidden effects and the Wynntils settings. See
 * {@link ShippedFiles ShippedFiles}.
 */
class ShippedScreensTest {

    /** The title the real {@code container_titles} rows give a Wynntils container class. */
    private static Optional<String> realTitle(String containerClass) throws IOException {
        return RetroManifest.load().screens().containerTitles().stream()
                .filter(row -> row.containers().matcher(containerClass).matches())
                .map(ScreenSections.ContainerTitle::title)
                .findFirst();
    }

    @Test
    void theBanksAndTheTradeMarketGetPlainTitles() throws IOException {
        // Wynntils' classes in models/containers/containers/ (personal/, trademarket/).
        Map<String, String> titles =
                Map.of(
                        "AccountBankContainer", "Account Bank",
                        "CharacterBankContainer", "Character Bank",
                        "TradeMarketContainer", "Trade Market",
                        "TradeMarketBuyContainer", "Trade Market: Buy",
                        "TradeMarketSellContainer", "Trade Market: Sell",
                        "TradeMarketOrderContainer", "Trade Market: Order",
                        "TradeMarketFiltersContainer", "Trade Market: Filters",
                        "TradeMarketTradesContainer", "Trade Market: Trades");
        for (Map.Entry<String, String> title : titles.entrySet()) {
            assertEquals(Optional.of(title.getValue()), realTitle(title.getKey()), title.getKey());
        }
        // The rest of Wynntils' PersonalStorageContainer family, which shares the banks' layout.
        assertEquals(Optional.of("Misc. Bucket"), realTitle("MiscBucketContainer"));
        assertEquals(Optional.of("Bookshelf"), realTitle("BookshelfContainer"));
        assertEquals(Optional.of("Block Bank"), realTitle("PersonalBlockBankContainer"));
        assertEquals(Optional.of("Island Block Bank"), realTitle("IslandBlockBankContainer"));
        // Its title is text ("<guild>: Members"), which the readable title keeps.
        assertEquals(Optional.empty(), realTitle("GuildMemberListContainer"));
        // Whole-name patterns: a class that merely starts like a row's gets no title from it.
        assertEquals(Optional.empty(), realTitle("TradeMarketRevealItemsContainer"));
    }

    @Test
    void characterInfoAndTheContentBookGetPlainTitlesAndTheAbilityTreeKeepsItsArt()
            throws IOException {
        // Wynntils' class names, the same in 4.1.17 (built against) and 4.2.16 (run).
        assertEquals(Optional.of("Character Info"), realTitle("CharacterInfoContainer"));
        assertEquals(Optional.of("Content Book"), realTitle("ContentBookContainer"));
        for (String kept :
                List.of("AbilityTreeContainer", "AbilityTreeResetContainer", "AspectsContainer")) {
            assertEquals(Optional.of(RetroManifest.KEEP), realTitle(kept), kept);
        }
        assertEquals(Optional.empty(), realTitle("CharacterCreationContainer"), "no row");
    }

    @Test
    void theSlotFillsStartWithThePlayersSlotsAndEndWithEveryOtherScreensSlots() throws IOException {
        // What the rows between pick on each screen, and in which glass, RetroSlotsTest pins.
        List<SlotFill> fills = RetroManifest.load().screens().slotFills();
        SlotFill.ContainerSlots last = (SlotFill.ContainerSlots) fills.get(fills.size() - 1);

        assertEquals(new SlotFill.PlayerSlots(), fills.get(0));
        assertEquals(".*", last.titles().pattern());
        assertTrue(last.all());
        assertTrue(last.fallback());
    }

    @Test
    void everyContainerWithSlotFillsHasItsLabelDrawnByRetro() throws IOException {
        // Slot fills apply only on a screen whose label retro draws, so a container row on a
        // class whose title row is keep would never paint. The content book is a Wynntils
        // full-screen view, where only rows naming its class apply.
        List<SlotFill> fills = RetroManifest.load().screens().slotFills();
        for (String containerClass :
                List.of(
                        "TradeMarketContainer",
                        "TradeMarketOrderContainer",
                        "TradeMarketFiltersContainer",
                        "ContentBookContainer",
                        "MasteryTomesContainer",
                        "TradeMarketRevealItemsContainer",
                        "BlacksmithSellContainer",
                        "BlacksmithRepairContainer",
                        "ItemIdentifierContainer",
                        "ItemIdentifierAugmentsContainer",
                        "GuildBankContainer",
                        "GuildManagementContainer",
                        "MountFeederContainer",
                        "CharacterInfoContainer",
                        "CraftingStationContainer",
                        "TradeMarketBuyContainer",
                        "TradeMarketSellContainer",
                        "TradeMarketTradesContainer",
                        "AccountBankContainer",
                        "CharacterBankContainer",
                        "MiscBucketContainer",
                        "BookshelfContainer",
                        "PersonalBlockBankContainer",
                        "IslandBlockBankContainer")) {
            assertTrue(
                    fills.stream()
                            .anyMatch(
                                    fill ->
                                            fill instanceof SlotFill.ContainerSlots row
                                                    && row.containers() != null
                                                    && row.containers()
                                                            .matcher(containerClass)
                                                            .matches()),
                    containerClass + " has a slot_fills row");
            assertFalse(
                    realTitle(containerClass).filter(RetroManifest.KEEP::equals).isPresent(),
                    containerClass + " keeps Wynncraft's label");
        }
    }

    @Test
    void theAttackSpeedEffectsAreHidden() throws IOException {
        // Wynncraft's attack speed is an endless haste or mining fatigue (its mob_effect
        // textures are the up and down arrows keep_wynncraft keeps).
        assertEquals(
                List.of("minecraft:(haste|mining_fatigue)"),
                RetroManifest.load().screens().hiddenInventoryEffects().stream()
                        .map(Pattern::pattern)
                        .toList());
    }

    @Test
    void theWynntilsSettingsAreTheFiveSeeded() throws IOException {
        // /wynntils config set ItemHighlight highlightTexture tag
        // /wynntils config set CustomPlayerListOverlay userEnabled false
        // /wynntils config set UnidentifiedItemIcon userEnabled false: its icon would sit on
        // retro's (gear_box_icons), and its texture setting has no "none".
        // /wynntils config set MountJumpBar userEnabled false: its bar would sit on vanilla's.
        // /wynntils config set WynntilsContentBook shiftBehaviorConfig enabledIfShiftHeld: the
        // feature's short name (WynntilsContentBookFeature), its Config field and
        // ShiftBehavior.ENABLED_IF_SHIFT_HELD as EnumUtils.toJsonFormat writes it, which
        // tryParseStringValue reads back (fromJsonFormat), in 4.1.17 and 4.2.16 alike.
        assertEquals(
                List.of(
                        new WynntilsSetting("ItemHighlight", "highlightTexture", "tag"),
                        new WynntilsSetting("CustomPlayerListOverlay", "userEnabled", "false"),
                        new WynntilsSetting("UnidentifiedItemIcon", "userEnabled", "false"),
                        new WynntilsSetting("MountJumpBar", "userEnabled", "false"),
                        new WynntilsSetting(
                                "WynntilsContentBook",
                                "shiftBehaviorConfig",
                                "enabledIfShiftHeld")),
                RetroManifest.load().wynntilsConfig());
    }
}
