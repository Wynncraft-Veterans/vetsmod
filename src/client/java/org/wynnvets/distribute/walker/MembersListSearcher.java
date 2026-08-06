package org.wynnvets.distribute.walker;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Managers;
import com.wynntils.core.text.StyledText;
import com.wynntils.mc.event.ContainerSetContentEvent;
import com.wynntils.mc.event.ContainerSetSlotEvent;
import com.wynntils.mc.event.MenuEvent;
import com.wynntils.utils.mc.McUtils;
import com.wynntils.utils.wynn.ContainerUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.distribute.distributor.RandomDistributor;
import org.wynnvets.distribute.utils.NameResolver;
import org.wynnvets.logging.VetsLogger;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Paginates the in-game {@code "<guild>: Members"} GUI looking for a
 * specific player and invokes a callback when their player-head slot is
 * located.
 *
 * <p>Mirrors the page-turn half of Wynntils'
 * {@code ContainerSearchFeature}: subscribes to the same
 * {@code MenuOpenedEvent.Pre} / {@code ContainerSetContentEvent.Post} /
 * {@code ContainerSetSlotEvent.Post} triple Wynntils uses, with a
 * {@link #SCAN_DELAY_TICKS}-tick scheduler delay so straggling slot
 * updates land before the rescan &mdash; see that constant for why the
 * value is 2 and not 1. Slot indices and bounds match
 * {@code GuildMemberListContainer}.</p>
 *
 * <h2>Bidirectional pagination</h2>
 * <p>Forward search is the default. When the forward sweep runs out of
 * {@code Next Page} buttons without a hit, the searcher transparently
 * switches to backward (clicking {@code Previous Page}) so a target on
 * an earlier page than where the search started is still reachable.
 * This is what makes {@link RandomDistributor} able to visit multiple
 * picks in one menu session without resetting to page 1 between them.</p>
 *
 * <h2>Re-arming while the menu is open</h2>
 * <p>The first {@link #armSearch} of a session waits for
 * {@code MenuOpenedEvent.Pre} to bind the container id. Subsequent
 * re-arms (multi-user flows) detect that the Members menu is already
 * open and bind + schedule a scan immediately so the next pick starts
 * searching from wherever the previous one left off.</p>
 *
 * <h2>Name matching</h2>
 * <p>Each player-head's hover name is the player's {@code legacyName} as
 * served by {@code wapi /v3/guild/<name>.members.<rank>.<currentName>.legacyName}.
 * Matching is case-insensitive against the §-stripped hover name. Multiple
 * acceptable names can be armed via {@link #addAlternative(String)}, used
 * by {@link NameResolver} to add the legacy form once a current Mojang
 * username has been resolved.</p>
 */
public final class MembersListSearcher {

    /** Mirrors {@code GuildMemberListContainer.TITLE_PATTERN}. */
    private static final Pattern MEMBERS_TITLE_PATTERN = Pattern.compile(".+: Members");
    /** Mirrors {@code GuildMemberListContainer.NEXT_PAGE_PATTERN}. */
    private static final Pattern NEXT_PAGE_PATTERN = Pattern.compile("§a§lNext Page");
    /** Mirrors {@code GuildMemberListContainer.PREVIOUS_PAGE_PATTERN}. */
    private static final Pattern PREVIOUS_PAGE_PATTERN = Pattern.compile("§a§lPrevious Page");
    /** Mirrors {@code GuildMemberListContainer.getNextItemSlot()}. */
    private static final int NEXT_PAGE_SLOT = 28;
    /** Mirrors {@code GuildMemberListContainer.getPreviousItemSlot()}. */
    private static final int PREVIOUS_PAGE_SLOT = 10;

    // ContainerBounds(0, 2, 4, 8) on GuildMemberListContainer — searchable
    // area is rows 0–4 × cols 2–8 of the 9-wide grid.
    private static final int BOUNDS_START_ROW = 0;
    private static final int BOUNDS_END_ROW = 4;
    private static final int BOUNDS_START_COL = 2;
    private static final int BOUNDS_END_COL = 8;

    /** Hard cap on page clicks per search to bound runaway loops.
     *  Generous enough to cover a full forward sweep followed by a full
     *  backward sweep on a max-size guild. */
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
     *  is page 1 after a successful backward exhaustion). The retry
     *  re-clicks NEXT to navigate forward, which forces the server to
     *  re-stream {@code SetSlot} updates for each page — fresh data that
     *  sidesteps any stale-update race the first sweep may have hit. */
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
     *  watchdog force-advances the chain so distribution can continue. */
    private static final int WATCHDOG_TICKS = 300;

    private enum Direction { FORWARD, BACKWARD }

    private static final MembersListSearcher INSTANCE = new MembersListSearcher();

    /** Human-readable label for chat messages; non-null iff armed. */
    private static volatile String displayQuery = null;
    /** Lowercased set of acceptable names. Concurrent because async
     *  resolvers ({@link NameResolver}) may add alternatives after arming. */
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
    /** Monotonic token bumped on every {@link #armSearch}; the per-search
     *  watchdog task captures it at arm time and bails at fire time if
     *  a newer search has replaced it. Lets the search complete normally
     *  (via match or stopNotFound) without needing the watchdog to know. */
    private static volatile int watchdogToken = 0;
    /** Callback invoked when the armed name is located. */
    private static volatile SlotMatchHandler matchHandler = null;
    /** Optional callback invoked when the search exhausts both directions
     *  without finding the name. */
    private static volatile Runnable notFoundHandler = null;

    /** Callback fired by {@link #scanAndPaginate()} when one of the armed
     *  names' player-head slot is located on the current page. The handler
     *  receives only the slot index; everything else (container id, items)
     *  is stale by the time the handler fires and should be re-read from
     *  the current screen by the callee. */
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
     * Overwrites any previous armed query.
     *
     * @param handler invoked on match; must be non-null. Passing null
     *                will NPE when a match is found.
     */
    public static void armSearch(String name, SlotMatchHandler handler) {
        armSearch(name, handler, null);
    }

    /**
     * Variant with a not-found callback. Used by multi-user flows
     * ({@link RandomDistributor}) so the queue can advance to the next
     * pick when a member can't be located on any page.
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
        // backstop for any silent-stall mechanism we haven't otherwise
        // covered: dropped pagination clicks, server-side menu close
        // without an event, exceptions in the scheduler, etc.
        final int myWatchdog = ++watchdogToken;
        Managers.TickScheduler.scheduleLater(() -> {
            if (myWatchdog != watchdogToken) return;
            if (displayQuery == null) return;
            VetsLogger.debug("MembersListSearcher: watchdog timeout for [{}] after {} ticks",
                    displayQuery, WATCHDOG_TICKS);
            ChatUtils.sendLocalMessage(
                    Component.literal("Search for " + displayQuery
                                    + " timed out — advancing.")
                            .withStyle(ChatFormatting.YELLOW));
            invokeNotFound();
        }, WATCHDOG_TICKS);

        // Re-arm fast-path: when the Members menu is already open (multi-user
        // flow), bind to its container id and kick off a scan now. Otherwise
        // leave membersContainerId at -1 and wait for the next
        // MenuOpenedEvent.Pre to bind.
        if (McUtils.mc().screen instanceof AbstractContainerScreen<?> screen
                && StyledText.fromComponent(screen.getTitle()).matches(MEMBERS_TITLE_PATTERN)) {
            membersContainerId = screen.getMenu().containerId;
            scheduleScan();
        } else {
            membersContainerId = -1;
        }
    }

    /**
     * Adds an additional acceptable name to the current armed search.
     * Used by async resolvers to register a current&rarr;legacy alias
     * without invalidating the literal-input match. No-op if the
     * searcher is not armed.
     */
    public static void addAlternative(String name) {
        if (displayQuery == null) return;
        if (name == null || name.isEmpty()) return;
        if (queryLower.add(name.toLowerCase(Locale.ROOT))) {
            VetsLogger.debug("MembersListSearcher: added alternative [{}] for [{}]",
                    name, displayQuery);
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
        // Already bound by the re-arm fast-path; ignore subsequent opens.
        if (membersContainerId != -1) return;
        StyledText title = StyledText.fromComponent(event.getTitle());
        if (!title.matches(MEMBERS_TITLE_PATTERN)) return;
        // Don't cancel — the menu must render so the player can see results
        // and so getMenu().getItems() reflects what the server sends.
        membersContainerId = event.getContainerId();
        VetsLogger.debug("MembersListSearcher: armed for [{}] in menu id={}",
                displayQuery, membersContainerId);
    }

    @SubscribeEvent
    public void onMenuClose(MenuEvent.MenuClosedEvent event) {
        if (displayQuery == null) return;
        if (event.getContainerId() != membersContainerId) return;
        // User (or server) closed our menu mid-search — abandon quietly.
        VetsLogger.debug("MembersListSearcher: menu closed mid-search, abandoning [{}]",
                displayQuery);
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
     * guarantees the scan only fires after the slot stream goes quiet.
     */
    @SubscribeEvent
    public void onSetSlot(ContainerSetSlotEvent.Post event) {
        if (displayQuery == null) return;
        if (event.getContainerId() != membersContainerId) return;
        int slot = event.getSlot();
        if (slot == NEXT_PAGE_SLOT || slot == PREVIOUS_PAGE_SLOT
                || isPlayerBoundsSlot(slot)) {
            scheduleScan();
        }
    }

    /** Returns true if {@code slot} falls within the bounded scan area
     *  ({@code [BOUNDS_START_ROW, BOUNDS_END_ROW] × [BOUNDS_START_COL,
     *  BOUNDS_END_COL]}). Matches the slots that
     *  {@link #scanVisiblePageForMatch} actually inspects. */
    private static boolean isPlayerBoundsSlot(int slot) {
        int row = slot / 9;
        int col = slot % 9;
        return row >= BOUNDS_START_ROW && row <= BOUNDS_END_ROW
                && col >= BOUNDS_START_COL && col <= BOUNDS_END_COL;
    }

    /**
     * Schedule a scan-and-paginate run. Tail-debounced via {@link #scanToken}:
     * every call bumps the token and posts its own task, and only the
     * <em>last</em>-posted task survives the token check at fire time.
     * The net effect is that {@link #scanAndPaginate()} runs
     * {@link #SCAN_DELAY_TICKS} ticks after the most recent triggering
     * event — so a burst of {@code SetSlot} packets that spans a tick or
     * two collapses into one scan against the fully-settled page state.
     */
    private static void scheduleScan() {
        final int myToken = ++scanToken;
        Managers.TickScheduler.scheduleLater(() -> {
            if (myToken != scanToken) return;
            if (displayQuery == null) return;
            scanAndPaginate();
        }, SCAN_DELAY_TICKS);
    }

    private static void scanAndPaginate() {
        if (displayQuery == null) return;

        AbstractContainerScreen<?> screen = currentMembersScreen();
        if (screen == null) {
            // Container id mismatch — typically a server-side close+reopen
            // of the Members menu between scheduleScan and now. In a
            // multi-pick chain the next pick's armSearch fires immediately
            // after the previous press's refresh; if Wynncraft happens to
            // refresh the menu again inside that 1-tick gap, our bound id
            // is stale before the scan runs. Try to rebind to whichever
            // Members menu is currently open before giving up — but cap
            // rebinds so a thrashing refresh loop can't pin the searcher.
            AbstractContainerScreen<?> reopened = openMembersScreen();
            if (reopened != null && rebindAttempts < MAX_REBIND_ATTEMPTS) {
                rebindAttempts++;
                int newId = reopened.getMenu().containerId;
                VetsLogger.debug("MembersListSearcher: rebinding from container {} to {} "
                        + "(attempt {}/{}) for [{}]",
                        membersContainerId, newId, rebindAttempts, MAX_REBIND_ATTEMPTS,
                        displayQuery);
                membersContainerId = newId;
                scheduleScan();
                return;
            }
            // No Members menu open at all, or we've exhausted rebinds.
            // Invoke not-found so chained callers (RandomDistributor,
            // GraidsDistributor, ObjectivesDistributor) advance their
            // queue rather than silently stalling with the menu open.
            VetsLogger.debug("MembersListSearcher: lost Members menu mid-search for [{}], advancing",
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
                VetsLogger.debug("MembersListSearcher: sweep exhausted for [{}], "
                                + "retrying from page 1 in {} ticks (attempt {}/{})",
                        displayQuery, RETRY_DELAY_TICKS, retryAttempts, MAX_RETRY_ATTEMPTS);
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
     * Scans the bounded slot area of the current page for any of the
     * armed names. On a hit, invokes the match handler (and clears
     * state via {@link #stop()}) and returns {@code true}. Returns
     * {@code false} if no slot on this page matched.
     */
    private static boolean scanVisiblePageForMatch(List<ItemStack> items) {
        for (int slot = 0; slot < items.size(); slot++) {
            int row = slot / 9;
            int col = slot % 9;
            if (row < BOUNDS_START_ROW || row > BOUNDS_END_ROW) continue;
            if (col < BOUNDS_START_COL || col > BOUNDS_END_COL) continue;

            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) continue;

            String plain = StyledText.fromComponent(stack.getHoverName())
                    .getStringWithoutFormatting();
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
     * pages are reachable in either direction &mdash; caller should
     * surface a "not found" outcome.
     */
    private static boolean advancePagination(List<ItemStack> items) {
        if (direction == Direction.FORWARD) {
            if (clickPaginationIfPresent(items, NEXT_PAGE_SLOT, NEXT_PAGE_PATTERN)) {
                pagesClicked++;
                return true;
            }
            // Forward exhausted. Switch to backward to cover any pages
            // that came before the page where the search started (the
            // multi-user case where we re-arm after a previous match).
            direction = Direction.BACKWARD;
            if (clickPaginationIfPresent(items, PREVIOUS_PAGE_SLOT, PREVIOUS_PAGE_PATTERN)) {
                pagesClicked++;
                return true;
            }
            // No previous either — single-page guild, name isn't in it.
            return false;
        }
        if (clickPaginationIfPresent(items, PREVIOUS_PAGE_SLOT, PREVIOUS_PAGE_PATTERN)) {
            pagesClicked++;
            return true;
        }
        // At page 1 going backward: every page has been visited.
        return false;
    }

    private static boolean clickPaginationIfPresent(
            List<ItemStack> items, int slot, Pattern pattern) {
        if (slot >= items.size()) return false;
        StyledText name = StyledText.fromComponent(items.get(slot).getHoverName());
        if (!name.matches(pattern)) return false;
        ContainerUtils.clickOnSlot(slot, membersContainerId, GLFW.GLFW_MOUSE_BUTTON_LEFT, items);
        return true;
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

    private static AbstractContainerScreen<?> currentMembersScreen() {
        if (McUtils.mc().screen instanceof AbstractContainerScreen<?> screen
                && screen.getMenu().containerId == membersContainerId) {
            return screen;
        }
        return null;
    }

    /**
     * Returns the currently-open container screen iff its title matches
     * the Members pattern, <em>regardless</em> of its container id.
     * Companion to {@link #currentMembersScreen()} which only accepts the
     * already-bound id — used by {@link #scanAndPaginate()} to rebind
     * after a server-side close+reopen refresh.
     */
    private static AbstractContainerScreen<?> openMembersScreen() {
        if (McUtils.mc().screen instanceof AbstractContainerScreen<?> screen
                && StyledText.fromComponent(screen.getTitle()).matches(MEMBERS_TITLE_PATTERN)) {
            return screen;
        }
        return null;
    }
}
