package org.wynnvets.distribute.utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wynntils.core.components.Models;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.wynnvets.api.WynnCraftApi;
import org.wynnvets.distribute.distributor.RandomDistributor;
import org.wynnvets.distribute.walker.MembersListSearcher;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

/**
 * Resolves a user-supplied name to the {@code legacyName} that the
 * Wynncraft Members GUI displays, by querying
 * {@code wapi /v3/guild/<localPlayerGuild>}.
 *
 * <p>The v3 payload's {@code members.<rank>.<currentName>.legacyName} shape (see
 * {@link org.wynnvets.fetcher.ondemand.UserInfoFetcher#analyzeRoster
 * UserInfoFetcher#analyzeRoster}) lets us map a player's <em>current</em> Mojang username to
 * whatever name the Wynncraft server has frozen onto its in-game tiles. Input matching is
 * case-insensitive against both the {@code currentName} key and the {@code legacyName} value, so
 * either form resolves to the correct GUI tile name.</p>
 *
 * <p>On any failure (no guild, no network, unexpected payload) returns
 * the input unchanged &mdash; the caller's literal-input arm still
 * matches if the user already typed the legacyName.</p>
 */
public final class NameResolver {

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

    private static final Gson GSON = new Gson();

    private NameResolver() {}

    /**
     * Async resolve. Always completes with a non-null name &mdash; either
     * the resolved {@code legacyName} or the original input if no
     * resolution was possible.
     */
    public static CompletableFuture<String> resolveLegacyName(String input) {
        if (input == null || input.isEmpty()) {
            return CompletableFuture.completedFuture(input);
        }
        if (!GuildStateManager.isWynntilsReady()) {
            return CompletableFuture.completedFuture(input);
        }
        String guildName = Models.Guild.getGuildName();
        if (guildName == null || guildName.isEmpty()) {
            return CompletableFuture.completedFuture(input);
        }

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(WynnCraftApi.guildInfo(guildName))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

        return HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        response -> {
                            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                                VetsLogger.debug(
                                        "NameResolver: wapi returned {} for guild [{}]",
                                        response.statusCode(),
                                        guildName);
                                return input;
                            }
                            return findLegacyName(response.body(), input);
                        })
                .exceptionally(
                        e -> {
                            VetsLogger.debug(
                                    "NameResolver: failed to resolve [{}]: {}",
                                    input,
                                    e.getMessage());
                            return input;
                        });
    }

    /**
     * Fetches the full guild roster from wapi and returns every member's
     * tile-displayed name &mdash; i.e. {@code legacyName} when present,
     * else the current Mojang username (for members who never renamed).
     *
     * <p>Used by {@link RandomDistributor} to pick recipients from the
     * exact name space the Members GUI uses, avoiding the race where
     * picking by current name and async-resolving per pick can miss
     * renamed members on small guilds with fast searches.</p>
     *
     * <p>Returns an empty list on any failure (no guild, no network,
     * unexpected payload).</p>
     */
    public static CompletableFuture<List<String>> fetchAllLegacyNames() {
        if (!GuildStateManager.isWynntilsReady()) {
            return CompletableFuture.completedFuture(List.of());
        }
        String guildName = Models.Guild.getGuildName();
        if (guildName == null || guildName.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(WynnCraftApi.guildInfo(guildName))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

        return HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        response -> {
                            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                                VetsLogger.debug(
                                        "NameResolver: wapi returned {} for guild [{}]",
                                        response.statusCode(),
                                        guildName);
                                return List.<String>of();
                            }
                            return extractAllLegacyNames(response.body());
                        })
                .exceptionally(
                        e -> {
                            VetsLogger.debug(
                                    "NameResolver: fetchAllLegacyNames failed: {}", e.getMessage());
                            return List.of();
                        });
    }

    // Package-private for unit tests. See NameResolverTest.
    static List<String> extractAllLegacyNames(String body) {
        List<String> names = new ArrayList<>();
        forEachGuildMember(
                body,
                (currentName, member) -> {
                    String legacy = legacyNameOf(member);
                    // Renamed member → tile shows legacy; otherwise → current
                    // (also covers malformed entries where member is null).
                    names.add(legacy != null ? legacy : currentName);
                    return false;
                });
        return names;
    }

    /**
     * Fetches the guild roster and returns a lowercase-keyed index from
     * <em>any known name</em> (current or legacy) to the tile-displayed
     * (legacy) name. Both forms are added so the caller can match a
     * username encountered in arbitrary text &mdash; e.g. a guild log
     * entry written before a player renamed &mdash; and recover the
     * single canonical legacy name that {@link MembersListSearcher} will
     * match against in the Members GUI.
     *
     * <p>Returns an empty map on failure.</p>
     */
    public static CompletableFuture<Map<String, String>> fetchNameIndex() {
        return fetchGuildJson()
                .thenApply(
                        body -> {
                            if (body == null) return Map.of();
                            return extractNameIndex(body);
                        });
    }

    /**
     * Fetches the guild roster and returns a map from each member's
     * normalized (dashless lowercase) Mojang UUID to the tile-displayed
     * (legacy) name shown in the Members GUI.
     *
     * <p>Used by {@link NoAspectsFilter} to translate an opt-out UUID
     * set served by {@code /v1/outbound/no-aspects} into the legacy-name
     * set the distributors filter against. UUID is preferred over name
     * as the load-bearing key because it survives renames.</p>
     *
     * <p>Returns an empty map on failure.</p>
     */
    public static CompletableFuture<Map<String, String>> fetchUuidToLegacyName() {
        return fetchGuildJson()
                .thenApply(
                        body -> {
                            if (body == null) return Map.of();
                            return extractUuidToLegacyName(body);
                        });
    }

    private static CompletableFuture<String> fetchGuildJson() {
        if (!GuildStateManager.isWynntilsReady()) {
            return CompletableFuture.completedFuture(null);
        }
        String guildName = Models.Guild.getGuildName();
        if (guildName == null || guildName.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(WynnCraftApi.guildInfo(guildName))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
        return HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        response -> {
                            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                                VetsLogger.debug(
                                        "NameResolver: wapi returned {} for guild [{}]",
                                        response.statusCode(),
                                        guildName);
                                return null;
                            }
                            return response.body();
                        })
                .exceptionally(
                        e -> {
                            VetsLogger.debug(
                                    "NameResolver: fetchGuildJson failed: {}", e.getMessage());
                            return null;
                        });
    }

    // Package-private for unit tests. See NameResolverTest.
    static Map<String, String> extractNameIndex(String body) {
        Map<String, String> index = new HashMap<>();
        forEachGuildMember(
                body,
                (currentName, member) -> {
                    String legacy = legacyNameOf(member);
                    String legacyName = legacy != null ? legacy : currentName;
                    // Both forms point at the canonical legacy (= tile) name.
                    // equalsIgnoreCase-style lookup is achieved by lowercasing
                    // the key.
                    index.put(currentName.toLowerCase(Locale.ROOT), legacyName);
                    index.put(legacyName.toLowerCase(Locale.ROOT), legacyName);
                    return false;
                });
        return index;
    }

    // Package-private for unit tests. See NameResolverTest.
    static Map<String, String> extractUuidToLegacyName(String body) {
        Map<String, String> map = new HashMap<>();
        forEachGuildMember(
                body,
                (currentName, member) -> {
                    if (member == null) return false;
                    String uuid = uuidOf(member);
                    if (uuid == null) return false;
                    String legacy = legacyNameOf(member);
                    map.put(normalizeUuid(uuid), legacy != null ? legacy : currentName);
                    return false;
                });
        return map;
    }

    // Package-private for unit tests. See NameResolverTest.
    static String findLegacyName(String body, String input) {
        String lowerInput = input.toLowerCase(Locale.ROOT);
        // One-element box so the lambda can publish its hit; default to
        // input so the not-found and parse-error paths both fall back
        // to the literal input the caller supplied.
        String[] result = {input};
        forEachGuildMember(
                body,
                (currentName, member) -> {
                    // Original skipped malformed entries entirely — preserve that
                    // so an input matching a malformed entry's currentName still
                    // returns the not-found fallback rather than the currentName.
                    if (member == null) return false;
                    String legacyName = legacyNameOf(member);
                    if (currentName.toLowerCase(Locale.ROOT).equals(lowerInput)) {
                        // Input matched a current name — return its legacy
                        // (or the current name itself if no rename happened).
                        result[0] = legacyName != null ? legacyName : currentName;
                        return true;
                    }
                    if (legacyName != null
                            && legacyName.toLowerCase(Locale.ROOT).equals(lowerInput)) {
                        // Input was already a legacy name — return verbatim.
                        result[0] = legacyName;
                        return true;
                    }
                    return false;
                });
        return result[0];
    }

    /** Callback invoked once per guild member by {@link #forEachGuildMember}.
     *  Return {@code true} to stop traversal early. */
    @FunctionalInterface
    private interface MemberHandler {
        boolean accept(String currentName, /* nullable */ JsonObject member);
    }

    /**
     * Walks {@code members.<rank>.<currentName>.<obj>} in a wapi guild
     * payload, calling {@code handler} once per member with the current
     * (Mojang) name and the member's JSON object (or {@code null} if the
     * value is missing or non-object). Skips the {@code "total"} bucket
     * the same way the upstream payload labels it. Silently swallows
     * parse / GSON exceptions &mdash; callers should pre-initialise
     * their accumulator so the empty-on-error case Just Works.
     */
    private static void forEachGuildMember(String body, MemberHandler handler) {
        try {
            JsonElement root = GSON.fromJson(body, JsonElement.class);
            if (root == null || !root.isJsonObject()) return;
            JsonElement membersEl = root.getAsJsonObject().get("members");
            if (membersEl == null || !membersEl.isJsonObject()) return;
            for (Map.Entry<String, JsonElement> rankBucket :
                    membersEl.getAsJsonObject().entrySet()) {
                if ("total".equals(rankBucket.getKey())) continue;
                JsonElement bucketEl = rankBucket.getValue();
                if (bucketEl == null || !bucketEl.isJsonObject()) continue;
                for (Map.Entry<String, JsonElement> memberEntry :
                        bucketEl.getAsJsonObject().entrySet()) {
                    String currentName = memberEntry.getKey();
                    JsonElement memberEl = memberEntry.getValue();
                    JsonObject member =
                            (memberEl != null && memberEl.isJsonObject())
                                    ? memberEl.getAsJsonObject()
                                    : null;
                    if (handler.accept(currentName, member)) return;
                }
            }
        } catch (Exception e) {
            VetsLogger.debug("NameResolver: parse error: {}", e.getMessage());
        }
    }

    /** Returns the member's {@code legacyName} field if present and
     *  non-null, else {@code null}. Tolerates a {@code null} member
     *  argument (returns {@code null}). */
    private static String legacyNameOf(JsonObject member) {
        if (member == null) return null;
        if (member.has("legacyName") && !member.get("legacyName").isJsonNull()) {
            return member.get("legacyName").getAsString();
        }
        return null;
    }

    /** Returns the member's {@code uuid} field as a raw string if
     *  present and non-null, else {@code null}. Callers should pass
     *  the result through {@link #normalizeUuid} before comparing. */
    private static String uuidOf(JsonObject member) {
        if (member == null) return null;
        if (member.has("uuid") && !member.get("uuid").isJsonNull()) {
            return member.get("uuid").getAsString();
        }
        return null;
    }

    /** Strips dashes and lowercases — wapi may emit either form, and
     *  the no-aspects endpoint emits dashed UUIDs; both reduce to the same 32-char hex string for
     *  equality comparison. Mirrors
     *  {@link org.wynnvets.fetcher.ondemand.UserInfoFetcher#normalizeUuidText
     *  UserInfoFetcher#normalizeUuidText}. */
    static String normalizeUuid(String uuid) {
        if (uuid == null) return "";
        return uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }
}
