package org.wynnvets.fetcher.ondemand;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.wynnvets.api.WynnCraftApi;
import org.wynnvets.util.HttpClients;
import org.wynnvets.util.Json;
import org.wynnvets.util.Uuids;

/**
 * Guild-roster cross-checks against Wynncraft's {@code /v3/guild} endpoint. For Returners it
 * reports whether a UUID is in the roster and, where the roster gives them, its rank and legacy
 * name; for any other guild, only the rank the roster lists for the UUID. A failed or unreadable
 * fetch is reported as {@link RosterVerdict#UNKNOWN}, never as absence.
 */
final class ReturnersRosterProbe {
    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    /** What a roster call established about one UUID. */
    enum RosterVerdict {
        /** The roster answered and lists the UUID. */
        IN_ROSTER,
        /** The roster answered and does not list the UUID. */
        NOT_IN_ROSTER,
        /** No usable answer: a failed request, a non-200 or an unreadable body. */
        UNKNOWN
    }

    /**
     * Lightweight result of a guild-roster cross-check; {@link #analyzeRoster}
     * also produces it for guilds other than Returners. {@code rank} and
     * {@code legacyName} are only ever set for {@link RosterVerdict#IN_ROSTER}, and may be
     * {@code null} there when the roster lists the UUID in a shape that gives neither.
     */
    record ReturnersMembership(RosterVerdict verdict, String legacyName, String rank) {
        /** True only when the roster answered and lists the UUID. */
        boolean inGuild() {
            return verdict == RosterVerdict.IN_ROSTER;
        }

        /** The roster answered and does not list the UUID. */
        static ReturnersMembership notInGuild() {
            return new ReturnersMembership(RosterVerdict.NOT_IN_ROSTER, null, null);
        }

        /** The roster call failed, so absence is not established. */
        static ReturnersMembership unknown() {
            return new ReturnersMembership(RosterVerdict.UNKNOWN, null, null);
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
     * walk only supplies the rank and {@code legacyName}. A non-200, failed
     * or unparseable fetch yields {@link ReturnersMembership#unknown()}, so
     * the caller can tell it from a roster that answered "not a member".
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
                        response ->
                                membershipFromResponse(
                                        response.statusCode(), response.body(), normalizedUuid))
                .exceptionally(e -> ReturnersMembership.unknown());
    }

    /**
     * The pure step after the HTTP call, shared by {@link #returnersMembership} and
     * {@link #otherGuildMembership}: a status other than 200, an unparseable body or one that is
     * not a JSON object is {@link RosterVerdict#UNKNOWN}; anything else is
     * {@link #analyzeRoster}'s answer.
     */
    // Package-private for unit tests. See ReturnersRosterProbeTest.
    static ReturnersMembership membershipFromResponse(
            int status, String body, String normalizedUuid) {
        if (status != HttpURLConnection.HTTP_OK) {
            return ReturnersMembership.unknown();
        }
        JsonElement payload;
        try {
            payload = Json.GSON.fromJson(body, JsonElement.class);
        } catch (JsonParseException e) {
            return ReturnersMembership.unknown();
        }
        if (payload == null || !payload.isJsonObject()) {
            return ReturnersMembership.unknown();
        }
        return analyzeRoster(payload, normalizedUuid);
    }

    // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
    // Reason: /v3/player/{uuid}.guild ~12h stale for offline players.
    // Remove: when Wynncraft invalidates the player endpoint on
    //   officer-write events, or exposes a webhook, or an offline-player
    //   spot-check shows <10min staleness.
    /**
     * Fetches {@code guildName}'s roster and reports what it says about
     * {@code normalizedUuid}: {@link RosterVerdict#IN_ROSTER} with the rank it lists (which may be
     * {@code null}, a member whose rank could not be read), {@link RosterVerdict#NOT_IN_ROSTER}
     * when it answered without the UUID, or {@link RosterVerdict#UNKNOWN} on a non-200, a timeout
     * or other failed request, or an unreadable response.
     * Mirrors {@link #returnersMembership} and reuses {@link #analyzeRoster}.
     */
    static CompletableFuture<ReturnersMembership> otherGuildMembership(
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
                        response ->
                                membershipFromResponse(
                                        response.statusCode(), response.body(), normalizedUuid))
                .exceptionally(e -> ReturnersMembership.unknown());
    }

    // <<< PATCH END: WYNN-STALE-WORKAROUND

    // Package-private for unit tests. See ReturnersRosterProbeTest.
    static ReturnersMembership analyzeRoster(JsonElement payload, String normalizedUuid) {
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
        return new ReturnersMembership(RosterVerdict.IN_ROSTER, legacyName, rank);
    }

    private static String capitalizeRank(String rank) {
        if (rank == null || rank.isEmpty()) return rank;
        return Character.toUpperCase(rank.charAt(0)) + rank.substring(1);
    }
}
