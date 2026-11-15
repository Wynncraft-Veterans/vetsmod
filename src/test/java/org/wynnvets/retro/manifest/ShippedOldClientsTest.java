package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ShippedFiles.CLIENT_JAR;
import static org.wynnvets.retro.manifest.ShippedFiles.inVanillaTweaks;
import static org.wynnvets.retro.manifest.ShippedFiles.realTextureRules;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.rules.OldClientRules;
import org.wynnvets.retro.rules.PackFacts;
import org.wynnvets.retro.rules.TextureDecision;
import org.wynnvets.retro.rules.TextureRules;

/**
 * Tests for the shipped {@code old_clients} and {@code old_client_clears} rows.
 *
 * <p>The old jars are not on the classpath, but their texture names are: one file per client under
 * {@code retro/old_clients/}, listed from Mojang's jars (names only, no art). So each row is checked
 * against the files its client really has, and the decisions are tried online, with each client
 * holding what its rows take, and offline, with each holding nothing ({@link ShippedFiles#CLIENT_JAR}).
 */
class ShippedOldClientsTest {

    private static final String ENTITY = "minecraft:entity/";
    private static final String ZOMBIE_VILLAGER = "minecraft:entity/zombie_villager/";
    private static final Pattern VILLAGER_LAYERS =
            Pattern.compile(
                    "minecraft:entity/villager/(villager|type/\\w+|profession/\\w+|profession_level/\\w+)");

    private static TextureSections sections;
    private static Set<String> today;
    private static final Map<RetroSource, Set<String>> HELD = new EnumMap<>(RetroSource.class);

    @BeforeAll
    static void read() throws IOException, URISyntaxException {
        sections = RetroManifest.load().textures();
        today = clientJarTextureIds();
        for (Map.Entry<RetroSource, List<TextureSections.OldClientRow>> client :
                sections.oldClients().entrySet()) {
            Set<String> files = oldClientFiles(client.getKey());
            Set<String> held = new HashSet<>();
            for (OldClientRules.Target target : OldClientRules.targets(client.getValue(), today)) {
                if (target.file() == null || files.contains(target.file())) {
                    held.add(target.textureId());
                }
            }
            HELD.put(client.getKey(), held);
        }
    }

    /** Every texture id the client jar on the test classpath has, in the minecraft namespace. */
    private static Set<String> clientJarTextureIds() throws IOException, URISyntaxException {
        String prefix = "assets/minecraft/textures/";
        URL stone = ShippedFiles.class.getResource("/" + prefix + "block/stone.png");
        assertNotNull(stone, "the client jar is on the test classpath");
        Set<String> ids = new TreeSet<>();
        if (stone.openConnection() instanceof JarURLConnection connection) {
            try (JarFile jar = new JarFile(Path.of(connection.getJarFileURL().toURI()).toFile())) {
                for (JarEntry entry : jar.stream().toList()) {
                    String name = entry.getName();
                    if (name.startsWith(prefix) && name.endsWith(".png")) {
                        ids.add("minecraft:" + name.substring(prefix.length(), name.length() - 4));
                    }
                }
            }
        } else {
            Path textures = Path.of(stone.toURI()).getParent().getParent();
            try (Stream<Path> files = Files.walk(textures)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".png")).toList()) {
                    String path = textures.relativize(file).toString().replace('\\', '/');
                    ids.add("minecraft:" + path.substring(0, path.length() - 4));
                }
            }
        }
        return ids;
    }

    /** The texture names of an old client's jar, as {@code minecraft:blocks/stone}. */
    private static Set<String> oldClientFiles(RetroSource client) throws IOException {
        String file = "/retro/old_clients/" + client.yamlName() + ".txt";
        try (InputStream in = ShippedOldClientsTest.class.getResourceAsStream(file)) {
            assertNotNull(in, file);
            return Set.copyOf(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        }
    }

    /** The packs with each old client holding what its rows take from it. */
    private static final PackFacts ONLINE =
            new PackFacts() {
                @Override
                public boolean wynncraftShips(String textureId) {
                    return true;
                }

                @Override
                public boolean wynncraftTransparent(String textureId) {
                    return false;
                }

                @Override
                public boolean has(RetroSource source, String textureId) {
                    return source.oldClient()
                            ? HELD.getOrDefault(source, Set.of()).contains(textureId)
                            : CLIENT_JAR.has(source, textureId);
                }
            };

    @Test
    void everyRowTakesAFileItsClientHasForATextureThereIsToday() throws IOException {
        List<String> empty = new ArrayList<>();
        for (Map.Entry<RetroSource, List<TextureSections.OldClientRow>> client :
                sections.oldClients().entrySet()) {
            Set<String> files = oldClientFiles(client.getKey());
            List<TextureSections.OldClientRow> rows = client.getValue();
            Set<Integer> taking = new HashSet<>();
            for (OldClientRules.Target target : OldClientRules.targets(rows, today)) {
                if (target.file() == null || files.contains(target.file())) {
                    taking.add(target.row());
                }
            }
            for (int i = 0; i < rows.size(); i++) {
                if (!taking.contains(i)) {
                    empty.add(client.getKey().yamlName() + ": " + rows.get(i).textures().pattern());
                }
            }
        }
        assertEquals(List.of(), empty, "a typo, a stale name, or a texture no client has");
    }

    @Test
    void noRowTakesATextureWynncraftsOwnAlwaysKeeps() {
        for (Map.Entry<RetroSource, Set<String>> held : HELD.entrySet()) {
            for (String textureId : held.getValue()) {
                for (Pattern keep : sections.keepWynncraft()) {
                    assertFalse(
                            keep.matcher(textureId).matches(),
                            held.getKey().yamlName() + " takes " + textureId + ", kept anyway");
                }
            }
        }
    }

    @Test
    void every2013VillagerLayerComesFrom1710AndTheHoodAndHemAreCleared() {
        Set<String> layers = new TreeSet<>();
        for (String textureId : today) {
            if (VILLAGER_LAYERS.matcher(textureId).matches()) layers.add(textureId);
        }
        assertTrue(layers.size() > 20, layers.toString());
        Set<String> held = HELD.get(RetroSource.MINECRAFT_1_7_10);
        Map<String, String> files = new HashMap<>();
        OldClientRules.targets(sections.oldClients().get(RetroSource.MINECRAFT_1_7_10), layers)
                .forEach(target -> files.put(target.textureId(), target.file()));
        for (String layer : layers) {
            assertTrue(held.contains(layer), layer + ", a layer of today's villager");
            if (layer.contains("/type/") || layer.contains("/profession_level/")) {
                assertNull(files.get(layer), layer + ": 1.7 had no biome or level layers");
            } else {
                assertTrue(
                        Set.of("farmer", "librarian", "priest", "smith", "butcher", "villager")
                                .contains(
                                        files.get(layer).replace("minecraft:entity/villager/", "")),
                        layer + " -> " + files.get(layer));
                assertEquals(
                        2,
                        OldClientRules.clears(sections.oldClientClears(), layer).size(),
                        layer + ": the hood and the hem");
            }
        }
    }

    @Test
    void aVillagerIs1710sWholeOnlineAndTodaysWholeOffline() throws IOException {
        // Every entity texture, as a generation decides them: the zombie villagers, which no old
        // client has, beside the villagers, which must not share their group.
        TextureRules rules = realTextureRules();
        List<String> entities = today.stream().filter(id -> id.startsWith(ENTITY)).toList();
        List<String> layers =
                entities.stream().filter(id -> VILLAGER_LAYERS.matcher(id).matches()).toList();
        List<String> zombieLayers =
                entities.stream().filter(id -> id.startsWith(ZOMBIE_VILLAGER)).toList();
        assertFalse(zombieLayers.isEmpty());

        Map<String, TextureDecision> online = rules.decideAll(entities, ONLINE);
        Map<String, TextureDecision> offline = rules.decideAll(entities, CLIENT_JAR);

        for (String layer : layers) {
            assertEquals(
                    new TextureDecision.Copy(layer, RetroSource.MINECRAFT_1_7_10),
                    online.get(layer),
                    layer);
            assertEquals(
                    new TextureDecision.Copy(layer, RetroSource.VANILLA),
                    offline.get(layer),
                    layer);
        }
        for (String layer : zombieLayers) {
            assertEquals(
                    new TextureDecision.Copy(layer, RetroSource.VANILLA), online.get(layer), layer);
        }
    }

    @Test
    void theOldestClientWithTheArtWinsOverTheBundledArt() throws IOException {
        TextureRules rules = realTextureRules();

        assertTrue(inVanillaTweaks("minecraft:block/white_wool"), "the offline fallback");
        assertEquals(
                new TextureDecision.Copy(
                        "minecraft:block/white_wool", RetroSource.MINECRAFT_1_7_10),
                rules.decide("minecraft:block/white_wool", ONLINE));
        assertEquals(
                new TextureDecision.Copy(
                        "minecraft:block/acacia_door_top", RetroSource.MINECRAFT_1_8_9),
                rules.decide("minecraft:block/acacia_door_top", ONLINE));
        assertEquals(
                new TextureDecision.Copy(
                        "minecraft:entity/shield/base", RetroSource.MINECRAFT_1_9_4),
                rules.decide("minecraft:entity/shield/base", ONLINE));
        assertEquals(
                new TextureDecision.Copy("minecraft:item/oak_boat", RetroSource.MINECRAFT_1_7_10),
                rules.decide("minecraft:item/oak_boat", ONLINE),
                "1.7.10's boat icon");
        assertEquals(
                new TextureDecision.Copy("minecraft:entity/boat/oak", RetroSource.VANILLA_TWEAKS),
                rules.decide("minecraft:entity/boat/oak", ONLINE),
                "the boats stay oarless: 1.7's boat has another layout, and 1.9's has oars");
    }

    @Test
    void theBundleHasNoArtThe2013ArtIsAlreadyIn() {
        // Programmer Art or vanilla has the 1.7.10 art of these, or an old client takes it, so a
        // Vanilla Tweaks copy would only stand in front of it.
        for (String textureId :
                List.of(
                        "minecraft:block/cobblestone",
                        "minecraft:block/mossy_cobblestone",
                        "minecraft:block/coal_ore",
                        "minecraft:block/diamond_ore",
                        "minecraft:block/gravel",
                        "minecraft:block/lapis_block",
                        "minecraft:block/crafting_table_front",
                        "minecraft:block/redstone_torch",
                        "minecraft:block/oak_planks",
                        "minecraft:block/ice",
                        "minecraft:block/netherrack",
                        "minecraft:block/lava_still",
                        "minecraft:block/water_still",
                        "minecraft:block/water_overlay",
                        "minecraft:entity/signs/oak")) {
            assertFalse(inVanillaTweaks(textureId), textureId);
        }
    }
}
