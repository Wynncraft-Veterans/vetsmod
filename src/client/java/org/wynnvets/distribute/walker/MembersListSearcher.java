package org.wynnvets.distribute.walker;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Managers;
import com.wynntils.core.text.StyledText;
import com.wynntils.mc.event.ContainerSetContentEvent;
import com.wynntils.mc.event.ContainerSetSlotEvent;
import com.wynntils.mc.event.MenuEvent;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.distribute.MembersGui;
import org.wynnvets.distribute.utils.NameResolver;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.ContainerScreens;

/**
 * Paginates the in-game {@code "<guild>: Members"} GUI looking for a
 * specific player and invokes a callback when their player-head slot is
 * located.
 *
 * <p>Modelled on the auto-search in Wynntils'
 * {@code ContainerSearchFeature}: it rescans on the same
 * {@code ContainerSetContentEvent.Post} / {@code ContainerSetSlotEvent.Post}
 * events, and, like Wynntils' {@code ContainerModel}, binds the container
 * id by title on {@code MenuOpenedEvent.Pre} &mdash; though unlike
 * {@code ContainerModel} it keeps its first binding (see
 * {@link #onMenuOpenPre}). The rescan waits a
 * {@link #SCAN_DELAY_TICKS}-tick scheduler delay so straggling slot
 * updates can land first &mdash; see that constant for why the value is
 * 2 and not 1. Slot indices and bounds match
 * {@code GuildMemberListContainer}.</p>
 *
 * <h2>Bidirectional pagination</h2>
 * <p>Forward search is the default. When the forward sweep runs out of
 * {@code Next Page} buttons without a hit, the searcher transparently
 * switches to backward (clicking {@code Previous Page}) so a target on
 * an earlier page than where the search started is still reachable.
 * This is what lets {@code DistributionQueue} visit multiple recipients
 * in one menu session without resetting to page 1 between them.</p>
 *
 * <h2>Re-arming while the menu is open</h2>
 * <p>An {@link #armSearch} made while no Members menu is open waits for
 * {@code MenuOpenedEvent.Pre} to bind the container id. One made while a
 * Members menu is open binds it and schedules a scan straight away, so
 * the pick starts searching from whatever page that menu is on &mdash;
 * normally every later pick of a multi-recipient run, since the previous
 * pick leaves the menu open, and {@code @objectives}' first pick, since
 * its walk ends in the menu.</p>
 *
 * <h2>Name matching</h2>
 * <p>Each player-head's hover name is the member's tile name: wapi's
 * {@code legacyName} ({@code /v3/guild/<name>} &rarr;
 * {@code members.<rank>.<currentName>.legacyName}) where the member has
 * one, otherwise the {@code currentName} key (see {@link NameResolver}).
 * Matching is case-insensitive against the §-stripped hover name. Multiple
 * acceptable names can be armed via {@link #addAlternative(String)}, which
 * {@code DistributeCommands}' literal-name path uses to add the resolved
 * tile name right after arming, when it differs from the literal
 * input.</p>
 */
public final class MembersListSearcher {

    /** Hard cap on page clicks per pass (the retry starts a new pass and
     *  resets the counter) to bound runaway loops. Generous enough to
     *  cover a full forward run followed by a full backward run on a
     *  max-size guild. */
    private static final int MAX_PAGES = 60;

    /** Cap on rebind attempts when the bound container id has gone stale
     *  mid-search. See {@link #scanAndPaginate()}. */
    private static final int MAX_REBIND_ATTEMPTS = 3;

    /** Debounce delay before {@link #scanAndPaginate()} runs. Each fresh
     *  event bumps {@link #scanToken}, and only the latest-scheduled task
     *  survives the token check — so the scan happens this many ticks
     *  after the <em>last</em> slot update, not the first. 2 ticks gives
     *  Wynncraft enough breathing room to finish streaming a page's
     *  {@code SetSlot} packets when they cross a tick boundary, which is
     *  the root cause of the false-negative scans that triggered this
     *  whole mechanism (a partially-updated page being scanned and the
     *  target player missed). */
    private static final int SCAN_DELAY_TICKS = 2;

    /** After a forward+backward sweep exhausts without a match, wait this
     *  many ticks then start a fresh sweep from the current page (which
     *  is page 1 after a successful backward exhaustion). On a multi-page
     *  guild the retry re-clicks NEXT to navigate forward, which forces the
     *  server to re-stream {@code SetSlot} updates for each page — fresh
     *  data that sidesteps any stale-update race the first sweep may have
     *  hit. On a single page it only rescans after the delay. */
    private static final int RETRY_DELAY_TICKS = 10;

    /** Number of full sweep retries before giving up. One retry catches
     *  the common stale-data false negative; more would just delay the
     *  not-found verdict without meaningful recovery. */
    private static final int MAX_RETRY_ATTEMPTS = 1;

    /** Hard upper bound on a single search's wall-clock time. A 4-page
     *  guild traversed forward+backward+retry should comfortably finish
     *  in under 10s; 15s gives margin without the user noticing if a
     *  search completes normally. If we hit this, something is genuinely
     *  stuck (dropped click, server stopped responding, etc.) and the
     *  watchdog force-advances the chain so distribution can continue.
     *  Counted from {@link #armSearch}, so when a search is armed before
     *  the menu opens (the literal head, and the first pick of
     *  {@code @random} and {@code @graids}) it also covers opening the
     *  menu. */
    private static final int WATCHDOG_TICKS = 300;

    private enum Direction {
        FORWARD,
        BACKWARD
    }

    private static final MembersListSearcher INSTANCE = new MembersListSearcher();

    /** Human-readable label for chat messages; non-null iff armed. */
    private static volatile String displayQuery = null;

    /** Lowercased set of acceptable names; {@link #addAlternative} can add
     *  to it after arming. */
    private static final Set<String> queryLower = ConcurrentHashMap.newKeySet();

    /** Container id of the Members menu we're currently driving. */
    private static volatile int membersContainerId = -1;

    private static volatile int pagesClicked = 0;

    /** Monotonic token bumped on every {@link #scheduleScan()} and every
     *  {@link #stop()}. Scheduled scan tasks capture the token at
     *  schedule time and bail at fire time if it's been superseded —
     *  which gives us tail-debouncing (scan happens
     *  {@link #SCAN_DELAY_TICKS} after the <em>last</em> event, not the
     *  first) plus automatic cancellation of pending scans when a
     *  search ends. */
    private static volatile int scanToken = 0;

    private static volatile Direction direction = Direction.FORWARD;

    /** Number of times {@link #scanAndPaginate()} has rebound to a
     *  refreshed container id during the current search. Bounded by
     *  {@link #MAX_REBIND_ATTEMPTS} so a thrashing refresh loop can't
     *  pin the searcher forever. Reset per {@link #armSearch} / {@link #stop}. */
    private static volatile int rebindAttempts = 0;

    /** Number of full forward+backward sweep retries used so far on the
     *  current search. Bounded by {@link #MAX_RETRY_ATTEMPTS}. */
    private static volatile int retryAttempts = 0;

    /** Monotonic token bumped on every {@link #armSearch} and every
     *  {@link #stop()}; the per-search watchdog task captures it at arm
     *  time and bails at fire time if it has moved on. So once a search
     *  ends by any path &mdash; a match, a not-found exit, or
     *  {@link #onMenuClose} &mdash; its watchdog is disarmed without
     *  needing to know, which is why a server-sent mid-search close is
     *  not covered by the watchdog. */
    private static volatile int watchdogToken = 0;

    /** Callback invoked when the armed name is located. */
    private static volatile SlotMatchHandler matchHandler = null;

    /** Optional callback run through {@link #invokeNotFound()} when the
     *  search gives up: both directions and the retry exhausted, the page
     *  cap reached, no screen with the bound id when a scan runs and no
     *  rebind possible, or the watchdog firing. {@link #onMenuClose} ends
     *  a search without it. */
    private static volatile Runnable notFoundHandler = null;

    /** Callback fired by {@link #scanAndPaginate()} when one of the armed
     *  names' player-head slot is located on the current page. The handler
     *  receives only the slot index, so the callee reads the container
     *  from the live screen &mdash; and must re-read it for anything it does
     *  later, because a send's refresh can replace the container id. (The
     *  searcher has already {@code stop()}ped by then.) */
    @FunctionalInterface
    public interface SlotMatchHandler {
        void onMatch(int slot);
    }

    private MembersListSearcher() {}

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        VetsLogger.debug("Registered MembersListSearcher on Wynntils event bus");
    }

    /**
     * Arms the searcher to scan the next {@code "<guild>: Members"} menu
     * for {@code name} and invoke {@code handler} once it's located.
     * Overwrites any previous armed query, discarding its handlers without
     * running them and superseding its watchdog
     * ({@code distribute-concurrent-runs-clobber-shared-state}).
     *
     * @param handler invoked on match; must be non-null. Passing null
     *                will NPE when a match is found.
     */
    public static void armSearch(String name, SlotMatchHandler handler) {
        armSearch(name, handler, null);
    }

    /**
     * Variant with a not-found callback. Used by the multi-recipient send
     * loop ({@code DistributionQueue}) so the queue can advance to the next
     * recipient when one cannot be located on any page.
     */
    public static void armSearch(String name, SlotMatchHandler handler, Runnable onNotFound) {
        displayQuery = name;
        queryLower.clear();
        if (name != null && !name.isEmpty()) {
            queryLower.add(name.toLowerCase(Locale.ROOT));
        }
        matchHandler = handler;
        notFoundHandler = onNotFound;
        pagesClicked = 0;
        rebindAttempts = 0;
        retryAttempts = 0;
        direction = Direction.FORWARD;

        // Arm the per-search watchdog. The token capture pattern means a
        // newer armSearch (or stop()) automatically invalidates this
        // task — no explicit cancellation needed. The watchdog is the
        // backstop for a stall where no further event arrives, before
        // binding (the Members menu never opens) or after (a dropped
        // pagination click, or the menu going away without a
        // MenuClosedEvent for our id — the player's own Esc posts none,
        // see onMenuClose). It is not a reliable backstop for a task that
        // throws: Wynntils' TickSchedulerManager does not catch, so a
        // throwing task is not removed and re-runs every tick until a run
        // returns normally, and whether this watchdog still counts down
        // meanwhile depends on map order.
        final int myWatchdog = ++watchdogToken;
        Managers.TickScheduler.scheduleLater(
                () -> {
                    if (myWatchdog != watchdogToken) return;
                    if (displayQuery == null) return;
                    VetsLogger.debug(
                            "MembersListSearcher: watchdog timeout for [{}] after {} ticks",
                            displayQuery,
                            WATCHDOG_TICKS);
                    ChatUtils.sendLocalMessage(
                            Component.literal(
                                            "Search for "
                                                    + displayQuery
                                                    + " timed out — advancing.")
                                    .withStyle(ChatFormatting.YELLOW));
                    invokeNotFound();
                },
                WATCHDOG_TICKS);

        // Re-arm fast-path: when the Members menu is already open (multi-user
        // flow), bind to its container id and kick off a scan now. Otherwise
        // leave membersContainerId at -1 and wait for the next
        // MenuOpenedEvent.Pre to bind.
        AbstractContainerScreen<?> open = MembersGui.currentByTitle();
        if (open != null) {
            membersContainerId = open.getMenu().containerId;
            scheduleScan();
        } else {
            membersContainerId = -1;
        }
    }

    /**
     * Adds an additional acceptable name to the current armed search.
     * Used by {@code DistributeCommands}' literal-name path, on the tick
     * thread right after arming, to add the resolved tile name alongside
     * the literal input without invalidating the literal-input match.
     * No-op if the searcher is not armed.
     */
    public static void addAlternative(String name) {
        if (displayQuery == null) return;
        if (name == null || name.isEmpty()) return;
        if (queryLower.add(name.toLowerCase(Locale.ROOT))) {
            VetsLogger.debug(
                    "MembersListSearcher: added alternative [{}] for [{}]", name, displayQuery);
        }
    }

    private static void stop() {
        displayQuery = null;
        queryLower.clear();
        membersContainerId = -1;
        pagesClicked = 0;
        rebindAttempts = 0;
        retryAttempts = 0;
        direction = Direction.FORWARD;
        matchHandler = null;
        notFoundHandler = null;
        // Bump tokens so any pending scan or watchdog task fires into
        // the void instead of acting on the cleared state.
        scanToken++;
        watchdogToken++;
    }

    @SubscribeEvent
    public void onMenuOpenPre(MenuEvent.MenuOpenedEvent.Pre event) {
        if (displayQuery == null) return;
        // Already bound (by the fast path, an earlier open, or a rebind):
        // ignore later opens. A replacement under a new id is picked up
        // only if a scan runs afterwards, through scanAndPaginate's
        // rebind; otherwise it waits until something else ends it,
        // normally the watchdog.
        if (membersContainerId != -1) return;
        StyledText title = StyledText.fromComponent(event.getTitle());
        if (!title.matches(MembersGui.TITLE_PATTERN)) return;
        // Don't cancel — the menu must render so the player can see results
        // and so getMenu().getItems() reflects what the server sends.
        membersContainerId = event.getContainerId();
        VetsLogger.debug(
                "MembersListSearcher: armed for [{}] in menu id={}",
                displayQuery,
                membersContainerId);
    }

    @SubscribeEvent
    public void onMenuClose(MenuEvent.MenuClosedEvent event) {
        if (displayQuery == null) return;
        if (event.getContainerId() != membersContainerId) return;
        // A clientbound close for our bound id — Wynntils posts
        // MenuClosedEvent only from its handleContainerClose hook, so this
        // is the server closing the menu. Abandon quietly. A client-side
        // close (the player's Esc) sends only a serverbound close and posts
        // nothing here. What ends that search then depends first on what is
        // already pending: a scheduled scan (or the retry hop) finds no
        // screen and ends it through scanAndPaginate's lost-menu branch.
        // Otherwise it depends on what the server sends for the menu
        // afterwards, and a search that stalls is ended by the watchdog.
        VetsLogger.debug(
                "MembersListSearcher: menu closed mid-search, abandoning [{}]", displayQuery);
        stop();
    }

    @SubscribeEvent
    public void onSetContent(ContainerSetContentEvent.Post event) {
        if (displayQuery == null) return;
        if (event.getContainerId() != membersContainerId) return;
        scheduleScan();
    }

    /**
     * Wynncraft updates paginated views in-place by sending {@code SetSlot}
     * packets for each changed slot rather than a fresh {@code SetContent}.
     * We trigger a scan on updates to either pagination button (signals a
     * new page is loading) <em>and</em> on any slot inside the player-tile
     * bounds (signals a player slot has actually been updated). The
     * original implementation triggered only on the pagination buttons,
     * which races when Wynncraft fires the button update before all the
     * page's player-slot packets — the scan ran on a half-updated page
     * and silently missed the target. {@link #scheduleScan()}'s debounce
     * makes the scan fire only once no triggering packet has arrived for
     * about {@link #SCAN_DELAY_TICKS}; a stream that pauses longer can still be
     * scanned half-updated, which a later pass over that page (the
     * backward sweep or the retry) may catch.
     */
    @SubscribeEvent
    public void onSetSlot(ContainerSetSlotEvent.Post event) {
        if (displayQuery == null) return;
        if (event.getContainerId() != membersContainerId) return;
        int slot = event.getSlot();
        if (slot == MembersGui.NEXT_PAGE_SLOT
                || slot == MembersGui.PREVIOUS_PAGE_SLOT
                || MembersGui.isTileSlot(slot)) {
            scheduleScan();
        }
    }

    /**
     * Schedule a scan-and-paginate run. Tail-debounced via {@link #scanToken}:
     * every call bumps the token and posts its own task, and only the
     * <em>last</em>-posted task survives the token check at fire time.
     * The net effect is that {@link #scanAndPaginate()} runs
     * {@link #SCAN_DELAY_TICKS} ticks after the most recent triggering
     * event — so a burst of {@code SetSlot} packets that spans a tick or
     * two collapses into one scan, run once the burst has paused for that
     * long.
     */
    private static void scheduleScan() {
        final int myToken = ++scanToken;
        Managers.TickScheduler.scheduleLater(
                () -> {
                    if (myToken != scanToken) return;
                    if (displayQuery == null) return;
                    scanAndPaginate();
                },
                SCAN_DELAY_TICKS);
    }

    private static void scanAndPaginate() {
        if (displayQuery == null) return;

        AbstractContainerScreen<?> screen = ContainerScreens.currentWithId(membersContainerId);
        if (screen == null) {
            // No screen carries our bound id. Ours was dismissed
            // client-side (the player's Esc posts no MenuClosedEvent), or
            // replaced, e.g. by a Members reopen under a new id with no
            // close for ours (a clientbound close for our id that arrived
            // first would have stopped the search in onMenuClose). In a
            // multi-pick chain the next pick's armSearch normally runs a
            // few ticks after the previous send's refresh; if Wynncraft
            // replaces the menu again, without a close for ours, before the
            // debounced scan runs, our bound id is stale by then. Try to
            // rebind to whichever
            // Members menu is currently open before giving up — but cap
            // rebinds so a thrashing refresh loop can't pin the searcher.
            AbstractContainerScreen<?> reopened = MembersGui.currentByTitle();
            if (reopened != null && rebindAttempts < MAX_REBIND_ATTEMPTS) {
                rebindAttempts++;
                int newId = reopened.getMenu().containerId;
                VetsLogger.debug(
                        "MembersListSearcher: rebinding from container {} to {} "
                                + "(attempt {}/{}) for [{}]",
                        membersContainerId,
                        newId,
                        rebindAttempts,
                        MAX_REBIND_ATTEMPTS,
                        displayQuery);
                membersContainerId = newId;
                scheduleScan();
                return;
            }
            // No Members menu open at all, or we've exhausted rebinds.
            // Invoke not-found so the chained caller (DistributionQueue)
            // advances its queue rather than silently stalling with the
            // menu open.
            VetsLogger.debug(
                    "MembersListSearcher: lost Members menu mid-search for [{}], advancing",
                    displayQuery);
            invokeNotFound();
            return;
        }

        List<ItemStack> items = screen.getMenu().getItems();

        if (scanVisiblePageForMatch(items)) return;

        if (pagesClicked >= MAX_PAGES) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Could not find " + displayQuery + " (reached page limit).")
                            .withStyle(ChatFormatting.YELLOW));
            invokeNotFound();
            return;
        }

        if (!advancePagination(items)) {
            if (retryAttempts < MAX_RETRY_ATTEMPTS) {
                retryAttempts++;
                VetsLogger.debug(
                        "MembersListSearcher: sweep exhausted for [{}], "
                                + "retrying from page 1 in {} ticks (attempt {}/{})",
                        displayQuery,
                        RETRY_DELAY_TICKS,
                        retryAttempts,
                        MAX_RETRY_ATTEMPTS);
                // After backward exhaustion we're sitting on page 1.
                // Reset sweep state and rescan; the next advancePagination
                // will click NEXT, forcing the server to re-stream
                // SetSlot updates for page 2 onwards — fresh data that
                // sidesteps the stale-update race that caused the first
                // miss. pagesClicked resets too so the retry isn't
                // throttled by the MAX_PAGES cap.
                pagesClicked = 0;
                direction = Direction.FORWARD;
                Managers.TickScheduler.scheduleLater(
                        MembersListSearcher::scheduleScan, RETRY_DELAY_TICKS);
                return;
            }
            stopNotFound();
        }
    }

    /**
     * Scans the {@linkplain MembersGui#isTileSlot(int) player-head tiles}
     * of the current page for any of the armed names. On a hit, invokes the match handler (and clears
     * state via {@link #stop()}) and returns {@code true}. Returns
     * {@code false} if no slot on this page matched.
     */
    private static boolean scanVisiblePageForMatch(List<ItemStack> items) {
        for (int slot = 0; slot < items.size(); slot++) {
            if (!MembersGui.isTileSlot(slot)) continue;

            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) continue;

            String plain =
                    StyledText.fromComponent(stack.getHoverName()).getStringWithoutFormatting();
            if (queryLower.contains(plain.toLowerCase(Locale.ROOT))) {
                VetsLogger.debug("MembersListSearcher: matched [{}] at slot {}", plain, slot);
                SlotMatchHandler handler = matchHandler;
                stop();
                handler.onMatch(slot);
                return true;
            }
        }
        return false;
    }

    /**
     * Issues one pagination click in the current direction, or
     * transparently flips forward&rarr;backward when the forward sweep
     * runs out. Returns {@code true} if a click was issued (with
     * {@code pagesClicked} incremented); {@code false} if no further
     * pages are reachable in either direction &mdash; the caller then
     * retries the sweep, and surfaces "not found" once the retry is
     * spent.
     */
    private static boolean advancePagination(List<ItemStack> items) {
        if (direction == Direction.FORWARD) {
            if (MembersGui.clickPaginationIfPresent(
                    items,
                    MembersGui.NEXT_PAGE_SLOT,
                    MembersGui.NEXT_PAGE_PATTERN,
                    membersContainerId)) {
                pagesClicked++;
                return true;
            }
            // Forward exhausted. Switch to backward to cover any pages
            // that came before the page where the search started (the
            // multi-user case where we re-arm after a previous match).
            direction = Direction.BACKWARD;
            if (MembersGui.clickPaginationIfPresent(
                    items,
                    MembersGui.PREVIOUS_PAGE_SLOT,
                    MembersGui.PREVIOUS_PAGE_PATTERN,
                    membersContainerId)) {
                pagesClicked++;
                return true;
            }
            // No previous either — single-page guild, name isn't in it.
            return false;
        }
        if (MembersGui.clickPaginationIfPresent(
                items,
                MembersGui.PREVIOUS_PAGE_SLOT,
                MembersGui.PREVIOUS_PAGE_PATTERN,
                membersContainerId)) {
            pagesClicked++;
            return true;
        }
        // At page 1 going backward: every page has been visited.
        return false;
    }

    private static void stopNotFound() {
        ChatUtils.sendLocalMessage(
                Component.literal("Could not find " + displayQuery + " in members list.")
                        .withStyle(ChatFormatting.YELLOW));
        invokeNotFound();
    }

    private static void invokeNotFound() {
        Runnable handler = notFoundHandler;
        stop();
        if (handler != null) handler.run();
    }
}
