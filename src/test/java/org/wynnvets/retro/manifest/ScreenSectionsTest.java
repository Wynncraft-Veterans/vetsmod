package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.wynnvets.retro.manifest.ManifestFixtures.parseTextures;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.ScreenSections.SlotFill;
import org.wynnvets.retro.manifest.ScreenSections.SlotValue;

/**
 * Tests for the parser's reading of {@link ScreenSections}: the container and glyph titles,
 * the slot fills and values and the hidden inventory effects, on small fixtures.
 */
class ScreenSectionsTest {

    @Test
    void aSlotFillFallbackIsTrueOrFalse() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                "slot_fills:\n  - {title: 'a', slots: all, fallback: 'yes'}\n"));
    }

    @Test
    void glyphTitlesAreKeyedByCodePointAndAMalformedOneFailsTheParse() {
        assertEquals(
                Map.of(0xE006, "Merchant"),
                parseTextures("glyph_titles:\n  'E006': 'Merchant'\n").screens().glyphTitles());
        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("glyph_titles:\n  'Merchant': 'E006'\n"));
        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("glyph_titles:\n  'E006': ''\n"));
    }

    @Test
    void containerTitlesReadATitleOrKeepInFileOrder() {
        RetroManifest manifest =
                parseTextures(
                        """
                        container_titles:
                          'AccountBankContainer': 'Account Bank'
                          'Blacksmith.*': keep
                        """);

        assertEquals(2, manifest.screens().containerTitles().size());
        assertEquals(
                "AccountBankContainer",
                manifest.screens().containerTitles().get(0).containers().pattern());
        assertEquals("Account Bank", manifest.screens().containerTitles().get(0).title());
        assertEquals(RetroManifest.KEEP, manifest.screens().containerTitles().get(1).title());

        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("container_titles:\n  'X': ''\n"),
                "an empty title");
    }

    @Test
    void slotFillsReadPlayerAndContainerRows() {
        RetroManifest manifest =
                parseTextures(
                        """
                        slot_fills:
                          - {player: wynntils}
                          - {container: 'TradeMarketContainer', rows: [5]}
                          - {container: 'BankContainer', slots: outside_bounds}
                          - {title: '.+ Rewards', cols: [0, 8], slots: [3, '45-47']}
                          - {container: 'Sell', slots: all, except: [22, '28-29']}
                          - {title: '.*', slots: all, unless: 'Chest|.+Bank'}
                        """);

        assertEquals(
                List.of(
                        new SlotFill.PlayerSlots(),
                        new SlotFill.ContainerSlots(
                                null, null, Set.of(5), Set.of(), Set.of(), false, false, Set.of(),
                                null, false),
                        new SlotFill.ContainerSlots(
                                null, null, Set.of(), Set.of(), Set.of(), true, false, Set.of(),
                                null, false),
                        new SlotFill.ContainerSlots(
                                null,
                                null,
                                Set.of(),
                                Set.of(0, 8),
                                Set.of(3, 45, 46, 47),
                                false,
                                false,
                                Set.of(),
                                null,
                                false),
                        new SlotFill.ContainerSlots(
                                null,
                                null,
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                false,
                                true,
                                Set.of(22, 28, 29),
                                null,
                                false),
                        new SlotFill.ContainerSlots(
                                null, null, Set.of(), Set.of(), Set.of(), false, true, Set.of(),
                                null, false)),
                manifest.screens().slotFills().stream()
                        .map(ManifestFixtures::withoutPatterns)
                        .toList());
        SlotFill.ContainerSlots market =
                (SlotFill.ContainerSlots) manifest.screens().slotFills().get(1);
        assertEquals("TradeMarketContainer", market.containers().pattern());
        assertNull(market.titles());
        assertNull(market.unless());
        SlotFill.ContainerSlots rewards =
                (SlotFill.ContainerSlots) manifest.screens().slotFills().get(3);
        assertNull(rewards.containers());
        assertEquals(".+ Rewards", rewards.titles().pattern());
        SlotFill.ContainerSlots rest =
                (SlotFill.ContainerSlots) manifest.screens().slotFills().get(5);
        assertEquals("Chest|.+Bank", rest.unless().pattern());
    }

    @Test
    void aMalformedSlotFillFailsTheParse() {
        for (String row :
                List.of(
                        "{player: everyone}",
                        "{player: wynntils, rows: [1]}",
                        "{rows: [1]}",
                        "{container: 'A', title: 'B', rows: [1]}",
                        "{container: 'A'}",
                        "{container: 'A', rows: [6]}",
                        "{container: 'A', cols: [9]}",
                        "{container: 'A', slots: [54]}",
                        "{container: 'A', slots: ['53-45']}",
                        "{container: 'A', slots: some}",
                        "{container: 'A', slots: all, unless: '('}",
                        "{container: 'A', slots: ['4 5']}",
                        "{container: 'A', slots: everything}",
                        "{container: 'A', row: [1]}",
                        "{container: 'A(', rows: [1]}",
                        "{container: 'A', except: [1]}",
                        "{container: 'A', slots: all, except: [54]}",
                        "{container: 'A', slots: all, except: everything}",
                        "{player: wynntils, except: [1]}")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> parseTextures("slot_fills:\n  - " + row + "\n"),
                    row);
        }
    }

    @Test
    void hiddenInventoryEffectsArePatterns() {
        RetroManifest manifest =
                parseTextures("hidden_inventory_effects:\n  - 'minecraft:(haste|speed)'\n");

        assertEquals(
                List.of("minecraft:(haste|speed)"),
                manifest.screens().hiddenInventoryEffects().stream()
                        .map(Pattern::pattern)
                        .toList());
    }

    @Test
    void aSlotValueRowNamesAContainerAnItemAndALoreGroup() {
        SlotValue row =
                parseTextures("slot_values:\n  - {container: 'A', name: 'B', lore: 'c (d)'}\n")
                        .screens()
                        .slotValues()
                        .get(0);
        assertEquals("A", row.containers().pattern());
        assertEquals("B", row.name());
        assertEquals("c (d)", row.lore().pattern());

        for (String bad :
                List.of(
                        "{name: 'B', lore: 'c (d)'}",
                        "{container: 'A', lore: 'c (d)'}",
                        "{container: 'A', name: 'B'}",
                        "{container: 'A', name: 'B', lore: 'no group'}",
                        "{container: 'A', name: 'B', lore: 'c (d)', slot: 31}")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> parseTextures("slot_values:\n  - " + bad + "\n"),
                    bad);
        }
    }
}
