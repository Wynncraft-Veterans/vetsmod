package org.wynnvets.mwe.anni.zone;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.wynnvets.logging.VetsLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cached fetcher for the anni zone — the union of 48-block discs
 * centred at the {@code location} of event {@code a63b2c02} on
 * {@code https://api.wynncraft.com/v3/map/world-events}.
 *
 * <p>Spec §3.1.1.3 / §3.1.1.4 gate the T-2m boss-bar countdown variant
 * on "When in the anni zone", and S4/S5 will gate outline overrides
 * + zone-line rendering on the same predicate. Centralising the disc
 * test here keeps S3..S5 from each rolling their own world-events
 * parser.</p>
 *
 * <p>API response shape (probed live 2026-06-16):
 * <pre>
 * [
 *   {
 *     "internalName": "a63b2c02",
 *     "name": "Prelude to Annihilation",
 *     "location": [
 *       {
 *         "event":  { "x": 315, "y": 31, "z": -1291 },
 *         "spawn":  { "x": 350, "y": 29, "z": -1291 },
 *         "reward": { "x": 348, "y": 29, "z": -1292 },
 *         "radius": 48
 *       }
 *     ]
 *   }, ...
 * ]
 * </pre>
 * Each location's {@code event} sub-object is the centre we test
 * against. The API also reports {@code radius: 48} — same as the spec —
 * so we trust the spec value as the source of truth in case the API
 * later drifts.</p>
 *
 * <p>Refresh cadence: 60 s. Stale-fallback: on fetch error we keep the
 * previous centres so a transient API blip never collapses zone
 * detection to "always out". On cold-start failure, {@link #isInZone}
 * returns {@code false} (= player never in zone), so the boss-bar
 * countdown variant falls back to the assigned/seeking text rather
 * than erroneously claiming the player is in the zone.</p>
 */
public final class AnniZone {

    private static final String API_URL = "https://api.wynncraft.com/v3/map/world-events";
    private static final String ANNI_INTERNAL_NAME = "a63b2c02";

    /** Disc radius per spec — 48 blocks (matches the live API value as
     *  of 2026-06-16; kept as a spec-anchored constant in case the API
     *  drifts). */
    private static final double DISC_RADIUS = 48.0;
    private static final double DISC_RADIUS_SQ = DISC_RADIUS * DISC_RADIUS;

    private static final long REFRESH_INTERVAL_SECONDS = 60L;
    private static final long HTTP_TIMEOUT_SECONDS = 10L;

    private static volatile List<DiscCentre> centres = Collections.emptyList();
    private static volatile boolean cold = true;
    private static volatile boolean started = false;

    private static ScheduledExecutorService scheduler;

    private AnniZone() {
    }

    /** Start the periodic fetcher. Idempotent. Runs an initial pull
     *  immediately so cold-start isn't gated on the 60 s interval. */
    public static void start() {
        if (started) return;
        started = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vetsmod-anni-zone");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(AnniZone::refresh,
                0L, REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        VetsLogger.debug("AnniZone started");
    }

    /** Predicate: is the given block-space (x, z) within any cached
     *  anni-zone disc? Returns {@code false} when the cache is cold —
     *  the boss-bar countdown variant prefers the assigned/seeking
     *  fallback over a false-positive zone claim. */
    public static boolean isInZone(double x, double z) {
        if (cold) return false;
        for (DiscCentre c : centres) {
            double dx = x - c.x;
            double dz = z - c.z;
            if (dx * dx + dz * dz <= DISC_RADIUS_SQ) return true;
        }
        return false;
    }

    /** {@code true} until the first successful fetch lands. Exposed so
     *  S4/S5 consumers can choose to gate their stronger overrides
     *  (outlines, ghosts prompt) on a warm cache rather than treating
     *  cold as "never in zone". */
    public static boolean isCold() {
        return cold;
    }

    /** S5 — snapshot of the cached disc set so the zone-line renderer can
     *  iterate centres without re-querying the API. Returns an immutable
     *  snapshot; centres are (x, z) only because the spec treats the zone
     *  as a 2D circle (y is irrelevant). Empty when cold. */
    public static List<Disc> getDiscs() {
        List<DiscCentre> snap = centres;
        if (snap.isEmpty()) return Collections.emptyList();
        List<Disc> out = new ArrayList<>(snap.size());
        for (DiscCentre c : snap) {
            out.add(new Disc(c.x, c.z, DISC_RADIUS));
        }
        return Collections.unmodifiableList(out);
    }

    /** Immutable public view of a single zone disc — centre (x, z) + radius
     *  in blocks. Consumed by S5's zone-line renderer. */
    public static final class Disc {
        private final double x;
        private final double z;
        private final double radius;

        public Disc(double x, double z, double radius) {
            this.x = x;
            this.z = z;
            this.radius = radius;
        }

        public double x() { return x; }
        public double z() { return z; }
        public double radius() { return radius; }
    }

    // ── Internals ───────────────────────────────────────────────────────

    private static void refresh() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                VetsLogger.debug("AnniZone refresh: HTTP {}", resp.statusCode());
                return;
            }
            List<DiscCentre> parsed = parse(resp.body());
            if (parsed.isEmpty()) {
                VetsLogger.debug("AnniZone refresh: no anni event in response");
                return;
            }
            centres = parsed;
            cold = false;
            VetsLogger.debug("AnniZone refresh: {} centre(s)", parsed.size());
        } catch (Exception e) {
            // Stale-fallback: retain previous centres on transient failure.
            VetsLogger.debug("AnniZone refresh failed: {}", e.getMessage());
        }
    }

    private static List<DiscCentre> parse(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonArray()) return Collections.emptyList();
            JsonArray events = root.getAsJsonArray();
            for (JsonElement e : events) {
                if (!e.isJsonObject()) continue;
                JsonObject obj = e.getAsJsonObject();
                if (!obj.has("internalName")) continue;
                if (!ANNI_INTERNAL_NAME.equals(obj.get("internalName").getAsString())) continue;
                return parseLocations(obj);
            }
        } catch (Exception ex) {
            VetsLogger.debug("AnniZone.parse error: {}", ex.getMessage());
        }
        return Collections.emptyList();
    }

    private static List<DiscCentre> parseLocations(JsonObject event) {
        List<DiscCentre> result = new ArrayList<>();
        JsonElement locEl = event.get("location");
        if (locEl == null || !locEl.isJsonArray()) return result;
        JsonArray arr = locEl.getAsJsonArray();
        for (JsonElement entry : arr) {
            if (!entry.isJsonObject()) continue;
            JsonObject loc = entry.getAsJsonObject();
            JsonElement evtCoord = loc.get("event");
            if (evtCoord == null || !evtCoord.isJsonObject()) continue;
            JsonObject xyz = evtCoord.getAsJsonObject();
            try {
                double x = xyz.get("x").getAsDouble();
                double z = xyz.get("z").getAsDouble();
                result.add(new DiscCentre(x, z));
            } catch (Exception ex) {
                VetsLogger.debug("AnniZone.parseLocations: bad xyz: {}", ex.getMessage());
            }
        }
        return result;
    }

    /** Immutable (x, z) pair — y is irrelevant since the spec describes
     *  the zone as 48-block circles (not spheres). */
    private static final class DiscCentre {
        final double x;
        final double z;

        DiscCentre(double x, double z) {
            this.x = x;
            this.z = z;
        }
    }
}
