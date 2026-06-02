package org.wynnvets.distribute;

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
 * {@code ContainerSetSlotEvent.Post} triple Wynntils uses, with a one-tick
 * scheduler delay so straggling slot updates land before the rescan.
 * The slot indices and bounds match
 * {@code GuildMemberListContainer}.</p>
 *
 * <h2>Name matching</h2>
 * <p>Each player-head's hover name is the player's {@code legacyName} as
 * served by {@code wapi /v3/guild/<name>.members.<rank>.<currentName>.legacyName}
 * &mdash; the name they held when joining the guild, which Wynncraft
 * keeps stable across Mojang renames. Matching is case-insensitive
 * against the §-stripped hover name. Multiple acceptable names can be
 * armed via {@link #addAlternative(String)}, used by
 * {@link NameResolver} to add the legacy form once a current Mojang
 * username has been resolved.</p>
 */
public final class MembersListSearcher {

    /** Mirrors {@code GuildMemberListContainer.TITLE_PATTERN}. */
    private static final Pattern MEMBERS_TITLE_PATTERN = Pattern.compile(".+: Members");
    /** Mirrors {@code GuildMemberListContainer.NEXT_PAGE_PATTERN}. */
    private static final Pattern NEXT_PAGE_PATTERN = Pattern.compile("§a§lNext Page");
    /** Mirrors {@code GuildMemberListContainer.getNextItemSlot()}. */
    private static final int NEXT_PAGE_SLOT = 28;

    // ContainerBounds(0, 2, 4, 8) on GuildMemberListContainer — searchable
    // area is rows 0–4 × cols 2–8 of the 9-wide grid.
    private static final int BOUNDS_START_ROW = 0;
    private static final int BOUNDS_END_ROW = 4;
    private static final int BOUNDS_START_COL = 2;
    private static final int BOUNDS_END_COL = 8;

    /** Hard cap on page clicks per search to bound runaway loops. */
    private static final int MAX_PAGES = 30;

    private static final MembersListSearcher INSTANCE = new MembersListSearcher();

    /** Human-readable label for chat messages; non-null iff armed. */
    private static volatile String displayQuery = null;
    /** Lowercased set of acceptable names. Concurrent because async
     *  resolvers ({@link NameResolver}) may add alternatives after arming. */
    private static final Set<String> queryLower = ConcurrentHashMap.newKeySet();
    /** Container id of the Members menu we're currently driving. */
    private static volatile int membersContainerId = -1;
    private static volatile int pagesClicked = 0;
    /** Set when a scan has been scheduled for the next tick — prevents
     *  double-scheduling when both SetContent and SetSlot fire for the
     *  same page transition. */
    private static volatile boolean scanScheduled = false;
    /** Callback invoked when the armed name is located. */
    private static volatile SlotMatchHandler matchHandler = null;

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
        displayQuery = name;
        queryLower.clear();
        if (name != null && !name.isEmpty()) {
            queryLower.add(name.toLowerCase(Locale.ROOT));
        }
        matchHandler = handler;
        membersContainerId = -1;
        pagesClicked = 0;
        scanScheduled = false;
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
        scanScheduled = false;
        matchHandler = null;
    }

    @SubscribeEvent
    public void onMenuOpenPre(MenuEvent.MenuOpenedEvent.Pre event) {
        if (displayQuery == null) return;
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
     * The {@link #NEXT_PAGE_SLOT} update is the signal that the new page's
     * contents have been streamed in &mdash; mirrors Wynntils' approach in
     * {@code ContainerSearchFeature.onContainerSetSlot}.
     */
    @SubscribeEvent
    public void onSetSlot(ContainerSetSlotEvent.Post event) {
        if (displayQuery == null) return;
        if (event.getContainerId() != membersContainerId) return;
        if (event.getSlot() != NEXT_PAGE_SLOT) return;
        scheduleScan();
    }

    /**
     * Schedule a scan-and-paginate run for the next tick. Coalesces
     * multiple events that fire for the same page transition into a
     * single scan, and the one-tick delay lets straggling slot updates
     * land before we read {@code screen.getMenu().getItems()}.
     */
    private static void scheduleScan() {
        if (scanScheduled) return;
        scanScheduled = true;
        Managers.TickScheduler.scheduleLater(MembersListSearcher::scanAndPaginate, 1);
    }

    private static void scanAndPaginate() {
        scanScheduled = false;
        if (displayQuery == null) return;

        AbstractContainerScreen<?> screen = currentMembersScreen();
        if (screen == null) {
            stop();
            return;
        }

        List<ItemStack> items = screen.getMenu().getItems();

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
                return;
            }
        }

        if (pagesClicked >= MAX_PAGES) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Could not find " + displayQuery + " (reached page limit).")
                            .withStyle(ChatFormatting.YELLOW));
            stop();
            return;
        }

        // Check whether a "Next Page" item exists; if not we're on the last page.
        if (NEXT_PAGE_SLOT >= items.size()) {
            stopNotFound();
            return;
        }
        ItemStack nextItem = items.get(NEXT_PAGE_SLOT);
        StyledText nextName = StyledText.fromComponent(nextItem.getHoverName());
        if (!nextName.matches(NEXT_PAGE_PATTERN)) {
            stopNotFound();
            return;
        }

        pagesClicked++;
        ContainerUtils.clickOnSlot(
                NEXT_PAGE_SLOT,
                membersContainerId,
                GLFW.GLFW_MOUSE_BUTTON_LEFT,
                items);
    }

    private static void stopNotFound() {
        ChatUtils.sendLocalMessage(
                Component.literal("Could not find " + displayQuery + " in members list.")
                        .withStyle(ChatFormatting.YELLOW));
        stop();
    }

    private static AbstractContainerScreen<?> currentMembersScreen() {
        if (McUtils.mc().screen instanceof AbstractContainerScreen<?> screen
                && screen.getMenu().containerId == membersContainerId) {
            return screen;
        }
        return null;
    }
}
