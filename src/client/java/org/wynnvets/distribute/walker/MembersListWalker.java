package org.wynnvets.distribute.walker;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Managers;
import com.wynntils.core.text.StyledText;
import com.wynntils.mc.event.ContainerSetContentEvent;
import com.wynntils.mc.event.ContainerSetSlotEvent;
import com.wynntils.mc.event.MenuEvent;
import com.wynntils.utils.mc.McUtils;
import com.wynntils.utils.wynn.ContainerUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import org.wynnvets.distribute.distributor.ObjectivesDistributor;
import org.wynnvets.distribute.opener.GuildManageOpener;
import org.wynnvets.logging.VetsLogger;

/**
 * Walks every page of the in-game {@code "<guild>: Members"} GUI and
 * collects an entry for each player-head it sees. Used by selectors
 * whose recipient list depends on per-member tile data &mdash; e.g.
 * {@link ObjectivesDistributor}, which reads the {@code Guild Objective:}
 * lore lines to decide who qualifies.
 *
 * <p>Built on the same event triple as {@link MembersListSearcher}
 * (MenuOpen / SetContent / SetSlot) and, like it, defers the rescan by a
 * scheduler delay so straggling slot updates land first &mdash; but the
 * two are not interchangeable. This class waits one tick and filters
 * {@link #onSetSlot} down to {@link #NEXT_PAGE_SLOT}; the searcher waits
 * {@code SCAN_DELAY_TICKS} (a different value, for the reason recorded
 * on that constant) and also triggers on every in-bounds tile. Instead of
 * stopping on a match it forward-paginates exhaustively, collecting
 * every bounded player-head into {@link #collected} along the way. When
 * the Next Page button disappears, it invokes
 * {@link Completion#onComplete(List)} with the accumulated list.</p>
 *
 * <p>Forward-only: the walk is always started on a freshly-opened
 * Members menu (page 1), so there's never anything backward to cover.
 * If the menu is somehow already mid-page when armed, anything before
 * that page is silently skipped &mdash; the caller should always pair
 * {@link #armWalk(Completion)} with a fresh
 * {@link GuildManageOpener#openManageMembers()}.</p>
 */
public final class MembersListWalker {

    /** Mirrors {@code GuildMemberListContainer.TITLE_PATTERN}. */
    private static final Pattern MEMBERS_TITLE_PATTERN = Pattern.compile(".+: Members");

    /** Mirrors {@code GuildMemberListContainer.NEXT_PAGE_PATTERN}. */
    private static final Pattern NEXT_PAGE_PATTERN = Pattern.compile("§a§lNext Page");

    /** Mirrors {@code GuildMemberListContainer.getNextItemSlot()}. */
    private static final int NEXT_PAGE_SLOT = 28;

    // ContainerBounds(0, 2, 4, 8) on GuildMemberListContainer — same area
    // the searcher scans for matches.
    private static final int BOUNDS_START_ROW = 0;
    private static final int BOUNDS_END_ROW = 4;
    private static final int BOUNDS_START_COL = 2;
    private static final int BOUNDS_END_COL = 8;

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
     * {@link #MAX_PAGES} cap), with the full member list.
     */
    public static void armWalk(Completion onComplete) {
        active = true;
        completion = onComplete;
        pagesClicked = 0;
        scanScheduled = false;
        collected.clear();

        // Re-arm fast-path (parity with MembersListSearcher): if the menu
        // is already open, bind to its container id and start scanning.
        if (McUtils.mc().screen instanceof AbstractContainerScreen<?> screen
                && StyledText.fromComponent(screen.getTitle()).matches(MEMBERS_TITLE_PATTERN)) {
            membersContainerId = screen.getMenu().containerId;
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
        if (!StyledText.fromComponent(event.getTitle()).matches(MEMBERS_TITLE_PATTERN)) return;
        membersContainerId = event.getContainerId();
        VetsLogger.debug("MembersListWalker: bound to menu id={}", membersContainerId);
    }

    @SubscribeEvent
    public void onMenuClose(MenuEvent.MenuClosedEvent event) {
        if (!active) return;
        if (event.getContainerId() != membersContainerId) return;
        VetsLogger.debug("MembersListWalker: menu closed mid-walk, abandoning");
        // No callback on abandon, and no chat line either — this path is
        // silent. There is no watchdog here at all. The searcher's
        // WATCHDOG_TICKS is the nearest equivalent, and note it does not
        // cover MembersListSearcher's own onMenuClose either — that calls
        // stop(), which bumps watchdogToken and disarms it. So an
        // @objectives run whose Members menu closes mid-walk stalls until
        // the next armWalk resets the state, with nothing to force it
        // along. Same for scanAndPaginate's screen-gone path.
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
        if (event.getSlot() != NEXT_PAGE_SLOT) return;
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

        AbstractContainerScreen<?> screen = currentMembersScreen();
        if (screen == null) {
            stop();
            return;
        }

        List<ItemStack> items = screen.getMenu().getItems();

        collectVisiblePage(items);
        advanceOrFinish(items);
    }

    /**
     * Appends every bounded player-head tile on the current page to
     * {@link #collected}. Names are deduped by legacyName so re-scans
     * of the same page (rare but possible if SetContent + SetSlot both
     * fire) don't double-count.
     */
    private static void collectVisiblePage(List<ItemStack> items) {
        for (int slot = 0; slot < items.size(); slot++) {
            int row = slot / 9;
            int col = slot % 9;
            if (row < BOUNDS_START_ROW || row > BOUNDS_END_ROW) continue;
            if (col < BOUNDS_START_COL || col > BOUNDS_END_COL) continue;

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
     */
    private static void advanceOrFinish(List<ItemStack> items) {
        if (pagesClicked >= MAX_PAGES) {
            finishWalk();
            return;
        }

        // Try to advance to the next page; if the Next Page button is
        // gone we've reached the end.
        if (NEXT_PAGE_SLOT >= items.size()) {
            finishWalk();
            return;
        }
        ItemStack nextItem = items.get(NEXT_PAGE_SLOT);
        if (!StyledText.fromComponent(nextItem.getHoverName()).matches(NEXT_PAGE_PATTERN)) {
            finishWalk();
            return;
        }

        pagesClicked++;
        ContainerUtils.clickOnSlot(
                NEXT_PAGE_SLOT, membersContainerId, GLFW.GLFW_MOUSE_BUTTON_LEFT, items);
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

    private static AbstractContainerScreen<?> currentMembersScreen() {
        if (McUtils.mc().screen instanceof AbstractContainerScreen<?> screen
                && screen.getMenu().containerId == membersContainerId) {
            return screen;
        }
        return null;
    }
}
