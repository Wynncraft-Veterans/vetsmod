package org.wynnvets.distribute.utils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.wynnvets.api.VetsApi;
import org.wynnvets.logging.VetsLogger;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Fetches the NoAspects opt-out list from
 * {@link VetsApi#NO_ASPECTS} and translates the UUID payload into a
 * set of legacy (= Members GUI tile) names that {@code /wv distribute}
 * selectors filter against.
 *
 * <p>The endpoint is server-curated by staff via {@code !noaspects} in
 * nazbot. The primary use case is members who already own every aspect
 * available to them &mdash; any aspect dispatched to them is wasted.</p>
 *
 * <h2>Why translate UUID &rarr; legacyName</h2>
 * <p>The endpoint returns {@code {uuid, username}} pairs. {@code uuid} is
 * the load-bearing key (survives renames), but the distributors operate
 * on tile-displayed legacy names &mdash; that's the form
 * {@link org.wynnvets.distribute.walker.MembersListSearcher} matches
 * against in the Members GUI. We translate by combining the opt-out
 * payload with the wapi guild response (which has both UUID and
 * legacyName per member) via
 * {@link NameResolver#fetchUuidToLegacyName()}.</p>
 *
 * <h2>Fail-open semantics</h2>
 * <p>Any failure &mdash; HTTP error, parse error, missing wapi response
 * &mdash; returns an empty set. An empty exclude set means no one is
 * filtered (= pre-change behaviour), so a flaky list fetch never blocks
 * {@code /wv distribute} from proceeding. Network issues are logged at
 * warn level for observability.</p>
 */
public final class NoAspectsFilter {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Gson GSON = new Gson();

    private NoAspectsFilter() {}

    /**
     * Fetches the opt-out list and returns the set of legacy names to
     * exclude. The no-aspects HTTP call and the wapi guild fetch run in
     * parallel (independent futures combined via {@code thenCombine}).
     *
     * <p>Returns an empty set on any failure path (see class javadoc).</p>
     */
    public static CompletableFuture<Set<String>> fetchExcludedLegacyNames() {
        CompletableFuture<Set<String>> uuidsF = fetchExcludedUuids();
        CompletableFuture<Map<String, String>> uuidToLegacyF =
                NameResolver.fetchUuidToLegacyName();
        return uuidsF.thenCombine(uuidToLegacyF, NoAspectsFilter::buildExcludedNames);
    }

    private static Set<String> buildExcludedNames(Set<String> excludedUuids,
                                                  Map<String, String> uuidToLegacy) {
        if (excludedUuids.isEmpty() || uuidToLegacy.isEmpty()) {
            return Set.of();
        }
        Set<String> excludedNames = new HashSet<>();
        for (String uuid : excludedUuids) {
            String legacy = uuidToLegacy.get(uuid);
            // No-op for opted-out members not in the current guild — they
            // can't appear in any selector anyway. Logging would be noise
            // because the list legitimately retains entries for members
            // who left the guild.
            if (legacy != null) {
                excludedNames.add(legacy);
            }
        }
        return excludedNames;
    }

    private static CompletableFuture<Set<String>> fetchExcludedUuids() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(VetsApi.NO_ASPECTS)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                        VetsLogger.debug("NoAspectsFilter: API returned {}",
                                response.statusCode());
                        return Set.<String>of();
                    }
                    return parseUuids(response.body());
                })
                .exceptionally(e -> {
                    VetsLogger.warn("NoAspectsFilter: fetch failed: {}", e.getMessage());
                    return Set.of();
                });
    }

    private static Set<String> parseUuids(String body) {
        try {
            JsonElement root = GSON.fromJson(body, JsonElement.class);
            if (root == null || !root.isJsonArray()) return Set.of();
            JsonArray array = root.getAsJsonArray();
            Set<String> uuids = new HashSet<>();
            for (int i = 0; i < array.size(); i++) {
                JsonElement el = array.get(i);
                if (el == null || !el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                if (!obj.has("uuid") || obj.get("uuid").isJsonNull()) continue;
                String uuid = obj.get("uuid").getAsString();
                if (uuid != null && !uuid.isEmpty()) {
                    uuids.add(NameResolver.normalizeUuid(uuid));
                }
            }
            return uuids;
        } catch (Exception e) {
            VetsLogger.debug("NoAspectsFilter: parse error: {}", e.getMessage());
            return Set.of();
        }
    }
}
