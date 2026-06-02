package org.wynnvets.distribute;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wynntils.core.components.Models;
import org.wynnvets.api.WynnCraftApi;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

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

/**
 * Resolves a user-supplied name to the {@code legacyName} that the
 * Wynncraft Members GUI displays, by querying
 * {@code wapi /v3/guild/<localPlayerGuild>}.
 *
 * <p>The v3 payload's {@code members.<rank>.<currentName>.legacyName}
 * shape (see {@code UserInfoFetcher.analyzeRoster}) lets us map a player's
 * <em>current</em> Mojang username to whatever name the Wynncraft server
 * has frozen onto its in-game tiles. Input matching is case-insensitive
 * against both the {@code currentName} key and the {@code legacyName}
 * value, so either form resolves to the correct GUI tile name.</p>
 *
 * <p>On any failure (no guild, no network, unexpected payload) returns
 * the input unchanged &mdash; the caller's literal-input arm still
 * matches if the user already typed the legacyName.</p>
 */
public final class NameResolver {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(WynnCraftApi.guildInfo(guildName))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                        VetsLogger.debug("NameResolver: wapi returned {} for guild [{}]",
                                response.statusCode(), guildName);
                        return input;
                    }
                    return findLegacyName(response.body(), input);
                })
                .exceptionally(e -> {
                    VetsLogger.debug("NameResolver: failed to resolve [{}]: {}",
                            input, e.getMessage());
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(WynnCraftApi.guildInfo(guildName))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                        VetsLogger.debug("NameResolver: wapi returned {} for guild [{}]",
                                response.statusCode(), guildName);
                        return List.<String>of();
                    }
                    return extractAllLegacyNames(response.body());
                })
                .exceptionally(e -> {
                    VetsLogger.debug("NameResolver: fetchAllLegacyNames failed: {}",
                            e.getMessage());
                    return List.of();
                });
    }

    private static List<String> extractAllLegacyNames(String body) {
        try {
            JsonElement root = GSON.fromJson(body, JsonElement.class);
            if (root == null || !root.isJsonObject()) return List.of();
            JsonElement membersEl = root.getAsJsonObject().get("members");
            if (membersEl == null || !membersEl.isJsonObject()) return List.of();

            List<String> names = new ArrayList<>();
            for (Map.Entry<String, JsonElement> rankBucket
                    : membersEl.getAsJsonObject().entrySet()) {
                if ("total".equals(rankBucket.getKey())) continue;
                JsonElement bucketEl = rankBucket.getValue();
                if (bucketEl == null || !bucketEl.isJsonObject()) continue;

                for (Map.Entry<String, JsonElement> memberEntry
                        : bucketEl.getAsJsonObject().entrySet()) {
                    String currentName = memberEntry.getKey();
                    JsonElement memberEl = memberEntry.getValue();
                    if (memberEl == null || !memberEl.isJsonObject()) {
                        names.add(currentName);
                        continue;
                    }
                    JsonObject member = memberEl.getAsJsonObject();
                    if (member.has("legacyName") && !member.get("legacyName").isJsonNull()) {
                        // Renamed member — the tile shows their original name.
                        names.add(member.get("legacyName").getAsString());
                    } else {
                        // No rename — the tile shows their current name.
                        names.add(currentName);
                    }
                }
            }
            return names;
        } catch (Exception e) {
            VetsLogger.debug("NameResolver: extractAllLegacyNames parse error: {}",
                    e.getMessage());
            return List.of();
        }
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
        return fetchGuildJson().thenApply(body -> {
            if (body == null) return Map.of();
            return extractNameIndex(body);
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(WynnCraftApi.guildInfo(guildName))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                        VetsLogger.debug("NameResolver: wapi returned {} for guild [{}]",
                                response.statusCode(), guildName);
                        return null;
                    }
                    return response.body();
                })
                .exceptionally(e -> {
                    VetsLogger.debug("NameResolver: fetchGuildJson failed: {}", e.getMessage());
                    return null;
                });
    }

    private static Map<String, String> extractNameIndex(String body) {
        try {
            JsonElement root = GSON.fromJson(body, JsonElement.class);
            if (root == null || !root.isJsonObject()) return Map.of();
            JsonElement membersEl = root.getAsJsonObject().get("members");
            if (membersEl == null || !membersEl.isJsonObject()) return Map.of();

            Map<String, String> index = new HashMap<>();
            for (Map.Entry<String, JsonElement> rankBucket
                    : membersEl.getAsJsonObject().entrySet()) {
                if ("total".equals(rankBucket.getKey())) continue;
                JsonElement bucketEl = rankBucket.getValue();
                if (bucketEl == null || !bucketEl.isJsonObject()) continue;
                for (Map.Entry<String, JsonElement> memberEntry
                        : bucketEl.getAsJsonObject().entrySet()) {
                    String currentName = memberEntry.getKey();
                    JsonElement memberEl = memberEntry.getValue();
                    String legacyName = currentName;
                    if (memberEl != null && memberEl.isJsonObject()) {
                        JsonObject member = memberEl.getAsJsonObject();
                        if (member.has("legacyName") && !member.get("legacyName").isJsonNull()) {
                            legacyName = member.get("legacyName").getAsString();
                        }
                    }
                    // Both forms point at the canonical legacy (= tile)
                    // name. equalsIgnoreCase-style lookup is achieved by
                    // lowercasing the key.
                    index.put(currentName.toLowerCase(Locale.ROOT), legacyName);
                    index.put(legacyName.toLowerCase(Locale.ROOT), legacyName);
                }
            }
            return index;
        } catch (Exception e) {
            VetsLogger.debug("NameResolver: extractNameIndex parse error: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String findLegacyName(String body, String input) {
        try {
            JsonElement root = GSON.fromJson(body, JsonElement.class);
            if (root == null || !root.isJsonObject()) return input;
            JsonElement membersEl = root.getAsJsonObject().get("members");
            if (membersEl == null || !membersEl.isJsonObject()) return input;

            String lowerInput = input.toLowerCase(Locale.ROOT);

            for (Map.Entry<String, JsonElement> rankBucket
                    : membersEl.getAsJsonObject().entrySet()) {
                if ("total".equals(rankBucket.getKey())) continue;
                JsonElement bucketEl = rankBucket.getValue();
                if (bucketEl == null || !bucketEl.isJsonObject()) continue;

                for (Map.Entry<String, JsonElement> memberEntry
                        : bucketEl.getAsJsonObject().entrySet()) {
                    String currentName = memberEntry.getKey();
                    JsonElement memberEl = memberEntry.getValue();
                    if (memberEl == null || !memberEl.isJsonObject()) continue;
                    JsonObject member = memberEl.getAsJsonObject();

                    String legacyName = null;
                    if (member.has("legacyName") && !member.get("legacyName").isJsonNull()) {
                        legacyName = member.get("legacyName").getAsString();
                    }

                    if (currentName.toLowerCase(Locale.ROOT).equals(lowerInput)) {
                        // Input matched a current name — return its legacy
                        // (or the current name itself if no rename happened).
                        return legacyName != null ? legacyName : currentName;
                    }
                    if (legacyName != null
                            && legacyName.toLowerCase(Locale.ROOT).equals(lowerInput)) {
                        // Input was already a legacy name — return verbatim.
                        return legacyName;
                    }
                }
            }
            return input;
        } catch (Exception e) {
            VetsLogger.debug("NameResolver: parse error: {}", e.getMessage());
            return input;
        }
    }
}
