package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.wynnvets.retro.manifest.ShippedFiles.realTextureRules;

import java.io.IOException;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.panorama.HostedPanorama;
import org.wynnvets.retro.rules.KeepReason;
import org.wynnvets.retro.rules.PackFacts;
import org.wynnvets.retro.rules.TextureDecision;
import org.wynnvets.retro.rules.TextureRules;

/**
 * The main menu's panorama against the shipped rules: retro reads it from vetsmod's own art alone,
 * which is downloaded ({@link HostedPanorama#OLD_DETLAS}), so until it is here Wynncraft's stays;
 * and the download keeps its faces where the rules look for them.
 */
class ShippedPanoramaTest {

    /** The main menu's panorama: its six faces, as Minecraft names them. */
    private static final List<String> PANORAMA =
            IntStream.range(0, 6)
                    .mapToObj(face -> "minecraft:gui/title/background/panorama_" + face)
                    .toList();

    /** Wynncraft ships every texture asked about, and every source has it but, maybe, vetsmod. */
    private static PackFacts facts(boolean downloaded) {
        return new PackFacts() {
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
                return source != RetroSource.VETSMOD || downloaded;
            }
        };
    }

    @Test
    void theMainMenusPanoramaIsReadFromVetsmodsDownloadAlone() throws IOException {
        TextureRules rules = realTextureRules();
        for (String id : PANORAMA) {
            assertEquals(List.of(RetroSource.VETSMOD), rules.sourceOrder(id), id);
            assertEquals(
                    new TextureDecision.Copy(id, RetroSource.VETSMOD),
                    rules.decide(id, facts(true)),
                    id);
            assertEquals(
                    new TextureDecision.Keep(KeepReason.NO_RULE),
                    rules.decide(id, facts(false)),
                    id + ": until it is downloaded Wynncraft's stays, not vanilla's");
        }
    }

    @Test
    void theDownloadKeepsEachFaceWhereTheRulesLookForIt() {
        // RetroPackInjector opens Old Detlas's folder as the vetsmod source's pack.
        for (int face = 0; face < PANORAMA.size(); face++) {
            String id = PANORAMA.get(face);
            assertEquals(
                    "assets/minecraft/textures/" + id.substring("minecraft:".length()) + ".png",
                    HostedPanorama.OLD_DETLAS.path(face),
                    id);
        }
    }
}
