package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Models;
import com.wynntils.models.players.event.PartyEvent;
import com.wynntils.models.worlds.event.WorldStateEvent;
import com.wynntils.models.worlds.type.WorldState;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.fetcher.ondemand.OnlineMemberService;
import org.wynnvets.fetcher.polling.AnniStampPoller;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reports the local player's current Wynncraft party roster to the v1 inbound
 * WebSocket so vets-anni can light up the {@code ONLINE_PARTY} status border.
 *
 * <p>Subscribes to every Wynntils {@link PartyEvent} variant that materially
 * changes the roster and, on each, captures a snapshot of {@code Models.Party}
 * and schedules a debounced {@link V1ApiManager#sendPartyStatus} send. The
 * debounce coalesces the typical burst (a {@code /party list} response fires
 * multiple events) into a single frame. Snapshot capture happens on the event
 * thread (where {@code PartyModel} is consistent); the send fires on the
 * scheduler thread from the captured snapshot, never re-reading
 * {@code Models.Party}.</p>
 *
 * <p>Wynntils has no dedicated event for "you left the party" or "your party
 * was disbanded" — both cases reset {@code PartyModel} silently. We catch
 * these via {@link WorldStateEvent}: any transition out of {@link
 * WorldState#WORLD} (server/world hop, disconnect, returning to hub) sends
 * a final snapshot reflecting whatever {@code Models.Party.isInParty()}
 * reports at the time, which correctly emits the empty "not in a party"
 * frame after a disband.</p>
 *
 * <p>Threading: Wynntils event listeners run on the render thread. We
 * capture a defensively-copied snapshot on that thread (so the send can't
 * race a concurrent {@code PartyModel} mutation), then hop to a daemon
 * single-thread scheduler for the actual send. The scheduler's send call
 * goes through {@link V1ApiManager#sendPartyStatus} which performs a
 * thread-safe WebSocket write.</p>
 */
public final class PartyRosterListener {

    private static final long DEBOUNCE_MS = 300L;

    /** Send party_status only when the announced anni is within this many
     *  seconds of {@code now} on either side. Outside this window no anni
     *  board is being actively used, so reports are pure overcollection. */
    static final long ACTIVE_WINDOW_SEC = 2L * 60 * 60;
    /** Max age of the {@link OnlineMemberService} cache to consult when
     *  evaluating walk-in eligibility. A cold cache → skip the send and
     *  warm async so the next event has fresh data. */
    static final long ONLINE_LIST_CACHE_MAX_AGE_SEC = 60;
    /** Vets cohort tiers — duplicated here so {@code shouldSend} stays
     *  testable against a constant set. */
    static final Set<String> VETS_TIERS = Set.of("guild", "waitlist", "honourary");

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
     * Re-broadcasts on every world transition. Entering {@link WorldState#WORLD}
     * covers re-auth after reconnect and world hops; leaving covers disband /
     * self-leave (Wynntils fires no dedicated event for those), and a
     * disconnect that drops {@code PartyModel} state.
     */
    @SubscribeEvent
    public void onWorldState(WorldStateEvent event) {
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
        if (!shouldSendNow(toSend)) {
            VetsLogger.debug(
                    "party_status suppressed (out of window / non-vets cohort)");
            return;
        }
        try {
            V1ApiManager.sendPartyStatus(toSend.leader(), toSend.members());
        } catch (Exception e) {
            VetsLogger.debug("party_status send failed: {}", e.getMessage());
        }
    }

    /**
     * Live wiring of the gating predicate — reads the singletons that hold
     * the inputs and delegates to {@link #shouldSend} for the testable
     * decision. Also kicks an async refresh of the {@link
     * OnlineMemberService} cache when it's cold, so the next event has a
     * fresh signal to evaluate. Privacy-first: we'd rather drop this send
     * than fabricate gating on stale data.
     */
    private static boolean shouldSendNow(Snapshot snap) {
        long stamp = AnniStampPoller.getLatestStamp();
        long now = System.currentTimeMillis() / 1000L;
        String tier = GuildStateManager.isAuthenticatedThisSession()
                ? GuildStateManager.currentAuthTier()
                : "";
        Set<String> vetsConnected =
                OnlineMemberService.lastVetsTierUsernamesIfFreshOrNull(
                        ONLINE_LIST_CACHE_MAX_AGE_SEC);
        boolean decision = shouldSend(snap, stamp, now, tier, vetsConnected);
        if (!decision && vetsConnected == null && stamp != 0
                && Math.abs(stamp - now) <= ACTIVE_WINDOW_SEC
                && !VETS_TIERS.contains(tier)) {
            // We're in-window and the only reason we suppressed was a cold
            // /list cache. Warm it for the next event.
            OnlineMemberService.refreshAsync();
        }
        return decision;
    }

    /**
     * Pure predicate (no statics, no I/O): is this snapshot eligible to be
     * sent as a {@code party_status} frame given the current inputs?
     *
     * <p>Rules — both branches must accept:
     * <ol>
     *   <li>Anni window: {@code stamp != 0} AND {@code |stamp - now| <= ACTIVE_WINDOW_SEC}.</li>
     *   <li>Cohort: {@code tier} is in {@link #VETS_TIERS}, OR
     *       {@code vetsConnected} (non-null) contains the leader or any
     *       party member name (case-insensitive).</li>
     * </ol>
     *
     * <p>Reads {@code vetsConnected == null} as "cache is cold, don't guess"
     * — the wrapper {@link #shouldSendNow} converts that into an async warm.
     *
     * @param snap            the captured party snapshot from Wynntils
     * @param stamp           anni epoch-seconds (0 = none announced)
     * @param now             current wall-clock epoch-seconds
     * @param tier            authenticated tier; "" or null = unauth
     * @param vetsConnected   lowercase set of vets-tier connected usernames,
     *                        or {@code null} for "cold cache, treat as no data"
     */
    static boolean shouldSend(Snapshot snap, long stamp, long now, String tier,
                              Set<String> vetsConnected) {
        // (1) Anni window — quietest possible default.
        if (stamp == 0) return false;
        long delta = stamp - now;
        if (delta < -ACTIVE_WINDOW_SEC || delta > ACTIVE_WINDOW_SEC) return false;

        // (2a) Self is in the vets cohort -> always send.
        if (tier != null && VETS_TIERS.contains(tier)) return true;

        // (2b) Self isn't vets-tier — still send iff some party member IS a
        // vets-tier vetsmod-connected user (walk-in attached to a vets party).
        //
        // TODO(fishbot-integration): replace this proxy with a real check
        // against the active anni's organisers (the party hosts on the
        // fishbot board). Today vetsmod has no way to read that list — it
        // lives in vets-anni's DB and would need (a) a new outbound endpoint
        // on temp-server, (b) a sync path from vets-anni populating it,
        // (c) a vetsmod poller consuming it. Full fishbot integration is
        // targeted for early next week — at that point this block becomes a
        // simple `vetsConnected` swap to `organiserNames`.
        //
        // Until then, "any vets-tier vetsmod-connected user is in your
        // party" is a strict superset of "an organiser is in your party"
        // (organisers are always staff = always guild-tier and always have
        // vetsmod on during the anni), so we don't under-collect — we just
        // collect a few legitimate extras from members partied with other
        // vets-tier players for non-anni reasons.
        if (vetsConnected == null) return false;
        if (snap.leader() != null && !snap.leader().isEmpty()
                && vetsConnected.contains(snap.leader().toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (String m : snap.members()) {
            if (m != null && !m.isEmpty()
                    && vetsConnected.contains(m.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    record Snapshot(String leader, List<String> members) {
        static final Snapshot EMPTY = new Snapshot("", List.of());
    }
}
