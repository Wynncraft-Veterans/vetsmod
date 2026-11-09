package org.wynnvets.extras.sudoku;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.wynntils.utils.mc.McUtils;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.api.VetsApi;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.Durations;
import org.wynnvets.util.HttpClients;
import org.wynnvets.util.Json;

/**
 * The daily sudoku's traffic with the VetsMod server, and where its progress is kept: fetches the
 * day's puzzle over HTTP, which works anywhere, sends a solved board on the inbound socket, which
 * exists only on Wynncraft, and turns the reply into chat lines. See {@code vetsmod_extras.md}.
 */
final class SudokuClient {
    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    private static final String FRAME_TYPE = "sudoku_submit";

    /** Printed in place of the board when the puzzle cannot be fetched. */
    private static final String UNAVAILABLE_LINE =
            "Sudoku unavailable right now; try again in a few minutes.";

    /** Printed for a player whose solve can never go out as things stand. */
    private static final String LINK_LINE = "Link your Discord with ~vetsmod to submit results.";

    /** Printed when a solve stays unsent and goes out at the next open on Wynncraft. */
    private static final String SEND_LATER_LINE = "Results send next time.";

    private static final Pattern GIVENS = Pattern.compile("[0-9]{" + SudokuGrid.CELLS + "}");

    /**
     * The day's puzzle, as the server sends it.
     *
     * @param day the puzzle's day
     * @param givens its digits, {@code 0} for a blank
     * @param difficulty its level
     */
    record Puzzle(String day, String givens, String difficulty) {

        /** The level as the book's left page shows it. */
        String difficultyLabel() {
            return StringUtils.capitalize(difficulty);
        }
    }

    /** What can happen to a solved board. */
    enum SendState {
        /** The session is authenticated, so it can go now. */
        READY,
        /**
         * The player holds a key the server has not rejected, but no session yet: the socket is
         * down, as it is off Wynncraft, or its auth has not gone through.
         */
        LATER,
        /** There is no key, or the server has rejected it. */
        NEVER
    }

    private SudokuClient() {}

    // ── The puzzle ───────────────────────────────────────────────────────

    /**
     * Fetches the day's puzzle.
     *
     * @return the puzzle, or {@code null} when it cannot be read; the failure is logged here
     */
    static CompletableFuture<Puzzle> fetchPuzzle() {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(VetsApi.SUDOKU)
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

        return HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        response -> {
                            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                                VetsLogger.debug(
                                        "Sudoku fetch returned status {}", response.statusCode());
                                return null;
                            }
                            return parsePuzzle(response.body());
                        })
                .exceptionally(
                        e -> {
                            VetsLogger.debug("Sudoku fetch failed: {}", e.toString());
                            return null;
                        });
    }

    /**
     * Parses a response body.
     *
     * @return the puzzle, or {@code null} when the body is not one
     */
    // Package-private for unit tests. See SudokuClientTest.
    static Puzzle parsePuzzle(String body) {
        Puzzle puzzle;
        try {
            puzzle = Json.GSON.fromJson(body, Puzzle.class);
        } catch (JsonParseException e) {
            return null;
        }
        boolean complete =
                puzzle != null
                        && puzzle.day() != null
                        && !puzzle.day().isEmpty()
                        && puzzle.givens() != null
                        && GIVENS.matcher(puzzle.givens()).matches()
                        && puzzle.difficulty() != null
                        && !puzzle.difficulty().isEmpty();
        return complete ? puzzle : null;
    }

    /** The red line for a failed fetch. */
    static Component unavailableLine() {
        return line(ChatFormatting.RED, UNAVAILABLE_LINE);
    }

    // ── Progress ─────────────────────────────────────────────────────────

    /**
     * The stored board.
     *
     * @return the board, or {@code null} when none is stored
     */
    static SudokuBoard loadProgress() {
        return SudokuBoard.fromJson(VetsConfig.getString(VetsConfigKeys.SUDOKU_PROGRESS));
    }

    static void saveProgress(SudokuBoard board) {
        VetsConfig.setString(VetsConfigKeys.SUDOKU_PROGRESS, board.toJson());
    }

    // ── Sending ──────────────────────────────────────────────────────────

    /** What can happen to a solved board right now. */
    static SendState sendState() {
        return sendState(
                GuildStateManager.isAuthenticatedThisSession(),
                V1ApiManager.isInboundConnected(),
                GuildStateManager.hasStoredAuthKey(),
                GuildStateManager.isStoredKeyRejected());
    }

    // Package-private for unit tests. See SudokuClientTest.
    static SendState sendState(
            boolean authenticated, boolean socketUp, boolean hasKey, boolean keyRejected) {
        if (authenticated) return SendState.READY;
        return hasKey && !(socketUp && keyRejected) ? SendState.LATER : SendState.NEVER;
    }

    /**
     * Deals with a solved board by {@link #sendState()}: sends it and prints the reply, or prints
     * why it did not go.
     *
     * @param board the solved board
     * @param then run on the client thread once that is settled
     */
    static void submit(SudokuBoard board, Runnable then) {
        submit(board, board, then);
    }

    /**
     * Sends what a stored board holds unsent, the board it keeps aside first, as {@link #submit}
     * does each.
     *
     * @param board the stored board
     * @param then run on the client thread once both are settled, or at once with nothing unsent
     */
    static void submitUnsent(SudokuBoard board, Runnable then) {
        Runnable own = () -> submitIfUnsent(board, board, then);
        if (board.aside() != null) {
            submitIfUnsent(board, board.aside(), own);
        } else {
            own.run();
        }
    }

    private static void submitIfUnsent(SudokuBoard saved, SudokuBoard sent, Runnable then) {
        if (sent.isUnsent()) {
            submit(saved, sent, then);
        } else {
            then.run();
        }
    }

    /**
     * Sends {@code sent}, which is {@code saved} or the board it keeps aside, and on its reply
     * saves {@code saved}.
     */
    private static void submit(SudokuBoard saved, SudokuBoard sent, Runnable then) {
        SendState state = sendState();
        if (state == SendState.READY) {
            V1ApiManager.sendStaffActionFrame(
                    FRAME_TYPE,
                    submitFields(sent),
                    ack -> McUtils.mc().execute(() -> settle(saved, sent, ack, then)));
            return;
        }
        unsentLines(sent, state).forEach(ChatUtils::sendLocalMessage);
        then.run();
    }

    /**
     * Applies a reply to the board sent, once back on the client thread, and lets go of it if it
     * was kept aside and is finished. The stored copy is rewritten only while it is still that
     * puzzle's: a slow reply must not put back a board the player has since moved on from, a new
     * day's or a replaced puzzle's.
     */
    private static void settle(SudokuBoard saved, SudokuBoard sent, JsonObject ack, Runnable then) {
        ackLines(sent, ack).forEach(ChatUtils::sendLocalMessage);
        saved.settleAside();
        SudokuBoard stored = loadProgress();
        if (stored != null && stored.samePuzzle(saved)) saveProgress(saved);
        then.run();
    }

    /**
     * The frame's fields. The server stamps the player's identity from the session and works the
     * time out from {@code fills}, so neither is sent.
     */
    // Package-private for unit tests. See SudokuClientTest.
    static JsonObject submitFields(SudokuBoard board) {
        JsonArray fills = new JsonArray();
        for (int i = 0; i < SudokuGrid.CELLS; i++) {
            fills.add(board.fill(i));
        }
        JsonObject fields = new JsonObject();
        fields.addProperty("day", board.day());
        fields.addProperty("grid", board.grid());
        fields.add("fills", fills);
        return fields;
    }

    /**
     * Applies a reply to its board and gives the chat lines for it. An error marked
     * {@code retry: false} will never go through, so the board stops trying; anything else stays
     * unsent. That covers the server's {@code retry: true} and the client's own error acks for a
     * lost socket or no reply, which carry no {@code retry} key.
     */
    // Package-private for unit tests. See SudokuClientTest.
    static List<Component> ackLines(SudokuBoard board, JsonObject ack) {
        if ("ok".equals(Json.optString(ack, "status", "error"))) {
            board.markSubmitted();
            int rank = Json.optInt(ack, "rank", 0);
            int streak = Json.optInt(ack, "streak", 0);
            board.recordResult(rank, streak);
            return List.of(
                    line(
                            ChatFormatting.GREEN,
                            solvedIn(Json.optInt(ack, "seconds", board.seconds()))
                                    + " Rank "
                                    + rank
                                    + ", "
                                    + streak
                                    + " day streak."));
        }
        if (Json.isFalse(ack, "retry")) {
            board.markSubmitted();
            return List.of(
                    line(ChatFormatting.GREEN, solvedIn(board.seconds())),
                    line(ChatFormatting.RED, Json.optString(ack, "detail", "Not accepted.")));
        }
        VetsLogger.debug("Sudoku result not sent: {}", ack);
        return unsentLines(board, SendState.LATER);
    }

    /** The chat lines for a board that did not go out. */
    // Package-private for unit tests. See SudokuClientTest.
    static List<Component> unsentLines(SudokuBoard board, SendState state) {
        return List.of(
                line(ChatFormatting.GREEN, solvedIn(board.seconds())),
                state == SendState.LATER
                        ? line(ChatFormatting.GRAY, SEND_LATER_LINE)
                        : line(ChatFormatting.YELLOW, LINK_LINE));
    }

    private static String solvedIn(int seconds) {
        return "Solved in " + Durations.minutesSeconds(seconds) + ".";
    }

    private static Component line(ChatFormatting colour, String text) {
        return Component.literal(text).withStyle(colour);
    }
}
