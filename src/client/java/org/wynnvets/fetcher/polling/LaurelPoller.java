package org.wynnvets.fetcher.polling;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.wynnvets.api.VetsApi;
import org.wynnvets.laurels.Laurel;
import org.wynnvets.laurels.LaurelMetric;
import org.wynnvets.laurels.LaurelStandings;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.Json;
import org.wynnvets.util.Uuids;

/**
 * Fetches the vets laurel standings the VetsMod server lists at {@code /v1/outbound/laurels} and
 * publishes them as an immutable {@link LaurelStandings}, refreshed every
 * {@value #REFRESH_INTERVAL_MINUTES} minutes.
 *
 * <p>The fetch, error policy and volatile snapshot are {@link PolledJson}'s; this class keeps
 * the parse. {@link org.wynnvets.laurels.LaurelBadges LaurelBadges} reads the players' laurels on
 * the render thread, and {@link org.wynnvets.laurels.LaurelCommands LaurelCommands} reads
 * {@code collect} on the client thread. {@link #standings()} is {@code null} until the first
 * fetch succeeds, which an empty {@code collect} set cannot say: the snapshot is a holder for
 * "not fetched yet" as well as for the standings. The server answers 503 until it has its first
 * guild data, which counts as a failed fetch, so a cold start simply stays {@code null}. See
 * {@code vetsmod_laurels.md} for the model.</p>
 */
public final class LaurelPoller {
    private static final int REFRESH_INTERVAL_MINUTES = 5;
    private static final int REQUEST_TIMEOUT_SECONDS = 5;

    private static final PolledJson<LaurelStandings> POLL =
            new PolledJson<>(
                    VetsApi.LAURELS,
                    "VetsMod-LaurelPoller",
                    "laurels",
                    REFRESH_INTERVAL_MINUTES,
                    REQUEST_TIMEOUT_SECONDS,
                    null,
                    LaurelPoller::parse);

    private LaurelPoller() {}

    public static void start() {
        POLL.start();
    }

    /**
     * Returns the standings the last successful fetch published.
     *
     * @return the latest standings, or {@code null} before the first successful fetch. One
     *     volatile read, and a new reference with each fetch, so a caller can tell two
     *     generations apart by identity.
     */
    public static LaurelStandings standings() {
        return POLL.snapshot();
    }

    /**
     * Parses a response body. Pure: it returns a value and publishes nothing.
     *
     * <p>An entry is skipped, and the rest kept, when it is not an object, when its {@code uuid}
     * does not parse, when its {@code metric} is one this client does not know, when its
     * {@code place} is outside 1 to {@value Laurel#PLACES}, or when it repeats a player and
     * metric already seen, in which case the first one wins. Each player's laurels are ordered
     * by sheet column. Unknown names in {@code collect} are dropped, and a missing or non-array
     * {@code collect} is empty. The response's {@code username}, {@code updated_at} and
     * {@code sources} are not read.</p>
     *
     * @param body the response body
     * @return the standings, or {@code null} when the body is not an object or has no
     *     {@code laurels} array, which keeps the previous snapshot
     */
    // Package-private for unit tests. See LaurelPollerTest.
    static LaurelStandings parse(String body) {
        JsonElement root = Json.GSON.fromJson(body, JsonElement.class);
        if (root == null || !root.isJsonObject()) {
            return null;
        }
        JsonObject response = root.getAsJsonObject();
        JsonElement entries = response.get("laurels");
        if (entries == null || !entries.isJsonArray()) {
            return null;
        }

        Map<UUID, List<Laurel>> byPlayer = new HashMap<>();
        for (JsonElement element : entries.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            UUID uuid = Uuids.parse(Json.stringOrNull(entry, "uuid"));
            LaurelMetric metric = LaurelMetric.fromWire(Json.stringOrNull(entry, "metric"));
            int place = Json.optInt(entry, "place", 0);
            if (uuid == null || metric == null || place < 1 || place > Laurel.PLACES) {
                continue;
            }
            List<Laurel> held = byPlayer.computeIfAbsent(uuid, key -> new ArrayList<>());
            if (held.stream().noneMatch(laurel -> laurel.metric() == metric)) {
                held.add(new Laurel(metric, place));
            }
        }
        byPlayer.values()
                .forEach(list -> list.sort(Comparator.comparingInt(l -> l.metric().column())));

        Set<LaurelMetric> collect = EnumSet.noneOf(LaurelMetric.class);
        JsonElement names = response.get("collect");
        if (names != null && names.isJsonArray()) {
            for (JsonElement name : names.getAsJsonArray()) {
                if (name.isJsonPrimitive()) {
                    LaurelMetric metric = LaurelMetric.fromWire(name.getAsString());
                    if (metric != null) {
                        collect.add(metric);
                    }
                }
            }
        }

        LaurelStandings standings = new LaurelStandings(byPlayer, collect);
        VetsLogger.debug("laurels loaded: {} players", standings.byPlayer().size());
        return standings;
    }
}
