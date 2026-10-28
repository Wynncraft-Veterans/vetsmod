package org.wynnvets.laurels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The wire names and sheet columns of {@link LaurelMetric}.
 *
 * <p>The names are the server's, and the columns are the layout of {@code laurels.png}; a change
 * to either breaks the other side without failing the laurels fetch (a renamed metric is refused
 * in a {@code laurels_set} frame, and a renamed {@code xp} or {@code graids} makes
 * {@code /wv list} 404; a moved column fails nothing), so both are pinned as literals rather than
 * derived from the enum.</p>
 */
class LaurelMetricTest {

    @Test
    void eachMetricHasTheServersNameAndItsSheetColumn() {
        assertEquals("xp", LaurelMetric.XP.wireName());
        assertEquals(0, LaurelMetric.XP.column());
        assertEquals("graids", LaurelMetric.GRAIDS.wireName());
        assertEquals(1, LaurelMetric.GRAIDS.column());
        assertEquals("streak", LaurelMetric.STREAK.wireName());
        assertEquals(2, LaurelMetric.STREAK.column());
        assertEquals("return", LaurelMetric.RETURN.wireName());
        assertEquals(3, LaurelMetric.RETURN.column());
    }

    @Test
    void columnsAreDistinct() {
        Set<Integer> columns = new HashSet<>();
        for (LaurelMetric metric : LaurelMetric.values()) {
            columns.add(metric.column());
        }

        assertEquals(LaurelMetric.values().length, columns.size());
    }

    @Test
    void fromWireRoundTripsEveryMetric() {
        for (LaurelMetric metric : LaurelMetric.values()) {
            assertEquals(metric, LaurelMetric.fromWire(metric.wireName()));
        }
    }

    @Test
    void fromWireIsNullForAnythingElse() {
        assertNull(LaurelMetric.fromWire(null));
        assertNull(LaurelMetric.fromWire(""));
        assertNull(LaurelMetric.fromWire("kills"));
        assertNull(LaurelMetric.fromWire("XP"));
        assertNull(LaurelMetric.fromWire(" xp"));
    }
}
