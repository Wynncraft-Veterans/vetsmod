package org.wynnvets.extras.panorama;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.panorama.HostedPanorama;

/**
 * Tests for {@link ExtrasPanorama}'s pure parts: when Vetlas is drawn, and that its faces are
 * downloaded rather than bundled. Downloading, loading and drawing need a running client and are
 * not tested here; the download's checks are {@code PanoramaDownloadsTest}'s.
 */
class ExtrasPanoramaTest {

    @Test
    void vetlasShowsOnlyWhileTheEggIsOnRetroIsOffAndNothingHasFailed() {
        assertTrue(ExtrasPanorama.shows(true, false, false));
        assertFalse(ExtrasPanorama.shows(false, false, false), "the egg is off");
        assertFalse(ExtrasPanorama.shows(true, true, false), "retro is on: the egg gives way");
        assertFalse(ExtrasPanorama.shows(true, false, true), "Vetlas failed this session");
    }

    @Test
    void vetlasIsDownloadedNotBundled() {
        // About 5 MB that few players want: the jar carries the pins, not the faces.
        for (int face = 0; face < 6; face++) {
            String path = HostedPanorama.VETLAS.path(face);
            assertNull(ExtrasPanoramaTest.class.getResource("/" + path), path);
        }
    }
}
