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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

    /** A container row with no patterns, by its slots and its glass. */
    private static SlotFill.ContainerSlots fill(
            Set<Integer> rows,
            Set<Integer> cols,
            Set<Integer> slots,
            boolean outsideBounds,
            boolean all,
            Set<Integer> except,
            String glass) {
        return new SlotFill.ContainerSlots(
                null,
                null,
                rows,
                cols,
                slots,
                outsideBounds,
                all,
                except,
                null,
                false,
                glass,
                false);
    }

    /** The slots from one to another, both included. */
    private static Set<Integer> range(int from, int to) {
        return IntStream.rangeClosed(from, to).boxed().collect(Collectors.toSet());
    }

    /** What a container row covers, as its pattern reads: a class, or a title after "title: ". */
    private static String target(SlotFill fill) {
        SlotFill.ContainerSlots row = (SlotFill.ContainerSlots) fill;
        return row.containers() != null
                ? row.containers().pattern()
                : "title: " + row.titles().pattern();
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
                        fill(Set.of(), Set.of(), Set.of(45, 53), false, false, Set.of(), "gray"),
                        fill(Set.of(), Set.of(), Set.of(), true, false, Set.of(), "white"),
                        fill(Set.of(), Set.of(), Set.of(22), false, false, Set.of(), "lime"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(28, 30, 31, 32, 34, 46, 47, 48, 49, 50, 51, 52),
                                false,
                                false,
                                Set.of(),
                                "white"),
                        fill(Set.of(), Set.of(), Set.of(), false, true, Set.of(), "gray"),
                        fill(Set.of(), Set.of(), Set.of(28), false, false, Set.of(), "lime"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(46, 47, 48, 49, 50, 51, 52),
                                false,
                                false,
                                Set.of(),
                                "white"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                false,
                                true,
                                Set.of(21, 22, 23, 24, 25, 30, 31, 32, 33, 34),
                                "gray"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(12, 21, 30, 46, 47, 48, 49, 50, 51, 52),
                                false,
                                false,
                                Set.of(),
                                "white"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(3, 39, 45, 53),
                                false,
                                false,
                                Set.of(),
                                "gray"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(46, 47, 48, 49, 50, 51, 52),
                                false,
                                false,
                                Set.of(),
                                "white"),
                        fill(
                                Set.of(0, 4, 5),
                                Set.of(0, 8),
                                Set.of(),
                                false,
                                false,
                                Set.of(),
                                "gray"),
                        fill(Set.of(5), Set.of(), Set.of(), false, false, Set.of(), "white"),
                        fill(Set.of(), Set.of(), Set.of(8), false, false, Set.of(), "white"),
                        fill(Set.of(), Set.of(), Set.of(17, 18), false, false, Set.of(), "white"),
                        fill(Set.of(), Set.of(), Set.of(18), false, false, Set.of(), "white"),
                        fill(Set.of(), Set.of(), Set.of(), true, false, Set.of(), "gray"),
                        fill(Set.of(), Set.of(), Set.of(), true, false, Set.of(13, 14, 15), "gray"),
                        fill(Set.of(), Set.of(0, 1), Set.of(), false, false, Set.of(), "white"),
                        fill(
                                Set.of(),
                                Set.of(0, 1, 7, 8),
                                Set.of(),
                                false,
                                false,
                                Set.of(),
                                "white"),
                        fill(Set.of(), Set.of(1), Set.of(), false, false, Set.of(), "gray"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(7, 8, 9, 16, 17, 25, 26),
                                false,
                                false,
                                Set.of(),
                                "white"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(2, 3, 4, 5, 6, 20, 21, 22, 23, 24),
                                false,
                                false,
                                Set.of(),
                                "clear"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                false,
                                true,
                                Set.of(11, 12, 13, 14, 15),
                                "gray"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(58, 65, 66, 67, 68, 69),
                                false,
                                false,
                                Set.of(),
                                "white"),
                        fill(Set.of(), Set.of(), range(54, 89), false, false, Set.of(), "gray"),
                        fill(Set.of(), Set.of(), Set.of(45), false, false, Set.of(), "white"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                false,
                                true,
                                Set.of(4, 11, 15, 19, 22, 25, 28, 30, 31, 32, 34, 38, 42, 49),
                                "gray"),
                        fill(Set.of(), Set.of(), Set.of(4), false, false, Set.of(), "white"),
                        fill(Set.of(0), Set.of(0, 8), Set.of(), false, false, Set.of(), "gray"),
                        fill(Set.of(), Set.of(), Set.of(5), false, false, Set.of(), "white"),
                        fill(Set.of(), Set.of(), Set.of(), false, true, Set.of(3), "gray"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(0, 9, 17, 18),
                                false,
                                false,
                                Set.of(),
                                "white"),
                        new SlotFill.ContainerSlots(
                                null,
                                null,
                                Set.of(),
                                Set.of(),
                                Set.of(11),
                                false,
                                false,
                                Set.of(),
                                null,
                                false,
                                "clear",
                                true),
                        new SlotFill.ContainerSlots(
                                null,
                                null,
                                Set.of(),
                                Set.of(),
                                Set.of(13),
                                false,
                                false,
                                Set.of(),
                                null,
                                false,
                                "clear",
                                true),
                        new SlotFill.ContainerSlots(
                                null,
                                null,
                                Set.of(),
                                Set.of(),
                                Set.of(15),
                                false,
                                false,
                                Set.of(),
                                null,
                                false,
                                "clear",
                                true),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                false,
                                true,
                                Set.of(12, 13, 14, 15),
                                "gray"),
                        fill(Set.of(), Set.of(), Set.of(), false, true, Set.of(), "gray"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                false,
                                true,
                                Set.of(11, 12, 13, 14),
                                "gray"),
                        fill(Set.of(0, 2), Set.of(0, 8), Set.of(), false, false, Set.of(), "white"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(10, 11, 12, 13, 14, 15, 16),
                                false,
                                false,
                                Set.of(),
                                "white"),
                        fill(Set.of(0, 2), Set.of(0, 8), Set.of(), false, false, Set.of(), "gray"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(0, 10, 19, 27, 28, 36),
                                false,
                                false,
                                Set.of(),
                                "white"),
                        fill(Set.of(), Set.of(0, 1), Set.of(), false, false, Set.of(), "gray"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(10, 11, 12, 13, 14, 16, 21),
                                false,
                                false,
                                Set.of(),
                                "white"),
                        fill(Set.of(), Set.of(), Set.of(), false, true, Set.of(), "gray"),
                        fill(Set.of(), Set.of(), Set.of(0, 8), false, false, Set.of(), "white"),
                        fill(Set.of(), Set.of(0, 8), Set.of(), false, false, Set.of(), "gray"),
                        fill(Set.of(5), Set.of(), Set.of(), false, false, Set.of(), "white"),
                        new SlotFill.ContainerSlots(
                                null,
                                null,
                                Set.of(),
                                Set.of(),
                                Set.of(52),
                                false,
                                false,
                                Set.of(),
                                null,
                                false,
                                "clear",
                                true),
                        fill(Set.of(), Set.of(0, 8), Set.of(51), false, false, Set.of(), "gray"),
                        fill(
                                Set.of(0, 1, 2, 3, 4),
                                Set.of(),
                                Set.of(),
                                false,
                                false,
                                Set.of(),
                                "white"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(10, 12, 14, 16, 20, 22, 24),
                                false,
                                false,
                                Set.of(),
                                "white"),
                        fill(Set.of(), Set.of(), Set.of(), false, true, Set.of(), "gray"),
                        new SlotFill.ContainerSlots(
                                null,
                                null,
                                Set.of(),
                                Set.of(),
                                Set.of(11),
                                false,
                                false,
                                Set.of(),
                                null,
                                false,
                                "clear",
                                true),
                        new SlotFill.ContainerSlots(
                                null,
                                null,
                                Set.of(),
                                Set.of(),
                                Set.of(15),
                                false,
                                false,
                                Set.of(),
                                null,
                                false,
                                "clear",
                                true),
                        fill(Set.of(), Set.of(), Set.of(24), false, false, Set.of(), "pink"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(2, 3, 4, 5, 6),
                                false,
                                false,
                                Set.of(),
                                "white"),
                        fill(Set.of(), Set.of(), Set.of(17, 18), false, false, Set.of(), "white"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                false,
                                true,
                                Set.of(12, 13, 14, 15, 21, 22, 23),
                                "gray"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                false,
                                true,
                                Set.of(12, 13, 14, 15, 21, 22, 23, 24),
                                "gray"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                false,
                                true,
                                Set.of(11, 12, 13, 14),
                                "gray"),
                        fill(Set.of(), Set.of(), Set.of(), false, true, Set.of(13), "gray"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(11, 13, 15),
                                false,
                                false,
                                Set.of(),
                                "white"),
                        fill(Set.of(), Set.of(), Set.of(), false, true, Set.of(), "gray"),
                        fill(Set.of(5), Set.of(), Set.of(), false, false, Set.of(), "white"),
                        fill(Set.of(0, 4), Set.of(0, 8), Set.of(), false, false, Set.of(), "gray"),
                        fill(Set.of(), Set.of(), Set.of(10, 13), false, false, Set.of(), "white"),
                        fill(
                                Set.of(),
                                Set.of(),
                                Set.of(1, 4, 18, 19, 22),
                                false,
                                false,
                                Set.of(),
                                "gray"),
                        fill(Set.of(), Set.of(), Set.of(5, 21), false, false, Set.of(), "white"),
                        fill(Set.of(), Set.of(4), Set.of(), false, false, Set.of(), "gray"),
                        new SlotFill.ContainerSlots(
                                null, null, Set.of(), Set.of(), Set.of(), false, true, Set.of(),
                                null, true, "white", false)),
                fills.subList(1, fills.size()).stream()
                        .map(ManifestFixtures::withoutPatterns)
                        .toList());
        assertEquals(
                List.of(
                        "((Account|Character|PersonalBlock|IslandBlock)Bank|Bookshelf|MiscBucket)Container",
                        "((Account|Character|PersonalBlock|IslandBlock)Bank|Bookshelf|MiscBucket)Container",
                        "TradeMarket(Buy|Sell)Container",
                        "TradeMarket(Buy|Sell)Container",
                        "TradeMarket(Buy|Sell)Container",
                        "TradeMarketOrderContainer",
                        "TradeMarketOrderContainer",
                        "TradeMarketOrderContainer",
                        "TradeMarketFiltersContainer",
                        "TradeMarketFiltersContainer",
                        "TradeMarketTradesContainer",
                        "TradeMarketTradesContainer",
                        "TradeMarketContainer",
                        "TradeMarketRevealItemsContainer",
                        "(BlacksmithSell|ItemIdentifier(Augments)?)Container",
                        "BlacksmithRepairContainer",
                        "(Blacksmith(Repair|Sell)|ItemIdentifier)Container",
                        "ItemIdentifierAugmentsContainer",
                        "GuildBankContainer",
                        "GuildManagementContainer",
                        "MountFeederContainer",
                        "CharacterInfoContainer",
                        "CharacterInfoContainer",
                        "CharacterInfoContainer",
                        "ContentBookContainer",
                        "ContentBookContainer",
                        "MasteryTomesContainer",
                        "MasteryTomesContainer",
                        "title: Merchant",
                        "title: Merchant",
                        "title: Item Pedestal",
                        "title: Item Pedestal",
                        "title: Powder Master: (Infuse|Remove|Upgrade)",
                        "title: Powder Master: Infuse",
                        "title: Powder Master: Remove",
                        "title: Powder Master: Upgrade",
                        "title: Powder Master: Infuse",
                        "title: Powder Master: Remove",
                        "title: Powder Master: Upgrade",
                        "title: Powder Master.*",
                        "title: Housing Islands",
                        "title: Housing Islands",
                        "title: Available Islands",
                        "title: Available Islands",
                        "title: Housing Master",
                        "title: Housing Master",
                        "title: .+ Type Blocks",
                        "title: .+ Type Blocks",
                        "title: Scavenger: Merchant",
                        "title: Scavenger: Tinker",
                        "title: Scavenger: Tinker",
                        "title: Scavenger: Tinker",
                        "title: Item Upgrader",
                        "title: Item Upgrader",
                        "title: Item Upgrader: (Ascension|Pouches)",
                        "title: Item Upgrader: Powder Upgrade",
                        "title: Item Upgrader: Ascension",
                        "title: Item Upgrader: Ascension",
                        "title: Item Upgrader: .+",
                        "title: Item Upgrader: Ascension",
                        "title: Item Upgrader: Pouches",
                        "title: Item Upgrader: Powder Upgrade",
                        "title: Item Upgrader: Powder Remove",
                        "title: Mount Settings",
                        "title: Mount Settings",
                        "title: Party Finder: .*",
                        "title: Party Finder: .*",
                        "CraftingStationContainer",
                        "CraftingStationContainer",
                        "title: \\S+ {4,}\\S+",
                        "title: \\S+ {4,}\\S+",
                        "title: .*"),
                fills.subList(1, fills.size()).stream().map(ShippedScreensTest::target).toList());
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
