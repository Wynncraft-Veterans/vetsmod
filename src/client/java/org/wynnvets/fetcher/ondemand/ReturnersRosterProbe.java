package org.wynnvets.fetcher.ondemand;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.wynnvets.api.WynnCraftApi;
import org.wynnvets.util.HttpClients;
import org.wynnvets.util.Json;
import org.wynnvets.util.Uuids;

/**
 * Guild-roster cross-checks against Wynncraft's {@code /v3/guild} endpoint: fetch a guild's
 * roster and report whether a UUID is in it and, where the roster gives them, its rank and
 * legacy name.
 */
final class ReturnersRosterProbe {
    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    /**
     * Lightweight result of a guild-roster cross-check; {@link #analyzeRoster}
     * also produces it for guilds other than Returners.
     */
    record ReturnersMembership(boolean inGuild, String legacyName, String rank) {
        static ReturnersMembership notInGuild() {
            return new ReturnersMembership(false, null, null);
        }
    }

    private ReturnersRosterProbe() {}

    private static boolean containsUuid(JsonElement element, String normalizedUuid) {
        if (element == null || element.isJsonNull()) {
            return false;
        }

        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String normalized = Uuids.normalizeUndashed(element.getAsString());
            return Uuids.isUndashedLowercase(normalized) && normalized.equals(normalizedUuid);
        }

        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                if (containsUuid(child, normalizedUuid)) {
                    return true;
                }
            }
            return false;
        }

        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String normalizedKey = Uuids.normalizeUndashed(entry.getKey());
                if (Uuids.isUndashedLowercase(normalizedKey)
                        && normalizedKey.equals(normalizedUuid)) {
                    return true;
                }

                if (containsUuid(entry.getValue(), normalizedUuid)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Fetches the Returners roster from the Wynncraft API and reports
     * membership plus a best-effort {@code legacyName} and rank. Membership
     * is {@link #containsUuid}'s answer: a shape-agnostic walk of the whole
     * payload, which is what defends against Wynncraft API shape changes.
     * {@link #analyzeRoster}'s structured {@code members.<rank>.<name>}
     * walk only supplies the rank and {@code legacyName}. Today a non-200,
     * failed or unparseable fetch also yields
     * {@link ReturnersMembership#notInGuild()}, indistinguishable from
     * absence (see
     * {@code wv-check-guild-line-conflates-roster-failure-with-absence}).
     */
    static CompletableFuture<ReturnersMembership> returnersMembership(String uuid) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(WynnCraftApi.guildInfo("Returners"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

        String normalizedUuid = Uuids.normalizeUndashed(uuid);

        return HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        response -> {
                            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                                return ReturnersMembership.notInGuild();
                            }

                            JsonElement payload =
                                    Json.GSON.fromJson(response.body(), JsonElement.class);
                            return analyzeRoster(payload, normalizedUuid);
                        })
                .exceptionally(e -> ReturnersMembership.notInGuild());
    }

    // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
    // Reason: /v3/player/{uuid}.guild ~12h stale for offline players.
    // Remove: when Wynncraft invalidates the player endpoint on
    //   officer-write events, or exposes a webhook, or an offline-player
    //   spot-check shows <10min staleness.
    // Imports used only by this workaround: java.util.Optional.
    /**
     * Fetches {@code guildName}'s roster and returns the rank it lists for
     * {@code normalizedUuid}. Empty on a non-200, a timeout or other failed
     * request, a malformed response or an absent UUID. It is also empty when the UUID is present
     * but no rank could be read, so an empty result does not by itself mean
     * "not a member"; today {@link UserInfoFetcher#renderGuildLine} reads it as one (see
     * {@code wv-check-guild-line-conflates-roster-failure-with-absence}).
     * Mirrors {@link #returnersMembership} and reuses {@link #analyzeRoster}.
     */
    static CompletableFuture<Optional<String>> confirmArbitraryGuildRank(
            String guildName, String normalizedUuid) {
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
                                return Optional.<String>empty();
                            }
                            JsonElement payload =
                                    Json.GSON.fromJson(response.body(), JsonElement.class);
                            ReturnersMembership hit = analyzeRoster(payload, normalizedUuid);
                            return hit.inGuild()
                                    ? Optional.ofNullable(hit.rank())
                                    : Optional.<String>empty();
                        })
                .exceptionally(e -> Optional.<String>empty());
    }

    // <<< PATCH END: WYNN-STALE-WORKAROUND

    private static ReturnersMembership analyzeRoster(JsonElement payload, String normalizedUuid) {
        if (!containsUuid(payload, normalizedUuid)) {
            return ReturnersMembership.notInGuild();
        }
        // Try to extract rank + legacyName from the V3-typical shape:
        //   { "members": { "<rank>": { "<username>": { "uuid": ..., "legacyName": ... } } } }
        String rank = null;
        String legacyName = null;
        if (payload != null && payload.isJsonObject()) {
            JsonElement membersEl = payload.getAsJsonObject().get("members");
            if (membersEl != null && membersEl.isJsonObject()) {
                outer:
                for (Map.Entry<String, JsonElement> rankBucket :
                        membersEl.getAsJsonObject().entrySet()) {
                    String rankKey = rankBucket.getKey();
                    if ("total".equals(rankKey)) continue;
                    JsonElement bucketEl = rankBucket.getValue();
                    if (bucketEl == null || !bucketEl.isJsonObject()) continue;
                    for (Map.Entry<String, JsonElement> memberEntry :
                            bucketEl.getAsJsonObject().entrySet()) {
                        JsonElement memberEl = memberEntry.getValue();
                        if (memberEl == null || !memberEl.isJsonObject()) continue;
                        JsonObject member = memberEl.getAsJsonObject();
                        JsonElement uuidEl = member.get("uuid");
                        if (uuidEl == null || !uuidEl.isJsonPrimitive()) continue;
                        String memberUuid = Uuids.normalizeUndashed(uuidEl.getAsString());
                        if (memberUuid.equals(normalizedUuid)) {
                            rank = capitalizeRank(rankKey);
                            if (member.has("legacyName")
                                    && !member.get("legacyName").isJsonNull()) {
                                legacyName = member.get("legacyName").getAsString();
                            }
                            break outer;
                        }
                    }
                }
            }
        }
        return new ReturnersMembership(true, legacyName, rank);
    }

    private static String capitalizeRank(String rank) {
        if (rank == null || rank.isEmpty()) return rank;
        return Character.toUpperCase(rank.charAt(0)) + rank.substring(1);
    }
}
