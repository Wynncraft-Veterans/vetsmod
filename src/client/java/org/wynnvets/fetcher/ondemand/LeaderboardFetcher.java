package org.wynnvets.fetcher.ondemand;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.api.VetsApi;
import org.wynnvets.laurels.LaurelMetric;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.HttpClients;
import org.wynnvets.util.Json;

/**
 * On-demand fetcher for {@code /wv list xp} and {@code /wv list graids}: the guild's members
 * ranked by an all-time value the VetsMod server keeps, printed the way {@code /gu list} prints
 * guild XP.
 *
 * <p>The server ranks ({@link VetsApi#leaderboard}), in the order the laurel podiums are cut
 * from, so the top three rows are the laurel holders. Its XP is cumulative across stints in the
 * guild, so it can differ from {@code /gu list}'s. This class prints the first {@value #SHOWN}
 * rows, as many as {@code /gu list} does. See {@code vetsmod_laurels.md}.</p>
 */
public final class LeaderboardFetcher {
    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    /** How many rows a list prints: as many as {@code /gu list} does. */
    // Package-private for unit tests. See LeaderboardFetcherTest.
    static final int SHOWN = 25;

    /** Printed in place of the list when the request itself fails. Worded like
     *  {@link MotdFetcher}'s line for the same outage. */
    private static final String UNREACHABLE_LINE =
            "Leaderboard Unavailable: Unable to reach the vets API, which may be down.";

    /** Printed in place of the list when the server answers but not with a list: its 503 right
     *  after a restart, before it has read the guild, or an unreadable body. */
    private static final String UNANSWERED_LINE =
            "Leaderboard unavailable right now; try again in a few minutes.";

    /**
     * One ranked row.
     *
     * @param rank the row's 1-based position in the server's list
     * @param username the player's name
     * @param value the player's all-time value
     */
    // Package-private for unit tests. See LeaderboardFetcherTest.
    record Entry(int rank, String username, long value) {}

    private LeaderboardFetcher() {}

    /**
     * Fetches the ranking for {@code metric} and formats it for chat.
     *
     * @param metric {@link LaurelMetric#XP} or {@link LaurelMetric#GRAIDS}, the two the server
     *     ranks in full
     * @return the list, or a red line when the server cannot be read
     */
    public static CompletableFuture<MutableComponent> fetchLeaderboard(LaurelMetric metric) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(VetsApi.leaderboard(metric.wireName()))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

        return HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        response -> {
                            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                                VetsLogger.debug(
                                        "Leaderboard fetch for {} returned status {}",
                                        metric.wireName(),
                                        response.statusCode());
                                return redLine(UNANSWERED_LINE);
                            }
                            List<Entry> entries = parse(response.body());
                            return entries == null
                                    ? redLine(UNANSWERED_LINE)
                                    : format(metric, entries);
                        })
                .exceptionally(
                        e -> {
                            VetsLogger.debug(
                                    "Leaderboard fetch for {} failed: {}",
                                    metric.wireName(),
                                    e.toString());
                            return redLine(UNREACHABLE_LINE);
                        });
    }

    private static MutableComponent redLine(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    /**
     * Parses a response body. A row's rank is its position in {@code entries}, so a row skipped
     * for a missing name or a non-numeric {@code value} leaves a gap in the ranks instead of
     * moving the rows below it up. Each skipped row warns, as a wrong-typed field read through
     * {@link Json} does.
     *
     * @param body the response body
     * @return the rows in the server's order, or {@code null} when the body is not JSON, not an
     *     object, or has no {@code entries} array
     */
    // Package-private for unit tests. See LeaderboardFetcherTest.
    static List<Entry> parse(String body) {
        JsonElement root;
        try {
            root = Json.GSON.fromJson(body, JsonElement.class);
        } catch (JsonParseException e) {
            return null;
        }
        if (root == null || !root.isJsonObject()) {
            return null;
        }
        JsonElement entries = root.getAsJsonObject().get("entries");
        if (entries == null || !entries.isJsonArray()) {
            return null;
        }

        List<Entry> parsed = new ArrayList<>();
        int rank = 0;
        for (JsonElement element : entries.getAsJsonArray()) {
            rank++;
            JsonObject entry = element.isJsonObject() ? element.getAsJsonObject() : null;
            String username = entry == null ? null : Json.stringOrNull(entry, "username");
            // Read inline as a long: guild XP passes 2^31, past Json.optInt.
            JsonElement value = entry == null ? null : entry.get("value");
            if (username == null
                    || username.isEmpty()
                    || value == null
                    || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isNumber()) {
                VetsLogger.warn("Leaderboard row {} skipped: no username or numeric value", rank);
                continue;
            }
            parsed.add(new Entry(rank, username, value.getAsLong()));
        }
        return parsed;
    }

    /**
     * Formats the rows ranked {@value #SHOWN} or higher under a heading, each as {@code /gu list}
     * colours it: {@code #rank} gold, the name white, the separator dark gray and the value gray.
     *
     * @param metric {@link LaurelMetric#XP} or {@link LaurelMetric#GRAIDS}
     * @param entries the rows from {@link #parse}
     * @return the list, or a yellow line when there are no rows
     */
    // Package-private for unit tests. See LeaderboardFetcherTest.
    static MutableComponent format(LaurelMetric metric, List<Entry> entries) {
        String heading =
                switch (metric) {
                    case XP -> "Guild XP";
                    case GRAIDS -> "Guild Raids";
                    case STREAK, RETURN ->
                            throw new IllegalArgumentException("no leaderboard for " + metric);
                };
        if (entries.isEmpty()) {
            return Component.literal("No all-time " + heading + " recorded yet.")
                    .withStyle(ChatFormatting.YELLOW);
        }

        // An empty root, so the rows do not inherit the heading's bold.
        MutableComponent msg = Component.empty();
        msg.append(
                Component.literal("All-time " + heading)
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (Entry entry : entries) {
            if (entry.rank() > SHOWN) {
                break;
            }
            String unit =
                    metric == LaurelMetric.XP
                            ? " XP"
                            : entry.value() == 1 ? " Guild Raid" : " Guild Raids";
            msg.append(Component.literal("\n#" + entry.rank()).withStyle(ChatFormatting.GOLD))
                    .append(
                            Component.literal(" " + entry.username())
                                    .withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(entry.value() + unit).withStyle(ChatFormatting.GRAY));
        }
        return msg;
    }
}
