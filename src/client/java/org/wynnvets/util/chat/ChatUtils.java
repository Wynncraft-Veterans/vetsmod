package org.wynnvets.util.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.util.colors.AnimatedGradientSequence;
import org.wynnvets.util.colors.ShaderColorPalette;

/**
 * Centralized utility for sending formatted chat messages to the local player.
 * Heavily inspired by pixlze's guild-api
 *
 * <p>All mod-initiated chat output should go through this class so that messages
 * are consistently formatted: {@code <badge> <pill> <username>: <message>}.</p>
 */
public final class ChatUtils {

    private static final ThreadLocal<Boolean> INTERNAL_CHAT_DISPATCH = ThreadLocal.withInitial(() -> false);

    /** Style used for rank "pill" text. */
    public static final Style RANK_STYLE = Style.EMPTY.withColor(ChatFormatting.AQUA);

    /** Style used for the display-name portion of guild chat. */
    public static final Style NAME_STYLE = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA);

    private ChatUtils() {
    }

    /**
     * Returns whether the current thread is dispatching a mod-generated chat message.
     */
    public static boolean isInternalDispatch() {
        return Boolean.TRUE.equals(INTERNAL_CHAT_DISPATCH.get());
    }

    // ── Simple messages ────────────────────────────────────────────────

    /**
     * Sends a message to the player's chat with a {@link Prepend#DEFAULT} badge.
     *
     * @param message the message component to display
     */
    public static void sendLocalMessage(Component message) {
        sendLocalMessage(message, Prepend.DEFAULT);
    }

    /**
     * Sends a message to the player's chat with the given badge prepended.
     *
     * @param message the message component to display
     * @param prepend the badge to prepend
     */
    public static void sendLocalMessage(Component message, Prepend prepend) {
        MutableComponent full = Component.empty()
                .append(prepend.get())
                .append(message);

        dispatchToChat(full);
    }

    // ── Guild-style messages (<badge> <pill> <username>: <message>) ───

    /**
     * Sends a guild-chat–style message:
     * {@code <guild badge> <rank> <displayName>: <message>}.
     *
     * @param rank        the rank text (pill); may be empty
     * @param displayName the player display name
     * @param message     the chat message body
     */
    public static void sendGuildChatMessage(String rank, String displayName, String message) {
        MutableComponent badge = Prepend.GUILD.get();
        String normalizedRank = rank == null ? "" : rank.trim();

        MutableComponent body = Component.empty();

        boolean isSupporter = !normalizedRank.isEmpty()
                && PillFormatter.isSupporterPill(displayName);

        if (!normalizedRank.isEmpty()) {
            body.append(PillFormatter.formatPill(normalizedRank, displayName))
                    .append(" ");
        }

        body.append(Component.literal(displayName).setStyle(NAME_STYLE))
                .append(Component.literal(": ").setStyle(RANK_STYLE))
                .append(Component.literal(message).setStyle(RANK_STYLE));

        MutableComponent full = Component.empty()
                .append(badge)
                .append(body);

        if (isSupporter) {
            dispatchAnimatedChat(full);
        } else {
            dispatchToChat(full);
        }
    }

    // ── Internal ───────────────────────────────────────────────────────

    /**
     * Dispatches a component with the animated gradient context active so that
     * {@code AnimatedChatMixin} wraps the stored lines.
     * Uses the supporter gradient (DARK_AQUA → white, 3 s cycle).
     */
    static void dispatchAnimatedChat(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        minecraft.execute(() -> {
            if (minecraft.player != null) {
                boolean previous = INTERNAL_CHAT_DISPATCH.get();
                INTERNAL_CHAT_DISPATCH.set(true);
                AnimatedGradientSequence.beginAnimation(
                    ShaderColorPalette.DARK_AQUA, 0x88FFE9, 3000);
                try {
                    minecraft.player.displayClientMessage(message, false);
                } finally {
                    AnimatedGradientSequence.endAnimation();
                    INTERNAL_CHAT_DISPATCH.set(previous);
                }
            }
        });
    }

    /**
     * Thread-safely dispatches a component to the player's chat HUD.
     * If the player is not yet available the message is silently dropped.
     *
     * <p>Package-private so that {@code ServerGuildChatRewriter} can dispatch
     * rebuilt messages without going through the public message-building API.</p>
     */
    static void dispatchToChat(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        minecraft.execute(() -> {
            if (minecraft.player != null) {
                boolean previous = INTERNAL_CHAT_DISPATCH.get();
                INTERNAL_CHAT_DISPATCH.set(true);
                try {
                    minecraft.player.displayClientMessage(message, false);
                } finally {
                    INTERNAL_CHAT_DISPATCH.set(previous);
                }
            }
        });
    }
}
