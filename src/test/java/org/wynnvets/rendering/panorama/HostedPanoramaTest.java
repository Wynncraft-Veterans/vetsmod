package org.wynnvets.rendering.panorama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HostedPanorama}'s pins: where each face is hosted, and where each set keeps
 * its faces.
 */
class HostedPanoramaTest {

    /**
     * A top-level name on i.wynnvets.org: its nginx sends only {@code /<name>.<ext>} to the
     * bucket, so a face in a folder would 404.
     */
    private static final Pattern HOSTED =
            Pattern.compile("https://i\\.wynnvets\\.org/[A-Za-z0-9]+\\.png");

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    @Test
    void everyFaceIsPinnedAtTheTopLevelOfIWynnvetsOrg() {
        Set<String> uris = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (HostedPanorama panorama : HostedPanorama.values()) {
            assertEquals(6, panorama.faces().size(), panorama.name());
            for (HostedPanorama.Face face : panorama.faces()) {
                assertTrue(HOSTED.matcher(face.uri().toString()).matches(), face.toString());
                assertTrue(SHA256.matcher(face.sha256()).matches(), face.toString());
                assertTrue(face.size() > 0, face.toString());
                assertTrue(uris.add(face.uri().toString()), "one upload per face: " + face);
                assertTrue(hashes.add(face.sha256()), "no two faces alike: " + face);
            }
        }
    }

    @Test
    void eachSetKeepsItsFacesWhereItsReaderLooks() {
        // Retro opens Old Detlas's folder as a pack (ShippedPanoramaTest); Vetlas is read as files.
        assertEquals(
                "assets/minecraft/textures/gui/title/background/panorama_0.png",
                HostedPanorama.OLD_DETLAS.path(0));
        assertEquals(
                "assets/vetsmod/textures/gui/title/background/vetlas_5.png",
                HostedPanorama.VETLAS.path(5));
    }

    @Test
    void eachSetsFolderIsNamedByItsPins() {
        for (HostedPanorama panorama : HostedPanorama.values()) {
            assertTrue(panorama.hash().matches("[0-9a-f]{16}"), panorama.name());
            assertEquals(panorama.hash(), panorama.hash(), "the same pins, the same name");
        }
        assertNotEquals(HostedPanorama.OLD_DETLAS.hash(), HostedPanorama.VETLAS.hash());
        assertNotEquals(HostedPanorama.OLD_DETLAS.folderName(), HostedPanorama.VETLAS.folderName());
    }
}
