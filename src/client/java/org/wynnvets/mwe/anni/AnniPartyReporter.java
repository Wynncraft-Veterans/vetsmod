package org.wynnvets.mwe.anni;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.wynnvets.listeners.PartyRosterListener;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

/**
 * S7 — snapshot-driven trigger for the party-back-report pipeline.
 *
 * <p>The {@link PartyRosterListener} already recaptures on the Wynntils {@code PartyEvent}s it
 * subscribes to and on every {@code WorldStateEvent}, sending {@code anni_party_observation} when
 * its gate passes. None of those marks the anni window opening, so a local player parked in the
 * same Wynncraft party since before the window opened would send nothing then. This reporter adds
 * two triggers, both read off {@link AnniSnapshotCache}.</p>
 *
 * <p>It calls {@link PartyRosterListener#requestRecapture()} when {@link #shouldRecapture} says
 * so: when the case-folded {@code organiser_usernames} set changes, or when the anni window opens.
 * The set trigger is a plain set inequality, so empty to non-empty, a content change (a new
 * organiser assigned mid-window) and non-empty back to empty are one condition; a first snapshot
 * carrying no organisers is not a change, since the set starts empty, so startup does not fire.
 * The window trigger is an edge: the stamp in the snapshot's {@code event} is within
 * {@link PartyRosterListener#ACTIVE_WINDOW_SEC} of now ({@link PartyRosterListener#inActiveWindow})
 * on this update and was not on the previous one, or on none before it. A snapshot with no stamp
 * counts as outside the window. The edge fires once per opening, not on every update inside the
 * window, so a client whose organiser set stays the same sends one recapture when the window
 * opens and none for the rest of it; keeping vets-anni's 60 s freshness fed is
 * {@code party-observation-lapses-within-vets-anni-freshness}. The window is read only when a
 * snapshot arrives, and {@code AnniSnapshotPoller} polls only from T-90m, so unless a pull lands
 * between T-2h and T-90m the edge fires at about T-90m rather than at T-2h. The listener's
 * debounce coalesces a recapture with any near-simultaneous Wynntils events so we never
 * double-send.</p>
 *
 * <p>Init order: {@link #register()} is called from
 * {@link org.wynnvets.VetsmodClient#onClientStarted() VetsmodClient.onClientStarted()}, the
 * {@code ClientLifecycleEvents.CLIENT_STARTED} phase, beside the other Wynntils-facing
 * registrations. Wynntils' {@code Models.*} access from the body of
 * {@link org.wynnvets.VetsmodClient#onInitializeClient VetsmodClient#onInitializeClient} can
 * crash the game at start-up (see {@code feedback_vetsmod_wynntils_init_order.md}), but this
 * listener makes none there. It reaches {@code Models.Party} only through
 * {@link org.wynnvets.listeners.PartyRosterListener#requestRecapture()
 * PartyRosterListener#requestRecapture()}, which hands the read to a render-thread task,
 * and snapshots arrive only over the sockets, which open at a Wynncraft join, or from a
 * {@code /wv debug tree anni} command, both after {@code CLIENT_STARTED}.</p>
 */
public final class AnniPartyReporter {

    private static volatile boolean registered = false;
    private static volatile Set<String> lastOrgs = Set.of();
    private static volatile boolean lastInWindow = false;

    private AnniPartyReporter() {}

    /** Wire the snapshot listener. Idempotent. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        AnniSnapshotCache.addListener(AnniPartyReporter::onSnapshotUpdate);
        VetsLogger.debug("AnniPartyReporter registered");
    }

    /**
     * Pure decision (no statics, no I/O): does a snapshot update call for a recapture? True when
     * the case-folded organiser set changed ({@code prev} and {@code next} differ as sets), or
     * when the anni window opened ({@code wasInWindow} false and {@code nowInWindow} true). The
     * window closing, and updates that change neither, recapture nothing.
     *
     * @param prev        the organiser set the previous update left
     * @param next        the organiser set of this update, case-folded
     * @param wasInWindow whether the previous update's stamp was inside the window
     * @param nowInWindow whether this update's stamp is inside the window
     */
    static boolean shouldRecapture(
            Set<String> prev, Set<String> next, boolean wasInWindow, boolean nowInWindow) {
        return !prev.equals(next) || (!wasInWindow && nowInWindow);
    }

    private static void onSnapshotUpdate(AnniSnapshot snapshot) {
        List<String> orgs = snapshot != null ? snapshot.organiserUsernames() : List.of();
        Set<String> norm = new HashSet<>(orgs.size());
        for (String name : orgs) {
            if (name != null && !name.isEmpty()) {
                norm.add(name.toLowerCase(Locale.ROOT));
            }
        }
        long now = System.currentTimeMillis() / 1000L;
        boolean nowInWindow =
                PartyRosterListener.inActiveWindow(
                        PartyRosterListener.snapshotStamp(snapshot), now);
        Set<String> prev = lastOrgs;
        boolean wasInWindow = lastInWindow;
        lastOrgs = Set.copyOf(norm);
        lastInWindow = nowInWindow;
        if (!shouldRecapture(prev, norm, wasInWindow, nowInWindow)) {
            return;
        }
        // The organiser set changed, or the window opened. The PartyRosterListener's gate
        // re-evaluates with the new snapshot and either fires (an organiser is in the party)
        // or stays silent. We do NOT pre-check the party half of the gate here: the listener
        // owns the captured-vs-empty-party logic.
        PartyRosterListener.requestRecapture();
    }
}
