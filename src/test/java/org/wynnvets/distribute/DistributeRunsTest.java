package org.wynnvets.distribute;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The chat lines {@link DistributeRuns} prints about a run, which name the run by the noun it was
 * submitted with.
 *
 * <p>{@code /wv distribute} submits {@code "distribution"}, and its three lines are pinned byte
 * for byte as they were before the noun was a parameter. Only the line builders are exercised:
 * the run state is static and its transitions go through the tick scheduler, which a test cannot
 * boot.</p>
 */
class DistributeRunsTest {

    private static final String DISTRIBUTION = "distribution";
    private static final String LAURELS = "laurels update";

    @Test
    void aRunQueuedBehindADistributionSaysItStartsWhenThatDistributionFinishes() {
        assertEquals(
                "Queued: starts when the current distribution finishes.",
                DistributeRuns.queuedLine(DISTRIBUTION));
    }

    @Test
    void aRunQueuedBehindALaurelsUpdateNamesTheUpdate() {
        assertEquals(
                "Queued: starts when the current laurels update finishes.",
                DistributeRuns.queuedLine(LAURELS));
    }

    @Test
    void aStoppedDistributionStillSaysDistributionStopped() {
        assertEquals(
                "Distribution stopped: the Members menu is not open.",
                DistributeRuns.stopLine(DISTRIBUTION, "the Members menu is not open"));
    }

    @Test
    void aStoppedLaurelsUpdateIsCapitalisedAndNamed() {
        assertEquals(
                "Laurels update stopped: the streak walk timed out.",
                DistributeRuns.stopLine(LAURELS, "the streak walk timed out"));
    }

    @Test
    void cancelledDistributionsAreCountedAndPluralised() {
        assertEquals(
                "Cancelled 1 queued distribution.",
                DistributeRuns.cancelledLine(List.of(DISTRIBUTION)));
        assertEquals(
                "Cancelled 2 queued distributions.",
                DistributeRuns.cancelledLine(List.of(DISTRIBUTION, DISTRIBUTION)));
    }

    @Test
    void cancelledRunsOfOneKindAreNamedByIt() {
        assertEquals(
                "Cancelled 3 queued laurels updates.",
                DistributeRuns.cancelledLine(List.of(LAURELS, LAURELS, LAURELS)));
    }

    @Test
    void cancelledRunsOfMixedKindsAreJustRuns() {
        assertEquals(
                "Cancelled 2 queued runs.",
                DistributeRuns.cancelledLine(List.of(DISTRIBUTION, LAURELS)));
        assertEquals(
                "Cancelled 3 queued runs.",
                DistributeRuns.cancelledLine(List.of(LAURELS, DISTRIBUTION, LAURELS)));
    }
}
