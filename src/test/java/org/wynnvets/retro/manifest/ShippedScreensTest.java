package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** A container row with no patterns, by its slots. */
    private static SlotFill.ContainerSlots fill(
            Set<Integer> rows,
            Set<Integer> cols,
            Set<Integer> slots,
            boolean outsideBounds,
            boolean all,
            Set<Integer> except) {
        return new SlotFill.ContainerSlots(
                null, null, rows, cols, slots, outsideBounds, all, except, null, false);
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
    void theSlotFillsAreThePlayersFixedSlotsEachScreensButtonsAndEveryOtherScreensSlots()
            throws IOException {
        List<SlotFill> fills = RetroManifest.load().screens().slotFills();

        assertEquals(new SlotFill.PlayerSlots(), fills.get(0));
        assertEquals(
                List.of(
                        fill(Set.of(), Set.of(), Set.of(), true, false, Set.of()),
                        // The item on sale, its price and its quantity, which the player sets.
                        fill(Set.of(), Set.of(), Set.of(), false, true, Set.of(22, 28, 31)),
                        fill(Set.of(0, 4, 5), Set.of(0, 8), Set.of(), false, false, Set.of()),
                        fill(Set.of(5), Set.of(), Set.of(), false, false, Set.of()),
                        fill(Set.of(), Set.of(), Set.of(8), false, false, Set.of()),
                        fill(Set.of(), Set.of(), Set.of(), true, false, Set.of()),
                        fill(Set.of(), Set.of(0, 1), Set.of(), false, false, Set.of()),
                        fill(Set.of(), Set.of(0, 1, 7, 8), Set.of(), false, false, Set.of()),
                        fill(Set.of(), Set.of(1), Set.of(), false, false, Set.of()),
                        fill(Set.of(), Set.of(), Set.of(), false, true, Set.of(11, 12, 13, 14, 15)),
                        fill(Set.of(0), Set.of(0, 8), Set.of(), false, false, Set.of()),
                        fill(Set.of(), Set.of(), Set.of(), false, true, Set.of(3, 5)),
                        fill(Set.of(0, 2), Set.of(0, 8), Set.of(), false, false, Set.of()),
                        fill(Set.of(0, 4, 5), Set.of(0, 8), Set.of(), false, false, Set.of()),
                        fill(Set.of(), Set.of(), Set.of(13), false, false, Set.of()),
                        fill(Set.of(), Set.of(4), Set.of(5, 21), false, false, Set.of()),
                        new SlotFill.ContainerSlots(
                                null, null, Set.of(), Set.of(), Set.of(), false, true, Set.of(),
                                null, true)),
                fills.subList(1, fills.size()).stream()
                        .map(ManifestFixtures::withoutPatterns)
                        .toList());
        assertEquals(
                List.of(
                        "((Account|Character|PersonalBlock|IslandBlock)Bank|Bookshelf|MiscBucket)Container",
                        "TradeMarket(Buy|Sell)Container",
                        "TradeMarketTradesContainer",
                        "TradeMarketContainer",
                        "TradeMarketRevealItemsContainer",
                        "(Blacksmith(Repair|Sell)|ItemIdentifier(Augments)?)Container",
                        "GuildBankContainer",
                        "GuildManagementContainer",
                        "MountFeederContainer",
                        "CharacterInfoContainer"),
                fills.subList(1, 11).stream()
                        .map(fill -> ((SlotFill.ContainerSlots) fill).containers().pattern())
                        .toList());
        assertEquals("Merchant", ((SlotFill.ContainerSlots) fills.get(11)).titles().pattern());
        assertEquals("Item Pedestal", ((SlotFill.ContainerSlots) fills.get(12)).titles().pattern());
        assertEquals(
                "Housing Islands|Powder Master.*",
                ((SlotFill.ContainerSlots) fills.get(13)).titles().pattern());
        assertEquals(
                "Party Finder: .*", ((SlotFill.ContainerSlots) fills.get(14)).titles().pattern());
        assertEquals(
                "CraftingStationContainer",
                ((SlotFill.ContainerSlots) fills.get(15)).containers().pattern());
        assertEquals("\\S+ {4,}\\S+", ((SlotFill.ContainerSlots) fills.get(16)).titles().pattern());
        assertEquals(".*", ((SlotFill.ContainerSlots) fills.get(17)).titles().pattern());
    }

    @Test
    void everyContainerWithSlotFillsHasItsLabelDrawnByRetro() throws IOException {
        // Slot fills apply only on a screen whose label retro draws, so a container row on a
        // class whose title row is keep would never paint. None of these is a Wynntils
        // full-screen view, where fills never apply.
        List<SlotFill> fills = RetroManifest.load().screens().slotFills();
        for (String containerClass :
                List.of(
                        "TradeMarketContainer",
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
    void theWynntilsSettingsAreTheFourSeeded() throws IOException {
        // /wynntils config set ItemHighlight highlightTexture tag
        // /wynntils config set CustomPlayerListOverlay userEnabled false
        // /wynntils config set UnidentifiedItemIcon userEnabled false: its icon would sit on
        // retro's (gear_box_icons), and its texture setting has no "none".
        // /wynntils config set WynntilsContentBook shiftBehaviorConfig enabledIfShiftHeld: the
        // feature's short name (WynntilsContentBookFeature), its Config field and
        // ShiftBehavior.ENABLED_IF_SHIFT_HELD as EnumUtils.toJsonFormat writes it, which
        // tryParseStringValue reads back (fromJsonFormat), in 4.1.17 and 4.2.16 alike.
        assertEquals(
                List.of(
                        new WynntilsSetting("ItemHighlight", "highlightTexture", "tag"),
                        new WynntilsSetting("CustomPlayerListOverlay", "userEnabled", "false"),
                        new WynntilsSetting("UnidentifiedItemIcon", "userEnabled", "false"),
                        new WynntilsSetting(
                                "WynntilsContentBook",
                                "shiftBehaviorConfig",
                                "enabledIfShiftHeld")),
                RetroManifest.load().wynntilsConfig());
    }
}
