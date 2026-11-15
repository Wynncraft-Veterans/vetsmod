package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ShippedFiles.EVERYTHING_BLANK;
import static org.wynnvets.retro.manifest.ShippedFiles.assetFile;
import static org.wynnvets.retro.manifest.ShippedFiles.bundledFile;
import static org.wynnvets.retro.manifest.ShippedFiles.realTextureRules;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.rules.TextureDecision;
import org.wynnvets.retro.rules.TextureRules;

/**
 * The Vanilla Tweaks art the jar bundles: the one GUI texture it draws, and its credit, as Vanilla
 * Tweaks' terms ask. That every bundled texture is one this game has and one the retro pack draws
 * is {@link ShippedBundledArtTest}'s.
 */
class ShippedVanillaTweaksTest {

    /** The credit Vanilla Tweaks' terms require, word for word. */
    private static final String CREDIT = "Vanilla Tweaks: https://vanillatweaks.net/";

    private static byte[] bytes(String resource) throws IOException {
        try (InputStream in = ShippedVanillaTweaksTest.class.getResourceAsStream(resource)) {
            return in.readAllBytes();
        }
    }

    @Test
    void witheredHeartsAreTheVisibleOnesAndNoOtherGuiIsTouched() throws Exception {
        TextureRules rules = realTextureRules();
        for (String fill : List.of("half", "full", "half_blinking", "full_blinking")) {
            String texture = "minecraft:gui/sprites/hud/heart/withered_" + fill;
            assertEquals(
                    new TextureDecision.Copy(texture, RetroSource.VANILLA_TWEAKS),
                    rules.decide(texture, EVERYTHING_BLANK),
                    texture);
            assertNotEquals(
                    new String(bytes(assetFile(texture, "textures", ".png")), "ISO-8859-1"),
                    new String(
                            bytes(bundledFile(RetroSource.VANILLA_TWEAKS, texture)), "ISO-8859-1"),
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
