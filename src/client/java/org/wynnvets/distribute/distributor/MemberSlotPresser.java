package org.wynnvets.distribute.distributor;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Managers;
import com.wynntils.core.text.StyledText;
import com.wynntils.mc.event.ContainerSetContentEvent;
import com.wynntils.mc.event.MenuEvent;
import com.wynntils.utils.wynn.ContainerUtils;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.distribute.MembersGui;
import org.wynnvets.logging.VetsLogger;

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
 * <p>Wynncraft refreshes the Members menu after every send action. The
 * observed shape is the menu reopening under a new container id, and
 * the code also accepts an in-place refresh under the same id. Sending
 * the next press before that refresh lands means the click goes against
 * a stale menu state and the server drops it silently &mdash; symptom: the
 * user asks for {@code count=N} but only one reward chat line appears.
 * To avoid that, each press arms an event listener for the menu
 * refresh ({@link MenuEvent.MenuOpenedEvent.Pre} on a reopen under a new id, or
 * {@link ContainerSetContentEvent.Post} on in-place refresh) and the
 * next press is scheduled {@link #PRESS_DELAY_TICKS} ticks after the
 * refresh is observed, not after a fixed wall-clock delay.</p>
 */
public final class MemberSlotPresser {

    /** Maps a resource label to the hotbar button index used by the
     *  {@code ClickType.SWAP} click, and says whether the NoAspects opt-out
     *  list gates it ({@link #gatedByNoAspects()}). */
    public enum Resource {
        ASPECTS(0, "Aspect"),
        TOMES(1, "Tome"),
        EMERALDS(2, "Emerald");

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

        /** Whether the NoAspects opt-out list applies to this resource: {@code true} for
         *  {@link #ASPECTS} only. Opted-out members still get tomes and emeralds. */
        public boolean gatedByNoAspects() {
            return this == ASPECTS;
        }
    }

    /** Settle buffer applied after every confirmed refresh &mdash; before
     *  the next press or, after the last, before {@code onComplete}. Gives
     *  the server some breathing room and keeps the cadence humane. */
    private static final int PRESS_DELAY_TICKS = 4;

    /** Safety bound on the refresh wait. If no refresh event lands within
     *  this window, we abort and surface a chat message rather than
     *  hanging indefinitely. ~2 seconds covers a generous network RTT. */
    private static final int REFRESH_TIMEOUT_TICKS = 40;

    private static final MemberSlotPresser INSTANCE = new MemberSlotPresser();

    // The in-flight batch's state. awaitingRefresh is set when a press is
    // sent and cleared when its refresh is observed or the batch is torn
    // down (completion, timeout, or screen gone); pendingResource is
    // nulled only by that teardown. The int fields are never reset, and
    // pendingContainerId is read only by onMenuOpenPre's debug line.
    private static volatile int pendingSlot;
    private static volatile Resource pendingResource;
    private static volatile int pendingTotal;
    private static volatile int pendingSent;
    private static volatile int pendingContainerId;
    private static volatile boolean awaitingRefresh;

    /** Monotonic token so a stale scheduled timeout doesn't trip a later run. */
    private static volatile int timeoutToken;

    /** Callback invoked when the press batch ends — on success (last press
     *  confirmed), on timeout, or when the Members screen is gone for the
     *  next press. Lets {@link DistributionQueue} chain the next recipient
     *  instead of closing the Members menu between sends. Every exit takes
     *  it through {@link #takePendingOnComplete()}, so none can clear it
     *  unrun. */
    private static volatile Runnable pendingOnComplete;

    private MemberSlotPresser() {}

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        VetsLogger.debug("Registered MemberSlotPresser on Wynntils event bus");
    }

    /**
     * Fires {@code count} consecutive {@code pressKeyOnSlot} packets
     * against {@code slot} in the currently-open Members container, each
     * one gated by the menu-refresh observed after the previous press,
     * and invokes {@code onComplete} {@link #PRESS_DELAY_TICKS} after the
     * last press is confirmed by a refresh event. Can stop early: if no
     * Members screen is open when a press is due &mdash; checked by title,
     * so a Members menu reopened under a new id still counts &mdash; or a
     * press's refresh is not observed within {@link #REFRESH_TIMEOUT_TICKS},
     * a yellow chat line says how many presses went out and the callback
     * runs then. A {@code count} that is not positive runs it at once.
     *
     * <p>The screen is intentionally left open so the callback can chain the
     * next recipient &mdash; used by {@link DistributionQueue} to visit
     * multiple recipients in one menu session. The single-target command
     * passes one that closes the menu instead.</p>
     */
    public static void fire(
            int slot, Resource resource, int count, String recipientName, Runnable onComplete) {
        if (count <= 0) {
            if (onComplete != null) onComplete.run();
            return;
        }
        pendingOnComplete = onComplete;
        ChatUtils.sendLocalMessage(
                Component.literal(
                                "Sending "
                                        + count
                                        + "x "
                                        + resource.displayName()
                                        + " to "
                                        + recipientName
                                        + "…")
                        .withStyle(ChatFormatting.AQUA));
        sendPressAndArm(slot, resource, count, 0);
    }

    private static void sendPressAndArm(int slot, Resource resource, int total, int sent) {
        AbstractContainerScreen<?> screen = MembersGui.currentByTitle();
        if (screen == null) {
            VetsLogger.debug(
                    "MemberSlotPresser: members screen gone after {}/{} presses", sent, total);
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    "Send stopped after "
                                            + sent
                                            + "/"
                                            + total
                                            + ": the Members menu is not open.")
                            .withStyle(ChatFormatting.YELLOW));
            // Run the callback, as the timeout does: a chained caller
            // (DistributionQueue) then finds the menu gone and stops the run.
            Runnable cb = takePendingOnComplete();
            if (cb != null) cb.run();
            return;
        }

        int containerId = screen.getMenu().containerId;
        List<ItemStack> items = screen.getMenu().getItems();
        ContainerUtils.pressKeyOnSlot(slot, containerId, resource.hotbarButton(), items);
        VetsLogger.debug(
                "MemberSlotPresser: sent press {}/{} on slot {} of container {}",
                sent + 1,
                total,
                slot,
                containerId);

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
        Managers.TickScheduler.scheduleLater(
                () -> {
                    if (!awaitingRefresh) return;
                    if (myToken != timeoutToken) return;
                    VetsLogger.debug(
                            "MemberSlotPresser: refresh wait timed out after {}/{} presses",
                            sentSoFar,
                            total);
                    ChatUtils.sendLocalMessage(
                            Component.literal(
                                            "Send timed out after "
                                                    + sentSoFar
                                                    + "/"
                                                    + total
                                                    + " (server didn't refresh the menu).")
                                    .withStyle(ChatFormatting.YELLOW));
                    // Invoke onComplete so chained callers (e.g.
                    // DistributionQueue) advance past the timed-out recipient
                    // rather than stalling.
                    Runnable cb = takePendingOnComplete();
                    if (cb != null) cb.run();
                },
                REFRESH_TIMEOUT_TICKS);
    }

    /**
     * Reopen refresh path: a Members menu opening under a new container
     * id is accepted as the refresh (whether a close for the old id
     * precedes it is not recorded; vanilla's {@code ServerPlayer.openMenu}
     * sends one).
     */
    @SubscribeEvent
    public void onMenuOpenPre(MenuEvent.MenuOpenedEvent.Pre event) {
        if (!awaitingRefresh) return;
        if (!StyledText.fromComponent(event.getTitle()).matches(MembersGui.TITLE_PATTERN)) return;
        VetsLogger.debug(
                "MemberSlotPresser: refresh observed via MenuOpened (new id={}, prev={})",
                event.getContainerId(),
                pendingContainerId);
        onRefreshObserved();
    }

    /**
     * In-place refresh path: a full-contents re-send ({@code SetContent})
     * for the open Members menu's current id is also accepted as the
     * refresh.
     */
    @SubscribeEvent
    public void onSetContent(ContainerSetContentEvent.Post event) {
        if (!awaitingRefresh) return;
        AbstractContainerScreen<?> screen = MembersGui.currentByTitle();
        if (screen == null) return;
        if (event.getContainerId() != screen.getMenu().containerId) return;
        VetsLogger.debug(
                "MemberSlotPresser: refresh observed via SetContent (id={})",
                event.getContainerId());
        onRefreshObserved();
    }

    private static void onRefreshObserved() {
        awaitingRefresh = false;
        final int slot = pendingSlot;
        final Resource resource = pendingResource;
        final int total = pendingTotal;
        final int sent = pendingSent;
        Managers.TickScheduler.scheduleLater(
                () -> {
                    if (sent >= total) {
                        VetsLogger.debug(
                                "MemberSlotPresser: completed {} presses on slot {}", total, slot);
                        Runnable cb = takePendingOnComplete();
                        if (cb != null) cb.run();
                    } else {
                        sendPressAndArm(slot, resource, total, sent);
                    }
                },
                PRESS_DELAY_TICKS);
    }

    /** Captures the batch's callback, then tears the batch down; the caller runs what this
     *  returns. Every exit goes through here, so the teardown can never drop the callback. */
    private static Runnable takePendingOnComplete() {
        Runnable cb = pendingOnComplete;
        clearPending();
        return cb;
    }

    private static void clearPending() {
        awaitingRefresh = false;
        pendingResource = null;
        pendingOnComplete = null;
        // Bumping the token cancels any in-flight timeout for the
        // run we're tearing down.
        timeoutToken++;
    }
}
