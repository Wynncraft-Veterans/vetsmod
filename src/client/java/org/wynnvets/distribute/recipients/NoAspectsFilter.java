package org.wynnvets.distribute.recipients;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.wynnvets.api.VetsApi;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.HttpClients;
import org.wynnvets.util.Json;
import org.wynnvets.util.Uuids;

/**
 * Fetches the NoAspects opt-out list from
 * {@link VetsApi#NO_ASPECTS} and translates the UUID payload into a
 * set of legacy (= Members GUI tile) names that {@code /wv distribute}
 * checks on every path of an aspects run: the selectors drop them from their
 * pools ({@code @split} through its three phases), and the literal-name path
 * refuses a send to one. Tomes and emeralds are not gated: for those runs the
 * set is empty and nothing is fetched.
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
 * filtered (the behaviour before the opt-out list existed), so a flaky
 * list fetch never blocks {@code /wv distribute} from proceeding. A
 * transport failure of the opt-out fetch logs at warn; most other failure
 * paths log at debug (silent unless debug logging is on) or not at all
 * &mdash; {@code vetsmod_distribute.md} §9 has the detail.</p>
 */
public final class NoAspectsFilter {

    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    private NoAspectsFilter() {}

    /**
     * Fetches the opt-out list and returns the set of legacy names to
     * exclude. The no-aspects HTTP call and the wapi guild fetch run in
     * parallel (independent futures combined via {@code thenCombine}).
     *
     * <p>{@code gated} is the run's {@code MemberSlotPresser.Resource#gatedByNoAspects()}.
     * When it is {@code false} this makes no request and returns an
     * already-completed empty set, so a caller's {@code thenCombine} on it
     * completes with the other future, on that future's thread.</p>
     *
     * <p>Returns an empty set on any failure path (see class javadoc).</p>
     */
    public static CompletableFuture<Set<String>> fetchExcludedLegacyNames(boolean gated) {
        if (!gated) {
            VetsLogger.debug("NoAspectsFilter: resource is not gated, skipping the opt-out fetch");
            return CompletableFuture.completedFuture(Set.of());
        }
        CompletableFuture<Set<String>> uuidsF = fetchExcludedUuids();
        CompletableFuture<Map<String, String>> uuidToLegacyF = NameResolver.fetchUuidToLegacyName();
        return uuidsF.thenCombine(uuidToLegacyF, NoAspectsFilter::buildExcludedNames);
    }

    // Package-private for unit tests. See NoAspectsFilterTest.
    static Set<String> buildExcludedNames(
            Set<String> excludedUuids, Map<String, String> uuidToLegacy) {
        if (excludedUuids.isEmpty() || uuidToLegacy.isEmpty()) {
            return Set.of();
        }
        Set<String> excludedNames = new HashSet<>();
        for (String uuid : excludedUuids) {
            String legacy = uuidToLegacy.get(uuid);
            // No-op for opted-out members not in the current guild — they
            // can't appear in any selector anyway. Logging would be noise
            // because the list legitimately retains entries for members
            // who left the guild. The same miss covers an in-guild member
            // whose wapi entry has no usable uuid (extractUuidToLegacyName
            // skips it), and such a member is not filtered even while on
            // the list.
            if (legacy != null) {
                excludedNames.add(legacy);
            }
        }
        return excludedNames;
    }

    private static CompletableFuture<Set<String>> fetchExcludedUuids() {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(VetsApi.NO_ASPECTS)
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
        return HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        response -> {
                            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                                VetsLogger.debug(
                                        "NoAspectsFilter: API returned {}", response.statusCode());
                                return Set.<String>of();
                            }
                            return parseUuids(response.body());
                        })
                .exceptionally(
                        e -> {
                            VetsLogger.warn("NoAspectsFilter: fetch failed: {}", e.getMessage());
                            return Set.of();
                        });
    }

    // Package-private for unit tests. See NoAspectsFilterTest.
    static Set<String> parseUuids(String body) {
        try {
            JsonElement root = Json.GSON.fromJson(body, JsonElement.class);
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
                    // The no-aspects endpoint emits dashed UUIDs; this gives the same undashed
                    // key NameResolver.extractUuidToLegacyName gives wapi's, so the two compare.
                    uuids.add(Uuids.normalizeUndashed(uuid));
                }
            }
            return uuids;
        } catch (Exception e) {
            VetsLogger.debug("NoAspectsFilter: parse error: {}", e.getMessage());
            return Set.of();
        }
    }
}
