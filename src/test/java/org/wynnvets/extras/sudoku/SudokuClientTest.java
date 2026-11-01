package org.wynnvets.extras.sudoku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

/**
 * The pure parts of {@link SudokuClient}: the puzzle parse, the frame's fields, who can send what,
 * and the chat lines and board state a reply turns into. The network send and the stored copy are
 * not exercised: they go through the socket and {@code VetsConfig}.
 */
class SudokuClientTest {

    private static final String DAY = "2026-11-01";

    private static final String SOLUTION =
            "534678912672195348198342567859761423426853791713924856961537284287419635345286179";

    private static final String GIVENS = "0" + SOLUTION.substring(1);

    private static JsonObject ack(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** A board solved by its one entry, 205 seconds in. */
    private static SudokuBoard solved() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        board.setActiveMs(205_000);
        board.set(0, 5);
        assertTrue(board.trySolve());
        return board;
    }

    private static List<String> texts(List<Component> lines) {
        return lines.stream().map(Component::getString).toList();
    }

    private static TextColor colour(ChatFormatting formatting) {
        return TextColor.fromLegacyFormat(formatting);
    }

    // ── The puzzle ───────────────────────────────────────────────────────

    @Test
    void parsePuzzle_readsTheThreeFields() {
        SudokuClient.Puzzle puzzle =
                SudokuClient.parsePuzzle(
                        "{\"day\":\""
                                + DAY
                                + "\",\"givens\":\""
                                + GIVENS
                                + "\",\"difficulty\":\"medium\"}");

        assertNotNull(puzzle);
        assertEquals(DAY, puzzle.day());
        assertEquals(GIVENS, puzzle.givens());
        assertEquals("medium", puzzle.difficulty());
        assertEquals("Medium", puzzle.difficultyLabel());
    }

    @Test
    void parsePuzzle_answersNullForABodyThatIsNotOne() {
        for (String bad :
                new String[] {
                    "",
                    "not json {",
                    "[]",
                    "{}",
                    "{\"givens\":\"" + GIVENS + "\",\"difficulty\":\"easy\"}",
                    "{\"day\":\"\",\"givens\":\"" + GIVENS + "\",\"difficulty\":\"easy\"}",
                    "{\"day\":\"d\",\"difficulty\":\"easy\"}",
                    "{\"day\":\"d\",\"givens\":\"123\",\"difficulty\":\"easy\"}",
                    "{\"day\":\"d\",\"givens\":\""
                            + GIVENS.replace('0', 'x')
                            + "\",\"difficulty\":\"easy\"}",
                    "{\"day\":\"d\",\"givens\":\"" + GIVENS + "\"}",
                    "{\"day\":\"d\",\"givens\":\"" + GIVENS + "\",\"difficulty\":\"\"}"
                }) {
            assertNull(SudokuClient.parsePuzzle(bad), bad);
        }
    }

    // ── The frame ────────────────────────────────────────────────────────

    @Test
    void submitFields_holdTheDayTheGridAndTheFills() {
        JsonObject fields = SudokuClient.submitFields(solved());

        assertEquals(Set.of("day", "grid", "fills"), fields.keySet());
        assertEquals(DAY, fields.get("day").getAsString());
        assertEquals(SOLUTION, fields.get("grid").getAsString());
        JsonArray fills = fields.getAsJsonArray("fills");
        assertEquals(81, fills.size());
        assertEquals(205, fills.get(0).getAsInt());
        for (int i = 1; i < 81; i++) {
            assertEquals(0, fills.get(i).getAsInt(), "the given at " + i);
        }
    }

    // ── Who can send ─────────────────────────────────────────────────────

    @Test
    void sendState_readyOnAnAuthenticatedSession() {
        assertEquals(SudokuClient.SendState.READY, SudokuClient.sendState(true, true, true, false));
    }

    @Test
    void sendState_laterForAKeyHolderWithNoSocket() {
        assertEquals(
                SudokuClient.SendState.LATER, SudokuClient.sendState(false, false, true, false));
        assertEquals(
                SudokuClient.SendState.LATER, SudokuClient.sendState(false, false, true, true));
    }

    @Test
    void sendState_laterForAKeyStillWaitingOnItsAuth() {
        assertEquals(
                SudokuClient.SendState.LATER, SudokuClient.sendState(false, true, true, false));
    }

    @Test
    void sendState_neverWithNoKeyOrARejectedKey() {
        assertEquals(
                SudokuClient.SendState.NEVER, SudokuClient.sendState(false, false, false, false));
        assertEquals(
                SudokuClient.SendState.NEVER, SudokuClient.sendState(false, true, false, false));
        assertEquals(SudokuClient.SendState.NEVER, SudokuClient.sendState(false, true, true, true));
    }

    // ── Replies ──────────────────────────────────────────────────────────

    @Test
    void anOkReply_printsTheTimeRankAndStreak_andFinishesTheBoard() {
        SudokuBoard board = solved();

        List<Component> lines =
                SudokuClient.ackLines(
                        board,
                        ack(
                                "{\"status\":\"ok\",\"seconds\":207,\"rank\":4,\"streak\":12,"
                                        + "\"duplicate\":false}"));

        assertEquals(List.of("Solved in 3:27. Rank 4, 12 day streak."), texts(lines));
        assertEquals("Solved, rank 4", board.status());
        assertTrue(board.ribbon(true).endsWith("Streak 12"));
        assertEquals(colour(ChatFormatting.GREEN), lines.get(0).getStyle().getColor());
        assertFalse(board.isUnsent());
    }

    @Test
    void aDuplicateReply_stillPrints() {
        SudokuBoard board = solved();

        List<Component> lines =
                SudokuClient.ackLines(
                        board,
                        ack(
                                "{\"status\":\"ok\",\"seconds\":205,\"rank\":1,\"streak\":2,"
                                        + "\"duplicate\":true}"));

        assertEquals(List.of("Solved in 3:25. Rank 1, 2 day streak."), texts(lines));
        assertFalse(board.isUnsent());
    }

    @Test
    void aFinalError_printsItsDetail_andFinishesTheBoard() {
        SudokuBoard board = solved();

        List<Component> lines =
                SudokuClient.ackLines(
                        board,
                        ack(
                                "{\"status\":\"error\",\"detail\":\"That puzzle's day is over.\","
                                        + "\"retry\":false}"));

        assertEquals(List.of("Solved in 3:25.", "That puzzle's day is over."), texts(lines));
        assertEquals(colour(ChatFormatting.RED), lines.get(1).getStyle().getColor());
        assertFalse(board.isUnsent());
    }

    @Test
    void aRetryableError_keepsTheBoardUnsent_andHidesItsDetail() {
        SudokuBoard board = solved();

        List<Component> lines =
                SudokuClient.ackLines(
                        board,
                        ack(
                                "{\"status\":\"error\","
                                        + "\"detail\":\"Results are unavailable right now.\","
                                        + "\"retry\":true}"));

        assertEquals(List.of("Solved in 3:25.", "Results send next time."), texts(lines));
        assertTrue(board.isUnsent());
    }

    @Test
    void theClientsOwnErrorAcks_keepTheBoardUnsent() {
        for (String synthetic :
                new String[] {
                    "{\"status\":\"error\",\"detail\":\"Inbound WebSocket not connected.\"}",
                    "{\"status\":\"error\",\"detail\":\"Connection closed before reply.\"}",
                    "{\"status\":\"error\",\"detail\":\"Connection lost before reply.\","
                            + "\"outcome_unknown\":\"connection lost\"}",
                    "{\"status\":\"error\",\"detail\":\"No reply from the vets server.\","
                            + "\"outcome_unknown\":\"no reply\"}"
                }) {
            SudokuBoard board = solved();

            List<Component> lines = SudokuClient.ackLines(board, ack(synthetic));

            assertEquals(List.of("Solved in 3:25.", "Results send next time."), texts(lines));
            assertTrue(board.isUnsent(), synthetic);
        }
    }

    // ── A board that does not go out ─────────────────────────────────────

    @Test
    void unsentLines_laterSaysItSendsNextTime() {
        assertEquals(
                List.of("Solved in 3:25.", "Results send next time."),
                texts(SudokuClient.unsentLines(solved(), SudokuClient.SendState.LATER)));
    }

    @Test
    void unsentLines_neverPointsAtTheDiscordCommand() {
        assertEquals(
                List.of("Solved in 3:25.", "Link your Discord with ~vetsmod to submit results."),
                texts(SudokuClient.unsentLines(solved(), SudokuClient.SendState.NEVER)));
    }
}
