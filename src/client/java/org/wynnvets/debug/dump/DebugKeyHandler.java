package org.wynnvets.debug.dump;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.debug.DebugConfigManager;
import org.wynnvets.items.LegacyItemHandler;

/**
 * Polls the numpad {@code +} key each client tick and, when
 * {@link DebugConfigManager#ITEM_DUMP} is enabled, dumps the hovered
 * item via {@link ItemDumpHandler}.
 *
 * <p>This is a <b>debug-only</b> utility.  Call {@link #register()}
 * once during client initialisation.</p>
 */
public final class DebugKeyHandler {

    /** Prevent accidental duplicate event registration. */
    private static boolean registered = false;

    /** Tracks previous frame's key state so we fire once per press. */
    private static boolean itemDumpKeyWasDown = false;

    /**
     * When focus is lost, ignore key presses until we observe a release after
     * focus returns. This prevents stale key state from triggering a dump.
     */
    private static boolean suppressUntilKeyRelease = false;

    private DebugKeyHandler() {}

    /**
     * Registers the tick handler.  Safe to call multiple times (idempotent
     * in practice because Fabric appends listeners).
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!VetsConfig.get(DebugConfigManager.ITEM_DUMP)) {
                itemDumpKeyWasDown = false;
                suppressUntilKeyRelease = false;
                return;
            }

            if (client == null || client.getWindow() == null) {
                itemDumpKeyWasDown = false;
                suppressUntilKeyRelease = true;
                return;
            }

            long window = client.getWindow().handle();
            if (window == 0L) {
                itemDumpKeyWasDown = false;
                suppressUntilKeyRelease = true;
                return;
            }

            boolean isFocused = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
            if (!isFocused) {
                itemDumpKeyWasDown = false;
                suppressUntilKeyRelease = true;
                return;
            }

            boolean isDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_ADD) == GLFW.GLFW_PRESS;

            if (suppressUntilKeyRelease) {
                if (!isDown) {
                    suppressUntilKeyRelease = false;
                }
                itemDumpKeyWasDown = isDown;
                return;
            }

            if (isDown && !itemDumpKeyWasDown) {
                ItemStack hovered = LegacyItemHandler.currentItemStack;
                if (hovered != null && !hovered.isEmpty()) {
                    ItemDumpHandler.dump(hovered);
                } else {
                    ChatUtils.sendLocalMessage(
                        Component.literal("No item under cursor. Hover over an item in an inventory.")
                            .withStyle(ChatFormatting.YELLOW));
                }
            }
            itemDumpKeyWasDown = isDown;
        });
    }
}
