package org.wynnvets.mwe.anni.state;

/**
 * The four snapshot fields the diffing surfaces read, pulled out once each.
 *
 * <p>{@link AnniSnapshot}'s contract is that callers must null-check at every
 * nested access, and two classes discharged it by carrying four private
 * extractors apiece — {@code FlashTracker} and {@code AggressiveAlertDispatcher},
 * fourteen call sites between them. Three of the four pairs were byte-identical
 * (one of those under two different names); the fourth,
 * {@link #partyWorld(AnniSnapshot)}, quietly was not. This class is the one place
 * those four fields are extracted. It discharges the contract for these four and
 * for nothing else — every other nested access still null-checks its own way
 * down.</p>
 *
 * <h2>Every accessor guards its argument</h2>
 *
 * <p>A {@code null} snapshot is a legal cache state, not a cold-start artefact.
 * {@link AnniSnapshotCache#update(AnniSnapshot)} documents {@code null} as an
 * accepted "no snapshot available" signal, and {@code AnniDebugCommands.snapshotClear}
 * ({@code /wv debug tree anni snapshot clear}) makes exactly that call — so
 * {@link AnniSnapshotCache#latest()} can return {@code null} at any point in a warm
 * session.</p>
 *
 * <p>Only one of the fourteen call sites this class replaces could ever pass
 * {@code null}: {@code FlashTracker.updateWorldMismatch} calls
 * {@code partyWorld(AnniSnapshotCache.latest())} unconditionally from the boss bar's
 * per-tick path, and its extractor was the only one of the eight bodies to carry
 * the guard. Without it, one debug command turns the world-mismatch flash into an NPE
 * on every client tick for the rest of the session, swallowed into a debug log — a
 * silently dead flash with no error the user can see. The other thirteen sites are
 * behind a null check of their own, so the guard is inert for them and the diff
 * proves it. All four accessors take it anyway: this is a public seam that new
 * callers will reach for, and "guarded on some fields" is not a contract anyone can
 * hold in their head.</p>
 *
 * <h2>Deliberately not here</h2>
 *
 * <p>Six further sites walk some prefix of the same
 * snapshot → event → stampEpoch chain and are <b>not</b> migrated:</p>
 *
 * <ul>
 *   <li>{@code GhostsPromptHandler.currentStampEpoch} and
 *       {@code PartyRosterListener.snapshotStamp} are this class's shape already, in
 *       the wrong place, twice — but they return {@code 0L} rather than a
 *       {@code Long}. Adopting them is a question about what {@code 0} means at their
 *       call sites, not a rehoming.</li>
 *   <li>{@code AnniWindowWatcher}'s variant is <b>load-bearing</b>. It keys off a
 *       locally cached {@code lastKnownStamp} anchor rather than the live snapshot,
 *       because vets-anni emits {@code stamp_epoch: null} once the anni begins — so
 *       reading the current snapshot would lose the anchor at exactly the moment it
 *       is needed. Do not "simplify" it onto this class.</li>
 *   <li>{@code AnniMotdRenderer}, {@code AnniCommandRenderer} and
 *       {@code AnniDebugCommands} are render and diagnostic surfaces scheduled for
 *       splits of their own.</li>
 * </ul>
 *
 * <p>Pure: no clock, no cache read, no statics. Callers fetch their own snapshot and
 * pass it in.</p>
 */
public final class AnniSnapshots {

    private AnniSnapshots() {}

    /**
     * The local player's assigned role code ({@code TANK} / {@code HEALER} /
     * {@code FILL} / …), or {@code null} if the board carries none.
     *
     * @param snapshot the current snapshot; {@code null} is accepted
     */
    public static String role(AnniSnapshot snapshot) {
        if (snapshot == null) return null;
        AnniSnapshot.Board board = snapshot.board();
        if (board != null && board.role() != null) return board.role();
        return null;
    }

    /**
     * The local player's party number, or {@code null} when unassigned.
     *
     * <p>⚠️ The {@code > 0} re-check is <b>not</b> a redundant positivity guard.
     * {@link AnniSnapshot.Party#ordinal()} returns a primitive {@code int} and
     * collapses an absent or JSON-null {@code ordinal} to {@code 0}; recovering the
     * "unassigned" signal is what this comparison is for. Deleting it turns every
     * unassigned player into a member of party 0.</p>
     *
     * @param snapshot the current snapshot; {@code null} is accepted
     */
    public static Integer partyOrdinal(AnniSnapshot snapshot) {
        if (snapshot == null) return null;
        AnniSnapshot.Board board = snapshot.board();
        if (board == null || board.party() == null) return null;
        int o = board.party().ordinal();
        return o > 0 ? o : null;
    }

    /**
     * The world the local player's party is assigned to ({@code EU5}, …), or
     * {@code null} when there is no party or no assignment yet.
     *
     * <p>The one accessor whose two source bodies disagreed, and the one whose guard
     * is reachable — see the class Javadoc.</p>
     *
     * @param snapshot the current snapshot; {@code null} is accepted
     */
    public static String partyWorld(AnniSnapshot snapshot) {
        if (snapshot == null) return null;
        AnniSnapshot.Board board = snapshot.board();
        if (board == null || board.party() == null) return null;
        return board.party().world();
    }

    /**
     * The local player's live RSVP notice code, or {@code null} when there is no
     * RSVP or it has been revoked.
     *
     * <p>A revoked RSVP reads as absent rather than as its old value, so the
     * surfaces that diff this field see revocation as a change to {@code null}.
     * That is the field's meaning, not a defensive nicety.</p>
     *
     * @param snapshot the current snapshot; {@code null} is accepted
     */
    public static String rsvpNotice(AnniSnapshot snapshot) {
        if (snapshot == null) return null;
        AnniSnapshot.Rsvp rsvp = snapshot.rsvp();
        if (rsvp == null || rsvp.revoked()) return null;
        return rsvp.notice();
    }
}
