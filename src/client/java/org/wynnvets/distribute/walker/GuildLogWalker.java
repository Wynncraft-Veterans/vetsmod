package org.wynnvets.distribute.walker;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Managers;
import com.wynntils.core.components.Models;
import com.wynntils.core.text.StyledText;
import com.wynntils.features.ui.CustomGuildLogScreenFeature;
import com.wynntils.handlers.wrappedscreen.WrappedScreen;
import com.wynntils.mc.event.ContainerSetSlotEvent;
import com.wynntils.mc.event.MenuEvent;
import com.wynntils.mc.event.TickEvent;
import com.wynntils.models.items.items.gui.GuildLogItem;
import com.wynntils.utils.mc.McUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.distribute.DistributeRuns;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.ContainerScreens;

/**
 * Walks the in-game {@code "<guild>'s Log: <category>"} GUI and
 * collects every {@link GuildLogItem} that lands in the container,
 * piggybacking on Wynntils' own auto-pagination of the log screen
 * &mdash; which exists only when Wynntils has wrapped that screen (see
 * "The piggyback is conditional" below).
 *
 * <h2>Why piggyback rather than paginate ourselves</h2>
 * <p>Wynntils ships a {@code GuildLogHolder} that wraps the vanilla log
 * GUI with a custom widget-based viewer and auto-clicks {@code Next
 * Page} (slot 45) as each full batch of 32 entries lands (when it does
 * both is covered below). The
 * holder's collected items aren't exposed via the model API, so we
 * can't read them directly &mdash; but the {@code ContainerSetSlot.Post}
 * events that feed it are public, and Wynntils' {@code GuildLogAnnotator}
 * (registered by {@code Models.Item}) turns each paper log-entry item into a
 * {@link GuildLogItem} containing its parsed timestamp and lore. Two
 * subscribers on the same events don't conflict on reading; only the
 * pagination is side-effectful, and we leave that to Wynntils.</p>
 *
 * <h2>The piggyback is conditional</h2>
 * <p>{@code GuildLogHolder} is on Wynntils' event bus only while Wynntils
 * has wrapped the log screen, and {@code WrappedScreenHandler} wraps it
 * only when a listener accepts {@code WrappedScreenOpenEvent} &mdash; for
 * the log, Wynntils' {@code CustomGuildLogScreenFeature}. That feature is
 * on by default only in some Wynntils config profiles ({@code DEFAULT}
 * and {@code LITE} at v4.1.17), and it is gated by its shift-behaviour
 * setting, which it compares against a shift flag that only a click made
 * through the game's own container-click path updates &mdash; the tile
 * click {@code GuildManageOpener} sends is a raw packet and does not.
 * When the log is not wrapped nothing auto-paginates, and the walk would
 * end on whatever had arrived. So {@code @graids} refuses up front when
 * the feature is off ({@link #wynntilsLogScreenEnabled()}), and the walk
 * checks {@link #WRAP_CHECK_TICKS} after binding (or at the overall cap, if
 * that comes first) that Wynntils did wrap the log; if the unwrapped log is
 * on top, the walk closes it and refuses in red rather than count only the
 * first page, and if no log is on top any more it stops the run as closed.</p>
 *
 * <h2>Completion detection</h2>
 * <p>Wynncraft caps the log at roughly 100 most-recent entries (about
 * 3-4 pages). While the log is wrapped, the holder turns the page only
 * while the Next Page slot is filled and 32 entries have arrived since
 * its last turn. We can't see that decision directly &mdash; the
 * holder field is private &mdash; so we use a settle-timer instead:
 * if no new {@link GuildLogItem} has been observed for
 * {@link #SETTLE_TICKS} ticks, the walk is considered complete.</p>
 *
 * <p>When the settle timer, or once bound the overall timeout, ends the
 * walk, the walker closes the log if it is still the screen on top
 * &mdash; Wynntils' wrap of it through {@code clientSideCloseContainer()},
 * whose wrapped-screen teardown sends the close packet, and the unwrapped
 * log through {@code closeContainer()} &mdash; and invokes the
 * {@link Completion} callback with a copy of the collected items. If the
 * log is no longer on top by then (the player's Esc, a respawn), it closes
 * nothing and stops the run instead, through
 * {@link org.wynnvets.distribute.DistributeRuns#stop(String) DistributeRuns.stop}, as does a
 * log that never opens once the overall timeout fires. A server close of the log stops the run
 * at once ({@link #onMenuClose}), so nothing is sent from a log the server closed mid-walk.</p>
 */
public final class GuildLogWalker {

    /** Mirrors {@code GuildLogHolder.TITLE_PATTERN}. */
    private static final Pattern LOG_TITLE_PATTERN = Pattern.compile(".+'s? Log: (.+)");

    /** No new log item for this many ticks &rArr; walk is done. Picked
     *  long enough to cover Wynntils' inter-page request delay
     *  ({@code REQUEST_TIMEOUT = 5} + {@code FORCED_LOAD_DELAY = 20}). */
    private static final int SETTLE_TICKS = 40;

    /** Safety cap, ~10 s from the arm: a walk still going this long ends. A bound log still on
     *  top is closed and what was collected is delivered; a log that never opened, or is no
     *  longer on top, stops the run instead. */
    private static final int OVERALL_TIMEOUT_TICKS = 200;

    /** Ticks after the bind before checking that Wynntils wrapped the log. Wynntils wraps on
     *  the same {@code MenuOpenedEvent.Post} the walk binds on, in no defined order, so the
     *  check waits until that event has certainly finished. */
    private static final int WRAP_CHECK_TICKS = 2;

    /** The red line for a log Wynntils opened but did not wrap. */
    private static final String NOT_WRAPPED =
            "Graid-based distribution requires a Wynntils feature that did not open the guild"
                    + " log (Custom Guild Log Screen).";

    /** Completion callback, fired once when the walk ends on settle or on the overall timeout
     *  with the bound log still on top, with whatever was collected. Not fired when the log
     *  never opened, the server closed it, or it is gone by the time the walk settles or times
     *  out (those stop the run), nor when Wynntils did not wrap it (that refuses the run). A
     *  later {@link #armWalk} replaces it without firing it. */
    @FunctionalInterface
    public interface Completion {
        void onComplete(List<GuildLogItem> entries);
    }

    private static final GuildLogWalker INSTANCE = new GuildLogWalker();

    private static volatile boolean active = false;
    private static volatile int containerId = -1;
    private static volatile Completion completion = null;
    private static final List<GuildLogItem> collected = new ArrayList<>();

    /** Ticks since the walk was armed, counted by {@link #onTick} itself. The player entity's
     *  {@code tickCount} would restart on a respawn or world change mid-walk, which reset both
     *  timers below. */
    private static volatile int ticks = 0;

    /** {@link #ticks} at the last new GuildLogItem, or at the bind if none has
     *  landed yet (-1 until the walk binds); the settle timer measures
     *  from this. The overall timeout measures from the arm, where
     *  {@link #ticks} starts. */
    private static volatile int lastItemTick = -1;

    /** {@link #ticks} at the bind, for the wrap check; -1 once the check has run. */
    private static volatile int bindTick = -1;

    private GuildLogWalker() {}

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        VetsLogger.debug("Registered GuildLogWalker on Wynntils event bus");
    }

    /**
     * Arms the walker for the next guild log open. The caller is expected to follow up with
     * something that opens the log GUI. The only caller today,
     * {@link org.wynnvets.distribute.distributor.GraidsDistributor GraidsDistributor}, uses
     * {@link org.wynnvets.distribute.opener.GuildManageOpener#openGuildLog()
     * GuildManageOpener#openGuildLog()}, which sends {@code /guild manage} and clicks the Guild Log
     * tile &mdash; Wynncraft has been observed to drop a direct {@code /guild log} sent shortly
     * after a menu close, which is the whole reason that route exists.
     */
    public static void armWalk(Completion onComplete) {
        active = true;
        bindTick = -1;
        completion = onComplete;
        containerId = -1;
        collected.clear();
        lastItemTick = -1;
        ticks = 0;
        VetsLogger.debug("GuildLogWalker: armed, awaiting menu open");
    }

    private static void stop() {
        active = false;
        completion = null;
        containerId = -1;
        collected.clear();
        lastItemTick = -1;
        ticks = 0;
    }

    @SubscribeEvent
    public void onMenuOpenPost(MenuEvent.MenuOpenedEvent.Post event) {
        if (!active) return;
        if (containerId != -1) return;
        String titleText = StyledText.fromComponent(event.getTitle()).getStringWithoutFormatting();
        if (!StyledText.fromComponent(event.getTitle()).matches(LOG_TITLE_PATTERN)) {
            VetsLogger.debug(
                    "GuildLogWalker: ignored menu open id={} title=[{}] (no log-title match)",
                    event.getContainerId(),
                    titleText);
            return;
        }
        containerId = event.getContainerId();
        // Set the last-item tick to "now" so we don't immediately settle
        // before any items have had time to arrive.
        lastItemTick = ticks;
        bindTick = ticks;
        VetsLogger.debug("GuildLogWalker: bound to menu id={} title=[{}]", containerId, titleText);
    }

    @SubscribeEvent
    public void onMenuClose(MenuEvent.MenuClosedEvent event) {
        if (!active) return;
        if (event.getContainerId() != containerId) return;
        // A clientbound close for the bound log — Wynntils posts
        // MenuClosedEvent only from its handleContainerClose hook, so this
        // is the server taking away the menu the run drives. Wynntils pages
        // the log in place, so this is never the walk's natural end, and the
        // walk may have missed pages: stop the run, without touching the
        // screen. A client-side close (the player's Esc) posts nothing here:
        // that walk ends on onTick's settle timer (or its overall timeout),
        // which finds the log gone and stops the run the same way.
        VetsLogger.debug("GuildLogWalker: menu closed mid-walk after {} entries", collected.size());
        stop();
        DistributeRuns.stop("the guild log closed");
    }

    @SubscribeEvent
    public void onSetSlot(ContainerSetSlotEvent.Post event) {
        if (!active) return;
        if (event.getContainerId() != containerId) return;
        ItemStack stack = event.getItemStack();
        Optional<GuildLogItem> opt = Models.Item.asWynnItem(stack, GuildLogItem.class);
        if (opt.isEmpty()) return;

        GuildLogItem item = opt.get();
        // Dedupe by (instant, first-line-text), in case the same entry
        // arrives in more than one SetSlot packet.
        if (alreadyCollected(item)) return;
        collected.add(item);
        lastItemTick = ticks;
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!active) return;
        int now = ++ticks;
        // The wrap check runs first, and at the cap if that comes sooner, so a
        // log bound just before the cap is checked before the cap delivers it.
        if (containerId != -1
                && bindTick >= 0
                && (now - bindTick >= WRAP_CHECK_TICKS || now > OVERALL_TIMEOUT_TICKS)) {
            bindTick = -1;
            if (!checkWrapped()) return;
        }
        // The overall-timeout check is outside the containerId guard
        // so a walk that's armed but never sees a menu open
        // (e.g. the open command was silently dropped server-side)
        // still ends after the deadline. That one stops the run, since an
        // empty list would read as a log with no graids in it, and it
        // disarms, so a later unrelated guild-log open can't bind the
        // stale arm.
        if (now > OVERALL_TIMEOUT_TICKS) {
            VetsLogger.debug(
                    "GuildLogWalker: overall timeout reached (boundId={}, {} entries)",
                    containerId,
                    collected.size());
            if (containerId == -1) {
                stop();
                DistributeRuns.stop("the guild log did not open");
                return;
            }
            finishWalk();
            return;
        }
        if (containerId == -1) return;
        if (lastItemTick >= 0 && now - lastItemTick > SETTLE_TICKS) {
            VetsLogger.debug("GuildLogWalker: settled after {} entries", collected.size());
            finishWalk();
        }
    }

    private static boolean alreadyCollected(GuildLogItem item) {
        for (GuildLogItem existing : collected) {
            if (!existing.getLogInstant().equals(item.getLogInstant())) continue;
            if (existing.getLogInfo().isEmpty() || item.getLogInfo().isEmpty()) continue;
            if (existing.getLogInfo().get(0).equals(item.getLogInfo().get(0))) return true;
        }
        return false;
    }

    private static void finishWalk() {
        Completion cb = completion;
        List<GuildLogItem> snapshot = new ArrayList<>(collected);
        int boundId = containerId;
        stop();
        if (!closeLogIfOnTop(boundId)) {
            // The log is no longer the screen on top — the player closed
            // it, or a respawn did — so whatever is there now is not ours to
            // close, and the walk may have missed pages: stop the run.
            VetsLogger.debug(
                    "GuildLogWalker: log id={} gone at the end of the walk ({} entries)",
                    boundId,
                    snapshot.size());
            DistributeRuns.stop("the guild log closed");
            return;
        }
        if (cb != null) cb.onComplete(snapshot);
    }

    /**
     * Whether Wynntils' Custom Guild Log Screen feature is enabled, without which nothing pages
     * the log (see the class Javadoc). {@code false} when the check itself fails:
     * {@code getFeatureInstance} throws for an unregistered feature.
     */
    public static boolean wynntilsLogScreenEnabled() {
        try {
            return Managers.Feature.getFeatureInstance(CustomGuildLogScreenFeature.class)
                    .isEnabled();
        } catch (RuntimeException | LinkageError e) {
            VetsLogger.debug("GuildLogWalker: could not read CustomGuildLogScreenFeature: {}", e);
            return false;
        }
    }

    /**
     * The wrap backstop; {@code true} to carry on. A log Wynntils wrapped passes. An unwrapped
     * log still on top would never be paged, so it is closed and the run refused rather than
     * counting only the entries that arrived without a page turn. A log no longer on top at all
     * (the player's Esc) is a lost menu, not a Wynntils problem, so the run stops.
     */
    private static boolean checkWrapped() {
        int boundId = containerId;
        if (isWrapped(boundId)) return true;
        stop();
        if (ContainerScreens.currentWithId(boundId) == null) {
            VetsLogger.debug("GuildLogWalker: log id={} gone before the wrap check", boundId);
            DistributeRuns.stop("the guild log closed");
            return false;
        }
        VetsLogger.debug("GuildLogWalker: log id={} not wrapped by Wynntils", boundId);
        LocalPlayer player = McUtils.player();
        if (player != null) player.closeContainer();
        DistributeRuns.refuse(NOT_WRAPPED);
        return false;
    }

    /** Whether the screen on top is Wynntils' wrap of the log bound as {@code boundId}. */
    private static boolean isWrapped(int boundId) {
        return McUtils.screen() instanceof WrappedScreen wrapped
                && wrapped.getWrappedScreenInfo().containerId() == boundId;
    }

    /**
     * Closes the log bound as {@code boundId} if it is still the screen on top, and returns
     * whether it was. Wynntils' wrap of the log goes through {@code clientSideCloseContainer()}:
     * the wrapped-screen teardown sends the one close packet, and the call also resets
     * {@code containerMenu}. The unwrapped log goes through {@code closeContainer()}, which sends
     * the close itself.
     */
    private static boolean closeLogIfOnTop(int boundId) {
        LocalPlayer player = McUtils.player();
        if (player == null) return false;
        if (isWrapped(boundId)) {
            player.clientSideCloseContainer();
            return true;
        }
        if (ContainerScreens.currentWithId(boundId) != null) {
            player.closeContainer();
            return true;
        }
        return false;
    }
}
