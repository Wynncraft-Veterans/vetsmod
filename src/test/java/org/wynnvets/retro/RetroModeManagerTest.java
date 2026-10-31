package org.wynnvets.retro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.manifest.RetroPart;

/**
 * Tests for {@link RetroModeManager}'s live flag: which finished reload may say the retro pack is
 * drawn, and which report counts as the live one; for the rule files it keeps for the session; and
 * for the parts that are on, read outside the game as every part.
 * The setting goes through {@code VetsConfig} and the latch posts a chat line through
 * Wynntils, so the suite leaves both alone;
 * {@code RetroPackResourcesTest} reaches the latch through the pack, which swallows that line's
 * failure.
 */
class RetroModeManagerTest {

    @AfterEach
    void reset() {
        RetroModeManager.clearForTesting();
    }

    @Test
    void aReloadThatFinishesLateCannotMarkALaterOneLive() {
        int first = RetroModeManager.reloadStarted();
        int second = RetroModeManager.reloadStarted();

        RetroModeManager.packWentLive(first);
        assertFalse(RetroModeManager.isPackLive(), "the first reload was overtaken");

        RetroModeManager.packWentLive(second);
        assertTrue(RetroModeManager.isPackLive());

        RetroModeManager.reloadStarted();
        assertFalse(RetroModeManager.isPackLive(), "a reload clears it as it starts");
    }

    @Test
    void aLivePackIsNotLiveOnWynncraftWhileWynntilsCannotSaySo() {
        assertFalse(RetroModeManager.isLiveOnWynncraft(), "no pack");

        RetroModeManager.packWentLive(RetroModeManager.reloadStarted());

        assertTrue(RetroModeManager.isPackLive());
        assertFalse(
                RetroModeManager.isLiveOnWynncraft(),
                "Wynntils is not on the test classpath, and nothing is thrown");
    }

    @Test
    void theLiveReportIsTheLatestGenerationsOnlyWhileThePackIsLive() {
        RetroReport.Seen seen =
                new RetroReport.Seen(Map.of(), Set.of(), List.of(), Set.of(), List.of(), Map.of());
        RetroReport report =
                new RetroReport(
                        "wynntils_preloaded/test",
                        List.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        0,
                        seen);
        assertNull(RetroModeManager.liveReport(), "no generation yet");

        RetroModeManager.generated(report);
        assertSame(report, RetroModeManager.lastReport());
        assertNull(RetroModeManager.liveReport(), "not live yet");

        RetroModeManager.packWentLive(RetroModeManager.reloadStarted());
        assertSame(report, RetroModeManager.liveReport());

        RetroModeManager.reloadStarted();
        assertNull(RetroModeManager.liveReport(), "a reload");
        assertSame(report, RetroModeManager.lastReport(), "which keeps the last report");
    }

    @Test
    void theRuleFilesAreParsedOnceASession() {
        RetroManifest manifest = RetroModeManager.manifest();

        assertSame(manifest, RetroModeManager.manifest());
        assertFalse(
                manifest.screens().slotFills().isEmpty(), "the real files, not an empty stand-in");
    }

    @Test
    void everyPartIsOnWhileTheSettingCannotBeRead() {
        assertEquals(RetroPart.all(), RetroModeManager.partsOn());
        assertSame(RetroModeManager.partsOn(), RetroModeManager.partsOn(), "parsed once");
        assertTrue(RetroModeManager.isPartOn(RetroPart.WEAPONS));
    }

    @Test
    void newPartsReloadOnlyAChangeOverALoadedRetroPack() {
        RetroModeManager.Outcome reloading = RetroModeManager.Outcome.RELOADING;
        RetroModeManager.Outcome unchanged = RetroModeManager.Outcome.UNCHANGED;

        assertEquals(reloading, RetroModeManager.partsOutcome(true, true, false, true, true));
        assertEquals(unchanged, RetroModeManager.partsOutcome(false, true, false, true, true));
        assertEquals(unchanged, RetroModeManager.partsOutcome(true, false, false, false, true));
        assertEquals(
                RetroModeManager.Outcome.NO_WYNNCRAFT_PACK,
                RetroModeManager.partsOutcome(true, true, false, false, false));
        assertEquals(
                unchanged,
                RetroModeManager.partsOutcome(true, false, false, false, false),
                "retro is off, so nothing waits for Wynncraft's pack");
    }

    @Test
    void aStoppedSessionAnswersThatWhileRetroIsOn() {
        assertEquals(
                RetroModeManager.Outcome.LATCHED,
                RetroModeManager.partsOutcome(true, true, true, true, true));
        assertEquals(
                RetroModeManager.Outcome.UNCHANGED,
                RetroModeManager.partsOutcome(false, false, true, false, true),
                "retro is off, so what stopped it does not matter");
    }

    @Test
    void aPartIsLiveOnlyWhileThePackIsLiveAndThePartIsOn() {
        RetroModeManager.setPartsOnForTesting(RetroPart.allBut(Set.of(RetroPart.HUD)));

        assertFalse(RetroModeManager.isPartLive(RetroPart.WEAPONS), "no pack");

        RetroModeManager.packWentLive(RetroModeManager.reloadStarted());

        assertTrue(RetroModeManager.isPartLive(RetroPart.WEAPONS));
        assertFalse(RetroModeManager.isPartLive(RetroPart.HUD), "the part is off");
        assertFalse(
                RetroModeManager.isPartLiveOnWynncraft(RetroPart.WEAPONS),
                "Wynntils is not on the test classpath, and nothing is thrown");
    }
}
