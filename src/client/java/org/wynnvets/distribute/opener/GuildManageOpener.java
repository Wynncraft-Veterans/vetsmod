package org.wynnvets.distribute.opener;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.text.StyledText;
import com.wynntils.mc.event.ContainerSetContentEvent;
import com.wynntils.mc.event.MenuEvent;
import com.wynntils.utils.mc.McUtils;
import com.wynntils.utils.wynn.ContainerUtils;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import org.wynnvets.distribute.command.OutboundCommand;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.ContainerScreens;

/**
 * Opens sub-views of the Guild Management GUI by sending
 * {@code /guild manage} and clicking the right tile in the resulting
 * {@code "<guild>: Manage"} menu.
 *
 * <h2>Members path</h2>
 * <p>{@link #openManageMembers()} intercepts the Manage menu in
 * {@link MenuEvent.MenuOpenedEvent.Pre} (so the Manage GUI never
 * visually flashes), creates a synthetic empty container, and sends a
 * left-click on {@link #MEMBERS_SLOT} (the top-left "Manage Members"
 * key icon). Mirrors Wynntils' {@code GuildBankHotkeyFeature} approach.</p>
 *
 * <h2>Log path</h2>
 * <p>{@link #openGuildLog()} can't use the Pre-cancel trick because the
 * Guild Log tile's slot index isn't hardcoded anywhere in Wynntils and
 * Wynncraft has been known to shift the Manage GUI layout. Instead the
 * Manage menu is allowed to render, {@link ContainerSetContentEvent.Post}
 * populates the items, the tile whose hover-name matches Wynntils'
 * {@code "§7§lGuild Log"} pattern is found dynamically, and a
 * left-click is dispatched on its slot.</p>
 *
 * <p>Why not just {@code /guild log}? Empirically Wynncraft drops
 * {@code /guild log} sent shortly after a menu close (even via the
 * chat field): the server requires the player to be in a "clean"
 * session state that {@code /guild manage} doesn't. Going through
 * Manage&rarr;click reuses the only command we know is reliable.</p>
 *
 * <h2>No outcome is reported</h2>
 * <p>Neither open method reports whether it worked: both are
 * {@code void}, with no callback. This class records a failed open at
 * most as a debug line; a Manage menu with no Guild Log tile is closed
 * again. An arm survives other menus opening in between, and
 * expires {@link #ARM_TTL_MS} after the open call if its
 * {@code /guild manage} goes unanswered. A caller
 * learns of failure only downstream &mdash; from the searcher's watchdog,
 * the Members walker's stall watchdog or the log walker's overall cap
 * &mdash; and none of those disarms a live arm here; only a disconnect does, through
 * {@link #cancel()}.</p>
 */
public final class GuildManageOpener {

    /** Title shown on the GUI opened by {@code /guild manage}. */
    private static final Pattern MANAGE_TITLE_PATTERN = Pattern.compile(".+: Manage");

    /** Hover-name pattern that identifies the Guild Log tile in the
     *  Manage GUI. Mirrors Wynntils'
     *  {@code CustomGuildLogScreenFeature.GUILD_LOG_ITEM_PATTERN}. */
    private static final Pattern GUILD_LOG_ITEM_PATTERN = Pattern.compile("§7§lGuild Log");

    /** Top-left slot of the Manage GUI — the "Manage Members" key icon. */
    private static final int MEMBERS_SLOT = 0;

    /** An arm older than this is cleared, unused, when the next menu opens: its
     *  {@code /guild manage} went unanswered. 10 s, below the searcher's 15 s watchdog, so an
     *  arm a run has given up on cannot catch a much later Manage menu. Chosen, not observed.
     *  Checked lazily, so no timer runs. */
    private static final long ARM_TTL_MS = 10_000;

    private enum Target {
        MEMBERS,
        LOG
    }

    private static final GuildManageOpener INSTANCE = new GuildManageOpener();

    /** What we're trying to navigate to, or null. {@code MEMBERS} is
     *  consumed by the next Manage menu's {@code MenuOpenedEvent.Pre}; other
     *  menus pass through and leave it armed. {@code LOG} is cleared once a
     *  Manage menu's content has been scanned. Either expires
     *  {@link #ARM_TTL_MS} after the open call that set it, checked when the
     *  next menu opens. */
    private static volatile Target target = null;

    /** {@link System#nanoTime()} when {@link #target} was last set. */
    private static volatile long armedAtNanos;

    /** Container id of the Manage menu we're driving (Log path only). */
    private static volatile int manageContainerId = -1;

    private GuildManageOpener() {}

    /**
     * Registers this opener with the Wynntils event bus. Must be called after Wynntils' entrypoint
     * has run {@code WynntilsMod.init} &mdash; {@code WynntilsMod.registerEventListener} needs the
     * event bus that it creates &mdash; so it must stay in
     * {@link org.wynnvets.VetsmodClient#onClientStarted() VetsmodClient.onClientStarted()}, the
     * {@code ClientLifecycleEvents.CLIENT_STARTED} phase (or later), where it is registered today.
     */
    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        VetsLogger.debug("Registered GuildManageOpener on Wynntils event bus");
    }

    /** Disarms the open without running any callback, and drops a {@code /guild manage} still
     *  waiting in Wynntils' queue, for
     *  {@link org.wynnvets.distribute.DistributeRuns#reset() DistributeRuns.reset()} on a
     *  disconnect. */
    public static void cancel() {
        target = null;
        manageContainerId = -1;
        OutboundCommand.dropQueued("guild manage");
    }

    /**
     * Sends {@code /guild manage} and arms the Pre-intercept: the next
     * Manage menu to open within {@link #ARM_TTL_MS} consumes the arm, is
     * cancelled and has {@link #MEMBERS_SLOT} auto-clicked, transitioning
     * straight to the Members GUI.
     *
     * <p>Routes through {@link OutboundCommand#queueFront(String)} so the
     * command sits at the head of Wynntils' rate-limited outbound queue.
     * It still respects the 7-tick spacing, but runs ahead of anything
     * already queued there &mdash; see {@link OutboundCommand} for why.</p>
     */
    public static void openManageMembers() {
        target = Target.MEMBERS;
        armedAtNanos = System.nanoTime();
        manageContainerId = -1;
        OutboundCommand.queueFront("guild manage");
    }

    /**
     * Sends {@code /guild manage} and arms the post-render scan so the
     * next Manage menu within {@link #ARM_TTL_MS} auto-clicks the Guild Log tile (whichever slot
     * holds the {@code §7§lGuild Log} item), transitioning to the Log
     * GUI. Used by {@link org.wynnvets.distribute.distributor.GraidsDistributor GraidsDistributor}.
     *
     * <p>Same front-of-queue routing as {@link #openManageMembers()}.</p>
     */
    public static void openGuildLog() {
        target = Target.LOG;
        armedAtNanos = System.nanoTime();
        manageContainerId = -1;
        OutboundCommand.queueFront("guild manage");
    }

    /** Whether an arm is live, clearing one older than {@link #ARM_TTL_MS}. */
    private static boolean armLive() {
        if (target == null) return false;
        if (System.nanoTime() - armedAtNanos <= ARM_TTL_MS * 1_000_000L) return true;
        VetsLogger.debug("GuildManageOpener: {} arm expired unanswered", target);
        target = null;
        manageContainerId = -1;
        return false;
    }

    @SubscribeEvent
    public void onMenuOpenPre(MenuEvent.MenuOpenedEvent.Pre event) {
        // Only the Members path uses the Pre intercept (hardcoded slot 0).
        // The Log path can't because the log tile's slot isn't fixed.
        if (!armLive() || target != Target.MEMBERS) return;

        StyledText title = StyledText.fromComponent(event.getTitle());
        if (!title.matches(MANAGE_TITLE_PATTERN)) {
            VetsLogger.debug(
                    "GuildManageOpener: title [{}] did not match Manage pattern; arm kept",
                    title.getString());
            return;
        }
        target = null;

        event.setCanceled(true);

        AbstractContainerMenu container =
                event.getMenuType().create(event.getContainerId(), McUtils.inventory());
        ContainerUtils.clickOnSlot(
                MEMBERS_SLOT,
                event.getContainerId(),
                GLFW.GLFW_MOUSE_BUTTON_LEFT,
                container.getItems());
    }

    @SubscribeEvent
    public void onMenuOpenPost(MenuEvent.MenuOpenedEvent.Post event) {
        if (!armLive() || target != Target.LOG) return;
        if (manageContainerId != -1) return;
        StyledText title = StyledText.fromComponent(event.getTitle());
        if (!title.matches(MANAGE_TITLE_PATTERN)) return;
        manageContainerId = event.getContainerId();
        VetsLogger.debug(
                "GuildManageOpener: Manage menu open (id={}), waiting for items to find Log tile",
                manageContainerId);
    }

    @SubscribeEvent
    public void onSetContent(ContainerSetContentEvent.Post event) {
        if (target != Target.LOG) return;
        if (event.getContainerId() != manageContainerId) return;

        AbstractContainerScreen<?> screen = ContainerScreens.currentWithId(manageContainerId);
        if (screen == null) return;

        List<ItemStack> items = screen.getMenu().getItems();
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) continue;
            StyledText hover = StyledText.fromComponent(stack.getHoverName());
            if (hover.matches(GUILD_LOG_ITEM_PATTERN)) {
                VetsLogger.debug("GuildManageOpener: Guild Log tile at slot {}, clicking", slot);
                ContainerUtils.clickOnSlot(
                        slot, manageContainerId, GLFW.GLFW_MOUSE_BUTTON_LEFT, items);
                target = null;
                manageContainerId = -1;
                return;
            }
        }
        // No tile to click: close the Manage menu we opened rather than leave
        // it on screen. The log walker's overall timeout then stops the run.
        VetsLogger.debug("GuildManageOpener: Guild Log tile not found in Manage items, closing");
        LocalPlayer player = McUtils.player();
        if (player != null) player.closeContainer();
        target = null;
        manageContainerId = -1;
    }
}
