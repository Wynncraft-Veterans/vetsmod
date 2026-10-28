package org.wynnvets.fetcher.ondemand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.Test;
import org.wynnvets.laurels.LaurelMetric;

/**
 * The pure parts of {@link LeaderboardFetcher}: {@code parse} over the server's body, and
 * {@code format}'s heading, rows, colours and cut-off. The request itself is not exercised.
 */
class LeaderboardFetcherTest {

    private static String body(String entries) {
        return "{\"metric\":\"xp\",\"updated_at\":1,\"entries\":[" + entries + "]}";
    }

    private static String row(String username, String value) {
        return "{\"uuid\":\"u\",\"username\":" + username + ",\"value\":" + value + "}";
    }

    private static List<String> lines(MutableComponent page) {
        return List.of(page.getString().split("\n"));
    }

    private static void assertColour(ChatFormatting expected, Component part) {
        assertEquals(expected.getColor().intValue(), part.getStyle().getColor().getValue());
    }

    // ----- parse -----

    @Test
    void aRowKeepsItsPositionAsItsRankAndAValuePast2To31() {
        List<LeaderboardFetcher.Entry> entries =
                LeaderboardFetcher.parse(
                        body(row("\"Crasher\"", "48618507581") + "," + row("\"Holidaze\"", "7")));

        assertEquals(
                List.of(
                        new LeaderboardFetcher.Entry(1, "Crasher", 48618507581L),
                        new LeaderboardFetcher.Entry(2, "Holidaze", 7L)),
                entries);
    }

    @Test
    void aRowWithNoNameOrNoNumericValueIsSkippedAndLeavesAGapInTheRanks() {
        List<LeaderboardFetcher.Entry> entries =
                LeaderboardFetcher.parse(
                        body(
                                String.join(
                                        ",",
                                        row("\"First\"", "9"),
                                        row("\"\"", "8"),
                                        row("null", "7"),
                                        row("\"Text\"", "\"6\""),
                                        "[]",
                                        row("\"Sixth\"", "5"))));

        assertEquals(
                List.of(
                        new LeaderboardFetcher.Entry(1, "First", 9L),
                        new LeaderboardFetcher.Entry(6, "Sixth", 5L)),
                entries);
    }

    @Test
    void aBodyThatIsNotJsonOrNotAnObjectOrHasNoEntriesArrayIsNull() {
        assertNull(LeaderboardFetcher.parse("<html>502 Bad Gateway</html>"));
        assertNull(LeaderboardFetcher.parse("{\"entries\":["));
        assertNull(LeaderboardFetcher.parse("[]"));
        assertNull(LeaderboardFetcher.parse("{}"));
        assertNull(LeaderboardFetcher.parse("{\"entries\":{}}"));
    }

    // ----- format -----

    @Test
    void xpRowsReadLikeGuList() {
        MutableComponent page =
                LeaderboardFetcher.format(
                        LaurelMetric.XP,
                        List.of(
                                new LeaderboardFetcher.Entry(1, "Crasher", 48618507581L),
                                new LeaderboardFetcher.Entry(2, "Holidaze", 1L)));

        assertEquals(
                List.of("All-time Guild XP", "#1 Crasher - 48618507581 XP", "#2 Holidaze - 1 XP"),
                lines(page));
    }

    @Test
    void graidRowsSayRaidsAndOneRaidIsSingular() {
        MutableComponent page =
                LeaderboardFetcher.format(
                        LaurelMetric.GRAIDS,
                        List.of(
                                new LeaderboardFetcher.Entry(1, "A", 2137L),
                                new LeaderboardFetcher.Entry(2, "B", 1L)));

        assertEquals(
                List.of("All-time Guild Raids", "#1 A - 2137 Guild Raids", "#2 B - 1 Guild Raid"),
                lines(page));
    }

    @Test
    void theHeadingIsBoldGoldAndARowIsColouredLikeGuListWithoutTheBold() {
        MutableComponent page =
                LeaderboardFetcher.format(
                        LaurelMetric.XP, List.of(new LeaderboardFetcher.Entry(1, "Crasher", 5L)));

        List<Component> parts = page.getSiblings();
        assertEquals(5, parts.size());
        assertColour(ChatFormatting.GOLD, parts.get(0));
        assertTrue(parts.get(0).getStyle().isBold());
        assertColour(ChatFormatting.GOLD, parts.get(1));
        assertColour(ChatFormatting.WHITE, parts.get(2));
        assertColour(ChatFormatting.DARK_GRAY, parts.get(3));
        assertColour(ChatFormatting.GRAY, parts.get(4));
        assertFalse(page.getStyle().isBold(), "the root must not pass the bold to the rows");
    }

    @Test
    void onlyTheRowsRankedUpToTheCutOffArePrinted() {
        List<LeaderboardFetcher.Entry> entries = new ArrayList<>();
        for (int rank = 1; rank <= LeaderboardFetcher.SHOWN + 5; rank++) {
            entries.add(new LeaderboardFetcher.Entry(rank, "P" + rank, 100 - rank));
        }

        List<String> lines = lines(LeaderboardFetcher.format(LaurelMetric.XP, entries));

        assertEquals(25, LeaderboardFetcher.SHOWN, "as many rows as /gu list prints");
        assertEquals(1 + LeaderboardFetcher.SHOWN, lines.size());
        assertEquals("#25 P25 - 75 XP", lines.get(lines.size() - 1));
    }

    @Test
    void noRowsIsAYellowLine() {
        MutableComponent page = LeaderboardFetcher.format(LaurelMetric.GRAIDS, List.of());

        assertEquals("No all-time Guild Raids recorded yet.", page.getString());
        assertColour(ChatFormatting.YELLOW, page);
    }

    @Test
    void aMetricTheServerDoesNotRankIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LeaderboardFetcher.format(LaurelMetric.STREAK, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> LeaderboardFetcher.format(LaurelMetric.RETURN, List.of()));
    }
}
