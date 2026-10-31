package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ShippedFiles.CLIENT_JAR;
import static org.wynnvets.retro.manifest.ShippedFiles.EVERYTHING_BLANK;
import static org.wynnvets.retro.manifest.ShippedFiles.realTextureRules;
import static org.wynnvets.retro.manifest.ShippedFiles.textureInClientJar;
import static org.wynnvets.retro.manifest.ShippedFiles.vanillaTweaksFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.rules.TextureDecision;
import org.wynnvets.retro.rules.TextureRules;

/**
 * The Vanilla Tweaks art the jar bundles against the shipped rules: each texture is one this game
 * has and one the retro pack draws, so nothing ships unused, and it is credited as Vanilla Tweaks'
 * terms ask.
 */
class ShippedVanillaTweaksTest {

    /** The credit Vanilla Tweaks' terms require, word for word. */
    private static final String CREDIT = "Vanilla Tweaks: https://vanillatweaks.net/";

    private static List<String> bundledIds() throws IOException, URISyntaxException {
        Path textures =
                Path.of(
                        ShippedVanillaTweaksTest.class
                                .getResource("/retro/vanilla_tweaks/assets/minecraft/textures")
                                .toURI());
        try (Stream<Path> files = Files.walk(textures)) {
            return files.filter(file -> file.toString().endsWith(".png"))
                    .map(file -> textures.relativize(file).toString().replace('\\', '/'))
                    .map(file -> "minecraft:" + file.substring(0, file.length() - ".png".length()))
                    .sorted()
                    .toList();
        }
    }

    private static byte[] bytes(String resource) throws IOException {
        try (InputStream in = ShippedVanillaTweaksTest.class.getResourceAsStream(resource)) {
            return in.readAllBytes();
        }
    }

    @Test
    void everyBundledTextureIsOneThisGameHas() throws Exception {
        List<String> ids = bundledIds();
        assertFalse(ids.isEmpty());
        for (String id : ids) assertTrue(textureInClientJar(id), id);
    }

    @Test
    void everyBundledTextureIsCopiedFromTheBundle() throws Exception {
        TextureRules rules = realTextureRules();
        for (String id : bundledIds()) {
            assertEquals(
                    new TextureDecision.Copy(id, RetroSource.VANILLA_TWEAKS),
                    rules.decide(id, CLIENT_JAR),
                    id);
        }
    }

    @Test
    void witheredHeartsAreTheVisibleOnesAndNoOtherGuiIsTouched() throws Exception {
        TextureRules rules = realTextureRules();
        for (String fill : List.of("half", "full", "half_blinking", "full_blinking")) {
            String texture = "minecraft:gui/sprites/hud/heart/withered_" + fill;
            String file = "/assets/minecraft/textures/gui/sprites/hud/heart/withered_";
            assertEquals(
                    new TextureDecision.Copy(texture, RetroSource.VANILLA_TWEAKS),
                    rules.decide(texture, EVERYTHING_BLANK),
                    texture);
            assertNotEquals(
                    new String(bytes(file + fill + ".png"), "ISO-8859-1"),
                    new String(bytes(vanillaTweaksFile(texture)), "ISO-8859-1"),
                    texture + " is the tweak's, not vanilla's");
        }
        String normal = "minecraft:gui/sprites/hud/heart/full";
        assertEquals(
                new TextureDecision.Copy(normal, RetroSource.VANILLA),
                rules.decide(normal, EVERYTHING_BLANK));
    }

    @Test
    void creditsCarryTheWordingTheTermsRequire() throws IOException {
        assertTrue(Files.readString(Path.of("credits.txt")).contains(CREDIT), "credits.txt");
        assertTrue(Files.readString(Path.of("README.md")).contains(CREDIT), "README.md");
    }
}
