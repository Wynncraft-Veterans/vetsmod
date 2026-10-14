package org.wynnvets.mwe.anni.render;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonParser;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wynnvets.mwe.anni.state.AnniSnapshot;

/**
 * {@link AnniCommandRenderer#render}'s {@code null} contract: for an external user with an
 * announced anni it returns {@code null}, not an empty list, so its caller falls back to the
 * legacy stamp text.
 *
 * <p>Each test forces the external override to {@code TRUE} through
 * {@link AnniCommandRenderer#setExternalOverride}, so {@code isExternal} never reaches
 * {@code GuildStateManager}, and the external arm returns before anything that reads
 * {@code VetsConfig}, which cannot initialise in a test. {@link #restoreOverride} puts the
 * default back for the classes that share this JVM. The override has no getter, and its one
 * reader, {@code isExternal}, reaches {@code VetsConfig} through {@code GuildStateManager} when
 * the override is {@code null}, so the restore is not asserted.</p>
 */
class AnniCommandRendererTest {

    @AfterEach
    void restoreOverride() {
        AnniCommandRenderer.setExternalOverride(null);
    }

    /** An announced anni {@code seconds} from now, and nothing else. */
    private static AnniSnapshot announcedIn(long seconds) {
        long stamp = Instant.now().getEpochSecond() + seconds;
        return AnniSnapshot.fromJson(
                JsonParser.parseString(
                                "{\"event\": {\"stamp_epoch\": "
                                        + stamp
                                        + ", \"announced\": true}}")
                        .getAsJsonObject());
    }

    @Test
    void render_returnsNullForAnExternalUserWithAnAnniMoreThanTwoHoursOut() {
        AnniCommandRenderer.setExternalOverride(Boolean.TRUE);

        assertNull(AnniCommandRenderer.render(announcedIn(8L * 3600L)));
    }

    @Test
    void render_returnsNullForAnExternalUserWithAnAnniWithinTwoHours() {
        AnniCommandRenderer.setExternalOverride(Boolean.TRUE);

        assertNull(AnniCommandRenderer.render(announcedIn(600L)));
    }
}
