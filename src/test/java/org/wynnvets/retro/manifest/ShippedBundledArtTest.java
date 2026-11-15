package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ShippedFiles.BUNDLES;
import static org.wynnvets.retro.manifest.ShippedFiles.CLIENT_JAR;
import static org.wynnvets.retro.manifest.ShippedFiles.bundledTextureIds;
import static org.wynnvets.retro.manifest.ShippedFiles.downloaded;
import static org.wynnvets.retro.manifest.ShippedFiles.realTextureRules;
import static org.wynnvets.retro.manifest.ShippedFiles.textureInClientJar;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.rules.TextureDecision;
import org.wynnvets.retro.rules.TextureRules;

/**
 * The art the jar bundles, Vanilla Tweaks', against the shipped rules: each texture is one this
 * game has and one the retro pack draws from its bundle, so nothing ships unused. vetsmod's own art
 * is downloaded instead ({@code ShippedPanoramaTest}).
 */
class ShippedBundledArtTest {

    @Test
    void theBundlesAreEverySourceNeitherDownloadedNorThePlayersOwn() {
        Set<RetroSource> bundled =
                Arrays.stream(RetroSource.values())
                        .filter(source -> !downloaded(source))
                        .filter(source -> source != RetroSource.PROGRAMMER_ART)
                        .filter(source -> source != RetroSource.VANILLA)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(RetroSource.class)));
        assertEquals(bundled, BUNDLES.keySet());
    }

    @Test
    void everyBundleHoldsOnlyTexturesThisGameHas() throws IOException {
        for (RetroSource source : BUNDLES.keySet()) {
            List<String> ids = bundledTextureIds(source);
            assertFalse(ids.isEmpty(), source.yamlName());
            for (String id : ids) assertTrue(textureInClientJar(id), id);
        }
    }

    @Test
    void everyBundledTextureIsCopiedFromItsBundle() throws IOException {
        TextureRules rules = realTextureRules();
        for (RetroSource source : BUNDLES.keySet()) {
            for (String id : bundledTextureIds(source)) {
                assertEquals(
                        new TextureDecision.Copy(id, source), rules.decide(id, CLIENT_JAR), id);
            }
        }
    }
}
