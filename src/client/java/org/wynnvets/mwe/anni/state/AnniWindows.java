package org.wynnvets.mwe.anni.state;

/**
 * The anni time windows, named once each.
 *
 * <p>Almost everything under {@code mwe/anni/} gates on "how far are we from the
 * announced anni". Before this class the answer was compared against <b>seven
 * constant declarations across five classes, expressing the three windows
 * below</b>. Two of the seven carried a comment naming their partner — one by
 * class, one by symbol — and neither comment was checkable by anything. This class
 * holds each edge once, so the "keep in sync with X" relationship is a compile-time
 * one.</p>
 *
 * <p><b>It unifies nothing.</b> The three constants below are three different
 * windows and stay that way — moving the bar and the poller from 90 m to 2 h
 * makes the bar appear thirty minutes earlier and adds a third again as much
 * polling; moving the tickers the other way stops highlights appearing between
 * T−2h and T−90m. Both are user-visible and neither has a motivation.</p>
 *
 * <h2>The windows</h2>
 *
 * <ul>
 *   <li>{@link #HOT_WINDOW_BEFORE_SECONDS} / {@link #HOT_WINDOW_AFTER_SECONDS} —
 *       the two-sided "hot window", spec §"WITHIN 2h of an anni, OR within 30
 *       mins after an anni". Read by
 *       {@link org.wynnvets.mwe.anni.outline.AnniOutlineTicker AnniOutlineTicker} and
 *       {@link org.wynnvets.mwe.anni.aggressive.AnniAggressiveTicker AnniAggressiveTicker}
 *       through {@link #inHotWindow(long)}, and by
 *       {@link org.wynnvets.mwe.anni.mode.AnniWindowWatcher AnniWindowWatcher} — which needs
 *       the closing edge alone, inverted — through {@link #hotWindowClosed(long, long)}.</li>
 *   <li>{@link #BAR_WINDOW_SECONDS} — boss-bar activation and snapshot-poll
 *       cadence. Exposed as a constant only; see below.</li>
 * </ul>
 *
 * <h2>Why {@code BAR_WINDOW_SECONDS} has no predicate</h2>
 *
 * <p>The two 90-minute comparisons share a number and <b>not a floor</b>, and the
 * difference is deliberate.
 * {@link org.wynnvets.fetcher.polling.AnniSnapshotPoller AnniSnapshotPoller} floors at
 * {@code secondsUntilAnni > 0}. {@link org.wynnvets.mwe.anni.bossbar.VetsBossBarManager
 * VetsBossBarManager} floors at {@code DROP_DEAD_SECONDS_BEFORE_ANNI} (20 s), applied by an
 * earlier hard return in the same method rather than by the window expression, which is why the
 * expression reads one-sided. The bar's effective window is therefore
 * {@code (T−20s, T−90m]} and the poller's is {@code (T, T−90m]} — twenty seconds
 * apart at the bottom. A shared {@code inBarWindow} predicate would have to change
 * one of them.</p>
 *
 * <p>That 20-second floor is one half of a documented <b>two</b>-gate boss-bar
 * design whose other half lives in
 * {@code VetsBossBarContentBuilder.T_MINUS_20_GATE_SECONDS};
 * it is a boss-bar timing constant, so it stays where {@code progressFor} can see
 * it and does not move here. It is named in this paragraph because the bar window
 * genuinely does have a lower bound, and reading the window expression alone
 * suggests otherwise.</p>
 *
 * <p>The bar's activation gate is also <b>not</b> a window test on its own — it is
 * {@code inWindow || AnniZone.isInZone(...)}. A player who walks into the zone
 * hours early gets the bar. This class supplies the number, not the gate.</p>
 *
 * <h2>Deliberate non-residents</h2>
 *
 * <p>Four further anni time constants live outside this class and stay outside it.
 * Agreeing on a number today is not shared configuration.</p>
 *
 * <ul>
 *   <li>{@code PartyRosterListener.ACTIVE_WINDOW_SEC} — 7200, but <b>symmetric</b>
 *       ({@code |stamp − now| <= 7200}); it answers "is the roster worth
 *       reporting", a different question with a third guard shape.</li>
 *   <li>{@code AnniCommandRenderer.TWO_HOURS_SECONDS} — 7200, used to select the
 *       far-out versus imminent render branch of {@code /wv anni}. A layout
 *       decision, not an activation window.</li>
 *   <li>{@code VetsBossBarManager.DROP_DEAD_SECONDS_BEFORE_ANNI} and
 *       {@code VetsBossBarContentBuilder.T_MINUS_20_GATE_SECONDS} — both 20, the two
 *       halves of the bar's documented T−20s design. The first is the bar window's
 *       real floor, discussed above.</li>
 * </ul>
 *
 * <p>So a later census finds <b>three constant declarations holding 7200</b> —
 * {@link #HOT_WINDOW_BEFORE_SECONDS} and the first two above — where before this
 * class there were four. ⚠️ <b>Do not census them by greping for {@code 7200}</b>:
 * not one of the three is spelled that way. Two are {@code 2L * 60L * 60L} and one
 * is {@code 2L * 60 * 60}, so that grep finds <b>none of the three declarations</b>
 * &mdash; what it does return is the {@code "7200"} suggestion string in
 * {@link org.wynnvets.mwe.anni.debug.AnniDebugCommands AnniDebugCommands}, this paragraph and the prose above it,
 * {@link #HOT_WINDOW_BEFORE_SECONDS}'s own field Javadoc, and the boundary list in
 * {@code AnniWindowsTest}. Grep
 * {@code 60L \* 60L\|60 \* 60} instead, or read the three names above.</p>
 *
 * <h2>Shape</h2>
 *
 * <p>Pure: no clock, no cache, no statics beyond the constants. Callers keep their
 * own {@code Instant.now().getEpochSecond()} line exactly where it was and pass the
 * result in — the same shape as {@code PartyRosterListener.shouldSend(…, long now)},
 * which was already in the repo, already pure and already tested with an inclusive-edge
 * case. Epoch <b>seconds</b> throughout, so a caller spelling its clock
 * {@code System.currentTimeMillis() / 1000L} is passing a legal argument.</p>
 */
public final class AnniWindows {

    /** Open edge of the hot window: 2 hours before the announced stamp.
     *  Inclusive — {@code secondsUntil == 7200} is in. */
    public static final long HOT_WINDOW_BEFORE_SECONDS = 2L * 60L * 60L;

    /** Close edge of the hot window: 30 minutes after the announced stamp.
     *  Inclusive — {@code secondsUntil == -1800} is in. Also the mode
     *  auto-reset deadline. */
    public static final long HOT_WINDOW_AFTER_SECONDS = 30L * 60L;

    /** Boss-bar activation window and snapshot-poll cadence gate: 90 minutes
     *  before the announced stamp. No predicate — the two comparisons that read
     *  it have different floors on purpose; see the class Javadoc. */
    public static final long BAR_WINDOW_SECONDS = 90L * 60L;

    private AnniWindows() {}

    /**
     * {@code true} while the announced stamp is inside the two-sided hot window.
     * Both edges inclusive.
     *
     * @param secondsUntil {@code stamp - now}, in epoch seconds. Positive means
     *     the anni is in the future, negative means it has already started.
     */
    public static boolean inHotWindow(long secondsUntil) {
        return secondsUntil >= -HOT_WINDOW_AFTER_SECONDS
                && secondsUntil <= HOT_WINDOW_BEFORE_SECONDS;
    }

    /**
     * The hot window's closing edge, in the inverted arrangement the mode
     * auto-reset needs: {@code true} once {@code now} is past
     * {@code anchorStamp + }{@link #HOT_WINDOW_AFTER_SECONDS}.
     *
     * <p>Exactly the complement of {@link #inHotWindow(long)}'s close edge —
     * {@code hotWindowClosed(anchor, now)} is {@code true} iff
     * {@code inHotWindow(anchor - now)} is {@code false} for a negative
     * {@code secondsUntil}. Pinned by {@code AnniWindowsTest}.</p>
     *
     * <p>The anchor is a caller's own most-recent non-null stamp, not the live
     * snapshot's: vets-anni emits {@code stamp_epoch: null} once the anni begins,
     * so keying this off the current snapshot would lose the anchor at exactly
     * the moment it is needed.</p>
     *
     * @param anchorStamp the announced stamp, epoch seconds
     * @param now current wall-clock epoch seconds
     */
    public static boolean hotWindowClosed(long anchorStamp, long now) {
        return now > anchorStamp + HOT_WINDOW_AFTER_SECONDS;
    }
}
