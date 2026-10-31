package org.wynnvets.retro.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.ResourceFilterSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.RetroTestState;

/**
 * Tests for {@link RetroPackResources}: what Minecraft's resource manager sees of the retro pack.
 * Each test gives the pack a generator over a small in-memory file set, and most a static-file
 * supplier over another, each counting its calls, so the suite can pin when generation runs,
 * that the static files never start it, what is served and listed, and that a failure leaves an
 * empty pack instead of an exception.
 *
 * <p>The failure test reaches {@link RetroModeManager#latch}, which sets the session latch and
 * then tries to post a chat line through Wynntils, which is absent at test runtime. The pack
 * swallows that failure too, which is the point: nothing it calls may throw into a reload. Each
 * test ends by clearing the latch through {@link org.wynnvets.retro.RetroTestState
 * RetroTestState}, so it never reaches another suite.</p>
 */
class RetroPackResourcesTest {

    private static final Identifier STONE = Identifier.parse("minecraft:textures/block/stone.png");
    private static final Identifier STONE_MCMETA = STONE.withSuffix(".mcmeta");
    private static final Identifier STICK_MODEL =
            Identifier.parse("minecraft:models/item/wynn/stick.json");
    private static final Identifier BLANK = Identifier.parse("vetsmod:textures/item/blank.png");

    /** Shares the {@code textures} prefix, but not as a folder. */
    private static final Identifier LOOKALIKE = Identifier.parse("minecraft:texturesque/x.png");

    private static final Identifier EN_US = Identifier.parse("minecraft:lang/en_us.json");
    private static final Identifier EN_GB = Identifier.parse("minecraft:lang/en_gb.json");

    @AfterEach
    void clearLatch() {
        RetroTestState.clear();
    }

    private static Map<Identifier, byte[]> files() {
        Map<Identifier, byte[]> files = new LinkedHashMap<>();
        for (Identifier id : List.of(STONE, STONE_MCMETA, STICK_MODEL, BLANK, LOOKALIKE)) {
            files.put(id, id.toString().getBytes(StandardCharsets.UTF_8));
        }
        return files;
    }

    /** The static files: two language files, each holding its own id's UTF-8 bytes. */
    private static Map<Identifier, byte[]> langFiles() {
        Map<Identifier, byte[]> files = new LinkedHashMap<>();
        for (Identifier id : List.of(EN_US, EN_GB)) {
            files.put(id, id.toString().getBytes(StandardCharsets.UTF_8));
        }
        return files;
    }

    /** A pack over {@code generator}, with no static files. */
    private static RetroPackResources pack(Callable<Map<Identifier, byte[]>> generator) {
        return new RetroPackResources(generator, Map::of);
    }

    /** A generator that returns {@code files} and counts its calls. */
    private static Callable<Map<Identifier, byte[]>> counting(
            AtomicInteger calls, Map<Identifier, byte[]> files) {
        return () -> {
            calls.incrementAndGet();
            return files;
        };
    }

    private static byte[] read(IoSupplier<InputStream> supplier) throws IOException {
        try (InputStream in = supplier.get()) {
            return in.readAllBytes();
        }
    }

    /** The ids {@code listResources} hands its output, sorted. */
    private static Set<Identifier> listed(
            RetroPackResources pack, PackType type, String namespace, String path) {
        Set<Identifier> listed = new TreeSet<>();
        pack.listResources(type, namespace, path, (id, supplier) -> listed.add(id));
        return listed;
    }

    @Test
    void nothingIsGeneratedUntilAFileIsAskedFor() {
        AtomicInteger calls = new AtomicInteger();
        RetroPackResources pack = pack(counting(calls, files()));

        // What the resource manager asks while the reload is created, on the render thread.
        assertEquals(
                Set.of("minecraft", "wynntils", "avomod2", "vetsmod"),
                pack.getNamespaces(PackType.CLIENT_RESOURCES));
        assertNull(pack.getMetadataSection(ResourceFilterSection.TYPE));
        assertEquals("vetsmod/retro", pack.packId());
        assertNull(pack.getRootResource("pack.mcmeta"));
        assertFalse(pack.generatedFiles());
        assertEquals(0, calls.get());

        // What the reload's listeners ask outside the generated folders, the font loader on the
        // render thread among them.
        assertEquals(Set.of(), listed(pack, PackType.CLIENT_RESOURCES, "minecraft", "font"));
        assertNull(
                pack.getResource(
                        PackType.CLIENT_RESOURCES,
                        Identifier.parse("minecraft:font/default.json")));
        assertNull(
                pack.getResource(
                        PackType.CLIENT_RESOURCES, Identifier.parse("minecraft:sounds.json")));
        assertEquals(Set.of(), listed(pack, PackType.CLIENT_RESOURCES, "minecraft", "lang"));
        assertEquals(Set.of(), listed(pack, PackType.CLIENT_RESOURCES, "minecraft", ""));
        assertNull(pack.getResource(PackType.CLIENT_RESOURCES, LOOKALIKE));
        assertEquals(0, calls.get());

        pack.getResource(PackType.CLIENT_RESOURCES, STONE);
        pack.listResources(PackType.CLIENT_RESOURCES, "minecraft", "textures", (id, s) -> {});
        pack.getResource(PackType.CLIENT_RESOURCES, STICK_MODEL);

        assertEquals(1, calls.get());
        assertTrue(pack.generatedFiles());
    }

    @Test
    void getResourceServesEachFilesBytesAgainAndAgain() throws IOException {
        RetroPackResources pack = pack(counting(new AtomicInteger(), files()));
        IoSupplier<InputStream> stone = pack.getResource(PackType.CLIENT_RESOURCES, STONE);
        byte[] expected = STONE.toString().getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(expected, read(stone));
        assertArrayEquals(expected, read(stone));
        assertNull(
                pack.getResource(
                        PackType.CLIENT_RESOURCES,
                        Identifier.parse("minecraft:textures/block/dirt.png")));
        assertNull(pack.getResource(PackType.SERVER_DATA, STONE));
    }

    @Test
    void listResourcesListsEveryFileUnderTheFolderInTheNamespace() {
        RetroPackResources pack = pack(counting(new AtomicInteger(), files()));

        assertEquals(
                Set.of(STONE, STONE_MCMETA),
                listed(pack, PackType.CLIENT_RESOURCES, "minecraft", "textures"));
        assertEquals(
                Set.of(STONE, STONE_MCMETA),
                listed(pack, PackType.CLIENT_RESOURCES, "minecraft", "textures/block"));
        assertEquals(
                Set.of(STICK_MODEL),
                listed(pack, PackType.CLIENT_RESOURCES, "minecraft", "models"));
        assertEquals(Set.of(BLANK), listed(pack, PackType.CLIENT_RESOURCES, "vetsmod", "textures"));
        assertEquals(Set.of(), listed(pack, PackType.CLIENT_RESOURCES, "minecraft", "item"));
        assertEquals(Set.of(), listed(pack, PackType.SERVER_DATA, "minecraft", "textures"));
        assertEquals(Set.of(), pack.getNamespaces(PackType.SERVER_DATA));
    }

    @Test
    void theEquipmentFolderIsGeneratedLikeTexturesAndModels() {
        // The equipment asset loader lists equipment/ on a reload worker, so a request there
        // may start the generation.
        Identifier leather = Identifier.parse("minecraft:equipment/pale_leather.json");
        Map<Identifier, byte[]> files = files();
        files.put(leather, leather.toString().getBytes(StandardCharsets.UTF_8));
        AtomicInteger calls = new AtomicInteger();
        RetroPackResources pack = pack(counting(calls, files));

        assertEquals(
                Set.of(leather), listed(pack, PackType.CLIENT_RESOURCES, "minecraft", "equipment"));
        assertEquals(1, calls.get());
        assertNotNull(pack.getResource(PackType.CLIENT_RESOURCES, leather));
    }

    @Test
    void theItemsFolderIsGeneratedLikeTexturesAndModels() {
        // The model manager's item definition loader lists items/ on a reload worker, so a
        // request there may start the generation.
        Identifier bow = Identifier.parse("vetsmod:items/retro/minecraft/bow.json");
        Map<Identifier, byte[]> files = files();
        files.put(bow, bow.toString().getBytes(StandardCharsets.UTF_8));
        AtomicInteger calls = new AtomicInteger();
        RetroPackResources pack = pack(counting(calls, files));

        assertEquals(Set.of(bow), listed(pack, PackType.CLIENT_RESOURCES, "vetsmod", "items"));
        assertEquals(1, calls.get());
        assertNotNull(pack.getResource(PackType.CLIENT_RESOURCES, bow));
        assertEquals(Set.of(), listed(pack, PackType.CLIENT_RESOURCES, "minecraft", "items"));
    }

    @Test
    void aFailedGenerationServesAnEmptyPackAndThrowsNothing() {
        AtomicInteger calls = new AtomicInteger();
        RetroPackResources pack =
                pack(
                        () -> {
                            calls.incrementAndGet();
                            throw new IllegalStateException("broken manifest");
                        });
        assertNull(RetroModeManager.latchReason(), "no earlier test left the latch set");

        assertNull(pack.getResource(PackType.CLIENT_RESOURCES, STONE));
        assertEquals(Set.of(), listed(pack, PackType.CLIENT_RESOURCES, "minecraft", "textures"));
        assertNull(pack.getResource(PackType.CLIENT_RESOURCES, STONE));
        assertEquals(1, calls.get());
        assertFalse(pack.generatedFiles());
        assertNotNull(RetroModeManager.latchReason());
    }

    @Test
    void anEmptyGenerationHasNoFiles() {
        RetroPackResources pack = pack(Map::of);

        assertNull(pack.getResource(PackType.CLIENT_RESOURCES, STONE));
        assertFalse(pack.generatedFiles());
    }

    @Test
    void closeLetsTheFilesGoButRemembersThatThereWereSome() {
        RetroPackResources pack = pack(counting(new AtomicInteger(), files()));
        pack.getResource(PackType.CLIENT_RESOURCES, STONE);

        pack.close();

        assertNull(pack.getResource(PackType.CLIENT_RESOURCES, STONE));
        assertEquals(Set.of(), listed(pack, PackType.CLIENT_RESOURCES, "minecraft", "textures"));
        assertTrue(pack.generatedFiles());
    }

    @Test
    void aPackClosedBeforeItsFirstRequestNeverGenerates() {
        AtomicInteger calls = new AtomicInteger();
        RetroPackResources pack = pack(counting(calls, files()));

        pack.close();

        assertNull(pack.getResource(PackType.CLIENT_RESOURCES, STONE));
        assertEquals(0, calls.get());
        assertFalse(pack.generatedFiles());
    }

    // ----- Static files -----

    @Test
    void theStaticFilesAreServedWithoutGenerating() throws IOException {
        // The language manager reads lang/ on the render thread, which must never generate.
        AtomicInteger generations = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        RetroPackResources pack =
                new RetroPackResources(
                        counting(generations, files()), counting(reads, langFiles()));

        assertArrayEquals(
                EN_US.toString().getBytes(StandardCharsets.UTF_8),
                read(pack.getResource(PackType.CLIENT_RESOURCES, EN_US)));
        assertEquals(
                Set.of(EN_US, EN_GB), listed(pack, PackType.CLIENT_RESOURCES, "minecraft", "lang"));
        assertNull(
                pack.getResource(
                        PackType.CLIENT_RESOURCES, Identifier.parse("minecraft:lang/de_de.json")));
        assertEquals(Set.of(), listed(pack, PackType.CLIENT_RESOURCES, "vetsmod", "lang"));
        assertEquals(0, generations.get());
        assertEquals(1, reads.get(), "read once");
        assertFalse(pack.generatedFiles(), "static files alone do not make the pack live");

        // And the generated files never include them.
        assertEquals(
                Set.of(STONE, STONE_MCMETA),
                listed(pack, PackType.CLIENT_RESOURCES, "minecraft", "textures"));
        assertEquals(1, generations.get());
        assertEquals(1, reads.get());
    }

    @Test
    void aFailedGenerationTakesTheStaticFilesWithIt() {
        // A pack whose generation failed stays empty, whatever was read before.
        AtomicInteger reads = new AtomicInteger();
        RetroPackResources pack =
                new RetroPackResources(
                        () -> {
                            throw new IllegalStateException("broken manifest");
                        },
                        counting(reads, langFiles()));
        assertNotNull(pack.getResource(PackType.CLIENT_RESOURCES, EN_US));

        assertNull(pack.getResource(PackType.CLIENT_RESOURCES, STONE));

        assertNull(pack.getResource(PackType.CLIENT_RESOURCES, EN_US));
        assertEquals(Set.of(), listed(pack, PackType.CLIENT_RESOURCES, "minecraft", "lang"));
        assertNotNull(RetroModeManager.latchReason());
    }

    @Test
    void failedStaticFilesEmptyThePackAndNeverGenerate() {
        AtomicInteger generations = new AtomicInteger();
        RetroPackResources pack =
                new RetroPackResources(
                        counting(generations, files()),
                        () -> {
                            throw new IllegalStateException("broken manifest");
                        });

        assertNull(pack.getResource(PackType.CLIENT_RESOURCES, EN_US));
        assertNull(pack.getResource(PackType.CLIENT_RESOURCES, STONE));
        assertEquals(Set.of(), listed(pack, PackType.CLIENT_RESOURCES, "minecraft", "textures"));
        assertEquals(0, generations.get());
        assertFalse(pack.generatedFiles());
        assertNotNull(RetroModeManager.latchReason());
    }

    @Test
    void aClosedPackServesNoStaticFiles() {
        AtomicInteger reads = new AtomicInteger();
        RetroPackResources pack =
                new RetroPackResources(
                        counting(new AtomicInteger(), files()), counting(reads, langFiles()));

        pack.close();

        assertNull(pack.getResource(PackType.CLIENT_RESOURCES, EN_US));
        assertEquals(Set.of(), listed(pack, PackType.CLIENT_RESOURCES, "minecraft", "lang"));
        assertEquals(0, reads.get());
    }
}
