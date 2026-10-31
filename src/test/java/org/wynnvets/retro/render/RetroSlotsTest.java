package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.manifest.AssetSections.Fill;
import org.wynnvets.retro.manifest.ManifestFixtures;
import org.wynnvets.retro.manifest.ScreenSections.SlotFill;
import org.wynnvets.retro.render.RetroSlots.Panels;
import org.wynnvets.retro.render.RetroSlots.SlotKey;

/**
 * Tests for {@link RetroSlots}' row logic on plain numbers: which container rows apply to a
 * container, which slots of a chest (nine columns, counted across then down) they pick, whatever
 * those hold or only while empty, and which they leave out; what the shipped rows pick on the
 * Trade Market's views and Character Info; and which inventory slots the shipped
 * {@code image_edits} paint over; and how a screen's picked slots divide into glass, accessories
 * and inert slots. Reading the screen, the player's slots (which come from Wynntils), whether a
 * slot holds an item and the painting need a running client and are not tested.
 */
class RetroSlotsTest {

    /** A player trade's title, as Wynncraft spaces its two names. */
    private static final String TRADE_TITLE = "Csabi15              Wenweia";

    /** Wynntils' bank bounds: rows 0-4, every column; slots 0-44. */
    private static final Set<Integer> BANK_BOUNDS = range(0, 44);

    private static List<SlotFill> fills(String yaml) {
        return ManifestFixtures.parseTextures(yaml).screens().slotFills();
    }

    private static SlotFill.ContainerSlots row(String yaml) {
        return (SlotFill.ContainerSlots) fills("slot_fills:\n  - " + yaml + "\n").get(0);
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

        assertEquals(Set.of(0), RetroSlots.containerSlots(fills, "BankContainer", "Bank", 9, null));
        assertEquals(
                Set.of(1, 2),
                RetroSlots.containerSlots(fills, null, "Merchant", 9, null),
                "a row after the fallback still counts");
        assertEquals(
                Set.of(8),
                RetroSlots.containerSlots(fills, "OtherContainer", "Other", 9, null),
                "the player row does not count as applying");
        assertEquals(
                Set.of(),
                RetroSlots.containerSlots(fills, "ChestContainer", "Chest", 9, null),
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
                        "TradeMarketContainer",
                        "TradeMarketBuyContainer",
                        "TradeMarketSellContainer",
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
                    RetroSlots.containerSlots(fills, container, "Title", 54, BANK_BOUNDS)
                            .equals(everySlot),
                    container);
        }
        for (String title :
                List.of(
                        "Merchant",
                        "Item Pedestal",
                        "Housing Islands",
                        "Powder Master: Remove",
                        "Party Finder: Join",
                        TRADE_TITLE)) {
            assertFalse(
                    RetroSlots.containerSlots(fills, null, title, 54, null).equals(everySlot),
                    title);
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

        assertEquals(
                range(45, 53),
                RetroSlots.containerSlots(fills, "TradeMarketContainer", "Trade Market", 54, null));
        Set<Integer> bank = new TreeSet<>(range(45, 53));
        bank.add(0);
        assertEquals(
                bank,
                RetroSlots.containerSlots(
                        fills, "AccountBankContainer", "Account Bank", 54, BANK_BOUNDS),
                "the class row and the title row together");
        Set<Integer> members = new TreeSet<>();
        for (int row = 0; row < 5; row++) {
            members.add(row * 9);
            members.add(row * 9 + 1);
        }
        assertEquals(
                members,
                RetroSlots.containerSlots(
                        fills, "GuildMemberListContainer", "Returners: Members", 45, null),
                "a five-row chest has no slot 45");
        assertEquals(Set.of(), RetroSlots.containerSlots(fills, null, "Loot Chest", 27, null));
    }

    @Test
    void theTradeMarketViewsNeverGreyTheOnesThePlayerSets() {
        // Wynntils' TradeMarketModel: the item on sale at 22 (its sellable slot, an "Empty Item
        // Slot" placeholder until one is picked), the price at 28 (TM_SELL_PRICE_SLOT) and the
        // quantity at 31 (TradeMarketBulkSellFeature's AMOUNT_ITEM_SLOT). The buy view's art
        // puts its item, price and quantity in the same places.
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        Set<Integer> greyed = range(0, 53);
        greyed.removeAll(Set.of(22, 28, 31));

        for (String view : List.of("TradeMarketSellContainer", "TradeMarketBuyContainer")) {
            assertEquals(
                    greyed,
                    RetroSlots.containerSlots(fills, view, "Trade Market", 54, Set.of(22)),
                    view);
        }
    }

    @Test
    void everyOtherScreenClosesEveryEmptySlot() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();

        assertEquals(
                range(0, 53),
                RetroSlots.containerSlots(
                        fills, "TradeMarketOrderContainer", "Trade Market: Order", 54, null));
    }

    @Test
    void eachScreenWithWaresOrAChoiceGlazesOnlyItsButtons() {
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();

        Set<Integer> notBooks = new TreeSet<>(range(0, 26));
        notBooks.removeAll(range(11, 15));
        assertEquals(
                notBooks,
                RetroSlots.containerSlots(
                        fills, "CharacterInfoContainer", "Character Info", 27, null),
                "all but the skill point books");
        assertEquals(
                range(45, 53),
                RetroSlots.containerSlots(fills, "TradeMarketContainer", "Trade Market", 54, null),
                "the row under the listings");
        assertEquals(
                Set.of(8),
                RetroSlots.containerSlots(
                        fills, "TradeMarketRevealItemsContainer", "Reveal", 9, null),
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
            assertEquals(
                    aroundBench,
                    RetroSlots.containerSlots(fills, screen, "Title", 27, bench),
                    screen);
        }
        Set<Integer> frame = new TreeSet<>(range(0, 8));
        for (int row = 1; row < 6; row++) {
            frame.add(row * 9);
            frame.add(row * 9 + 8);
        }
        assertEquals(
                frame,
                RetroSlots.containerSlots(fills, null, "Merchant", 54, null),
                "the wares, down to the last row, are slots");
        assertEquals(
                Set.of(0, 1, 2, 4, 6, 7, 8),
                RetroSlots.containerSlots(fills, null, "Item Pedestal", 9, null),
                "its item and its sign are slots");
        Set<Integer> small = new TreeSet<>(range(0, 8));
        small.addAll(range(18, 26));
        small.addAll(Set.of(9, 17));
        for (String title : List.of("Housing Islands", "Powder Master: Remove")) {
            assertEquals(small, RetroSlots.containerSlots(fills, null, title, 27, null), title);
        }
        Set<Integer> guildButtons = new TreeSet<>();
        for (int row = 0; row < 5; row++) {
            guildButtons.add(row * 9);
            guildButtons.add(row * 9 + 1);
        }
        assertEquals(
                guildButtons,
                RetroSlots.containerSlots(fills, "GuildBankContainer", "Guild Bank", 45, null),
                "the two left columns; the rest is storage");
        assertEquals(
                Set.of(1, 10, 19, 28, 37),
                RetroSlots.containerSlots(fills, "MountFeederContainer", "Mount Feeder", 45, null),
                "the column between the mount's slots and the food's");
        Set<Integer> listings = new TreeSet<>(range(0, 53));
        for (int row = 1; row < 4; row++) {
            listings.removeAll(range(row * 9 + 1, row * 9 + 7));
        }
        assertEquals(
                listings,
                RetroSlots.containerSlots(fills, null, "Party Finder: Join", 54, null),
                "all but the listings");
        assertEquals(
                Set.of(13),
                RetroSlots.containerSlots(fills, "CraftingStationContainer", "Jeweling", 27, null),
                "the checkmark");
    }

    @Test
    void aPlayerTradeIsGlassOnlyOnItsCentreColumnAndCheckmarks() {
        // Through readableTitle, so its rule and the shipped row's pattern cannot drift apart.
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        for (int spaces : List.of(4, 14)) {
            String title =
                    RetroContainerTitles.readableTitle(
                            Component.literal("Csabi15" + " ".repeat(spaces) + "Wenweia"));
            assertEquals(
                    Set.of(4, 5, 13, 21, 22),
                    RetroSlots.containerSlots(fills, null, title, 27, null),
                    spaces + " spaces");
        }
        assertEquals(
                range(0, 26),
                RetroSlots.containerSlots(
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
            assertEquals(
                    Set.of(),
                    RetroSlots.containerSlots(fills, screen[0], screen[1], 54, null),
                    screen[1]);
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
                        Set.of(),
                        Set.of(7, 9),
                        Set.of(9, 10, 11, 12),
                        List.of());

        assertEquals(bits(30, 31), panels.picked());
        assertEquals(bits(31), panels.unmovable(), "the compass is fixed; the ring slot is not");
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
                        Set.of(9),
                        Set.of(),
                        Set.of(9, 10, 11, 12),
                        List.of());

        assertEquals(bits(9), panels.picked(), "the chest's slot 9 alone: the player's has no row");
        assertEquals(bits(9), panels.unmovable());
    }

    @Test
    void aSlotWhollyUnderAnEditIsInertWhetherPickedOrNot() {
        Panels panels =
                RetroSlots.panelsOf(
                        List.of(new SlotKey(1, 1, false, 98, 18), new SlotKey(2, 2, false, 8, 8)),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        List.of(new Rect(97, 17, 74, 36)));

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
