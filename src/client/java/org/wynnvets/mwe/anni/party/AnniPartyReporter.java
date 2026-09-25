package org.wynnvets.mwe.anni.party;

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
 * its gate passes. This reporter is meant to add the missing case: if the local player has been
 * parked in the same Wynncraft party for some time BEFORE the anni window opens, no event marks
 * the opening, so the listener may not recapture. Today it fires on an organiser-set change
 * (below), not on the window opening, so a set that was already complete before the window opened
 * triggers nothing then; see {@code party-reporter-window-open-not-a-trigger}.</p>
 *
 * <p>Solution: subscribe to {@link AnniSnapshotCache} and trigger a
 * synthetic {@link PartyRosterListener#requestRecapture()} on <b>any</b>
 * change to the case-folded {@code organiser_usernames} set — empty to
 * non-empty, a content change (a new organiser assigned mid-window),
 * <em>and</em> non-empty back to empty. The guard is a plain set
 * inequality, so all three are one condition; listing only the first two
 * understates it. A first snapshot carrying no organisers is not a
 * change, since the field starts empty, so startup does not fire.
 * The listener's debounce coalesces this with any near-simultaneous
 * Wynntils events so we never double-send.</p>
 *
 * <p>Init order: call {@link #init()} from the {@code ClientLifecycleEvents.CLIENT_STARTED} lambda
 * inside {@link org.wynnvets.VetsmodClient#onInitializeClient VetsmodClient#onInitializeClient}
 * (there is no {@code onClientStarted} method), NEVER from the initializer body itself —
 * Wynntils' {@code Models.*} access from the initializer body cold-start-crashes the game (see
 * {@code feedback_vetsmod_wynntils_init_order.md}). Runtime hooks are safe; Wynntils is fully
 * constructed by the time snapshots arrive.</p>
 */
public final class AnniPartyReporter {

    private static volatile boolean registered = false;
    private static volatile Set<String> lastOrgs = Set.of();

    private AnniPartyReporter() {}

    /** Wire the snapshot listener. Idempotent. */
    public static void init() {
        if (registered) {
            return;
        }
        registered = true;
        AnniSnapshotCache.addListener(AnniPartyReporter::onSnapshotUpdate);
        VetsLogger.debug("AnniPartyReporter registered");
    }

    private static void onSnapshotUpdate(AnniSnapshot snapshot) {
        List<String> orgs = snapshot != null ? snapshot.organiserUsernames() : List.of();
        Set<String> norm = new HashSet<>(orgs.size());
        for (String name : orgs) {
            if (name != null && !name.isEmpty()) {
                norm.add(name.toLowerCase(Locale.ROOT));
            }
        }
        if (norm.equals(lastOrgs)) {
            return;
        }
        lastOrgs = Set.copyOf(norm);
        // Set transitioned (empty→non-empty, content change, or non-empty→empty).
        // The PartyRosterListener's gate re-evaluates with the new snapshot
        // and either fires (organiser is in the party) or stays silent. We do
        // NOT pre-check the gate here — the listener owns the
        // captured-vs-empty-party logic.
        PartyRosterListener.requestRecapture();
    }
}
