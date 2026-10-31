package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.manifest.ScreenSections.SlotValue;

/**
 * Tests for {@link RetroSlotValues}' row logic: which {@code slot_values} row an item on a
 * container has, and what it reads from the lore. Drawing the value needs a running client and is
 * not tested.
 */
class RetroSlotValuesTest {

    @Test
    void aSlotValueComesFromTheNamedItemsLore() throws IOException {
        List<SlotValue> rows = RetroManifest.load().screens().slotValues();
        SlotValue row = RetroSlotValues.valueRow(rows, "TradeMarketBuyContainer", "Set Amount");
        assertNotNull(row);
        assertNotNull(RetroSlotValues.valueRow(rows, "TradeMarketSellContainer", "Set Amount"));
        assertNotNull(RetroSlotValues.valueRow(rows, "TradeMarketOrderContainer", "Set Amount"));
        assertNull(
                RetroSlotValues.valueRow(rows, "TradeMarketBuyContainer", "Set Price"),
                "another item");
        assertNull(
                RetroSlotValues.valueRow(rows, "AccountBankContainer", "Set Amount"),
                "another screen");

        // The Set Amount dump's lore, with the section signs Wynncraft leaves in the text.
        assertEquals(
                "20",
                RetroSlotValues.value(
                        row,
                        List.of(
                                "\u00A77Sets the amount of this item",
                                "\u00A77you plan to buy",
                                " ",
                                "\u00A72Current: 20",
                                " ",
                                "\u00A77 Click to set")));
        assertEquals("1,024", RetroSlotValues.value(row, List.of("\u00A72Current: 1,024")));
        assertNull(RetroSlotValues.value(row, List.of("\u00A77you plan to buy")), "no such line");
        assertNull(RetroSlotValues.value(row, List.of()), "no lore");
    }
}
