package org.wynnvets.extras.panorama;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.panorama.PanoramaDownloads.State;

/**
 * Tests for {@link PanoramaCommand}'s answer. The tree is {@code ExtrasCommandsTest}'s, and the
 * command itself is not run: it reads the stored setting and answers through Wynntils' chat.
 */
class PanoramaCommandTest {

    private static final String SHOWS = "The Vetlas panorama is on: it shows behind the main menu.";
    private static final String ONCE_DOWNLOADED =
            "The Vetlas panorama is on: it shows behind the main menu once it is downloaded.";
    private static final String ONCE_RETRO_IS_OFF =
            "The Vetlas panorama is on, and shows once retro is off.";
    private static final String NOT_DRAWN =
            "The Vetlas panorama is on, but could not be drawn: it returns after a restart.";
    private static final String NOT_DOWNLOADED =
            "The Vetlas panorama is on, but could not be downloaded: the next start-up tries again.";
    private static final String OFF = "The Vetlas panorama is off.";

    @Test
    void eachAnswerSaysWhetherItIsOnAndWhatKeepsItFromShowing() {
        assertEquals(SHOWS, PanoramaCommand.text(true, false, false, State.HERE));
        assertEquals(ONCE_RETRO_IS_OFF, PanoramaCommand.text(true, true, false, State.HERE));
        assertEquals(NOT_DRAWN, PanoramaCommand.text(true, false, true, State.HERE));
        assertEquals(
                NOT_DRAWN,
                PanoramaCommand.text(true, true, true, State.HERE),
                "a failure outranks retro");
        assertEquals(OFF, PanoramaCommand.text(false, false, false, State.HERE));
        assertEquals(
                OFF,
                PanoramaCommand.text(false, true, true, State.FAILED),
                "off says nothing of retro, a failure or the download");
    }

    @Test
    void untilItsFacesAreHereTheAnswerSaysSo() {
        assertEquals(ONCE_DOWNLOADED, PanoramaCommand.text(true, false, false, State.DOWNLOADING));
        assertEquals(
                ONCE_DOWNLOADED,
                PanoramaCommand.text(true, false, false, State.MISSING),
                "turning it on starts the download, so missing is about to be downloading");
        assertEquals(
                ONCE_RETRO_IS_OFF,
                PanoramaCommand.text(true, true, false, State.DOWNLOADING),
                "retro outranks a download still running");
        assertEquals(NOT_DOWNLOADED, PanoramaCommand.text(true, false, false, State.FAILED));
        assertEquals(
                NOT_DOWNLOADED,
                PanoramaCommand.text(true, true, false, State.FAILED),
                "a failed download outranks retro");
        assertEquals(
                NOT_DRAWN,
                PanoramaCommand.text(true, false, true, State.FAILED),
                "a failure to draw outranks a failed download");
    }
}
