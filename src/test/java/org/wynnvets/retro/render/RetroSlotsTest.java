package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.manifest.AssetSections.Fill;
import org.wynnvets.retro.manifest.ManifestFixtures;
import org.wynnvets.retro.manifest.ScreenSections;
import org.wynnvets.retro.manifest.ScreenSections.SlotFill;
import org.wynnvets.retro.render.RetroSlots.Pane;
import org.wynnvets.retro.render.RetroSlots.Panels;
import org.wynnvets.retro.render.RetroSlots.SlotKey;

/**
 * Tests for {@link RetroSlots}' row logic on plain numbers: which container rows apply to a
 * container, which slots of a chest (nine columns, counted across then down) they pick, whatever
 * those hold or only while empty, and which they leave out, and in which glass, on a full-screen
 * view too; what the shipped rows pick, and in which glass, on each screen they draw in colour;
 * which inventory slots the shipped {@code image_edits} paint over; and how a screen's picked
 * slots divide into glass, accessories and inert slots. Reading the screen, the player's slots
 * (which come from Wynntils), whether a slot holds an item and the painting need a running client
 * and are not tested.
 */
class RetroSlotsTest {

    /** A player trade's title, as Wynncraft spaces its two names. */
    private static final String TRADE_TITLE = "Csabi15              Wenweia";

    /** Wynntils' bank bounds: rows 0-4, every column; slots 0-44. */
    private static final Set<Integer> BANK_BOUNDS = range(0, 44);

    private static List<SlotFill> fills(String yaml) {
        return ManifestFixtures.parse(yaml).screens().slotFills();
    }

    private static SlotFill.ContainerSlots row(String yaml) {
        return (SlotFill.ContainerSlots) fills("slot_fills:\n  - " + yaml + "\n").get(0);
    }

    /** The container slots the rows glaze, whatever their glass. */
    private static Set<Integer> slots(
            List<SlotFill> fills,
            String containerClass,
            String title,
            int size,
            Set<Integer> bounds) {
        return RetroSlots.containerSlots(fills, containerClass, title, size, bounds, false)
                .keySet();
    }

    /** The given slots, each on glass of one colour. */
    private static Map<Integer, Pane> glazed(Set<Integer> slots, String colour) {
        Map<Integer, Pane> glass = new TreeMap<>();
        for (int slot : slots) glass.put(slot, new Pane(colour, false));
        return glass;
    }

    private static Set<Integer> range(int from, int to) {
        Set<Integer> slots = new TreeSet<>();
        IntStream.rangeClosed(from, to).forEach(slots::add);
        return slots;
    }

    @Test
    void aRowPicksItsRowsColumnsAndSlots() {
        SlotFill.ContainerSlots row = row("{container: 'A', rows: [5], cols: [0], slots: [12]}");

        assertTrue(RetroSlots.covers(row, 45, null), "row 5 starts at 45");
        assertTrue(RetroSlots.covers(row, 53, null), "and ends at 53");
        assertFalse(RetroSlots.covers(row, 44, null), "44 is row 4, column 8");
        assertTrue(RetroSlots.covers(row, 9, null), "column 0");
        assertTrue(RetroSlots.covers(row, 12, null));
        assertFalse(RetroSlots.covers(row, 13, null));
    }

    @Test
    void allPicksEverySlotAndExceptLeavesSlotsOut() {
        SlotFill.ContainerSlots sell = row("{container: 'A', slots: all, except: [22, '28-29']}");
        SlotFill.ContainerSlots buttons = row("{container: 'A', rows: [5], except: [49]}");

        assertTrue(RetroSlots.covers(sell, 0, null));
        assertTrue(RetroSlots.covers(sell, 53, null));
        assertFalse(RetroSlots.covers(sell, 22, null), "the slot the player fills");
        assertFalse(RetroSlots.covers(sell, 29, null), "a range");
        assertTrue(RetroSlots.covers(buttons, 45, null));
        assertFalse(RetroSlots.covers(buttons, 49, null), "except beats its own rows");
        assertFalse(RetroSlots.covers(buttons, 44, null), "a row without all");
    }

    @Test
    void outsideBoundsPicksEverySlotWynntilsLeavesOutAndNothingWithoutBounds() {
        SlotFill.ContainerSlots row = row("{container: 'A', slots: outside_bounds}");

        assertFalse(RetroSlots.covers(row, 44, BANK_BOUNDS));
        assertTrue(RetroSlots.covers(row, 45, BANK_BOUNDS));
        assertTrue(RetroSlots.covers(row, 53, BANK_BOUNDS));
        assertFalse(RetroSlots.covers(row, 53, null), "no bounds, nothing outside them");
    }

    @Test
    void aRowAppliesByWholeClassNameOrByTitle() {
        SlotFill.ContainerSlots bank =
                row("{container: '(Account|Character)BankContainer', rows: [5]}");
        SlotFill.ContainerSlots rewards = row("{title: '.+ Rewards', cols: [8]}");

        assertTrue(RetroSlots.applies(bank, "AccountBankContainer", "Account Bank"));
        assertTrue(RetroSlots.applies(bank, "CharacterBankContainer", "Character Bank"));
        assertFalse(RetroSlots.applies(bank, "GuildBankContainer", "Account Bank"));
        assertFalse(RetroSlots.applies(bank, null, "Account Bank"), "Wynntils does not name it");
        assertTrue(RetroSlots.applies(rewards, null, "Daily Rewards"));
        assertTrue(RetroSlots.applies(rewards, "DailyRewardContainer", "Daily Rewards"));
        assertFalse(RetroSlots.applies(rewards, null, "Rewards"));
    }

    @Test
    void unlessSkipsAContainerByClassOrByTitle() {
        SlotFill.ContainerSlots rest =
                row("{title: '.*', slots: all, unless: 'GuildBankContainer|Powder Master.*'}");

        assertTrue(RetroSlots.applies(rest, "GuildMemberListContainer", "Returners: Members"));
        assertTrue(RetroSlots.applies(rest, null, "Merchant"));
        assertFalse(RetroSlots.applies(rest, "GuildBankContainer", "Guild Bank"));
        assertFalse(RetroSlots.applies(rest, null, "Powder Master: Remove"));
    }

    @Test
    void aFallbackRowAppliesOnlyWhereNoEarlierRowDid() {
        List<SlotFill> fills =
                fills(
                        """
                        slot_fills:
                          - {player: wynntils}
                          - {container: 'BankContainer', slots: [0]}
                          - {title: 'Merchant', slots: [1]}
                          - {title: '.*', slots: [8], fallback: true, unless: 'ChestContainer'}
                          - {title: 'Merchant', slots: [2]}
                        """);

        assertEquals(Set.of(0), slots(fills, "BankContainer", "Bank", 9, null));
        assertEquals(
                Set.of(1, 2),
                slots(fills, null, "Merchant", 9, null),
                "a row after the fallback still counts");
        assertEquals(
                Set.of(8),
                slots(fills, "OtherContainer", "Other", 9, null),
                "the player row does not count as applying");
        assertEquals(
                Set.of(),
                slots(fills, "ChestContainer", "Chest", 9, null),
                "its own unless still skips");
    }

    @Test
    void theFallbackRowLeavesEveryScreenWithARowOfItsOwnToThatRow() {
        // The screens with a row of their own, and the ones whose empty slots take the player's
        // things: none of them gets every slot. Each is checked at a size its own row does not
        // fill completely, so a fallback painting too would show.
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Set<Integer> everySlot = range(0, 53);
        for (String container :
                List.of(
                        "AccountBankContainer",
                        "CharacterBankContainer",
                        "PersonalBlockBankContainer",
                        "IslandBlockBankContainer",
                        "GuildBankContainer",
                        "BookshelfContainer",
                        "MiscBucketContainer",
                        "IngredientPouchContainer",
                        "EmeraldPouchContainer",
                        "ItemIdentifierContainer",
                        "ItemIdentifierAugmentsContainer",
                        "BlacksmithRepairContainer",
                        "BlacksmithSellContainer",
                        "CraftingStationContainer",
                        "ScrapMenuContainer",
                        "MountFeederContainer",
                        // Not the buy and sell views: their own rows glaze every slot, so a
                        // fallback would not show there (their colours are pinned below).
                        "TradeMarketContainer",
                        "TradeMarketOrderContainer",
                        "TradeMarketFiltersContainer",
                        "TradeMarketTradesContainer",
                        "TradeMarketRevealItemsContainer",
                        "CharacterInfoContainer",
                        "LootChestContainer",
                        "FlyingChestContainer",
                        "DailyRewardContainer",
                        "ChallengeRewardContainer",
                        "ObjectiveRewardContainer",
                        "ItemBombRewardContainer",
                        "IngredientBombRewardContainer",
                        "EventContainer")) {
            assertFalse(
                    slots(fills, container, "Title", 54, BANK_BOUNDS).equals(everySlot), container);
        }
        for (String title :
                List.of(
                        "Merchant",
                        "Item Pedestal",
                        "Housing Islands",
                        "Available Islands",
                        "Powder Master: Infuse",
                        "Party Finder: Join",
                        TRADE_TITLE)) {
            assertFalse(slots(fills, null, title, 54, null).equals(everySlot), title);
        }
    }

    @Test
    void aBlankNameIsAFillerAndAnyOtherIsAnItem() {
        assertTrue(RetroSlots.blankName(""));
        assertTrue(RetroSlots.blankName(" "));
        assertTrue(RetroSlots.blankName("\u00a77 \u00a7r"));
        assertTrue(RetroSlots.blankName("\u00a78\u00C0\u00C0"), "Wynncraft's space");
        assertTrue(RetroSlots.blankName("\uE000\uDB3F\uDFDC"), "glyphs");
        assertFalse(RetroSlots.blankName("Skill Crystal"));
        assertFalse(RetroSlots.blankName("\u00a7aSkill Crystal"));
        assertFalse(RetroSlots.blankName("\u00C0Page 2"));
    }

    @Test
    void containerSlotsJoinTheRowsThatApplyWithinTheContainer() {
        List<SlotFill> fills =
                fills(
                        """
                        slot_fills:
                          - {player: wynntils}
                          - {container: 'TradeMarketContainer', rows: [5]}
                          - {container: '(Account|Character)BankContainer', slots: outside_bounds}
                          - {container: 'GuildMemberListContainer', cols: [0, 1]}
                          - {title: 'Account Bank', slots: [0]}
                        """);

        assertEquals(range(45, 53), slots(fills, "TradeMarketContainer", "Trade Market", 54, null));
        Set<Integer> bank = new TreeSet<>(range(45, 53));
        bank.add(0);
        assertEquals(
                bank,
                slots(fills, "AccountBankContainer", "Account Bank", 54, BANK_BOUNDS),
                "the class row and the title row together");
        Set<Integer> members = new TreeSet<>();
        for (int row = 0; row < 5; row++) {
            members.add(row * 9);
            members.add(row * 9 + 1);
        }
        assertEquals(
                members,
                slots(fills, "GuildMemberListContainer", "Returners: Members", 45, null),
                "a five-row chest has no slot 45");
        assertEquals(Set.of(), slots(fills, null, "Loot Chest", 27, null));
    }

    @Test
    void whereTwoRowsPickASlotTheFirstOnesGlassShows() {
        List<SlotFill> fills =
                fills(
                        """
                        slot_fills:
                          - {container: 'A', slots: [0], glass: lime}
                          - {container: 'A', rows: [0], glass: gray}
                          - {container: 'A', slots: all}
                        """);
        Map<Integer, Pane> expected = glazed(range(9, 17), "white");
        expected.putAll(glazed(range(1, 8), "gray"));
        expected.put(0, new Pane("lime", false));

        assertEquals(expected, RetroSlots.containerSlots(fills, "A", "Title", 18, null, false));
    }

    @Test
    void theGlassColoursAreClearAndTheDyesEachAPane() {
        Set<String> dyes =
                Arrays.stream(DyeColor.values())
                        .map(DyeColor::getSerializedName)
                        .collect(Collectors.toSet());
        Set<String> expected = new TreeSet<>(dyes);
        expected.add("clear");

        assertEquals(expected, new TreeSet<>(ScreenSections.GLASS_COLOURS));
        assertEquals("glass_pane", RetroStacks.paneId("clear"));
        assertEquals("gray_stained_glass_pane", RetroStacks.paneId("gray"));
        assertEquals("light_blue_stained_glass_pane", RetroStacks.paneId("light_blue"));
    }

    @Test
    void theBanksButtonsAreWhiteBetweenTwoGreyEnds() {
        // Wynntils' PersonalStorageContainer: the storage type at 47, the pages at 51 and 52.
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Map<Integer, Pane> expected = glazed(range(46, 52), "white");
        expected.putAll(glazed(Set.of(45, 53), "gray"));

        for (String bank :
                List.of(
                        "AccountBankContainer",
                        "CharacterBankContainer",
                        "PersonalBlockBankContainer",
                        "IslandBlockBankContainer",
                        "BookshelfContainer",
                        "MiscBucketContainer")) {
            assertEquals(
                    expected,
                    RetroSlots.containerSlots(fills, bank, "Bank", 54, BANK_BOUNDS, false),
                    bank);
        }
    }

    @Test
    void theTradeMarketsViewsAreGreyWithWhiteButtonsAndTheItemTradedLime() {
        // Wynntils' TradeMarketModel: the item on sale at 22 (its sellable slot, an "Empty Item
        // Slot" placeholder until one is picked), the price at 28 (TM_SELL_PRICE_SLOT) and the
        // quantity at 31 (TradeMarketBulkSellFeature's AMOUNT_ITEM_SLOT), each always holding
        // a named item, so they answer the mouse on their glass. The buy view's art puts its
        // item, price and quantity in the same places.
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Map<Integer, Pane> sale = glazed(range(0, 53), "gray");
        sale.putAll(glazed(Set.of(28, 30, 31, 32, 34), "white"));
        sale.putAll(glazed(range(46, 52), "white"));
        sale.put(22, new Pane("lime", false));
        for (String view : List.of("TradeMarketSellContainer", "TradeMarketBuyContainer")) {
            assertEquals(
                    sale,
                    RetroSlots.containerSlots(fills, view, "Trade Market", 54, Set.of(22), false),
                    view);
        }

        Map<Integer, Pane> order = glazed(range(0, 53), "gray");
        order.keySet().removeAll(range(21, 25));
        order.keySet().removeAll(range(30, 34));
        order.putAll(glazed(range(46, 52), "white"));
        order.put(28, new Pane("lime", false));
        assertEquals(
                order,
                RetroSlots.containerSlots(
                        fills, "TradeMarketOrderContainer", "Trade Market: Order", 54, null, false),
                "the order's ten middle slots stay slots");

        Map<Integer, Pane> filters = glazed(Set.of(3, 39, 45, 53), "gray");
        filters.putAll(glazed(Set.of(12, 21, 30), "white"));
        filters.putAll(glazed(range(46, 52), "white"));
        assertEquals(
                filters,
                RetroSlots.containerSlots(
                        fills,
                        "TradeMarketFiltersContainer",
                        "Trade Market: Filters",
                        54,
                        null,
                        false),
                "a divider down the fourth column; the rest are slots");

        Map<Integer, Pane> trades = glazed(range(0, 8), "gray");
        trades.putAll(glazed(range(36, 45), "gray"));
        trades.putAll(glazed(Set.of(9, 17, 18, 26, 27, 35, 53), "gray"));
        trades.putAll(glazed(range(46, 52), "white"));
        assertEquals(
                trades,
                RetroSlots.containerSlots(
                        fills,
                        "TradeMarketTradesContainer",
                        "Trade Market: Trades",
                        54,
                        null,
                        false),
                "a grey frame round the three shelves");
    }

    @Test
    void theBlacksmithAndTheIdentifierAreGreyRoundTheBenchWithWhiteButtons() {
        // Wynntils' bounds: Sell's and the identifier's rows 1 and 2, Repair's row 1 alone,
        // columns 2 to 6.
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Set<Integer> bench = new TreeSet<>(range(11, 15));
        bench.addAll(range(20, 24));
        Map<Integer, Pane> sell = glazed(range(0, 26), "gray");
        sell.keySet().removeAll(bench);
        sell.putAll(glazed(Set.of(17, 18), "white"));
        for (String screen : List.of("BlacksmithSellContainer", "ItemIdentifierContainer")) {
            assertEquals(
                    sell,
                    RetroSlots.containerSlots(fills, screen, "Title", 27, bench, false),
                    screen);
        }

        Map<Integer, Pane> repair = glazed(range(0, 26), "gray");
        repair.keySet().removeAll(range(11, 15));
        repair.put(18, new Pane("white", false));
        assertEquals(
                repair,
                RetroSlots.containerSlots(
                        fills, "BlacksmithRepairContainer", "Title", 27, range(11, 15), false));

        // Wynntils bounds the augments view at 11 alone.
        Map<Integer, Pane> augments = glazed(range(0, 26), "gray");
        augments.keySet().removeAll(Set.of(11, 13, 14, 15));
        augments.putAll(glazed(Set.of(17, 18), "white"));
        assertEquals(
                augments,
                RetroSlots.containerSlots(
                        fills, "ItemIdentifierAugmentsContainer", "Title", 27, Set.of(11), false),
                "13 to 15 stay slots too");
    }

    @Test
    void characterInfosButtonsAreWhiteRoundClearAndGreyWithTheSkillBooksSlots() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Map<Integer, Pane> expected = glazed(Set.of(0, 1, 10, 18, 19), "gray");
        expected.putAll(glazed(Set.of(7, 8, 9, 16, 17, 25, 26), "white"));
        expected.putAll(glazed(Set.of(2, 3, 4, 5, 6, 20, 21, 22, 23, 24), "clear"));

        assertEquals(
                expected,
                RetroSlots.containerSlots(
                        fills, "CharacterInfoContainer", "Character Info", 27, null, false),
                "the skill books, 11 to 15, stay slots; the clear slots are not open");
    }

    @Test
    void aFullScreenViewTakesOnlyRowsNamingItsClassAndTheirSlotsReachThePartBelow() {
        List<SlotFill> fills =
                fills(
                        """
                        slot_fills:
                          - {player: wynntils}
                          - {title: 'Book', slots: [0]}
                          - {container: 'BookContainer', slots: [1, 9, '30-31', 45], except: [31]}
                          - {title: '.*', slots: all, fallback: true}
                        """);

        assertEquals(
                glazed(Set.of(1, 9, 30), "white"),
                RetroSlots.containerSlots(fills, "BookContainer", "Book", 9, null, true),
                "no title row, the fallback neither; 9 and 30 below its nine, 45 past the hotbar");
        assertEquals(
                glazed(Set.of(0, 1), "white"),
                RetroSlots.containerSlots(fills, "BookContainer", "Book", 9, null, false),
                "elsewhere a row picks the chest's slots alone");
    }

    @Test
    void theContentBooksEntriesStaySlotsAndWynncraftsButtonsBelowThemAreGlass() {
        // A Wynntils full-screen view: Wynncraft fills the part below the chest with its
        // buttons, the scroll buttons at menu slots 65 and 69.
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Map<Integer, Pane> expected = glazed(range(54, 89), "gray");
        expected.putAll(glazed(Set.of(58, 65, 66, 67, 68, 69), "white"));

        assertEquals(
                expected,
                RetroSlots.containerSlots(
                        fills, "ContentBookContainer", "Content Book", 54, range(0, 53), true));
    }

    @Test
    void thePowderMastersItemSlotIsClearAndOpenItsPowdersPlainAndItsButtonsWhite() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        // The item's slot, then the slots left plain, on each view.
        Map<String, Integer> item = Map.of("Infuse", 11, "Remove", 13, "Upgrade", 15);
        Map<String, Set<Integer>> plain =
                Map.of("Infuse", range(12, 15), "Remove", Set.of(), "Upgrade", range(11, 14));

        for (String view : List.of("Infuse", "Remove", "Upgrade")) {
            Map<Integer, Pane> expected = glazed(range(0, 26), "gray");
            expected.keySet().removeAll(plain.get(view));
            expected.putAll(glazed(Set.of(0, 9, 17, 18), "white"));
            expected.put(item.get(view), new Pane("clear", true));
            assertEquals(
                    expected,
                    RetroSlots.containerSlots(
                            fills, null, "Powder Master: " + view, 27, null, false),
                    view);
        }
    }

    @Test
    void theMerchantsFrameIsGreyWithOneWhiteButtonAndTheWaresSlots() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Map<Integer, Pane> expected = glazed(range(0, 8), "gray");
        for (int row = 1; row < 6; row++) {
            expected.putAll(glazed(Set.of(row * 9, row * 9 + 8), "gray"));
        }
        expected.put(4, new Pane("white", false));

        assertEquals(expected, RetroSlots.containerSlots(fills, null, "Merchant", 54, null, false));
    }

    @Test
    void eachPartyFinderViewIsFramedInGreyOverARowOfWhiteButtons() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Map<Integer, Pane> expected = glazed(range(0, 8), "gray");
        expected.putAll(glazed(range(36, 44), "gray"));
        expected.putAll(glazed(Set.of(9, 17, 18, 26, 27, 35), "gray"));
        expected.putAll(glazed(range(45, 53), "white"));

        for (String view : List.of("Create", "Join", "Queue", "Search")) {
            assertEquals(
                    expected,
                    RetroSlots.containerSlots(
                            fills, null, "Party Finder: " + view, 54, null, false),
                    view);
        }
    }

    @Test
    void theMasteryTomesAreGreyBarTheTomesRingOfSlotsAndTheBackButton() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Map<Integer, Pane> expected = glazed(range(0, 53), "gray");
        expected.keySet().removeAll(Set.of(4, 11, 15, 19, 22, 25, 28, 30, 31, 32, 34, 38, 42, 49));
        expected.put(45, new Pane("white", false));

        assertEquals(
                expected,
                RetroSlots.containerSlots(
                        fills, "MasteryTomesContainer", "Mastery Tomes", 54, null, false));
    }

    @Test
    void theScavengersMerchantHasARowOfWhiteButtonsUnderItsWares() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();

        assertEquals(
                glazed(range(45, 53), "white"),
                RetroSlots.containerSlots(fills, null, "Scavenger: Merchant", 54, null, false));
    }

    @Test
    void theScavengersTinkerIsWhiteBetweenGreyEdgesOverAClearOpenItemSlot() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Map<Integer, Pane> expected = glazed(range(0, 44), "white");
        for (int row = 0; row < 6; row++) {
            expected.putAll(glazed(Set.of(row * 9, row * 9 + 8), "gray"));
        }
        expected.put(51, new Pane("gray", false));
        expected.put(52, new Pane("clear", true));

        assertEquals(
                expected,
                RetroSlots.containerSlots(fills, null, "Scavenger: Tinker", 54, null, false),
                "46 to 50 stay slots");
    }

    @Test
    void theItemUpgraderIsGreyWithItsTablesSevenMarksWhite() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Map<Integer, Pane> expected = glazed(range(0, 26), "gray");
        expected.putAll(glazed(Set.of(10, 12, 14, 16, 20, 22, 24), "white"));

        assertEquals(
                expected, RetroSlots.containerSlots(fills, null, "Item Upgrader", 27, null, false));
    }

    @Test
    void ascensionsItemSlotIsClearAndOpenItsGemPinkAndItsButtonsWhite() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Map<Integer, Pane> expected = glazed(range(0, 26), "gray");
        expected.keySet().removeAll(range(12, 15));
        expected.keySet().removeAll(range(21, 23));
        expected.putAll(glazed(Set.of(2, 3, 4, 5, 6, 17, 18), "white"));
        expected.put(11, new Pane("clear", true));
        expected.put(24, new Pane("pink", false));

        assertEquals(
                expected,
                RetroSlots.containerSlots(
                        fills, null, "Item Upgrader: Ascension", 27, null, false));
    }

    @Test
    void pouchesItemSlotIsClearAndOpenAndItsButtonsWhite() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Map<Integer, Pane> expected = glazed(range(0, 26), "gray");
        expected.keySet().removeAll(range(12, 15));
        expected.keySet().removeAll(range(21, 24));
        expected.putAll(glazed(Set.of(17, 18), "white"));
        expected.put(11, new Pane("clear", true));

        assertEquals(
                expected,
                RetroSlots.containerSlots(fills, null, "Item Upgrader: Pouches", 27, null, false));
    }

    @Test
    void thePowderViewsAreGreyWithTheirButtonsWhiteAndUpgradesItemSlotClearAndOpen() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Map<Integer, Pane> upgrade = glazed(range(0, 26), "gray");
        upgrade.keySet().removeAll(range(11, 14));
        upgrade.putAll(glazed(Set.of(17, 18), "white"));
        upgrade.put(15, new Pane("clear", true));
        Map<Integer, Pane> remove = glazed(range(0, 26), "gray");
        remove.remove(13);
        remove.putAll(glazed(Set.of(17, 18), "white"));

        assertEquals(
                upgrade,
                RetroSlots.containerSlots(
                        fills, null, "Item Upgrader: Powder Upgrade", 27, null, false));
        assertEquals(
                remove,
                RetroSlots.containerSlots(
                        fills, null, "Item Upgrader: Powder Remove", 27, null, false),
                "13 stays a slot");
    }

    @Test
    void theHousingMasterIsGreyWithItsSevenButtonsWhite() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Map<Integer, Pane> expected = glazed(range(0, 26), "gray");
        expected.putAll(glazed(Set.of(10, 11, 12, 13, 14, 16, 21), "white"));

        assertEquals(
                expected,
                RetroSlots.containerSlots(fills, null, "Housing Master", 27, null, false),
                "a title in plain text, which the fallback glazed white before");
    }

    @Test
    void aHousingBlockListHasGreyEdgesWithWhiteArrowsAndItsBlocksOnSlots() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Map<Integer, Pane> expected = new TreeMap<>();
        for (int row = 0; row < 6; row++) {
            expected.putAll(glazed(Set.of(row * 9, row * 9 + 8), "gray"));
        }
        expected.putAll(glazed(Set.of(0, 8), "white"));

        for (String title : List.of("Decoration Type Blocks", "Wood Type Blocks")) {
            assertEquals(
                    expected,
                    RetroSlots.containerSlots(fills, null, title, 54, null, false),
                    title);
        }
    }

    @Test
    void mountSettingsIsGreyWithItsPanelsThreeMarksWhite() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Map<Integer, Pane> expected = glazed(range(0, 26), "gray");
        expected.putAll(glazed(Set.of(11, 13, 15), "white"));

        assertEquals(
                expected,
                RetroSlots.containerSlots(fills, null, "Mount Settings", 27, null, false));
    }

    @Test
    void theMountFeedersDividerIsGrey() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();

        assertEquals(
                glazed(Set.of(1, 10, 19, 28, 37, 46), "gray"),
                RetroSlots.containerSlots(
                        fills, "MountFeederContainer", "Mount Feeder", 54, null, false));
    }

    @Test
    void everyOtherScreenClosesEveryEmptySlotInWhite() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();

        assertEquals(
                glazed(range(0, 53), "white"),
                RetroSlots.containerSlots(
                        fills, "GuildMemberListContainer", "Returners: Members", 54, null, false));
    }

    @Test
    void eachScreenWithWaresOrAChoiceGlazesOnlyItsButtons() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();

        Set<Integer> notBooks = new TreeSet<>(range(0, 26));
        notBooks.removeAll(range(11, 15));
        assertEquals(
                notBooks,
                slots(fills, "CharacterInfoContainer", "Character Info", 27, null),
                "all but the skill point books");
        assertEquals(
                range(45, 53),
                slots(fills, "TradeMarketContainer", "Trade Market", 54, null),
                "the row under the listings");
        assertEquals(
                Set.of(8),
                slots(fills, "TradeMarketRevealItemsContainer", "Reveal", 9, null),
                "the checkmark");
        // Wynntils' BlacksmithSellContainer bounds: rows 1 and 2, columns 2 to 6.
        Set<Integer> bench = new TreeSet<>(range(11, 15));
        bench.addAll(range(20, 24));
        Set<Integer> aroundBench = new TreeSet<>(range(0, 26));
        aroundBench.removeAll(bench);
        for (String screen :
                List.of(
                        "BlacksmithSellContainer",
                        "BlacksmithRepairContainer",
                        "ItemIdentifierContainer",
                        "ItemIdentifierAugmentsContainer")) {
            assertEquals(aroundBench, slots(fills, screen, "Title", 27, bench), screen);
        }
        Set<Integer> frame = new TreeSet<>(range(0, 8));
        for (int row = 1; row < 6; row++) {
            frame.add(row * 9);
            frame.add(row * 9 + 8);
        }
        assertEquals(
                frame,
                slots(fills, null, "Merchant", 54, null),
                "the wares, down to the last row, are slots");
        Map<Integer, Pane> pedestal = glazed(Set.of(0, 1, 2, 4, 6, 7, 8), "gray");
        pedestal.put(5, new Pane("white", false));
        assertEquals(
                pedestal,
                RetroSlots.containerSlots(fills, null, "Item Pedestal", 9, null, false),
                "its item a slot, its sign on white");
        Map<Integer, Pane> islands = glazed(range(0, 8), "gray");
        islands.putAll(glazed(range(18, 26), "gray"));
        islands.putAll(glazed(Set.of(9, 17), "gray"));
        islands.putAll(glazed(range(10, 16), "white"));
        assertEquals(
                islands,
                RetroSlots.containerSlots(fills, null, "Housing Islands", 27, null, false),
                "a grey frame round a white row of buttons");
        Map<Integer, Pane> available = glazed(Set.of(1, 9, 18, 37), "gray");
        available.putAll(glazed(Set.of(0, 10, 19, 27, 28, 36), "white"));
        assertEquals(
                available,
                RetroSlots.containerSlots(fills, null, "Available Islands", 45, null, false),
                "the two left columns; the islands are slots");
        Set<Integer> guildButtons = new TreeSet<>();
        for (int row = 0; row < 5; row++) {
            guildButtons.add(row * 9);
            guildButtons.add(row * 9 + 1);
        }
        assertEquals(
                guildButtons,
                slots(fills, "GuildBankContainer", "Guild Bank", 45, null),
                "the two left columns; the rest is storage");
        assertEquals(
                Set.of(0, 1, 7, 8, 9, 10, 16, 17, 18, 19, 25, 26),
                slots(fills, "GuildManagementContainer", "Returners: Manage", 27, null),
                "the two columns each side; the middle's buttons are on plain slots");
        assertEquals(
                Set.of(1, 10, 19, 28, 37),
                slots(fills, "MountFeederContainer", "Mount Feeder", 45, null),
                "the column between the mount's slots and the food's");
        Set<Integer> listings = new TreeSet<>(range(0, 53));
        for (int row = 1; row < 4; row++) {
            listings.removeAll(range(row * 9 + 1, row * 9 + 7));
        }
        assertEquals(
                listings,
                slots(fills, null, "Party Finder: Join", 54, null),
                "all but the listings");
        // Three rows: Wynntils' bounds, rows 0 to 6, are its 27 slots and the inventory's 36.
        Map<Integer, Pane> station = glazed(Set.of(1, 4, 18, 19, 22), "gray");
        station.putAll(glazed(Set.of(10, 13), "white"));
        assertEquals(
                station,
                RetroSlots.containerSlots(
                        fills, "CraftingStationContainer", "Jeweling", 27, null, false),
                "white at 10 and at 13, the checkmark");
    }

    @Test
    void aPlayerTradeIsGlassOnlyOnItsCentreColumnAndCheckmarks() {
        // Through readableTitle, so its rule and the shipped row's pattern cannot drift apart.
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        for (int spaces : List.of(4, 14)) {
            String title =
                    RetroContainerTitles.readableTitle(
                            Component.literal("Csabi15" + " ".repeat(spaces) + "Wenweia"));
            Map<Integer, Pane> trade = glazed(Set.of(4, 13, 22), "gray");
            trade.putAll(glazed(Set.of(5, 21), "white"));
            assertEquals(
                    trade,
                    RetroSlots.containerSlots(fills, null, title, 27, null, false),
                    spaces + " spaces: the checkmarks white, the centre column grey");
        }
        assertEquals(
                range(0, 26),
                slots(
                        fills,
                        null,
                        RetroContainerTitles.readableTitle(Component.literal("Csabi15   Wenweia")),
                        27,
                        null),
                "three spaces are no trade");
    }

    @Test
    void theScreensThatTakeThePlayersThingsKeepTheirEmptySlotsOpen() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();

        for (String[] screen :
                List.of(
                        new String[] {"IngredientPouchContainer", "Ingredient Pouch"},
                        new String[] {"LootChestContainer", "Loot Chest"},
                        new String[] {"FlyingChestContainer", "Flying Chest"},
                        new String[] {"DailyRewardContainer", "Daily Rewards"},
                        new String[] {"ChallengeRewardContainer", "Challenge Rewards"},
                        new String[] {"ObjectiveRewardContainer", "Objective Rewards"},
                        new String[] {"ItemBombRewardContainer", "Item Bomb"},
                        new String[] {"IngredientBombRewardContainer", "Ingredient Bomb"},
                        new String[] {"EventContainer", "Event"})) {
            assertEquals(Set.of(), slots(fills, screen[0], screen[1], 54, null), screen[1]);
        }
    }

    private static BitSet bits(int... indices) {
        BitSet bits = new BitSet();
        for (int index : indices) bits.set(index);
        return bits;
    }

    @Test
    void aPlayersAccessorySlotIsPickedButMovableAndOtherPickedSlotsAreGlass() {
        Panels panels =
                RetroSlots.panelsOf(
                        List.of(
                                new SlotKey(30, 9, true, 8, 84),
                                new SlotKey(31, 7, true, 26, 84),
                                new SlotKey(32, 20, true, 44, 84)),
                        Map.of(),
                        Set.of(7, 9),
                        Set.of(9, 10, 11, 12),
                        List.of(),
                        false);

        assertEquals(bits(30, 31), panels.picked());
        assertEquals(
                Map.of(31, Pane.PLAYERS),
                panels.glass(),
                "the compass is fixed, on white glass; the ring slot is not");
        assertEquals(bits(), panels.inert());
    }

    @Test
    void aChestSlotNumberedLikeAnAccessoryIsNoAccessory() {
        Panels panels =
                RetroSlots.panelsOf(
                        List.of(
                                new SlotKey(9, 9, false, 8, 36),
                                new SlotKey(45, 9, true, 8, 104),
                                new SlotKey(10, 10, false, 26, 36)),
                        Map.of(9, new Pane("gray", false)),
                        Set.of(),
                        Set.of(9, 10, 11, 12),
                        List.of(),
                        false);

        assertEquals(bits(9), panels.picked(), "the chest's slot 9 alone: the player's has no row");
        assertEquals(Map.of(9, new Pane("gray", false)), panels.glass(), "on its row's glass");
    }

    @Test
    void onAFullScreenViewThePlayersSlotsAreTheContainersByMenuIndex() {
        // The player's 9 is an accessory slot and the player row's, elsewhere; here it is menu
        // slot 54, a button of Wynncraft's.
        Panels panels =
                RetroSlots.panelsOf(
                        List.of(
                                new SlotKey(54, 9, true, 8, 140),
                                new SlotKey(65, 20, true, 44, 158),
                                new SlotKey(0, 0, false, 8, 18)),
                        Map.of(54, new Pane("gray", false), 65, new Pane("white", false)),
                        Set.of(9),
                        Set.of(9, 10, 11, 12),
                        List.of(),
                        true);

        assertEquals(bits(54, 65), panels.picked());
        assertEquals(
                Map.of(54, new Pane("gray", false), 65, new Pane("white", false)), panels.glass());
    }

    @Test
    void aSlotWhollyUnderAnEditIsInertWhetherPickedOrNot() {
        Panels panels =
                RetroSlots.panelsOf(
                        List.of(new SlotKey(1, 1, false, 98, 18), new SlotKey(2, 2, false, 8, 8)),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        List.of(new Rect(97, 17, 74, 36)),
                        false);

        assertEquals(bits(1), panels.inert());
        assertEquals(bits(), panels.picked());
    }

    @Test
    void theShippedInventoryEditsPaintOverTheCraftingGridResultAndOffhandAlone() {
        // InventoryMenu's slots: the result at (154, 28), the 2x2 grid from (98, 18), the
        // offhand at (77, 62), the armour down from (8, 8), the inventory from (8, 84) and the
        // hotbar along y 142.
        List<Rect> painted =
                RetroModeManager.manifest()
                        .assets()
                        .imageEdits()
                        .get(RetroSlots.INVENTORY_TEXTURE)
                        .stream()
                        .map(Fill::rect)
                        .toList();
        int[][] gone = {{154, 28}, {98, 18}, {116, 18}, {98, 36}, {116, 36}, {77, 62}};
        int[][] kept = {{8, 8}, {8, 62}, {8, 84}, {152, 120}, {8, 142}, {152, 142}};

        for (int[] slot : gone) {
            assertTrue(RetroSlots.paintedOver(painted, slot[0], slot[1]), slot[0] + "," + slot[1]);
        }
        for (int[] slot : kept) {
            assertFalse(RetroSlots.paintedOver(painted, slot[0], slot[1]), slot[0] + "," + slot[1]);
        }
        assertFalse(
                RetroSlots.paintedOver(List.of(new Rect(97, 17, 74, 36)), 77, 62),
                "the offhand answers when its own fill was refused");
        assertFalse(
                RetroSlots.paintedOver(List.of(new Rect(77, 62, 16, 15)), 77, 62),
                "a slot only partly painted still shows");
        assertFalse(RetroSlots.paintedOver(List.of(), 154, 28), "nothing painted");
    }
}
