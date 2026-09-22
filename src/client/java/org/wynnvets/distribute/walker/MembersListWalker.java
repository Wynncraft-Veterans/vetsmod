package org.wynnvets.distribute.walker;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Managers;
import com.wynntils.core.text.StyledText;
import com.wynntils.mc.event.ContainerSetContentEvent;
import com.wynntils.mc.event.ContainerSetSlotEvent;
import com.wynntils.mc.event.MenuEvent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.distribute.MembersGui;
import org.wynnvets.distribute.distributor.ObjectivesDistributor;
import org.wynnvets.distribute.opener.GuildManageOpener;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.ContainerScreens;

/**
 * Walks every page of the in-game {@code "<guild>: Members"} GUI and
 * collects an entry for each player-head it sees. Used by selectors
 * whose recipient list depends on per-member tile data &mdash; e.g.
 * {@link ObjectivesDistributor}, which reads the {@code Guild Objective:}
 * lore lines to decide who qualifies.
 *
 * <p>Built on the same event triple as {@link MembersListSearcher}
 * (MenuOpen / SetContent / SetSlot) and, like it, defers the rescan by a
 * scheduler delay &mdash; but the two are not interchangeable. This class
 * uses a one-tick scheduler delay and filters {@link #onSetSlot} down to
 * {@link MembersGui#NEXT_PAGE_SLOT}; the searcher uses
 * {@code SCAN_DELAY_TICKS} (a different value, for the reason recorded on
 * that constant) and also triggers on every in-bounds tile. And they
 * debounce differently: this class latches on the <em>first</em> trigger
 * and scans after its delay, ignoring later triggers until then, where
 * the searcher's scan waits a delay after the <em>last</em>. So here the
 * wait for straggling slot updates is fixed by the first trigger and is
 * not extended by later ones. Instead of stopping on a match it
 * forward-paginates exhaustively,
 * collecting every bounded player-head into {@link #collected} along the
 * way. When the Next Page button disappears, it invokes
 * {@link Completion#onComplete(List)} with the accumulated list.</p>
 *
 * <p>Forward-only: the walk is meant to start on a freshly-opened Members
 * menu (page 1), so there's never anything backward to cover. Its one
 * caller pairs {@link #armWalk(Completion)} with a fresh
 * {@link GuildManageOpener#openManageMembers()}. That assumes no Members
 * menu is already open at arm time: if one is, {@code armWalk}'s fast
 * path binds that menu instead, from whatever page it is on, and the walk
 * does not follow the fresh menu the caller then opens
 * ({@code members-list-fast-path-binds-a-menu-about-to-be-replaced}).</p>
 */
public final class MembersListWalker {

    /** Hard cap on page clicks per walk to bound runaway loops. */
    private static final int MAX_PAGES = 30;

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
    private static volatile boolean scanScheduled = false;
    private static volatile Completion completion = null;
    private static final List<MemberEntry> collected = new ArrayList<>();

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
     * missed), and only the pages reached when it ends at the cap.
     */
    public static void armWalk(Completion onComplete) {
        active = true;
        completion = onComplete;
        pagesClicked = 0;
        scanScheduled = false;
        collected.clear();

        // Fast path mirroring MembersListSearcher's: if a Members menu is
        // already open, bind to its container id and start scanning. The
        // walker has no re-arm flow, and its caller opens a fresh menu
        // right after arming, so if this ever fires it binds a menu that
        // is about to be replaced (see the class Javadoc).
        AbstractContainerScreen<?> open = MembersGui.currentByTitle();
        if (open != null) {
            membersContainerId = open.getMenu().containerId;
            scheduleScan();
        } else {
            membersContainerId = -1;
        }
    }

    private static void stop() {
        active = false;
        completion = null;
        membersContainerId = -1;
        pagesClicked = 0;
        scanScheduled = false;
        collected.clear();
    }

    @SubscribeEvent
    public void onMenuOpenPre(MenuEvent.MenuOpenedEvent.Pre event) {
        if (!active) return;
        if (membersContainerId != -1) return;
        if (!StyledText.fromComponent(event.getTitle()).matches(MembersGui.TITLE_PATTERN)) return;
        membersContainerId = event.getContainerId();
        VetsLogger.debug("MembersListWalker: bound to menu id={}", membersContainerId);
    }

    @SubscribeEvent
    public void onMenuClose(MenuEvent.MenuClosedEvent event) {
        if (!active) return;
        if (event.getContainerId() != membersContainerId) return;
        VetsLogger.debug("MembersListWalker: menu closed mid-walk, abandoning");
        // A clientbound close for the bound menu (Wynntils posts
        // MenuClosedEvent only from its handleContainerClose hook). No
        // callback on abandon, and no chat line either — this path is
        // silent. There is no watchdog here at all. The searcher's
        // WATCHDOG_TICKS is the nearest equivalent, and note it does not
        // cover MembersListSearcher's own onMenuClose either — that calls
        // stop(), which bumps watchdogToken and disarms it. stop() below
        // clears the walker, so the @objectives run never resumes and any
        // @split phase after it never starts. Same for scanAndPaginate's
        // screen-gone path. A client-side close (the player's Esc) posts
        // nothing here: the walk then takes that screen-gone path if a
        // scan is pending or a late SetSlot/SetContent for the bound id
        // schedules one, and otherwise waits armed with nothing scheduled.
        // See members-list-walker-drops-completion.
        stop();
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
        if (event.getSlot() != MembersGui.NEXT_PAGE_SLOT) return;
        scheduleScan();
    }

    private static void scheduleScan() {
        if (scanScheduled) return;
        scanScheduled = true;
        Managers.TickScheduler.scheduleLater(MembersListWalker::scanAndPaginate, 1);
    }

    private static void scanAndPaginate() {
        scanScheduled = false;
        if (!active) return;

        AbstractContainerScreen<?> screen = ContainerScreens.currentWithId(membersContainerId);
        if (screen == null) {
            stop();
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
