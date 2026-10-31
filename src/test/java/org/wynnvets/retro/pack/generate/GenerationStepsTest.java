package org.wynnvets.retro.pack.generate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.rendering.image.TestImages.filled;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.image.Pixels;
import org.wynnvets.rendering.image.PngCodec;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.RetroReport;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.RetroTestState;
import org.wynnvets.retro.manifest.ManifestFixtures;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.pack.MemoryPack;
import org.wynnvets.retro.pack.PackFiles;
import org.wynnvets.retro.rules.ModelStub;

/**
 * Tests for the generation steps, {@link GeneratedItemWriter} and {@link StubWriter}, each step run
 * alone or in {@link RetroPackGenerator#STEPS}' order over rule-file fixtures and packs held in
 * memory: one for vanilla, the only source, and one for Wynncraft, above it. Images go through
 * Minecraft's own PNG decoder, as in play.
 */
class GenerationStepsTest {

    private static final String SOURCES = "sources:\n  '.*': [vanilla]\n";

    private static final int GREY = 0xFF808080;
    private static final byte[] GREY_PNG = PngCodec.png(filled(2, 2, GREY));
    private static final byte[] MCMETA = "{\"animation\":{}}".getBytes(StandardCharsets.UTF_8);

    private final MemoryPack vanilla = new MemoryPack("vanilla");
    private final MemoryPack wynncraft = new MemoryPack("wynncraft");

    @AfterEach
    void clearTheRecordedReport() {
        RetroTestState.clear();
    }

    private GenerationContext gen(String textures, String ingredients) {
        return new GenerationContext(
                ManifestFixtures.parse(SOURCES + textures, ingredients),
                List.of(vanilla, wynncraft),
                1,
                ProgrammerArtSources.open(null, vanilla),
                new PackOutput());
    }

    private PackOutput run(GenerationStep step, String textures) {
        return run(step, textures, "");
    }

    private PackOutput run(GenerationStep step, String textures, String ingredients) {
        GenerationContext gen = gen(textures, ingredients);
        step.run(gen);
        return gen.out;
    }

    private static byte[] file(PackOutput out, String file) {
        return out.files.get(Identifier.parse(file));
    }

    private static String text(PackOutput out, String file) {
        byte[] bytes = file(out, file);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static Pixels image(PackOutput out, String file) throws IOException {
        return PngCodec.decode(file(out, file));
    }

    private static int count(PackOutput out, Count count) {
        return out.counts.getOrDefault(count, 0);
    }

    // ----- The order -----

    @Test
    void theStepsRunInTheirStatedOrder() {
        assertEquals(
                List.of(
                        TextureStep.class,
                        WynncraftCopyStep.class,
                        SourceCopyStep.class,
                        BlankTextureStep.class,
                        WynntilsTextureStep.class,
                        AccessoryTierStep.class,
                        ModelStubStep.class,
                        BlockModelStep.class,
                        EquipmentStep.class,
                        ItemDefinitionStep.class,
                        OutlinedIconStep.class),
                RetroPackGenerator.STEPS.stream().map(GenerationStep::getClass).toList());
    }

    @Test
    void everyStepThatWritesWhatAStubNamesComesBeforeTheStubs() {
        List<Class<?>> order =
                RetroPackGenerator.STEPS.stream().<Class<?>>map(GenerationStep::getClass).toList();
        int firstStub = order.indexOf(ModelStubStep.class);
        for (Class<?> writer :
                List.of(
                        TextureStep.class,
                        WynncraftCopyStep.class,
                        SourceCopyStep.class,
                        BlankTextureStep.class,
                        WynntilsTextureStep.class,
                        AccessoryTierStep.class)) {
            assertTrue(order.indexOf(writer) < firstStub, writer.getSimpleName());
        }
        assertTrue(firstStub < order.indexOf(BlockModelStep.class));
    }

    /** An ingredient that parents a generated accessory, and that accessory's art. */
    private GenerationContext ingredientOverAnAccessory() {
        wynncraft
                .texture("minecraft:wynn/ring", GREY_PNG)
                .file("minecraft:models/item/wynn/ingredient/nose_ring.json", "{}");
        return gen(
                """
                accessory_tiers:
                  outline_below_luma: 64
                  empty_outline: '#555555'
                  art:
                    'RING': 'minecraft:wynn/ring'
                  tiers:
                    - {name: iron, ramp: ['#000000', '#FFFFFF']}
                """,
                """
                ingredients:
                  nose_ring: 'vetsmod:item/retro/accessory/ring_iron'
                """);
    }

    @Test
    void aStubResolvesTheAccessoryAnEarlierStepWrote() {
        GenerationContext gen = ingredientOverAnAccessory();

        new AccessoryTierStep().run(gen);
        new ModelStubStep().run(gen);

        assertEquals(
                "{\"parent\":\"vetsmod:item/retro/accessory/ring_iron\"}",
                text(gen.out, "minecraft:models/item/wynn/ingredient/nose_ring.json"));
        assertEquals(List.of(), gen.out.skipped);
    }

    @Test
    void theSameStubBeforeTheAccessoryIsSkipped() {
        // What the order prevents.
        GenerationContext gen = ingredientOverAnAccessory();

        new ModelStubStep().run(gen);
        new AccessoryTierStep().run(gen);

        assertNull(file(gen.out, "minecraft:models/item/wynn/ingredient/nose_ring.json"));
        assertEquals(
                List.of(
                        "the stub for minecraft:item/wynn/ingredient/nose_ring needs"
                                + " vetsmod:models/item/retro/accessory/ring_iron.json, which is"
                                + " missing"),
                gen.out.skipped);
    }

    @Test
    void aBlankTextureReplacesTheTextureRulesFile() throws IOException {
        // A later step's file wins at the same path.
        vanilla.texture("minecraft:block/a", GREY_PNG);
        wynncraft.texture("minecraft:block/a", GREY_PNG);
        GenerationContext gen =
                gen(
                        """
                        same_name:
                          - 'minecraft:block/.*'
                        blank_textures:
                          - 'minecraft:block/a'
                        """,
                        "");

        for (GenerationStep step : RetroPackGenerator.STEPS) step.run(gen);

        assertEquals(0, image(gen.out, "minecraft:textures/block/a.png").get(0, 0));
    }

    @Test
    void aFullGenerationOverEmptyPacksThrowsNothingAndRecordsItsReport() throws IOException {
        Map<Identifier, byte[]> files =
                new RetroPackGenerator(List.of(vanilla, wynncraft), 1, null, vanilla).generate();

        assertEquals(Map.of(), files, "every row has nothing to read");
        RetroReport report = RetroModeManager.lastReport();
        assertEquals("wynncraft", report.wynncraftPack());
        assertEquals(List.of(RetroSource.VANILLA), report.sources());
        assertFalse(report.skipped().isEmpty(), "every missing thing is a line");
    }

    // ----- TextureStep -----

    @Test
    void aSameNameCopyTakesTheSourcesPngAndTheMcmetaBesideIt() {
        vanilla.texture("minecraft:block/a", GREY_PNG)
                .file("minecraft:textures/block/a.png.mcmeta", MCMETA);
        wynncraft.texture("minecraft:block/a", PngCodec.png(filled(1, 1, 0xFF112233)));

        PackOutput out =
                run(
                        new TextureStep(),
                        """
                        same_name:
                          - 'minecraft:block/.*'
                        """);

        assertArrayEquals(GREY_PNG, file(out, "minecraft:textures/block/a.png"));
        assertArrayEquals(MCMETA, file(out, "minecraft:textures/block/a.png.mcmeta"));
        assertEquals(1, count(out, Count.SAME_NAME));
        assertEquals(1, count(out, Count.MCMETA));
        assertTrue(out.decisions.containsKey("minecraft:block/a"));
    }

    @Test
    void aVanillaCopyOfATextureWynncraftDoesNotShipIsLeftOut() {
        vanilla.texture("minecraft:block/a", GREY_PNG);

        PackOutput out =
                run(
                        new TextureStep(),
                        """
                        same_name:
                          - 'minecraft:block/.*'
                        """);

        assertEquals(Map.of(), out.files);
        assertEquals(1, count(out, Count.UNCHANGED));
    }

    @Test
    void anAliasCopiesItsTargetUnderTheTexturesOwnId() {
        vanilla.texture("minecraft:block/b", GREY_PNG);
        wynncraft.texture("minecraft:block/a", GREY_PNG);

        PackOutput out =
                run(
                        new TextureStep(),
                        """
                        aliases:
                          'minecraft:block/a': 'minecraft:block/b'
                        """);

        assertArrayEquals(GREY_PNG, file(out, "minecraft:textures/block/a.png"));
        assertEquals(1, count(out, Count.ALIASES));
    }

    @Test
    void aTextureNoRuleReplacesCountsOnlyWhenWynncraftShipsIt() {
        vanilla.texture("minecraft:block/vanillas", GREY_PNG);
        wynncraft.texture("minecraft:block/wynncrafts", GREY_PNG);

        assertEquals(1, count(run(new TextureStep(), ""), Count.NO_RULE));
    }

    @Test
    void aFileThatCannotBeReadCostsOnlyItsOwnRow() {
        vanilla.unreadable("minecraft:textures/block/a.png").texture("minecraft:block/b", GREY_PNG);
        wynncraft.texture("minecraft:block/a", GREY_PNG).texture("minecraft:block/b", GREY_PNG);

        PackOutput out =
                run(
                        new TextureStep(),
                        """
                        same_name:
                          - 'minecraft:block/.*'
                        """);

        assertEquals(
                List.of(
                        "could not read minecraft:block/a from vanilla"
                                + " (java.io.IOException: cannot read)"),
                out.skipped);
        assertArrayEquals(GREY_PNG, file(out, "minecraft:textures/block/b.png"));
    }

    @Test
    void aTintMultipliesItsSourceAndRecordsItsFactors() throws IOException {
        vanilla.texture("minecraft:block/a", GREY_PNG)
                .file("minecraft:textures/block/a.png.mcmeta", MCMETA);
        wynncraft.texture("minecraft:block/a", GREY_PNG);

        PackOutput out =
                run(
                        new TextureStep(),
                        """
                        tints:
                          'minecraft:block/a':
                            tint: '#FF0000'
                        """);

        assertEquals(0xFF800000, image(out, "minecraft:textures/block/a.png").get(0, 0));
        assertArrayEquals(MCMETA, file(out, "minecraft:textures/block/a.png.mcmeta"));
        assertEquals(List.of(1.0, 0.0, 0.0), out.tintFactors.get("minecraft:block/a"));
        assertEquals(1, count(out, Count.TINTS));
    }

    @Test
    void aLikeTintMakesTheChangeOfTheRowItNames() throws IOException {
        vanilla.texture("minecraft:block/a", GREY_PNG)
                .texture("minecraft:block/b", PngCodec.png(filled(1, 1, 0xFF404040)));
        wynncraft.texture("minecraft:block/a", GREY_PNG).texture("minecraft:block/b", GREY_PNG);

        PackOutput out =
                run(
                        new TextureStep(),
                        """
                        tints:
                          'minecraft:block/a':
                            tint: '#FF0000'
                          'minecraft:block/b':
                            tint: {like: 'minecraft:block/a'}
                        """);

        assertEquals(0xFF400000, image(out, "minecraft:textures/block/b.png").get(0, 0));
        assertEquals(List.of(1.0, 0.0, 0.0), out.tintFactors.get("minecraft:block/b"));
    }

    @Test
    void aTintWhoseSourceIsMissingLeavesWynncraftsAndIsReported() {
        wynncraft.texture("minecraft:block/a", GREY_PNG);

        PackOutput out =
                run(
                        new TextureStep(),
                        """
                        tints:
                          'minecraft:block/a':
                            tint: '#FF0000'
                        """);

        assertEquals(Map.of(), out.files);
        assertEquals(
                List.of("no source has a texture the alias or tint for minecraft:block/a reads"),
                out.skipped);
    }

    @Test
    void aTintThatCannotBeMeasuredIsReported() {
        vanilla.texture("minecraft:block/a", PngCodec.png(Pixels.blank(1, 1)));
        wynncraft.texture("minecraft:block/a", GREY_PNG);

        PackOutput out =
                run(
                        new TextureStep(),
                        """
                        tints:
                          'minecraft:block/a':
                            tint: auto
                        """);

        assertEquals(Map.of(), out.files);
        assertEquals(1, out.skipped.size());
        assertTrue(out.skipped.get(0).startsWith("the tint for minecraft:block/a ("));
    }

    private void imageEditFixture() {
        Pixels art = filled(2, 2, GREY);
        art.set(1, 0, 0xFF112233);
        vanilla.texture("minecraft:block/a", PngCodec.png(art));
        wynncraft.texture("minecraft:block/a", GREY_PNG);
    }

    @Test
    void anImageEditPaintsItsSamplesColourAndIsRecorded() throws IOException {
        imageEditFixture();

        PackOutput out =
                run(
                        new TextureStep(),
                        """
                        same_name:
                          - 'minecraft:block/.*'
                        image_edits:
                          'minecraft:block/a':
                            - fill: [0, 0, 1, 1]
                              sample: [1, 0]
                              palette: ['#808080']
                        """);

        assertEquals(0xFF112233, image(out, "minecraft:textures/block/a.png").get(0, 0));
        assertEquals(List.of(new Rect(0, 0, 1, 1)), out.imageEdits.get("minecraft:block/a"));
        assertEquals(1, count(out, Count.IMAGE_EDITS));
    }

    @Test
    void anImageEditItsPaletteRefusesLeavesTheCopyAsItIs() throws IOException {
        imageEditFixture();

        PackOutput out =
                run(
                        new TextureStep(),
                        """
                        same_name:
                          - 'minecraft:block/.*'
                        image_edits:
                          'minecraft:block/a':
                            - fill: [0, 0, 1, 1]
                              sample: [1, 0]
                              palette: ['#000001']
                            - fill: [0, 0, 1, 1]
                              sample: [9, 9]
                              palette: ['#808080']
                        """);

        assertEquals(GREY, image(out, "minecraft:textures/block/a.png").get(0, 0));
        assertFalse(out.imageEdits.containsKey("minecraft:block/a"));
        assertEquals(
                List.of(
                        "image_edits for minecraft:block/a: fill [0, 0, 1, 1] leaves the texture"
                                + " or holds a colour outside its palette, so the texture's layout"
                                + " has changed",
                        "image_edits for minecraft:block/a: fill [0, 0, 1, 1] samples a pixel"
                                + " outside the texture"),
                out.skipped);
    }

    @Test
    void anImageEditOfAKeptOrMissingTextureIsReported() {
        wynncraft.texture("minecraft:block/a", GREY_PNG);

        PackOutput out =
                run(
                        new TextureStep(),
                        """
                        keep_wynncraft:
                          - 'minecraft:block/a'
                        image_edits:
                          'minecraft:block/a':
                            - fill: [0, 0, 1, 1]
                              sample: [0, 0]
                              palette: ['#808080']
                          'minecraft:block/gone':
                            - fill: [0, 0, 1, 1]
                              sample: [0, 0]
                              palette: ['#808080']
                        """);

        assertEquals(
                List.of(
                        "image_edits for minecraft:block/a: it is kept (KEEP_WYNNCRAFT)",
                        "image_edits for minecraft:block/gone: no pack has it"),
                out.skipped);
    }

    // ----- Copies -----

    @Test
    void aWynncraftCopyTakesThePngAndTheMcmetaOfOnePack() {
        vanilla.file("minecraft:textures/block/a.png.mcmeta", "{\"vanillas\":1}");
        wynncraft
                .texture("minecraft:block/a", GREY_PNG)
                .file("minecraft:textures/block/a.png.mcmeta", MCMETA);

        PackOutput out =
                run(
                        new WynncraftCopyStep(),
                        """
                        wynncraft_copies:
                          'vetsmod:block/retro/a': 'minecraft:block/a'
                          'vetsmod:block/retro/b': 'minecraft:block/gone'
                        """);

        assertArrayEquals(GREY_PNG, file(out, "vetsmod:textures/block/retro/a.png"));
        assertArrayEquals(MCMETA, file(out, "vetsmod:textures/block/retro/a.png.mcmeta"));
        assertEquals(Set.of("vetsmod:block/retro/a"), out.wynncraftCopies);
        assertEquals(1, count(out, Count.WYNNCRAFT_COPIES));
        assertEquals(
                List.of("Wynncraft has no minecraft:block/gone to copy to vetsmod:block/retro/b"),
                out.skipped);
    }

    @Test
    void aSourceCopyTakesTheSourcesFilesAsTheyAre() {
        vanilla.texture("minecraft:block/a", GREY_PNG)
                .file("minecraft:textures/block/a.png.mcmeta", MCMETA);

        PackOutput out =
                run(
                        new SourceCopyStep(),
                        """
                        source_copies:
                          'vetsmod:block/retro/a': 'minecraft:block/a'
                          'vetsmod:block/retro/b': 'minecraft:block/gone'
                        """);

        assertArrayEquals(GREY_PNG, file(out, "vetsmod:textures/block/retro/a.png"));
        assertArrayEquals(MCMETA, file(out, "vetsmod:textures/block/retro/a.png.mcmeta"));
        assertEquals(1, count(out, Count.SOURCE_COPIES));
        assertEquals(
                List.of("no source has minecraft:block/gone to copy to vetsmod:block/retro/b"),
                out.skipped);
    }

    @Test
    void aSourceCopyWithARecipeIsInItsRowsColour() throws IOException {
        vanilla.texture("minecraft:item/a", GREY_PNG);

        PackOutput out =
                run(
                        new SourceCopyStep(),
                        """
                        recipes:
                          a: {art: 'minecraft:item/a', scale_from: '#808080'}
                        source_copies:
                          'vetsmod:item/retro/a': {a: '#FF0000'}
                        """);

        assertEquals(0xFFFF0000, image(out, "vetsmod:textures/item/retro/a.png").get(0, 0));
    }

    // ----- Textures drawn at their live size -----

    @Test
    void aBlankTextureIsTransparentAtItsLiveSize() throws IOException {
        wynncraft.texture("minecraft:block/a", PngCodec.png(filled(3, 5, GREY)));

        PackOutput out =
                run(
                        new BlankTextureStep(),
                        """
                        blank_textures:
                          - 'minecraft:block/a'
                          - 'minecraft:block/gone'
                        """);

        Pixels blank = image(out, "minecraft:textures/block/a.png");
        assertEquals(3, blank.width());
        assertEquals(5, blank.height());
        assertEquals(0, blank.get(2, 4));
        assertEquals(1, count(out, Count.BLANK_TEXTURES));
        assertEquals(List.of("no pack has minecraft:block/gone to blank"), out.skipped);
    }

    @Test
    void aWynntilsTextureDrawsItsFramesOneAboveTheOther() throws IOException {
        vanilla.texture("minecraft:gui/top", PngCodec.png(filled(3, 3, 0xFF111111)))
                .texture("minecraft:gui/bottom", PngCodec.png(filled(3, 3, 0xFF222222)));
        wynncraft.texture("wynntils:a", PngCodec.png(filled(4, 8, GREY)));

        PackOutput out =
                run(
                        new WynntilsTextureStep(),
                        """
                        wynntils_textures:
                          'wynntils:a':
                            - {source: 'minecraft:gui/top', border: 1}
                            - {source: 'minecraft:gui/bottom', border: 1}
                        """);

        Pixels drawn = image(out, "wynntils:textures/a.png");
        assertEquals(4, drawn.width());
        assertEquals(8, drawn.height());
        assertEquals(0xFF111111, drawn.get(0, 0));
        assertEquals(0xFF222222, drawn.get(0, 7));
        assertEquals(1, count(out, Count.WYNNTILS_TEXTURES));
    }

    @Test
    void aWynntilsTextureThatCannotBeDrawnIsReported() {
        wynncraft
                .texture("wynntils:odd", PngCodec.png(filled(4, 5, GREY)))
                .texture("wynntils:even", PngCodec.png(filled(4, 8, GREY)));
        vanilla.texture("minecraft:gui/a", GREY_PNG);

        PackOutput out =
                run(
                        new WynntilsTextureStep(),
                        """
                        wynntils_textures:
                          'wynntils:odd':
                            - {source: 'minecraft:gui/a', border: 0}
                            - {source: 'minecraft:gui/a', border: 0}
                          'wynntils:even':
                            - {source: 'minecraft:gui/gone', border: 0}
                          'wynntils:absent': blank
                        """);

        assertEquals(Map.of(), out.files);
        assertEquals(
                List.of(
                        "wynntils:odd is 5 pixels high, which 2 frames cannot share",
                        "no source has minecraft:gui/gone to draw wynntils:even",
                        "no pack has wynntils:absent to draw again"),
                out.skipped);
    }

    // ----- Generated items -----

    @Test
    void eachAccessoryTierAndTheEmptySlotIsAGeneratedItem() throws IOException {
        wynncraft.texture("minecraft:wynn/ring", GREY_PNG);

        PackOutput out =
                run(
                        new AccessoryTierStep(),
                        """
                        accessory_tiers:
                          outline_below_luma: 64
                          empty_outline: '#123456'
                          art:
                            'RING': 'minecraft:wynn/ring'
                            'BRACELET': 'minecraft:wynn/gone'
                          tiers:
                            - {name: gold, below: 50, ramp: ['#FF0000', '#FF0000']}
                            - {name: iron, ramp: ['#00FF00', '#00FF00']}
                        """);

        assertEquals(
                Set.of(
                        "vetsmod:retro/accessory/ring_empty",
                        "vetsmod:retro/accessory/ring_gold",
                        "vetsmod:retro/accessory/ring_iron"),
                out.generatedItems);
        assertEquals(3, count(out, Count.ACCESSORY_TIERS));
        assertEquals(
                0xFFFF0000,
                image(out, "vetsmod:textures/item/retro/accessory/ring_gold.png").get(0, 0));
        assertEquals(
                0xFF00FF00,
                image(out, "vetsmod:textures/item/retro/accessory/ring_iron.png").get(0, 0));
        assertEquals(
                0xFF123456,
                image(out, "vetsmod:textures/item/retro/accessory/ring_empty.png").get(0, 0));
        assertEquals(
                List.of("Wynncraft has no minecraft:wynn/gone for its accessories"), out.skipped);
    }

    @Test
    void aGeneratedItemIsATextureAModelOfItAndADefinitionOfThat() {
        PackOutput out = new PackOutput();

        GeneratedItemWriter.write(out, "retro/a", filled(1, 1, GREY), Count.OUTLINED_ICONS);

        assertEquals(3, out.files.size());
        assertTrue(out.files.containsKey(Identifier.parse("vetsmod:textures/item/retro/a.png")));
        assertEquals(
                "{\"parent\":\"minecraft:item/generated\","
                        + "\"textures\":{\"layer0\":\"vetsmod:item/retro/a\"}}",
                text(out, "vetsmod:models/item/retro/a.json"));
        assertEquals(
                "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"vetsmod:item/retro/a\"}}",
                text(out, "vetsmod:items/retro/a.json"));
        assertEquals(Set.of("vetsmod:retro/a"), out.generatedItems);
        assertEquals(1, count(out, Count.OUTLINED_ICONS));
    }

    @Test
    void anOutlinedIconGrowsByItsOutlineAndArtTheSourcesLackIsReported() throws IOException {
        vanilla.texture("minecraft:item/a", GREY_PNG);

        PackOutput out =
                run(
                        new OutlinedIconStep(),
                        """
                        outlined_icons:
                          - scale: 0.625
                            outline: {pixels: 1, colour: '#191919'}
                            art:
                              'SPEAR': 'minecraft:item/a'
                              'WAND': 'minecraft:item/gone'
                        """);

        Pixels icon = image(out, "vetsmod:textures/item/retro/icon/spear.png");
        assertEquals(4, icon.width());
        assertEquals(0xFF191919, icon.get(0, 1));
        assertEquals(GREY, icon.get(1, 1));
        assertEquals(Set.of("vetsmod:retro/icon/spear"), out.generatedItems);
        assertEquals(1, count(out, Count.OUTLINED_ICONS));
        assertEquals(List.of("nothing has minecraft:item/gone for an outlined icon"), out.skipped);
    }

    // ----- Stubs -----

    private static final String STICK_ROW =
            """
            item_models:
              'minecraft:item/wynn/x': 'minecraft:item/stick'
            """;

    @Test
    void anItemModelStubDrawsItsRowsArtOnItsTintProofParent() {
        wynncraft.file("minecraft:models/item/wynn/x.json", "{}");
        vanilla.texture("minecraft:item/stick", GREY_PNG);
        GenerationContext gen = gen(STICK_ROW, "");
        ModelStub.TintProof stub =
                (ModelStub.TintProof)
                        gen.modelRules.modelStub("minecraft:item/wynn/x").orElseThrow();
        vanilla.file(PackFiles.modelFile(stub.parentModel()).toString(), "{}")
                .texture(stub.blank(), GREY_PNG);

        new ModelStubStep().run(gen);

        assertEquals(
                "{\"parent\":\""
                        + stub.parentModel()
                        + "\",\"textures\":{\"art\":\"minecraft:item/stick\"}}",
                text(gen.out, "minecraft:models/item/wynn/x.json"));
        assertEquals(List.of("minecraft:item/wynn/x"), gen.out.modelIds);
        assertEquals(Set.of("minecraft:item/wynn/x"), gen.out.stubbed);
        assertEquals(1, count(gen.out, Count.ITEM_MODELS));
    }

    @Test
    void aStubNamingAMissingFileIsReportedAndNotWritten() {
        wynncraft.file("minecraft:models/item/wynn/x.json", "{}");

        PackOutput out = run(new ModelStubStep(), STICK_ROW);

        assertEquals(Map.of(), out.files);
        assertEquals(Set.of(), out.stubbed);
        assertEquals(List.of("minecraft:item/wynn/x"), out.modelIds, "still looked at");
        assertEquals(
                List.of(
                        "the stub for minecraft:item/wynn/x needs"
                                + " minecraft:textures/item/stick.png, which is missing"),
                out.skipped);
    }

    @Test
    void anIngredientStubNamesItsItemModelAndAStemWynncraftLacksIsReported() {
        wynncraft.file("minecraft:models/item/wynn/ingredient/egg.json", "{}");
        vanilla.file("minecraft:models/item/egg.json", "{}");

        PackOutput out =
                run(
                        new ModelStubStep(),
                        "",
                        """
                        ingredients:
                          egg: 'minecraft:item/egg'
                          gone: 'minecraft:item/egg'
                        """);

        assertEquals(
                "{\"parent\":\"minecraft:item/egg\"}",
                text(out, "minecraft:models/item/wynn/ingredient/egg.json"));
        assertEquals(1, count(out, Count.INGREDIENTS));
        assertEquals(List.of("Wynncraft has no ingredient model gone"), out.skipped);
    }

    private static final String MANA_ROW =
            ManifestFixtures.POTION_RECIPE
                    + """
                    potions:
                      mana: {colour: '#385DC6', avomod: potion_mana}
                    """;

    private static final String MANA_LIQUID = "vetsmod:item/retro/potion/mana";

    /** A potion model of Wynncraft's and one of Avomod's, and what their stubs name but art. */
    private void potionModels() {
        wynncraft.file("minecraft:models/item/wynn/potion/mana_full.json", "{}");
        vanilla.file("avomod2:models/item/potion_mana.json", "{}")
                .file("vetsmod:models/item/retro/tinted_potion.json", "{}")
                .texture("minecraft:item/potion", GREY_PNG)
                .texture("vetsmod:item/retro/blank", GREY_PNG);
    }

    /** The liquid's step, then the stubs', as {@link RetroPackGenerator#STEPS} runs them. */
    private PackOutput runPotions() {
        GenerationContext gen = gen(MANA_ROW, "");
        new SourceCopyStep().run(gen);
        new ModelStubStep().run(gen);
        return gen.out;
    }

    @Test
    void potionStubsShareOneTintedLiquid() throws IOException {
        potionModels();
        vanilla.texture("minecraft:item/potion_overlay", PngCodec.png(filled(1, 1, 0xFFFFFFFF)));

        PackOutput out = runPotions();

        assertEquals(
                0xFF385DC6, image(out, "vetsmod:textures/item/retro/potion/mana.png").get(0, 0));
        String stub =
                "{\"parent\":\"vetsmod:item/retro/tinted_potion\",\"textures\":{\"liquid\":\""
                        + MANA_LIQUID
                        + "\"}}";
        assertEquals(stub, text(out, "minecraft:models/item/wynn/potion/mana_full.json"));
        assertEquals(stub, text(out, "avomod2:models/item/potion_mana.json"));
        assertEquals(1, count(out, Count.SOURCE_COPIES));
        assertEquals(2, count(out, Count.POTIONS));
        assertEquals(List.of(), out.skipped);
    }

    @Test
    void aPotionWhoseLiquidIsMissingIsReportedPerStub() {
        potionModels();

        PackOutput out = runPotions();

        assertEquals(Map.of(), out.files);
        String liquidFile = "vetsmod:textures/item/retro/potion/mana.png";
        assertEquals(
                List.of(
                        "no source has minecraft:item/potion_overlay to copy to " + MANA_LIQUID,
                        "the stub for avomod2:item/potion_mana needs "
                                + liquidFile
                                + ", which is missing",
                        "the stub for minecraft:item/wynn/potion/mana_full needs "
                                + liquidFile
                                + ", which is missing"),
                out.skipped);
    }

    private static final String WALL_CANDLE_ROW =
            """
            block_models:
              'minecraft:block/torch': 'vetsmod:block/retro/candle'
            """;

    @Test
    void aBlockModelRowThatIsNoIdIsOneSkippedLineAndTheRestStillRun() {
        // ManifestYaml.literalId takes this key, and only Identifier refuses its capital.
        vanilla.file("minecraft:models/block/candle.json", "{}");

        PackOutput out =
                run(
                        new BlockModelStep(),
                        """
                        block_models:
                          'minecraft:block/Redstone_torch': 'minecraft:block/candle'
                          'minecraft:block/torch': 'minecraft:block/candle'
                        """);

        assertEquals(List.of("not a model id: minecraft:block/Redstone_torch"), out.skipped);
        assertEquals(
                "{\"parent\":\"minecraft:block/candle\"}",
                text(out, "minecraft:models/block/torch.json"));
    }

    @Test
    void aBlockModelStubOfOurOwnModelNeedsTheTexturesThatModelNames() {
        vanilla.file(
                "vetsmod:models/block/retro/candle.json",
                "{\"textures\":{\"art\":\"vetsmod:block/retro/art\",\"other\":\"#art\"}}");

        PackOutput out = run(new BlockModelStep(), WALL_CANDLE_ROW);

        assertEquals(Map.of(), out.files);
        assertEquals(
                List.of(
                        "the stub for minecraft:block/torch needs"
                                + " vetsmod:textures/block/retro/art.png, which is missing"),
                out.skipped);

        vanilla.texture("vetsmod:block/retro/art", GREY_PNG);
        out = run(new BlockModelStep(), WALL_CANDLE_ROW);

        assertEquals(
                "{\"parent\":\"vetsmod:block/retro/candle\"}",
                text(out, "minecraft:models/block/torch.json"));
        assertEquals(1, count(out, Count.BLOCK_MODELS));
    }

    @Test
    void aBlockModelStubOfOurOwnModelReadsASpriteAndNeedsNoParticle() {
        vanilla.file(
                "vetsmod:models/block/retro/candle.json",
                "{\"textures\":{\"art\":{\"sprite\":\"vetsmod:block/retro/art\"},"
                        + "\"particle\":\"vetsmod:block/retro/gone\"}}");

        assertEquals(
                List.of(
                        "the stub for minecraft:block/torch needs"
                                + " vetsmod:textures/block/retro/art.png, which is missing"),
                run(new BlockModelStep(), WALL_CANDLE_ROW).skipped);

        vanilla.texture("vetsmod:block/retro/art", GREY_PNG);

        assertEquals(1, count(run(new BlockModelStep(), WALL_CANDLE_ROW), Count.BLOCK_MODELS));
    }

    @Test
    void aBlockModelStubOfAnUnreadableModelOfOursIsSkipped() {
        vanilla.file("vetsmod:models/block/retro/candle.json", "not json");

        PackOutput out = run(new BlockModelStep(), WALL_CANDLE_ROW);

        assertEquals(Map.of(), out.files);
        assertEquals(2, out.skipped.size());
        assertTrue(
                out.skipped
                        .get(0)
                        .startsWith(
                                "could not read the textures vetsmod:block/retro/candle names"));
        assertEquals(
                "the stub for minecraft:block/torch needs"
                        + " vetsmod:models/block/retro/candle.json.unreadable, which is missing",
                out.skipped.get(1));
    }

    // ----- Files copied byte for byte -----

    @Test
    void anEquipmentRowTakesVanillasFileForItsAsset() {
        wynncraft
                .file("minecraft:equipment/pale_iron.json", "{\"wynncrafts\":1}")
                .file("minecraft:equipment/pale_gold.json", "{}")
                .file("minecraft:equipment/hidden.json", "{}");
        vanilla.file("minecraft:equipment/iron.json", MCMETA);

        PackOutput out =
                run(
                        new EquipmentStep(),
                        """
                        equipment:
                          'minecraft:hidden': keep
                          'minecraft:pale_(iron|gold)': 'minecraft:$1'
                        """);

        assertArrayEquals(MCMETA, file(out, "minecraft:equipment/pale_iron.json"));
        assertEquals(1, count(out, Count.EQUIPMENT));
        assertEquals(
                List.of("minecraft:hidden", "minecraft:pale_gold", "minecraft:pale_iron"),
                out.equipmentIds);
        assertEquals(
                List.of("vanilla has no equipment asset minecraft:gold for minecraft:pale_gold"),
                out.skipped);
        assertEquals(1, out.files.size());
    }

    @Test
    void anItemDefinitionIsCopiedUnderOurOwnId() {
        vanilla.file("minecraft:items/iron_helmet.json", MCMETA);

        PackOutput out =
                run(
                        new ItemDefinitionStep(),
                        """
                        gear_box_icons:
                          'HELMET': 'minecraft:iron_helmet'
                          'BOOTS': 'minecraft:gone'
                        """);

        assertArrayEquals(MCMETA, file(out, "vetsmod:items/retro/minecraft/iron_helmet.json"));
        assertEquals(Set.of("minecraft:iron_helmet"), out.copiedItems);
        assertEquals(1, count(out, Count.ITEM_DEFINITIONS));
        assertEquals(List.of("vanilla has no item definition for minecraft:gone"), out.skipped);
    }

    // ----- Parts -----

    /** A generation with {@code off} turned off, over a fixture that gives each part its ids. */
    private GenerationContext genWithOff(RetroPart off, String textures) {
        return new GenerationContext(
                ManifestFixtures.parseTextures(
                        SOURCES
                                + """
                                parts:
                                  blocks:
                                    about: 'a'
                                    textures: ['minecraft:block/.*']
                                    models: ['minecraft:block/.*']
                                  armour:
                                    about: 'b'
                                    textures: ['minecraft:entity/equipment/.*']
                                  screens:
                                    about: 'c'
                                    textures: ['minecraft:gui/.*', 'wynntils:.*']
                                  hud:
                                    about: 'd'
                                    textures: ['minecraft:font/hud/.*']
                                  items:
                                    about: 'e'
                                    models: ['minecraft:item/wynn/.*']
                                """
                                + textures),
                List.of(vanilla, wynncraft),
                1,
                ProgrammerArtSources.open(null, vanilla),
                new PackOutput(),
                RetroPart.allBut(Set.of(off)));
    }

    @Test
    void aTextureOfAPartOffStaysWynncraftsCountedAndUnreported() {
        vanilla.texture("minecraft:block/a", GREY_PNG);
        wynncraft.texture("minecraft:block/a", GREY_PNG);
        vanilla.texture("minecraft:gui/b", GREY_PNG);
        wynncraft.texture("minecraft:gui/b", GREY_PNG);
        GenerationContext gen =
                genWithOff(
                        RetroPart.BLOCKS,
                        """
                        same_name:
                          - 'minecraft:block/.*'
                          - 'minecraft:gui/.*'
                        """);

        new TextureStep().run(gen);

        assertNull(file(gen.out, "minecraft:textures/block/a.png"));
        assertArrayEquals(GREY_PNG, file(gen.out, "minecraft:textures/gui/b.png"));
        assertEquals(1, count(gen.out, Count.PARTS_OFF));
        assertEquals(1, count(gen.out, Count.SAME_NAME));
        assertEquals(List.of(), gen.out.skipped);
    }

    @Test
    void anImageEditOfATextureAPartLeftOutIsNoSkippedLineAndPaintsNothing() {
        vanilla.texture("minecraft:gui/container/inventory", GREY_PNG);
        wynncraft.texture("minecraft:gui/container/inventory", GREY_PNG);
        GenerationContext gen =
                genWithOff(
                        RetroPart.SCREENS,
                        """
                        same_name:
                          - 'minecraft:gui/.*'
                        image_edits:
                          'minecraft:gui/container/inventory':
                            - fill: [0, 0, 1, 1]
                              sample: [1, 1]
                              palette: ['#808080']
                        """);

        new TextureStep().run(gen);

        assertEquals(List.of(), gen.out.skipped);
        assertEquals(Map.of(), gen.out.imageEdits, "so the hover rule has nothing to make inert");
        assertEquals(1, count(gen.out, Count.PARTS_OFF));
    }

    @Test
    void aModelOfAPartOffHasNoStubAndIsStillListed() {
        wynncraft.file("minecraft:models/item/wynn/dungeon/key.json", "{}");
        vanilla.texture("minecraft:item/stick", GREY_PNG);
        GenerationContext gen =
                genWithOff(
                        RetroPart.ITEMS,
                        """
                        item_models:
                          'minecraft:item/wynn/dungeon/key': 'minecraft:item/stick'
                        """);

        new ModelStubStep().run(gen);

        assertNull(file(gen.out, "minecraft:models/item/wynn/dungeon/key.json"));
        assertEquals(List.of("minecraft:item/wynn/dungeon/key"), gen.out.modelIds);
        assertEquals(Set.of(), gen.out.stubbed);
        assertEquals(List.of(), gen.out.skipped);
    }

    @Test
    void aBlockModelOfAPartOffHasNoStub() {
        vanilla.file("minecraft:models/block/b.json", "{}");
        GenerationContext gen =
                genWithOff(
                        RetroPart.BLOCKS,
                        """
                        block_models:
                          'minecraft:block/a': 'minecraft:block/b'
                        """);

        new BlockModelStep().run(gen);

        assertEquals(Map.of(), gen.out.files);
        assertEquals(0, count(gen.out, Count.BLOCK_MODELS));
    }

    @Test
    void withArmourOffNoEquipmentAssetIsReplacedAndEachIsStillListed() {
        vanilla.file("minecraft:equipment/iron.json", "{\"vanilla\": true}");
        wynncraft.file("minecraft:equipment/pale_iron.json", "{\"wynncraft\": true}");
        GenerationContext gen =
                genWithOff(
                        RetroPart.ARMOUR,
                        """
                        equipment:
                          'minecraft:pale_iron': 'minecraft:iron'
                        """);

        new EquipmentStep().run(gen);

        assertEquals(Map.of(), gen.out.files);
        assertEquals(List.of("minecraft:pale_iron"), gen.out.equipmentIds);
    }

    @Test
    void aBlankOrWynntilsTextureOfAPartOffIsNotDrawn() {
        vanilla.texture("minecraft:font/hud/bar", GREY_PNG);
        vanilla.texture("wynntils:ui/panel", GREY_PNG);
        GenerationContext gen =
                genWithOff(
                        RetroPart.HUD,
                        """
                        blank_textures:
                          - 'minecraft:font/hud/bar'
                        """);
        new BlankTextureStep().run(gen);
        assertEquals(Map.of(), gen.out.files);
        assertEquals(1, count(gen.out, Count.PARTS_OFF));

        GenerationContext screensOff =
                genWithOff(
                        RetroPart.SCREENS,
                        """
                        wynntils_textures:
                          'wynntils:ui/panel': blank
                        """);
        new WynntilsTextureStep().run(screensOff);
        assertEquals(Map.of(), screensOff.out.files);
        assertEquals(1, count(screensOff.out, Count.PARTS_OFF));
    }

    @Test
    void theSummaryNamesThePartsOffOnlyWhenThereAreAny() throws IOException {
        new RetroPackGenerator(List.of(vanilla, wynncraft), 1, null, vanilla).generate();
        RetroReport report = RetroModeManager.lastReport();
        assertEquals(List.of(), report.partsOff());
        assertFalse(report.summary().contains("parts off"));

        RetroReport off =
                new RetroReport(
                        report.wynncraftPack(),
                        report.sources(),
                        report.counts(),
                        report.wynncraftCopies(),
                        report.copiedItems(),
                        report.generatedItems(),
                        report.imageEdits(),
                        report.skipped(),
                        List.of(RetroPart.MOBS, RetroPart.HUD),
                        report.millis(),
                        report.seen());
        assertTrue(off.summary().endsWith(", parts off: mobs, hud"), off.summary());
    }
}
