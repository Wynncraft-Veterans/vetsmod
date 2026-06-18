package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Models;
import com.wynntils.models.players.event.PartyEvent;
import com.wynntils.models.worlds.event.WorldStateEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.fetcher.polling.AnniStampPoller;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reports the local player's current Wynncraft party roster to the v1
 * inbound WebSocket as an {@code anni_party_observation} frame (S7) so
 * vets-anni can light up the {@code ONLINE_PARTY} status border for any
 * board member sharing this client's party with the assigned host.
 *
 * <p>Subscribes to every Wynntils {@link PartyEvent} variant that
 * materially changes the roster and, on each, captures a snapshot of
 * {@code Models.Party} and schedules a debounced
 * {@link V1ApiManager#sendAnniPartyObservation} send. The debounce
 * coalesces the typical burst (a {@code /party list} response fires
 * multiple events) into a single frame. Snapshot capture happens on the
 * event thread (where {@code PartyModel} is consistent); the send fires
 * on the scheduler thread from the captured snapshot, never re-reading
 * {@code Models.Party}.</p>
 *
 * <p>Wynntils has no dedicated event for "you left the party" or "your
 * party was disbanded" — both cases reset {@code PartyModel} silently.
 * We catch these via {@link WorldStateEvent}: any transition out of
 * {@code WorldState#WORLD} (server/world hop, disconnect, returning to
 * hub) sends a final snapshot reflecting whatever
 * {@code Models.Party.isInParty()} reports at the time, which correctly
 * emits the empty "not in a party" frame after a disband.</p>
 *
 * <p>S7 gate: send iff the anni stamp is within {@link
 * #ACTIVE_WINDOW_SEC} of {@code now} AND the snapshot's
 * {@code organiser_usernames} list contains at least one party member's
 * username (case-insensitive). Anni parties only exist in-window and an
 * anni party's host is always in {@code organisers}, so "any party member
 * is an organiser" is the exact signal vets-anni needs to upgrade
 * {@code ONLINE_WORLD → ONLINE_PARTY}. Snapshot-driven recaptures
 * ({@link #requestRecapture()}) cover the "anni window opens while parked
 * in a party" case, where no {@link PartyEvent} would fire on its own.</p>
 *
 * <p>Threading: Wynntils event listeners run on the render thread. We
 * capture a defensively-copied snapshot on that thread (so the send can't
 * race a concurrent {@code PartyModel} mutation), then hop to a daemon
 * single-thread scheduler for the actual send. The scheduler's send call
 * goes through {@link V1ApiManager#sendAnniPartyObservation} which
 * performs a thread-safe WebSocket write.</p>
 */
public final class PartyRosterListener {

    private static final long DEBOUNCE_MS = 300L;

    /** Send {@code anni_party_observation} only when the announced anni
     *  is within this many seconds of {@code now} on either side. Outside
     *  this window no anni board is being actively used. */
    static final long ACTIVE_WINDOW_SEC = 2L * 60 * 60;

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "vetsmod-party-roster");
                t.setDaemon(true);
                return t;
            });

    private static final Object scheduleLock = new Object();
    private static ScheduledFuture<?> pending;
    private static volatile Snapshot latest = Snapshot.EMPTY;

    private static final PartyRosterListener INSTANCE = new PartyRosterListener();

    private PartyRosterListener() {
    }

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        VetsLogger.debug("Registered party-roster listener");
    }

    public static void unregister() {
        WynntilsMod.unregisterEventListener(INSTANCE);
    }

    // --- Wynntils events ---------------------------------------------------

    @SubscribeEvent
    public void onListed(PartyEvent.Listed event) {
        captureAndSchedule();
    }

    @SubscribeEvent
    public void onJoined(PartyEvent.OtherJoined event) {
        captureAndSchedule();
    }

    @SubscribeEvent
    public void onLeft(PartyEvent.OtherLeft event) {
        captureAndSchedule();
    }

    @SubscribeEvent
    public void onDisconnected(PartyEvent.OtherDisconnected event) {
        captureAndSchedule();
    }

    @SubscribeEvent
    public void onReconnected(PartyEvent.OtherReconnected event) {
        captureAndSchedule();
    }

    @SubscribeEvent
    public void onPromoted(PartyEvent.Promoted event) {
        captureAndSchedule();
    }

    /**
     * Re-broadcasts on every world transition. Entering {@code WorldState#WORLD}
     * covers re-auth after reconnect and world hops; leaving covers disband /
     * self-leave (Wynntils fires no dedicated event for those), and a
     * disconnect that drops {@code PartyModel} state.
     */
    @SubscribeEvent
    public void onWorldState(WorldStateEvent event) {
        captureAndSchedule();
    }

    /**
     * Trigger a synthetic recapture from outside the Wynntils event bus.
     * Used by {@link org.wynnvets.mwe.anni.party.AnniPartyReporter} when an
     * incoming snapshot's {@code organiser_usernames} list changes — at
     * window-open the local party may have been static for ages and no
     * {@link PartyEvent} would fire on its own.
     */
    public static void requestRecapture() {
        captureAndSchedule();
    }

    // --- snapshot + send ---------------------------------------------------

    private static void captureAndSchedule() {
        // Capture on the calling (render) thread where PartyModel reads are
        // consistent. The send runs from the scheduler thread off this snapshot.
        Snapshot snap;
        if (Models.Party.isInParty()) {
            String leader = Models.Party.getPartyLeader().orElse("");
            List<String> members = List.copyOf(Models.Party.getPartyMembers());
            snap = new Snapshot(leader, members);
        } else {
            snap = Snapshot.EMPTY;
        }
        latest = snap;

        synchronized (scheduleLock) {
            if (pending != null && !pending.isDone()) {
                pending.cancel(false);
            }
            pending = SCHEDULER.schedule(
                    PartyRosterListener::flush, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static void flush() {
        Snapshot toSend = latest;
        AnniSnapshot snapshot = AnniSnapshotCache.latest();
        long stamp = AnniStampPoller.getLatestStamp();
        long now = System.currentTimeMillis() / 1000L;
        if (!shouldSend(toSend, snapshot, stamp, now)) {
            VetsLogger.debug(
                    "anni_party_observation suppressed (no organiser in party / out of window)");
            return;
        }
        String world = Models.WorldState.getCurrentWorldName();
        try {
            V1ApiManager.sendAnniPartyObservation(
                    toSend.members(), toSend.leader(), world);
        } catch (Exception e) {
            VetsLogger.debug(
                    "anni_party_observation send failed: {}", e.getMessage());
        }
    }

    /**
     * Pure predicate (no statics, no I/O): is this snapshot eligible to be
     * sent as an {@code anni_party_observation} frame given the current
     * inputs?
     *
     * <p>Rules — all must hold:
     * <ol>
     *   <li>Anni window: {@code stamp != 0} AND
     *       {@code |stamp - now| <= ACTIVE_WINDOW_SEC}.</li>
     *   <li>Snapshot present and has at least one organiser username.</li>
     *   <li>Organiser overlap: the captured party's leader or any member
     *       name (case-insensitive) is in
     *       {@code snapshot.organiserUsernames()}.</li>
     * </ol>
     *
     * @param snap     the captured party snapshot from Wynntils
     * @param snapshot the latest anni snapshot, or {@code null} when cold
     * @param stamp    anni epoch-seconds (0 = none announced)
     * @param now      current wall-clock epoch-seconds
     */
    static boolean shouldSend(
            Snapshot snap, AnniSnapshot snapshot, long stamp, long now) {
        // (1) Anni window — quietest possible default.
        if (stamp == 0) return false;
        long delta = stamp - now;
        if (delta < -ACTIVE_WINDOW_SEC || delta > ACTIVE_WINDOW_SEC) return false;

        // (2) Snapshot present and has organiser usernames.
        if (snapshot == null) return false;
        List<String> orgs = snapshot.organiserUsernames();
        if (orgs.isEmpty()) return false;

        // (3) Organiser overlap with the captured party.
        Set<String> orgLower = new HashSet<>(orgs.size());
        for (String name : orgs) {
            if (name != null && !name.isEmpty()) {
                orgLower.add(name.toLowerCase(Locale.ROOT));
            }
        }
        if (orgLower.isEmpty()) return false;
        if (snap.leader() != null && !snap.leader().isEmpty()
                && orgLower.contains(snap.leader().toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (String m : snap.members()) {
            if (m != null && !m.isEmpty()
                    && orgLower.contains(m.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    record Snapshot(String leader, List<String> members) {
        static final Snapshot EMPTY = new Snapshot("", List.of());
    }
}
