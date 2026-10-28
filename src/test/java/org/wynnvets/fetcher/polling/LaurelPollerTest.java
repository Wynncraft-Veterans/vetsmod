package org.wynnvets.fetcher.polling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonSyntaxException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.wynnvets.laurels.Laurel;
import org.wynnvets.laurels.LaurelMetric;
import org.wynnvets.laurels.LaurelStandings;

/**
 * What {@link LaurelPoller#parse(String)} makes of a response body.
 *
 * <p>The server's answer is untrusted input to a render-thread lookup, so the rule pinned here is
 * that a bad entry costs only itself: each skip rule, the first-wins duplicate rule, the column
 * order of a player's laurels and the reading of {@code collect}. A body that is not the
 * expected shape yields {@code null}, which {@link PolledJson} turns into a kept snapshot. Only
 * {@code parse} is exercised, with no network.</p>
 */
class LaurelPollerTest {

    private static final UUID ALICE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID BOB = UUID.fromString("66666666-7777-8888-9999-000000000000");

    private static String entry(String uuid, String metric, int place) {
        return "{\"uuid\":\"" + uuid + "\",\"metric\":\"" + metric + "\",\"place\":" + place + "}";
    }

    private static String body(String... entries) {
        return "{\"laurels\":[" + String.join(",", entries) + "]}";
    }

    @Test
    void aResponseBecomesAPerPlayerLookup() {
        LaurelStandings standings =
                LaurelPoller.parse(
                        "{\"laurels\":["
                                + entry(ALICE.toString(), "xp", 1)
                                + ","
                                + entry(ALICE.toString(), "return", 3)
                                + ","
                                + entry(BOB.toString(), "graids", 2)
                                + "],\"updated_at\":{\"xp\":1.0},\"sources\":{\"streak\":\"api\"},"
                                + "\"collect\":[]}");

        assertEquals(2, standings.byPlayer().size());
        assertEquals(
                List.of(new Laurel(LaurelMetric.XP, 1), new Laurel(LaurelMetric.RETURN, 3)),
                standings.byPlayer().get(ALICE));
        assertEquals(List.of(new Laurel(LaurelMetric.GRAIDS, 2)), standings.byPlayer().get(BOB));
        assertTrue(standings.collect().isEmpty());
    }

    @Test
    void anEmptyLaurelsArrayIsAnEmptyGenerationNotNull() {
        // Null means "not fetched yet", which an empty set of standings must not be mistaken for.
        LaurelStandings standings = LaurelPoller.parse(body());

        assertNotNull(standings);
        assertTrue(standings.byPlayer().isEmpty());
    }

    @Test
    void dashedAndUndashedUuidsAreTheSamePlayer() {
        LaurelStandings standings =
                LaurelPoller.parse(
                        body(
                                entry(ALICE.toString(), "xp", 1),
                                entry("11111111222233334444555555555555", "graids", 2)));

        assertEquals(1, standings.byPlayer().size());
        assertEquals(2, standings.byPlayer().get(ALICE).size());
    }

    @Test
    void anEntryThatIsNotAnObjectIsSkippedAndTheRestKept() {
        LaurelStandings standings =
                LaurelPoller.parse(
                        "{\"laurels\":[\"xp\",7,null,[],"
                                + entry(ALICE.toString(), "xp", 1)
                                + "]}");

        assertEquals(List.of(new Laurel(LaurelMetric.XP, 1)), standings.byPlayer().get(ALICE));
    }

    @Test
    void anUnparseableUuidIsSkipped() {
        LaurelStandings standings =
                LaurelPoller.parse(
                        body(
                                entry("not-a-uuid", "xp", 1),
                                "{\"metric\":\"xp\",\"place\":1}",
                                entry(ALICE.toString(), "graids", 1)));

        assertEquals(Set.of(ALICE), standings.byPlayer().keySet());
    }

    @Test
    void anUnknownOrMissingMetricIsSkipped() {
        LaurelStandings standings =
                LaurelPoller.parse(
                        body(
                                entry(ALICE.toString(), "kills", 1),
                                entry(ALICE.toString(), "XP", 1),
                                "{\"uuid\":\"" + ALICE + "\",\"place\":1}",
                                entry(BOB.toString(), "xp", 1)));

        assertEquals(Set.of(BOB), standings.byPlayer().keySet());
    }

    @Test
    void aPlaceOutsideOneToThreeIsSkipped() {
        LaurelStandings standings =
                LaurelPoller.parse(
                        body(
                                entry(ALICE.toString(), "xp", 0),
                                entry(ALICE.toString(), "graids", 4),
                                entry(ALICE.toString(), "streak", -1),
                                "{\"uuid\":\"" + ALICE + "\",\"metric\":\"return\"}",
                                entry(BOB.toString(), "xp", 3)));

        assertEquals(Set.of(BOB), standings.byPlayer().keySet());
    }

    @Test
    void theFirstOfARepeatedPlayerAndMetricWins() {
        LaurelStandings standings =
                LaurelPoller.parse(
                        body(entry(ALICE.toString(), "xp", 2), entry(ALICE.toString(), "xp", 1)));

        assertEquals(List.of(new Laurel(LaurelMetric.XP, 2)), standings.byPlayer().get(ALICE));
    }

    @Test
    void aPlayersLaurelsAreOrderedBySheetColumn() {
        LaurelStandings standings =
                LaurelPoller.parse(
                        body(
                                entry(ALICE.toString(), "return", 1),
                                entry(ALICE.toString(), "streak", 2),
                                entry(ALICE.toString(), "xp", 3),
                                entry(ALICE.toString(), "graids", 1)));

        assertEquals(
                List.of(
                        new Laurel(LaurelMetric.XP, 3),
                        new Laurel(LaurelMetric.GRAIDS, 1),
                        new Laurel(LaurelMetric.STREAK, 2),
                        new Laurel(LaurelMetric.RETURN, 1)),
                standings.byPlayer().get(ALICE));
    }

    @Test
    void collectNamesAreReadAndUnknownOnesDropped() {
        LaurelStandings standings =
                LaurelPoller.parse(
                        "{\"laurels\":[],\"collect\":[\"streak\",\"kills\",7,null,{},\"streak\"]}");

        assertEquals(Set.of(LaurelMetric.STREAK), standings.collect());
    }

    @Test
    void aMissingOrNonArrayCollectIsEmpty() {
        assertTrue(LaurelPoller.parse("{\"laurels\":[]}").collect().isEmpty());
        assertTrue(
                LaurelPoller.parse("{\"laurels\":[],\"collect\":\"streak\"}").collect().isEmpty());
        assertTrue(LaurelPoller.parse("{\"laurels\":[],\"collect\":null}").collect().isEmpty());
    }

    @Test
    void aBodyThatIsNotTheExpectedShapeIsNull() {
        assertNull(LaurelPoller.parse(""));
        assertNull(LaurelPoller.parse("null"));
        assertNull(LaurelPoller.parse("[]"));
        assertNull(LaurelPoller.parse("\"laurels\""));
        assertNull(LaurelPoller.parse("{}"));
        assertNull(LaurelPoller.parse("{\"laurels\":{}}"));
        assertNull(LaurelPoller.parse("{\"laurels\":null,\"collect\":[\"streak\"]}"));
    }

    @Test
    void malformedJsonThrowsForPolledJsonToCatch() {
        assertThrows(JsonSyntaxException.class, () -> LaurelPoller.parse("{\"laurels\":["));
    }

    @Test
    void theResultCannotBeMutatedThroughItsAccessors() {
        LaurelStandings standings = LaurelPoller.parse(body(entry(ALICE.toString(), "xp", 1)));

        assertThrows(
                UnsupportedOperationException.class,
                () -> standings.byPlayer().get(ALICE).add(new Laurel(LaurelMetric.RETURN, 1)));
        assertThrows(UnsupportedOperationException.class, () -> standings.byPlayer().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> standings.collect().add(LaurelMetric.XP));
    }
}
