package org.wynnvets.distribute;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Managers;
import com.wynntils.core.text.StyledText;
import com.wynntils.mc.event.ContainerSetContentEvent;
import com.wynntils.mc.event.MenuEvent;
import com.wynntils.utils.mc.McUtils;
import com.wynntils.utils.wynn.ContainerUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.logging.VetsLogger;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Drives the "Press N to send X" hand-over interaction in the Wynncraft
 * Members GUI by sending repeated {@link ContainerUtils#pressKeyOnSlot}
 * packets against a target player-head slot.
 *
 * <p>The number keys 1, 2, 3 in the GUI correspond to Minecraft hotbar
 * button indices 0, 1, 2 &mdash; sent as {@code ClickType.SWAP} click
 * packets and intercepted by the Wynncraft server, which interprets each
 * press as one resource-send action (1 Aspect, 1 Guild Tome, 1024
 * Emeralds respectively).</p>
 *
 * <p>Wynncraft refreshes the Members menu after every send action,
 * assigning a new container id in the process. Sending the next press
 * before that refresh lands means the click goes against a stale
 * container id and the server drops it silently &mdash; symptom: the
 * user asks for {@code count=N} but only one reward chat line appears.
 * To avoid that, each press arms an event listener for the menu
 * refresh ({@link MenuEvent.MenuOpenedEvent.Pre} on close+reopen, or
 * {@link ContainerSetContentEvent.Post} on in-place refresh) and the
 * next press is scheduled {@link #PRESS_DELAY_TICKS} ticks after the
 * refresh is observed, not after a fixed wall-clock delay.</p>
 */
public final class MemberDistributor {

    /** Maps a resource label to the hotbar button index used by the
     *  {@code ClickType.SWAP} click. */
    public enum Resource {
        ASPECTS(0, "Aspect"),
        TOMES(1, "Guild Tome"),
        EMERALDS(2, "Emerald payment");

        private final int hotbarButton;
        private final String displayName;

        Resource(int hotbarButton, String displayName) {
            this.hotbarButton = hotbarButton;
            this.displayName = displayName;
        }

        public int hotbarButton() {
            return hotbarButton;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** Mirrors {@code GuildMemberListContainer.TITLE_PATTERN}. */
    private static final Pattern MEMBERS_TITLE_PATTERN = Pattern.compile(".+: Members");

    /** Settle buffer applied after a confirmed refresh and before the next
     *  press &mdash; gives the server a tick of breathing room and keeps
     *  the cadence humane. */
    private static final int PRESS_DELAY_TICKS = 4;

    /** Safety bound on the refresh wait. If no refresh event lands within
     *  this window, we abort and surface a chat message rather than
     *  hanging indefinitely. ~2 seconds covers a generous network RTT. */
    private static final int REFRESH_TIMEOUT_TICKS = 40;

    private static final MemberDistributor INSTANCE = new MemberDistributor();

    // Pending-press state. Non-null/true iff a press has been fired and
    // we're waiting on the server's menu refresh before firing the next.
    private static volatile int pendingSlot;
    private static volatile Resource pendingResource;
    private static volatile int pendingTotal;
    private static volatile int pendingSent;
    private static volatile int pendingContainerId;
    private static volatile boolean awaitingRefresh;
    /** Monotonic token so a stale scheduled timeout doesn't trip a later run. */
    private static volatile int timeoutToken;

    private MemberDistributor() {}

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        VetsLogger.debug("Registered MemberDistributor on Wynntils event bus");
    }

    /**
     * Fires {@code count} consecutive {@code pressKeyOnSlot} packets
     * against {@code slot} in the currently-open Members container, each
     * one gated by the menu-refresh observed after the previous press.
     * Aborts cleanly if the Members screen is closed or replaced
     * mid-loop, or if the refresh never arrives within
     * {@link #REFRESH_TIMEOUT_TICKS}.
     */
    public static void fire(int slot, Resource resource, int count, String recipientName) {
        if (count <= 0) return;
        ChatUtils.sendLocalMessage(
                Component.literal("Sending " + count + "x " + resource.displayName()
                        + " to " + recipientName + "…")
                        .withStyle(ChatFormatting.AQUA));
        sendPressAndArm(slot, resource, count, 0);
    }

    private static void sendPressAndArm(int slot, Resource resource, int total, int sent) {
        AbstractContainerScreen<?> screen = currentMembersScreen();
        if (screen == null) {
            VetsLogger.debug("MemberDistributor: members screen gone after {}/{} presses",
                    sent, total);
            clearPending();
            return;
        }

        int containerId = screen.getMenu().containerId;
        List<ItemStack> items = screen.getMenu().getItems();
        ContainerUtils.pressKeyOnSlot(slot, containerId, resource.hotbarButton(), items);
        VetsLogger.debug("MemberDistributor: sent press {}/{} on slot {} of container {}",
                sent + 1, total, slot, containerId);

        pendingSlot = slot;
        pendingResource = resource;
        pendingTotal = total;
        pendingSent = sent + 1;
        pendingContainerId = containerId;
        awaitingRefresh = true;
        armRefreshTimeout(sent + 1, total);
    }

    private static void armRefreshTimeout(int sentSoFar, int total) {
        final int myToken = ++timeoutToken;
        Managers.TickScheduler.scheduleLater(() -> {
            if (!awaitingRefresh) return;
            if (myToken != timeoutToken) return;
            VetsLogger.debug("MemberDistributor: refresh wait timed out after {}/{} presses",
                    sentSoFar, total);
            ChatUtils.sendLocalMessage(
                    Component.literal("Send timed out after " + sentSoFar + "/" + total
                            + " (server didn't refresh the menu).")
                            .withStyle(ChatFormatting.YELLOW));
            clearPending();
        }, REFRESH_TIMEOUT_TICKS);
    }

    /**
     * Close+reopen refresh path: Wynncraft tears down the old container
     * and opens a fresh Members menu under a new container id.
     */
    @SubscribeEvent
    public void onMenuOpenPre(MenuEvent.MenuOpenedEvent.Pre event) {
        if (!awaitingRefresh) return;
        if (!StyledText.fromComponent(event.getTitle()).matches(MEMBERS_TITLE_PATTERN)) return;
        VetsLogger.debug("MemberDistributor: refresh observed via MenuOpened (new id={}, prev={})",
                event.getContainerId(), pendingContainerId);
        onRefreshObserved();
    }

    /**
     * In-place refresh path: Wynncraft reuses the same container id and
     * streams a fresh {@code SetContent} for the updated slots.
     */
    @SubscribeEvent
    public void onSetContent(ContainerSetContentEvent.Post event) {
        if (!awaitingRefresh) return;
        AbstractContainerScreen<?> screen = currentMembersScreen();
        if (screen == null) return;
        if (event.getContainerId() != screen.getMenu().containerId) return;
        VetsLogger.debug("MemberDistributor: refresh observed via SetContent (id={})",
                event.getContainerId());
        onRefreshObserved();
    }

    private static void onRefreshObserved() {
        awaitingRefresh = false;
        final int slot = pendingSlot;
        final Resource resource = pendingResource;
        final int total = pendingTotal;
        final int sent = pendingSent;
        Managers.TickScheduler.scheduleLater(() -> {
            if (sent >= total) {
                VetsLogger.debug("MemberDistributor: completed {} presses on slot {}",
                        total, slot);
                clearPending();
                closeMembersScreen();
            } else {
                sendPressAndArm(slot, resource, total, sent);
            }
        }, PRESS_DELAY_TICKS);
    }

    private static void clearPending() {
        awaitingRefresh = false;
        pendingResource = null;
        // Bumping the token cancels any in-flight timeout for the
        // run we're tearing down.
        timeoutToken++;
    }

    /**
     * Closes the Members GUI client-side. {@code setScreen(null)} triggers
     * {@code AbstractContainerScreen.removed()}, which calls
     * {@code player.closeContainer()} &mdash; so both the visual screen
     * dismiss and the server-side {@code ServerboundContainerClosePacket}
     * happen in one call. Guarded so it never closes an unrelated screen
     * the user happens to have open by the time we get here.
     */
    private static void closeMembersScreen() {
        if (currentMembersScreen() == null) return;
        McUtils.mc().setScreen(null);
    }

    /**
     * Returns the currently-open container screen iff its title still
     * matches the Members pattern. Handles Wynncraft refreshing the menu
     * under us (fresh container id, same logical screen) and aborts
     * cleanly if the user navigated away.
     */
    private static AbstractContainerScreen<?> currentMembersScreen() {
        if (McUtils.mc().screen instanceof AbstractContainerScreen<?> screen
                && StyledText.fromComponent(screen.getTitle()).matches(MEMBERS_TITLE_PATTERN)) {
            return screen;
        }
        return null;
    }
}
