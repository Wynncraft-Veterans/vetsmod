package org.wynnvets.retro.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.wynnvets.retro.manifest.ManifestFixtures.oldClientRow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.image.Pixels;
import org.wynnvets.rendering.image.PngCodec;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.manifest.TextureSections.OldClientClear;
import org.wynnvets.retro.manifest.TextureSections.OldClientRow;

/** Tests for {@link OldClientFetch}, over a jar built in memory. */
class OldClientFetchTest {

    private static final String TEXTURES = "assets/minecraft/textures/";
    private static final byte[] MCMETA =
            "{\"animation\":{\"frametime\":2}}".getBytes(StandardCharsets.UTF_8);

    private static byte[] opaque(int width, int height) {
        int[] argb = new int[width * height];
        Arrays.fill(argb, 0xFF336699);
        return PngCodec.png(new Pixels(width, height, argb));
    }

    private static final byte[] WOOL = opaque(16, 16);
    private static final byte[] WATER = opaque(16, 32);
    private static final byte[] FARMER = opaque(64, 64);

    private static TestZip jar() throws IOException {
        return TestZip.builder()
                .deflated(TEXTURES + "blocks/wool_colored_white.png", WOOL)
                .deflated(TEXTURES + "blocks/water_still.png", WATER)
                .deflated(TEXTURES + "blocks/water_still.png.mcmeta", MCMETA)
                .deflated(TEXTURES + "entity/villager/farmer.png", FARMER)
                .build();
    }

    private static final List<OldClientRow> ROWS =
            List.of(
                    oldClientRow("minecraft:block/(\\w+)_wool", "minecraft:blocks/wool_colored_$1"),
                    oldClientRow("minecraft:block/water_still", "minecraft:blocks/water_still"),
                    oldClientRow(
                            "minecraft:entity/villager/profession/(farmer|fisherman)",
                            "minecraft:entity/villager/farmer"),
                    oldClientRow("minecraft:entity/villager/type/\\w+", null),
                    oldClientRow("minecraft:block/gone", "minecraft:blocks/gone"));

    private static final List<OldClientClear> CLEARS =
            List.of(
                    new OldClientClear(
                            Pattern.compile("minecraft:entity/villager/profession/\\w+"),
                            List.of(new Rect(32, 0, 32, 18))));

    private static final Set<String> TODAY =
            Set.of(
                    "minecraft:block/white_wool",
                    "minecraft:block/black_wool",
                    "minecraft:block/water_still",
                    "minecraft:block/stone",
                    "minecraft:block/gone",
                    "minecraft:entity/villager/profession/farmer",
                    "minecraft:entity/villager/profession/fisherman",
                    "minecraft:entity/villager/type/plains");

    private static OldClientFetch.Fetched fetch(TestZip zip) throws IOException {
        return OldClientFetch.fetch(
                zip.jar(),
                ROWS,
                CLEARS,
                TODAY,
                textureId ->
                        textureId.equals("minecraft:entity/villager/type/plains")
                                ? opaque(64, 64)
                                : null,
                zip.ranges());
    }

    @Test
    void eachFileIsWrittenAtEveryTodaysIdItStandsFor() throws IOException {
        OldClientFetch.Fetched fetched = fetch(jar());

        assertEquals(
                Set.of(
                        TEXTURES + "block/white_wool.png",
                        TEXTURES + "block/water_still.png",
                        TEXTURES + "block/water_still.png.mcmeta",
                        TEXTURES + "entity/villager/profession/farmer.png",
                        TEXTURES + "entity/villager/profession/fisherman.png",
                        TEXTURES + "entity/villager/type/plains.png"),
                fetched.files().keySet(),
                "black wool has no file of its own, and stone no row");
        assertArrayEquals(WOOL, fetched.files().get(TEXTURES + "block/white_wool.png"));
        assertArrayEquals(MCMETA, fetched.files().get(TEXTURES + "block/water_still.png.mcmeta"));
        assertEquals(List.of("minecraft:block/gone"), fetched.emptyRows());
    }

    @Test
    void aFileTwoIdsShareIsReadOnce() throws IOException {
        TestZip zip = jar();

        fetch(zip);

        // The directory, the wool, the water and its .mcmeta, and the farmer once.
        assertEquals(5, zip.reads.size());
    }

    @Test
    void aClearMakesItsRectangleTransparentAndABlankIsTodaysSize() throws IOException {
        OldClientFetch.Fetched fetched = fetch(jar());

        Pixels farmer =
                PngCodec.decode(
                        fetched.files().get(TEXTURES + "entity/villager/profession/fisherman.png"));
        assertEquals(0, farmer.get(40, 5) >>> 24, "the hood is cleared");
        assertEquals(0xFF, farmer.get(5, 5) >>> 24, "the head is not");
        Pixels plains =
                PngCodec.decode(fetched.files().get(TEXTURES + "entity/villager/type/plains.png"));
        assertEquals(64, plains.width());
        assertEquals(64, plains.height());
        assertEquals(0, Arrays.stream(plains.argb()).filter(argb -> argb >>> 24 != 0).count());
    }

    @Test
    void aDirectoryThatIsNotThePinnedOneIsRefused() throws IOException {
        TestZip zip = jar();
        OldClients.Jar pinned = zip.jar();
        OldClients.Jar wrong =
                new OldClients.Jar(
                        pinned.sha1(),
                        pinned.directoryOffset(),
                        pinned.directorySize(),
                        "1111111111111111111111111111111111111111");

        assertThrows(
                IOException.class,
                () ->
                        OldClientFetch.fetch(
                                wrong, ROWS, CLEARS, TODAY, textureId -> null, zip.ranges()));
    }
}
