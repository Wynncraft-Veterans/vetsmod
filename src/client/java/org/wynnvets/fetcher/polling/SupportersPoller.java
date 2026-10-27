package org.wynnvets.fetcher.polling;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.wynnvets.api.VetsApi;
import org.wynnvets.util.Json;

/**
 * Fetches and caches the usernames the VetsMod server lists at {@code /v1/outbound/supporters}. Per
 * temp-server, that is today its currently-glinted list rather than every supporter.
 *
 * <p>Supporters can get glint styling on chat pills, nametags and {@code /wv list} entries, gated
 * on the viewing client's {@code showSupporterGlints}; the readers are listed in
 * vetsmod_networking.md §5. The list is refreshed every {@value #REFRESH_INTERVAL_MINUTES}
 * minutes.</p>
 */
public final class SupportersPoller {
    private static final int REFRESH_INTERVAL_MINUTES = 5;
    private static final int REQUEST_TIMEOUT_SECONDS = 5;

    private static final PolledJson<Set<String>> POLL =
            new PolledJson<>(
                    VetsApi.SUPPORTERS,
                    "VetsMod-SupportersFetcher",
                    "supporters",
                    REFRESH_INTERVAL_MINUTES,
                    REQUEST_TIMEOUT_SECONDS,
                    Set.of(),
                    SupportersPoller::parseSupporters);

    private SupportersPoller() {}

    public static void start() {
        POLL.start();
    }

    /**
     * Checks whether a username is in the supporters list.
     * Comparison is case-insensitive.
     *
     * @param username the username to check
     * @return {@code true} if the username belongs to a supporter
     */
    public static boolean isSupporter(String username) {
        if (username == null || username.isEmpty()) {
            return false;
        }

        String normalized = normalizeCandidate(username);
        if (normalized.isEmpty()) {
            return false;
        }

        Set<String> snapshot = POLL.snapshot();

        if (snapshot.contains(normalized)) {
            return true;
        }

        // Nickname mode may render names as "real/nick" or "nick/real".
        int slashIndex = normalized.indexOf('/');
        if (slashIndex >= 0) {
            String left = normalizeCandidate(normalized.substring(0, slashIndex));
            String right = normalizeCandidate(normalized.substring(slashIndex + 1));
            return (!left.isEmpty() && snapshot.contains(left))
                    || (!right.isEmpty() && snapshot.contains(right));
        }

        return false;
    }

    private static String normalizeCandidate(String value) {
        if (value == null) {
            return "";
        }

        return value.trim()
                .replace("\u00A0", "")
                .replaceAll("(?i)<\\d+>", "")
                .toLowerCase(Locale.ROOT);
    }

    private static Set<String> parseSupporters(String jsonResponse) {
        JsonArray supporters = Json.GSON.fromJson(jsonResponse, JsonArray.class);
        Set<String> newSet = new HashSet<>();

        for (int i = 0; i < supporters.size(); i++) {
            JsonObject supporter = supporters.get(i).getAsJsonObject();
            String username = supporter.get("username").getAsString();
            if (username != null && !username.isEmpty()) {
                newSet.add(username.toLowerCase(Locale.ROOT));
            }
        }

        return Set.copyOf(newSet);
    }
}
