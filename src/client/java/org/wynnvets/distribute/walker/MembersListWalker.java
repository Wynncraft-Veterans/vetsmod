package org.wynnvets.distribute.walker;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Managers;
import com.wynntils.core.text.StyledText;
import com.wynntils.mc.event.ContainerSetContentEvent;
import com.wynntils.mc.event.ContainerSetSlotEvent;
import com.wynntils.mc.event.MenuEvent;
import com.wynntils.mc.event.ScreenClosedEvent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.distribute.DistributeRuns;
import org.wynnvets.distribute.MembersGui;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.ContainerScreens;

/**
 * Walks every page of the in-game {@code "<guild>: Members"} GUI and
 * collects an entry for each player-head it sees. Used by selectors
 * whose recipient list depends on per-member tile data &mdash; e.g.
 * {@link org.wynnvets.distribute.distributor.ObjectivesDistributor ObjectivesDistributor}, which
 * reads the {@code Guild Objective:} lore lines to decide who qualifies.
 *
 * <p>Built on the same event triple as {@link MembersListSearcher}
 * (MenuOpen / SetContent / SetSlot), plus the same screen-close nudge
 * ({@link #onScreenClosed}), and the same scan debounce: {@link #onSetSlot}
 * triggers on the Next Page slot and on every in-bounds tile, and the scan
 * runs {@link #SCAN_DELAY_TICKS} after the <em>last</em> trigger, so a
 * page whose {@code SetSlot} packets cross a tick boundary is scanned once
 * it has finished arriving. Instead of stopping on a match it
 * forward-paginates exhaustively,
 * collecting every bounded player-head into {@link #collected} along the
 * way. When the Next Page button disappears, it invokes
 * {@link Completion#onComplete(List)} with the accumulated list.</p>
 *
 * <p>A walk that loses its menu, or makes no progress for
 * {@link #STALL_TICKS}, never calls back: it stops the run through
 * {@link org.wynnvets.distribute.DistributeRuns#stop(String) DistributeRuns.stop}, with one
 * yellow line, and disarms, so a later Members menu cannot bind a stale walk.</p>
 *
 * <p>Forward-only: the walk is meant to start on a freshly-opened Members
 * menu (page 1), so there's never anything backward to cover. Its one
 * caller pairs {@link #armWalk(Completion)} with a fresh
 * {@link org.wynnvets.distribute.opener.GuildManageOpener#openManageMembers()
 * GuildManageOpener.openManageMembers()}, and the walk always binds on that menu's
 * {@code MenuOpenedEvent.Pre}: a Members menu already open at arm time is about to be
 * replaced, so it is ignored.</p>
 */
public final class MembersListWalker {

    /** Hard cap on page clicks per walk to bound runaway loops. */
    private static final int MAX_PAGES = 30;

    /** A walk that has not moved on &mdash; armed, bound to its menu, or clicked a page
     *  &mdash; for this many ticks has stalled and stops the run. ~10 s. Chosen when the
     *  watchdog was added rather than sized against observed behaviour: well above a page
     *  turn's round trip, and the same bound as {@code GuildLogWalker}'s overall timeout. */
    private static final int STALL_TICKS = 200;

    /** Debounce delay before {@link #scanAndPaginate()} runs: each trigger bumps
     *  {@link #scanToken}, so the scan runs this many ticks after the <em>last</em> one. Copied
     *  from {@code MembersListSearcher}'s constant of the same name, for the same reason: a
     *  page's {@code SetSlot} packets can cross a tick boundary, and a scan before they finish
     *  reads a half-updated page. */
    private static final int SCAN_DELAY_TICKS = 2;

    /** One member tile's worth of data captured during the walk. */
    public record MemberEntry(String legacyName, List<String> loreLines) {}

    /** Completion callback invoked once the last page has been scanned. */
    @FunctionalInterface
    public interface Completion {
        void onComplete(List<MemberEntry> members);
    }

    private static final MembersListWalker INSTANCE = new MembersListWalker();

    private static volatile boolean active = false;
    private static volatile int membersContainerId = -1;
    private static volatile int pagesClicked = 0;

    /** Bumped by every {@link #scheduleScan()} and every {@link #stop()}; a scheduled scan
     *  runs only if it is still the latest. */
    private static volatile int scanToken = 0;

    private static volatile Completion completion = null;
    private static final List<MemberEntry> collected = new ArrayList<>();

    /** Bumped by every {@link #armStallWatchdog()} and every {@link #stop()}, so only the
     *  latest watchdog of a live walk can fire. */
    private static volatile int stallToken = 0;

    private MembersListWalker() {}

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        VetsLogger.debug("Registered MembersListWalker on Wynntils event bus");
    }

    /**
     * Arms the walker for the next Members menu open. The callback fires
     * once the walk reaches a page with no Next Page button (or the
     * {@link #MAX_PAGES} cap), with the members it collected: meant to be
     * the whole roster when the walk ends on the last page (see the class
     * Javadoc and {@link #collectVisiblePage} for ways a page can be
     * missed), and only the pages reached when it ends at the cap. A walk
     * that loses its menu or stalls stops the run instead, and the callback
     * never fires.
     */
    public static void armWalk(Completion onComplete) {
        active = true;
        completion = onComplete;
        pagesClicked = 0;
        scanToken++;
        collected.clear();
        // Bind only on the next MenuOpenedEvent.Pre: the caller opens a fresh
        // menu right after arming, so one already open is about to be replaced.
        membersContainerId = -1;
        armStallWatchdog();
    }

    private static void stop() {
        active = false;
        completion = null;
        membersContainerId = -1;
        pagesClicked = 0;
        scanToken++;
        collected.clear();
        stallToken++;
    }

    /**
     * Ends the walk without its callback and stops the run with {@code reason}, closing the
     * Members menu first when {@code closeMenu} is set and one is still on top.
     */
    private static void abandon(String reason, boolean closeMenu) {
        VetsLogger.debug(
                "MembersListWalker: abandoning walk ({}) after {} members",
                reason,
                collected.size());
        stop();
        if (closeMenu) MembersGui.closeIfOpen();
        DistributeRuns.stop(reason);
    }

    /** (Re)starts the {@link #STALL_TICKS} watchdog; a later call or {@link #stop()}
     *  supersedes it. */
    private static void armStallWatchdog() {
        final int myToken = ++stallToken;
        Managers.TickScheduler.scheduleLater(
                () -> {
                    if (myToken != stallToken || !active) return;
                    abandon("the objectives walk timed out", true);
                },
                STALL_TICKS);
    }

    @SubscribeEvent
    public void onMenuOpenPre(MenuEvent.MenuOpenedEvent.Pre event) {
        if (!active) return;
        if (membersContainerId != -1) return;
        if (!StyledText.fromComponent(event.getTitle()).matches(MembersGui.TITLE_PATTERN)) return;
        membersContainerId = event.getContainerId();
        VetsLogger.debug("MembersListWalker: bound to menu id={}", membersContainerId);
        armStallWatchdog();
    }

    @SubscribeEvent
    public void onMenuClose(MenuEvent.MenuClosedEvent event) {
        if (!active) return;
        if (event.getContainerId() != membersContainerId) return;
        // A clientbound close for the bound menu (Wynntils posts
        // MenuClosedEvent only from its handleContainerClose hook), so the
        // server closed it: stop the run. A client-side close (the player's
        // Esc) posts nothing here; onScreenClosed notices that one.
        abandon("the Members menu closed", false);
    }

    /**
     * Notices a client-side close of the bound menu, such as the player's Esc, which posts no
     * {@link MenuEvent.MenuClosedEvent}: any screen closing during a bound walk schedules the
     * usual scan, which then finds the menu gone and stops the run.
     */
    @SubscribeEvent
    public void onScreenClosed(ScreenClosedEvent.Post event) {
        if (!active) return;
        if (membersContainerId == -1) return;
        scheduleScan();
    }

    @SubscribeEvent
    public void onSetContent(ContainerSetContentEvent.Post event) {
        if (!active) return;
        if (event.getContainerId() != membersContainerId) return;
        scheduleScan();
    }

    @SubscribeEvent
    public void onSetSlot(ContainerSetSlotEvent.Post event) {
        if (!active) return;
        if (event.getContainerId() != membersContainerId) return;
        int slot = event.getSlot();
        if (slot != MembersGui.NEXT_PAGE_SLOT && !MembersGui.isTileSlot(slot)) return;
        scheduleScan();
    }

    /** Schedules a scan {@link #SCAN_DELAY_TICKS} from now, superseding any scan not yet run.
     *  Each call posts its own lambda: the scheduler keys tasks by their {@code Runnable}. */
    private static void scheduleScan() {
        final int myToken = ++scanToken;
        Managers.TickScheduler.scheduleLater(
                () -> {
                    if (myToken != scanToken) return;
                    scanAndPaginate();
                },
                SCAN_DELAY_TICKS);
    }

    private static void scanAndPaginate() {
        if (!active) return;

        AbstractContainerScreen<?> screen = ContainerScreens.currentWithId(membersContainerId);
        if (screen == null) {
            abandon("the Members menu is not open", true);
            return;
        }

        List<ItemStack> items = screen.getMenu().getItems();

        collectVisiblePage(items);
        advanceOrFinish(items);
    }

    /**
     * Appends every {@linkplain MembersGui#isTileSlot(int) player-head tile}
     * on the current page to {@link #collected}. Names are deduped by legacyName so re-scans
     * of the same page (rare, but possible if a trigger for the page
     * arrives after its scan has run) don't double-count. The re-scan also re-runs
     * {@link #advanceOrFinish}, though, so it can click Next a second time
     * ({@code members-list-walker-rescan-double-clicks-next}).
     */
    private static void collectVisiblePage(List<ItemStack> items) {
        for (int slot = 0; slot < items.size(); slot++) {
            if (!MembersGui.isTileSlot(slot)) continue;

            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) continue;

            String name =
                    StyledText.fromComponent(stack.getHoverName()).getStringWithoutFormatting();
            if (anyMatchesName(name)) continue;

            collected.add(new MemberEntry(name, readLore(stack)));
        }
    }

    /**
     * Either clicks {@code Next Page} (incrementing
     * {@link #pagesClicked}) or finalises the walk via
     * {@link #finishWalk()}. Finalises when: the page-click cap is hit,
     * the next-page slot is out of bounds, or that slot doesn't carry
     * the Next Page pattern (we've reached the last page).
     *
     * <p>The last two are one branch here because
     * {@link MembersGui#clickPaginationIfPresent} does not distinguish
     * them and neither did this method — both said {@code finishWalk()}
     * and neither logged.</p>
     */
    private static void advanceOrFinish(List<ItemStack> items) {
        if (pagesClicked >= MAX_PAGES) {
            finishWalk();
            return;
        }

        // Try to advance to the next page; if the Next Page button is
        // gone we've reached the end.
        if (!MembersGui.clickPaginationIfPresent(
                items,
                MembersGui.NEXT_PAGE_SLOT,
                MembersGui.NEXT_PAGE_PATTERN,
                membersContainerId)) {
            finishWalk();
            return;
        }
        pagesClicked++;
        armStallWatchdog();
    }

    private static boolean anyMatchesName(String name) {
        for (MemberEntry e : collected) {
            if (e.legacyName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static List<String> readLore(ItemStack stack) {
        ItemLore lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        List<String> out = new ArrayList<>(lore.lines().size());
        for (Component line : lore.lines()) {
            // StyledText.getStringWithoutFormatting gives the rendered
            // text without §-codes, matching how MembersListSearcher
            // compares names so callers can use the same predicates.
            out.add(StyledText.fromComponent(line).getStringWithoutFormatting());
        }
        return out;
    }

    private static void finishWalk() {
        VetsLogger.debug(
                "MembersListWalker: walk complete — {} members across {} page clicks",
                collected.size(),
                pagesClicked);
        Completion cb = completion;
        List<MemberEntry> snapshot = new ArrayList<>(collected);
        stop();
        if (cb != null) cb.onComplete(snapshot);
    }
}
