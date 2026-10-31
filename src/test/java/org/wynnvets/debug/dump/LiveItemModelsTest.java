package org.wynnvets.debug.dump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for the entry {@link LiveItemModels}' walk picks of a range dispatch, as Minecraft's own
 * picks it. The walk itself needs parsed definitions and is not tested.
 */
class LiveItemModelsTest {

    @Test
    void theHighestThresholdAtOrBelowTheValueIsPicked() {
        float[] thresholds = {0, 10, 5};

        assertEquals(0, LiveItemModels.pickedEntry(thresholds, 0));
        assertEquals(0, LiveItemModels.pickedEntry(thresholds, 4.9f));
        assertEquals(2, LiveItemModels.pickedEntry(thresholds, 5), "whatever the file's order");
        assertEquals(1, LiveItemModels.pickedEntry(thresholds, 10));
        assertEquals(1, LiveItemModels.pickedEntry(thresholds, 1000));
    }

    @Test
    void theLaterOfEqualThresholdsIsPicked() {
        assertEquals(2, LiveItemModels.pickedEntry(new float[] {1, 5, 5}, 7));
        assertEquals(2, LiveItemModels.pickedEntry(new float[] {5, 1, 5}, 7));
    }

    @Test
    void noThresholdAtOrBelowOrNoNumberPicksNone() {
        assertEquals(-1, LiveItemModels.pickedEntry(new float[] {1, 5}, 0.5f));
        assertEquals(-1, LiveItemModels.pickedEntry(new float[] {1, 5}, Float.NaN));
        assertEquals(-1, LiveItemModels.pickedEntry(new float[] {}, 1));
    }
}
