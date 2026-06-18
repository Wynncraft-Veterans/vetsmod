package org.wynnvets.mwe.anni.aggressive;

import com.wynntils.core.components.Models;
import com.wynntils.models.marker.type.MarkerInfo;
import com.wynntils.models.marker.type.MarkerProvider;
import com.wynntils.models.marker.type.StaticLocationSupplier;
import com.wynntils.services.map.pois.MarkerPoi;
import com.wynntils.utils.colors.CommonColors;
import com.wynntils.utils.colors.CustomColor;
import com.wynntils.utils.mc.type.Location;
import com.wynntils.utils.mc.type.PoiLocation;
import com.wynntils.utils.render.Texture;
import net.minecraft.ChatFormatting;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * S5 — Wynntils {@link MarkerProvider} for the party's pinned scroll spot.
 *
 * <p>Implements the parallel-marker pattern from
 * {@code waypoints.md} §5(b): we register our own provider rather than
 * overriding {@code USER_WAYPOINTS_PROVIDER}, so the user's existing
 * {@code /compass} waypoint coexists with ours. Both render
 * simultaneously; the user keeps full control of their compass.</p>
 *
 * <p>The spot is sourced from the latest {@link AnniSnapshot}'s
 * {@code board.party.scroll_spot}. When the spot is unset (or the user
 * isn't in a party), we fall back to the hardcoded default
 * {@code 345 45 -1315} per the parent plan §S5 — useful for users in a
 * party with no host-pinned spot yet.</p>
 *
 * <p><b>Visual:</b> {@link Texture#MAP} (the 14×14 generic map icon —
 * <i>not</i> {@code MAP_ICON}, which is the 21×38 content-book tab — the
 * enum names are a known footgun per {@code waypoints.md} §3).
 *
 * <p>Originally specced as "no beacon beam, icon only" but Wynntils'
 * {@code BeaconBeamFeature.onRenderLevelLast} unconditionally calls
 * {@code marker.beaconColor().withAlpha(float)} for every marker — passing
 * {@code null} crashes the render thread, and even
 * {@code CustomColor.NONE} falls back to the user's
 * {@code waypointBeamColor} config rather than suppressing the beam.
 * A 0-alpha custom colour also doesn't work: Wynntils overrides the
 * alpha to {@code 1f} (or distance-faded) before rendering. The only
 * way to truly skip the beam would be a mixin into Wynntils itself,
 * which we don't take lightly. Compromise: use a subtle
 * {@link CommonColors#GRAY} beacon — visible but unobtrusive against
 * most terrain. Swap the colour at one line below if a different
 * aesthetic emerges.</p>
 *
 * <p><b>Gate:</b> {@link AnniAggressiveTicker#isAggressiveActive()} AND
 * {@link VetsConfig#VETS_ANNI_SCROLL_WAYPOINT}. Registration happens once
 * at vetsmod load via {@link Models#Marker} — {@link #isEnabled()} is
 * what gates per-tick visibility.</p>
 */
public final class ScrollSpotMarkerProvider implements MarkerProvider<MarkerPoi> {

    private static final String MARKER_NAME = "Scroll Spot";

    /** Default fallback when the snapshot has no scroll spot pinned and
     *  the user is in a party. Source: parent plan §S5 ("default;
     *  lead-overridable per party for this anni"). */
    private static final int DEFAULT_X = 345;
    private static final int DEFAULT_Y = 45;
    private static final int DEFAULT_Z = -1315;

    private static final ScrollSpotMarkerProvider INSTANCE = new ScrollSpotMarkerProvider();
    private static volatile boolean wynntilsRegistered = false;

    /** Current marker, or {@code null} when we have nothing to show. Set
     *  by the snapshot listener / debug command; read by {@link #getMarkerInfos()}
     *  and {@link #getPois()}. Atomic reference so we can swap without
     *  partial state being read mid-frame. */
    private final AtomicReference<Entry> current = new AtomicReference<>(null);

    private ScrollSpotMarkerProvider() {
    }

    public static ScrollSpotMarkerProvider get() {
        return INSTANCE;
    }

    /** One-time wiring to Wynntils' {@link Models#Marker} + snapshot
     *  listener. Idempotent. */
    public static void registerWithWynntils() {
        if (wynntilsRegistered) return;
        wynntilsRegistered = true;
        Models.Marker.registerMarkerProvider(INSTANCE);
        AnniSnapshotCache.addListener(INSTANCE::onSnapshot);
        // Apply the current cache, if any, so a marker shows up
        // immediately on registration instead of waiting for the next
        // snapshot push.
        INSTANCE.onSnapshot(AnniSnapshotCache.latest());
        VetsLogger.debug("ScrollSpotMarkerProvider registered with Wynntils");
    }

    /** Debug entry — inject a local-only scroll spot regardless of
     *  snapshot. Used by {@code /wv debug tree anni scrollspot set <x> <y> <z>}. */
    public void debugSet(int x, int y, int z) {
        current.set(Entry.at(x, y, z));
        VetsLogger.debug("ScrollSpotMarkerProvider.debugSet {} {} {}", x, y, z);
    }

    /** Debug entry — clear the local marker. */
    public void debugClear() {
        current.set(null);
        VetsLogger.debug("ScrollSpotMarkerProvider.debugClear");
    }

    private void onSnapshot(AnniSnapshot snapshot) {
        Entry next = computeEntry(snapshot);
        current.set(next);
    }

    private static Entry computeEntry(AnniSnapshot snapshot) {
        if (snapshot == null) return null;
        AnniSnapshot.Board board = snapshot.board();
        if (board == null || board.party() == null) return null;
        AnniSnapshot.ScrollSpot spot = board.party().scrollSpot();
        if (spot != null) {
            return Entry.at(spot.x(), spot.y(), spot.z());
        }
        // Fall back to the default for users who ARE in a party but
        // whose host hasn't pinned a spot yet. Outside a party, no spot.
        return Entry.at(DEFAULT_X, DEFAULT_Y, DEFAULT_Z);
    }

    @Override
    public Stream<MarkerInfo> getMarkerInfos() {
        Entry entry = current.get();
        return entry == null ? Stream.empty() : Stream.of(entry.markerInfo);
    }

    @Override
    public Stream<MarkerPoi> getPois() {
        Entry entry = current.get();
        return entry == null ? Stream.empty() : Stream.of(entry.poi);
    }

    @Override
    public boolean isEnabled() {
        return current.get() != null
                && AnniAggressiveTicker.isAggressiveActive()
                && VetsConfig.get(VetsConfig.VETS_ANNI_SCROLL_WAYPOINT);
    }

    /** Cached {@link MarkerInfo} + {@link MarkerPoi} pair so we build them
     *  once per spot change, not once per frame. */
    private record Entry(MarkerInfo markerInfo, MarkerPoi poi) {
        static Entry at(int x, int y, int z) {
            Location loc = new Location(x, y, z);
            // Beacon colour MUST be non-null and MUST NOT be CustomColor.NONE
            // — Wynntils' BeaconBeamFeature.onRenderLevelLast calls
            // `marker.beaconColor().withAlpha(float)` unconditionally and
            // NPE-crashes the render thread on null; NONE triggers a
            // fallback to the user's waypointBeamColor config. There's no
            // per-marker "skip beacon" path. Dark red (vanilla §4 ≈ 0xAA0000)
            // matches the anni blood-biome aesthetic without competing with
            // PINK (= party colour) or PURPLE (= boss-bar party state).
            // CommonColors has no DARK_RED constant, so build from §4.
            // Texture + text colour = WHITE so the generic MAP icon doesn't
            // get tinted.
            CustomColor beacon = CustomColor.fromChatFormatting(ChatFormatting.DARK_RED);
            MarkerInfo info = new MarkerInfo(
                    MARKER_NAME,
                    new StaticLocationSupplier(loc),
                    Texture.MAP,
                    beacon,
                    CommonColors.WHITE,
                    CommonColors.WHITE,
                    null
            );
            MarkerPoi poi = new MarkerPoi(
                    PoiLocation.fromLocation(loc),
                    MARKER_NAME,
                    Texture.MAP
            );
            return new Entry(info, poi);
        }
    }
}
