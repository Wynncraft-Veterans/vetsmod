package org.wynnvets.distribute;

import com.wynntils.core.components.Managers;
import com.wynntils.core.text.StyledText;
import com.wynntils.utils.mc.McUtils;
import com.wynntils.utils.wynn.ContainerUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
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
 * <p>Each press is spaced by {@link #PRESS_DELAY_TICKS} so the server has
 * time to process and refresh the menu between presses, and the current
 * container id is re-read from the screen on every iteration &mdash;
 * Wynncraft assigns a new container id when it refreshes the menu after
 * each press, so using the search-time id would silently drop the second
 * and subsequent packets.</p>
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

    /** Ticks between consecutive presses. Wynncraft refreshes the Members
     *  menu after each send action; a too-fast follow-up can land before
     *  the server is ready. A few ticks of spacing matches the cadence a
     *  human would use. */
    private static final int PRESS_DELAY_TICKS = 4;

    private MemberDistributor() {}

    /**
     * Fires {@code count} consecutive {@code pressKeyOnSlot} packets
     * against {@code slot} in the currently-open Members container,
     * spaced by {@link #PRESS_DELAY_TICKS} ticks. Aborts silently if the
     * Members screen is closed or replaced mid-loop.
     */
    public static void fire(int slot, Resource resource, int count, String recipientName) {
        if (count <= 0) return;
        ChatUtils.sendLocalMessage(
                Component.literal("Sending " + count + "x " + resource.displayName()
                        + " to " + recipientName + "…")
                        .withStyle(ChatFormatting.AQUA));
        firePress(slot, resource, count, 0);
    }

    private static void firePress(int slot, Resource resource, int total, int sent) {
        if (sent >= total) {
            VetsLogger.debug("MemberDistributor: completed {} presses on slot {}",
                    total, slot);
            closeMembersScreen();
            return;
        }

        AbstractContainerScreen<?> screen = currentMembersScreen();
        if (screen == null) {
            VetsLogger.debug("MemberDistributor: members screen gone after {}/{} presses",
                    sent, total);
            return;
        }

        int containerId = screen.getMenu().containerId;
        List<ItemStack> items = screen.getMenu().getItems();
        ContainerUtils.pressKeyOnSlot(slot, containerId, resource.hotbarButton(), items);
        VetsLogger.debug("MemberDistributor: sent press {}/{} on slot {} of container {}",
                sent + 1, total, slot, containerId);

        Managers.TickScheduler.scheduleLater(
                () -> firePress(slot, resource, total, sent + 1),
                PRESS_DELAY_TICKS);
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
