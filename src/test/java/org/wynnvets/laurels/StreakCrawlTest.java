package org.wynnvets.laurels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.wynnvets.distribute.walker.MembersListWalker.MemberEntry;

/**
 * The pure parts of {@link StreakCrawl}: reading a streak off a Members tile's lore, building the
 * frame's entries from the tiles, and the chat lines an ack to that frame becomes.
 *
 * <p>The walk itself ({@code run}) drives the Members menu and is not exercised. A tile is built
 * with {@code tile(name, lore…)}, and an ack with {@code ack(json)}.</p>
 */
class StreakCrawlTest {

    private static MemberEntry tile(String name, String... lore) {
        return new MemberEntry(name, List.of(lore));
    }

    private static JsonObject ack(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static List<String> texts(List<Component> lines) {
        return lines.stream().map(Component::getString).toList();
    }

    private static void assertColour(ChatFormatting expected, Component line) {
        assertEquals(expected.getColor().intValue(), line.getStyle().getColor().getValue());
    }

    // ----- parseStreak -----

    @Test
    void theStreakLineOfATypicalTileIsRead() {
        assertEquals(
                OptionalInt.of(76),
                StreakCrawl.parseStreak(
                        List.of(
                                "Rank: Chief",
                                "Guild Objective:",
                                "- Gather Ores: 200/200",
                                "- Streak: 76")));
    }

    @Test
    void aStreakLineIsReadWhateverComesBeforeIt() {
        assertEquals(OptionalInt.of(3), StreakCrawl.parseStreak(List.of("- Streak: 3")));
    }

    @Test
    void aZeroStreakIsAStreak() {
        assertEquals(OptionalInt.of(0), StreakCrawl.parseStreak(List.of("- Streak: 0")));
    }

    @Test
    void theFirstStreakLineWins() {
        assertEquals(
                OptionalInt.of(5), StreakCrawl.parseStreak(List.of("- Streak: 5", "- Streak: 9")));
    }

    @Test
    void aTileWithoutAStreakLineHasNone() {
        assertEquals(OptionalInt.empty(), StreakCrawl.parseStreak(List.of()));
        assertEquals(
                OptionalInt.empty(),
                StreakCrawl.parseStreak(List.of("Guild Objective:", "- Gather Ores: 200/200")));
    }

    @Test
    void aStreakLineThatIsNotJustDigitsIsNotAStreak() {
        assertEquals(OptionalInt.empty(), StreakCrawl.parseStreak(List.of("- Streak: ")));
        assertEquals(OptionalInt.empty(), StreakCrawl.parseStreak(List.of("- Streak: many")));
        assertEquals(OptionalInt.empty(), StreakCrawl.parseStreak(List.of("- Streak: 7 weeks")));
        assertEquals(OptionalInt.empty(), StreakCrawl.parseStreak(List.of("- Streak: -2")));
        assertEquals(OptionalInt.empty(), StreakCrawl.parseStreak(List.of("Streak: 4")));
    }

    @Test
    void aStreakTooBigForAnIntIsNone() {
        assertEquals(
                OptionalInt.empty(), StreakCrawl.parseStreak(List.of("- Streak: 99999999999")));
    }

    // ----- streakEntries -----

    @Test
    void entriesFollowTheMenuOrderAndSkipTilesWithoutAStreak() {
        assertEquals(
                "[{\"name\":\"Alpha\",\"streak\":4},{\"name\":\"Gamma\",\"streak\":0}]",
                StreakCrawl.streakEntries(
                                List.of(
                                        tile("Alpha", "- Streak: 4"),
                                        tile("Beta", "Guild Objective:"),
                                        tile("Gamma", "- Streak: 0")))
                        .toString());
    }

    @Test
    void noTilesGiveNoEntries() {
        assertTrue(StreakCrawl.streakEntries(List.of()).isEmpty());
        assertTrue(StreakCrawl.streakEntries(List.of(tile("Alpha"))).isEmpty());
    }

    // ----- okLines -----

    @Test
    void anUploadThatWasStoredAndShownIsOneGreenLine() {
        List<Component> lines =
                StreakCrawl.okLines(
                        ack(
                                "{\"status\":\"ok\",\"accepted\":98,\"unresolved\":[],"
                                        + "\"replaced\":true,\"active\":true}"));

        assertEquals(List.of("Read objective streaks: 98 matched to members."), texts(lines));
        assertColour(ChatFormatting.GREEN, lines.get(0));
    }

    @Test
    void membersTheServerDidNotMatchAreListedInYellow() {
        List<Component> lines =
                StreakCrawl.okLines(
                        ack(
                                "{\"accepted\":2,\"unresolved\":[\"Alpha\",\"Beta\"],"
                                        + "\"replaced\":true,\"active\":true}"));

        assertEquals(
                List.of(
                        "Read objective streaks: 2 matched to members.",
                        "Not matched to a member: Alpha, Beta."),
                texts(lines));
        assertColour(ChatFormatting.YELLOW, lines.get(1));
    }

    @Test
    void onlyTheFirstTenUnmatchedNamesAreListed() {
        List<Component> lines =
                StreakCrawl.okLines(
                        ack(
                                "{\"accepted\":1,\"unresolved\":[\"a\",\"b\",\"c\",\"d\",\"e\","
                                        + "\"f\",\"g\",\"h\",\"i\",\"j\",\"k\",\"l\"],"
                                        + "\"replaced\":true,\"active\":true}"));

        assertEquals(
                "Not matched to a member: a, b, c, d, e, f, g, h, i, j and 2 more.",
                texts(lines).get(1));
    }

    @Test
    void anUploadTheServerDidNotStoreSaysSoInGray() {
        List<Component> lines =
                StreakCrawl.okLines(ack("{\"accepted\":10,\"unresolved\":[],\"replaced\":false}"));

        assertEquals(
                List.of(
                        "Read objective streaks: 10 matched to members.",
                        "Too few members matched, so the server kept its previous streaks."),
                texts(lines));
        assertColour(ChatFormatting.GRAY, lines.get(1));
    }

    @Test
    void anUploadTheApiHasSupersededSaysItIsStoredButNotShown() {
        List<Component> lines =
                StreakCrawl.okLines(
                        ack(
                                "{\"accepted\":98,\"unresolved\":[],\"replaced\":true,"
                                        + "\"active\":false}"));

        assertEquals(
                List.of(
                        "Read objective streaks: 98 matched to members.",
                        "The server reads streaks from the Wynncraft API now, so this upload is"
                                + " stored but not shown."),
                texts(lines));
        assertColour(ChatFormatting.GRAY, lines.get(1));
    }

    @Test
    void anAckMissingItsOptionalFieldsIsJustTheCount() {
        assertEquals(
                List.of("Read objective streaks: 0 matched to members."),
                texts(StreakCrawl.okLines(ack("{}"))));
    }
}
