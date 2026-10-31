package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.ManifestFixtures;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.manifest.ScreenSections.ContainerTitle;

/**
 * Tests for {@link RetroContainerTitles}' decision on real {@link Component} titles: the
 * {@code container_titles} row, else the readable title, else Wynncraft's label. The titles are
 * built like Wynncraft's: glyph runs in its {@code interface} and {@code space} fonts beside text
 * in the default font; which screens keep the player inventory's label; and which container
 * texture a screen is drawn on. Drawing the labels needs Wynntils' events and is not tested.
 */
class RetroContainerTitlesTest {

    /** Wynncraft's account bank title: a space glyph, the bank art, a space glyph, its title. */
    private static final String BANK_GLYPHS = "󏿰󏽨";

    private static MutableComponent inFont(String text, String font) {
        return Component.literal(text)
                .withStyle(
                        style ->
                                style.withFont(
                                        new FontDescription.Resource(
                                                Identifier.withDefaultNamespace(font))));
    }

    /** The {@code container_titles} rows of a fixture. */
    private static List<ContainerTitle> rows(String yaml) {
        return ManifestFixtures.parseTextures(yaml).screens().containerTitles();
    }

    @Test
    void glyphRunsInOtherFontsAreDropped() {
        Component title =
                Component.empty()
                        .append(inFont(BANK_GLYPHS, "interface"))
                        .append(inFont("󏿸", "space"))
                        .append(Component.literal("Returners: Members"));

        assertEquals("Returners: Members", RetroContainerTitles.readableTitle(title));
    }

    @Test
    void aPlayerTradesTwoNamesKeepTheSpacesBetweenThem() {
        assertEquals(
                "Csabi15              Wenweia",
                RetroContainerTitles.readableTitle(
                        Component.literal(" Csabi15              Wenweia ")),
                "each name over its own side");
        assertEquals(
                "Item Pedestal",
                RetroContainerTitles.readableTitle(Component.literal("Item   Pedestal")));
        assertEquals(
                "A Daily Reward",
                RetroContainerTitles.readableTitle(Component.literal("A    Daily    Reward")),
                "more than two words is no trade");
    }

    @Test
    void aTitleOfGlyphsAloneHasNothingReadable() {
        assertNull(RetroContainerTitles.readableTitle(inFont(BANK_GLYPHS, "interface")));
        // Private-use and unassigned characters in the default font are glyphs too.
        assertNull(RetroContainerTitles.readableTitle(Component.literal(BANK_GLYPHS)));
        assertNull(RetroContainerTitles.readableTitle(Component.literal(" - ")));
    }

    @Test
    void glyphsFormattingCodesAndExtraSpacesAreStrippedFromText() {
        Component title =
                Component.literal("   §6Daily󏿰   Rewards ").withStyle(ChatFormatting.DARK_GRAY);

        assertEquals("Daily Rewards", RetroContainerTitles.readableTitle(title));
        // U+0378 is unassigned inside the BMP, which PillCodec.isCustomGlyph would keep.
        assertEquals(
                "Bank",
                RetroContainerTitles.readableTitle(Component.literal("Bank" + (char) 0x0378)),
                "an unassigned character is dropped wherever it is");
    }

    @Test
    void theFirstMatchingRowDecidesAndKeepKeepsWynncraftsLabel() {
        List<ContainerTitle> rows =
                rows(
                        """
                        container_titles:
                          'AccountBankContainer': 'Account Bank'
                          'Account.*': 'Not this one'
                          'Blacksmith.*': keep
                        """);
        Component text = Component.literal("Blacksmith");

        assertEquals(
                "Account Bank",
                RetroContainerTitles.title(
                        rows, Map.of(), "AccountBankContainer", inFont(BANK_GLYPHS, "x")));
        assertNull(
                RetroContainerTitles.title(rows, Map.of(), "BlacksmithSellContainer", text),
                "keep, even over a readable title");
    }

    @Test
    void withoutARowTheReadableTitleOrNothingIsDrawn() {
        List<ContainerTitle> rows = rows("container_titles:\n  'AccountBankContainer': 'Bank'\n");

        assertEquals(
                "Returners: Members",
                RetroContainerTitles.title(
                        rows,
                        Map.of(),
                        "GuildMemberListContainer",
                        Component.literal("Returners: Members")));
        assertEquals(
                "Loot Chest",
                RetroContainerTitles.title(rows, Map.of(), null, Component.literal("Loot Chest")),
                "a screen Wynntils does not name");
        assertNull(
                RetroContainerTitles.title(rows, Map.of(), null, inFont(BANK_GLYPHS, "interface")),
                "nothing readable: Wynncraft's label stays");
        assertEquals(
                "Returners: Members",
                RetroContainerTitles.title(
                        rows,
                        Map.of(),
                        "AccountBankContainerX",
                        Component.literal("Returners: Members")),
                "rows match the whole class name");
    }

    @Test
    void aTitleOfGlyphsTakesItsGlyphsRowBelowARowAndAReadableTitle() {
        List<ContainerTitle> rows = rows("container_titles:\n  'AccountBankContainer': 'Bank'\n");
        Map<Integer, String> glyphs = Map.of(0xE01B, "Powder Master: Remove");

        assertEquals(
                "Powder Master: Remove",
                RetroContainerTitles.title(
                        rows, glyphs, null, inFont("\uDB3F\uDFDC\uE01B", "interface")));
        assertEquals(
                "Bank",
                RetroContainerTitles.title(
                        rows, glyphs, "AccountBankContainer", inFont("\uE01B", "interface")));
        assertEquals(
                "Merchant",
                RetroContainerTitles.title(rows, glyphs, null, Component.literal("Merchant")));
        assertNull(
                RetroContainerTitles.title(rows, glyphs, null, inFont("\uE006", "interface")),
                "a glyph without a row");
    }

    @Test
    void aGlyphCountsInTheInterfaceFontAlone() {
        Map<Integer, String> glyphs = Map.of(0xE006, "Merchant");

        assertEquals(
                "Merchant", RetroContainerTitles.glyphTitle(glyphs, inFont("\uE006", "interface")));
        assertNull(
                RetroContainerTitles.glyphTitle(glyphs, inFont("\uE006", "offset/store/front")),
                "the store's ribbon, the same code point");
    }

    @Test
    void theStoreKeepsWynncraftsLabel() throws IOException {
        RetroManifest manifest = RetroManifest.load();

        for (String store : List.of("StoreContainer", "StorePageContainer")) {
            assertNull(
                    RetroContainerTitles.title(
                            manifest.screens().containerTitles(),
                            manifest.screens().glyphTitles(),
                            store,
                            inFont("\uE006", "interface")),
                    store);
        }
    }

    @Test
    void theShippedGlyphRowsAreTheMerchantAndThePowderMaster() throws IOException {
        Map<Integer, String> glyphs = RetroManifest.load().screens().glyphTitles();

        assertEquals(
                "Merchant", RetroContainerTitles.glyphTitle(glyphs, inFont("\uE006", "interface")));
        assertEquals(
                "Powder Master: Infuse",
                RetroContainerTitles.glyphTitle(glyphs, inFont("\uE01A", "interface")));
        assertEquals(
                "Powder Master: Remove",
                RetroContainerTitles.glyphTitle(glyphs, inFont("\uE01B", "interface")));
        assertEquals(
                "Powder Master: Upgrade",
                RetroContainerTitles.glyphTitle(glyphs, inFont("\uE01C", "interface")));
        assertEquals(
                "Party Finder: Join",
                RetroContainerTitles.glyphTitle(glyphs, inFont("\uE03E", "interface")));
        assertEquals(
                "Jeweling",
                RetroContainerTitles.glyphTitle(glyphs, inFont("\uE053\uF044", "interface")));
        assertEquals(
                "Woodworking",
                RetroContainerTitles.glyphTitle(glyphs, inFont("\uE053\uF048", "interface")));
        assertNull(
                RetroContainerTitles.glyphTitle(glyphs, inFont("\uE003", "interface")),
                "Character Info");
    }

    @Test
    void theInventoryLabelIsDrawnOnlyWhereRetroDrewTheContainers() {
        Component inventory = Component.literal("Inventory");
        RetroContainerTitles.Relabelled bank =
                new RetroContainerTitles.Relabelled(
                        Component.literal("Account Bank"),
                        "AccountBankContainer",
                        "Account Bank",
                        null,
                        false);
        RetroContainerTitles.Relabelled contentBook =
                new RetroContainerTitles.Relabelled(
                        Component.literal("Content Book"),
                        "ContentBookContainer",
                        "Content Book",
                        null,
                        true);

        assertSame(inventory, RetroContainerTitles.inventoryLabel(bank, inventory));
        assertEquals(
                "",
                RetroContainerTitles.inventoryLabel(null, inventory).getString(),
                "a screen that keeps Wynncraft's label keeps its art there too");
        assertEquals(
                "",
                RetroContainerTitles.inventoryLabel(contentBook, inventory).getString(),
                "a full-screen view holds Wynncraft's buttons there, not the inventory");
    }

    @Test
    void aScreenThatKeepsWynncraftsLabelIsDrawnOnTheCopyOfWynncraftsTexture() {
        Identifier chest = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
        Function<String, Optional<String>> copies =
                textureId ->
                        textureId.equals("minecraft:gui/container/generic_54")
                                ? Optional.of("vetsmod:gui/container/wynncraft/generic_54")
                                : Optional.empty();
        RetroContainerTitles.Relabelled bank =
                new RetroContainerTitles.Relabelled(
                        Component.literal("Account Bank"),
                        "AccountBankContainer",
                        "Account Bank",
                        null,
                        false);

        assertEquals(
                Identifier.fromNamespaceAndPath(
                        "vetsmod", "textures/gui/container/wynncraft/generic_54.png"),
                RetroContainerTitles.background(null, chest, copies));
        assertSame(
                chest,
                RetroContainerTitles.background(bank, chest, copies),
                "retro drew the label: the vanilla chest the pack copied in");
        Identifier dispenser =
                Identifier.withDefaultNamespace("textures/gui/container/dispenser.png");
        assertSame(
                dispenser,
                RetroContainerTitles.background(null, dispenser, copies),
                "no copy: the texture as it is");
    }
}
