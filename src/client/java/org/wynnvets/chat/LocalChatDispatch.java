package org.wynnvets.chat;

import com.wynntils.utils.mc.McUtils;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.wynnvets.rendering.colors.AnimatedGradientSequence;

/**
 * Puts a mod-built chat line on the player's chat HUD. It wraps the line in Wynncraft's block style
 * for the chat width, records how many lines it renders for {@link Prepend}'s badge deduplication,
 * and displays it on the client thread, dropping it while there is no player.
 *
 * <p>While the line is displayed, an internal-dispatch flag is set on that thread, so the chat
 * hooks the line passes through can tell it from server chat: they do not log it, parse it as a
 * guild check, run the rewriter chain on it or relay it as guild chat. The flag's previous value
 * is restored in a {@code finally} block, and the restore must stay there: a display that threw
 * would otherwise leave the flag set, and every later chat line on the thread would be taken for
 * the mod's own.</p>
 *
 * <p>The animated dispatch also sets the animated gradient context around the display and ends it
 * in the same {@code finally} block, but that context is not what animates the line; see
 * {@link #dispatchAnimatedChat}.</p>
 *
 * <p>{@link Prepend}'s badge dedup state is confined to the render thread by
 * {@link #dispatchBadged}: the badge is chosen, the new-block reset applied and the rendered lines
 * counted inside one {@code minecraft.execute} task, so decisions and counts keep the order of
 * the tasks. {@code Minecraft.execute} runs a task inline when it is called on the render thread
 * outside any running task, and queues it otherwise (from any other thread, or from inside a
 * running task such as packet handling).</p>
 */
public final class LocalChatDispatch {

    private static final ThreadLocal<Boolean> INTERNAL_CHAT_DISPATCH =
            ThreadLocal.withInitial(() -> false);

    private LocalChatDispatch() {}

    /**
     * Returns whether the current thread is dispatching a mod-generated chat message.
     */
    public static boolean isInternalDispatch() {
        return Boolean.TRUE.equals(INTERNAL_CHAT_DISPATCH.get());
    }

    // ── Block-message wrapping ──────────────────────────────────────────

    /**
     * Wraps a message in Wynncraft's block-message style: continuation lines are
     * prefixed with the compact block indicator ({@code \uDAFF\uDFFC\uE001\uDB00\uDC06})
     * in the {@code chat/prefix} font, matching the visual style of server-wrapped
     * guild chat.
     *
     * <p>Uses {@link StringSplitter#splitLines(FormattedText, int, Style)}
     * to word-wrap to the current chat width.</p>
     *
     * @param message      the full message component (badge + body)
     * @param prependStyle the style for the continuation prefix (inherits colour from the badge)
     * @return the wrapped message with {@code \n}-separated lines
     */
    private static Component wrapBlockMessage(Component message, Style prependStyle) {
        Minecraft minecraft = McUtils.mc();
        Font font = minecraft.font;
        StringSplitter splitter = font.getSplitter();

        int chatWidth = ChatComponent.getWidth(minecraft.options.chatWidth().get());

        // Build the continuation prefix: block marker + space, styled like the badge
        Style continuationStyle = MessageBodyFormatter.CHAT_PREFIX_STYLE;
        TextColor prependColor = prependStyle.getColor();
        if (prependColor != null) {
            continuationStyle = continuationStyle.withColor(prependColor);
        }
        MutableComponent continuation =
                Component.literal(MessageBodyFormatter.GUILD_PREPEND_COMPACT)
                        .append(" ")
                        .setStyle(continuationStyle);

        List<FormattedText> lines = splitter.splitLines(message, chatWidth, message.getStyle());

        if (lines.size() <= 1) {
            return message;
        }

        MutableComponent wrapped = toComponent(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            wrapped.append("\n");
            wrapped.append(continuation.copy()).append(toComponent(lines.get(i)));
        }
        return wrapped;
    }

    /**
     * Converts a {@link FormattedText} back to a {@link MutableComponent}.
     */
    private static MutableComponent toComponent(FormattedText text) {
        MutableComponent result = Component.empty();
        text.visit(
                (style, content) -> {
                    if (!content.isEmpty()) {
                        result.append(Component.literal(content).setStyle(style));
                    }
                    return java.util.Optional.empty();
                },
                Style.EMPTY);
        return result;
    }

    /**
     * Counts the number of rendered lines a component will occupy in chat and
     * updates {@link Prepend}'s badge deduplication counter.
     */
    private static void countRenderedLines(Component message) {
        Minecraft minecraft = McUtils.mc();
        int chatWidth = ChatComponent.getWidth(minecraft.options.chatWidth().get());
        int lineCount =
                ComponentRenderUtils.wrapComponents(message, chatWidth, minecraft.font).size();
        Prepend.addRenderedLines(lineCount);
    }

    // ── Dispatch ───────────────────────────────────────────────────────

    /**
     * Dispatches a component with the animated gradient context set. That context is not what
     * animates it: {@link org.wynnvets.mixin.client.chat.AnimatedChatMixin AnimatedChatMixin}
     * wraps every newly inserted chat line without reading the context, and the wrapper recolours
     * only characters carrying a marker colour. The gradient comes from
     * {@link AnimatedGradientSequence#effectiveDefaultStart()} and
     * {@link AnimatedGradientSequence#effectiveDefaultEnd()}
     * ({@link org.wynnvets.rendering.colors.ShaderColorPalette#DARK_AQUA
     * ShaderColorPalette.DARK_AQUA}, {@code 0x55FFFF}, to {@code 0xAADDFF}, or the
     * colour-blind pair) on a 3 s cycle, the same values this method passes.
     *
     * @param message      the full message component
     * @param prependStyle the badge style used for continuation-line block markers
     */
    public static void dispatchAnimatedChat(Component message, Style prependStyle) {
        Minecraft minecraft = McUtils.mc();
        if (minecraft.player == null) {
            return;
        }

        minecraft.execute(
                () -> {
                    if (minecraft.player != null) {
                        Component wrapped = wrapBlockMessage(message, prependStyle);
                        countRenderedLines(wrapped);
                        displayInternal(minecraft, wrapped, true);
                    }
                });
    }

    /**
     * Thread-safely dispatches a component to the player's chat HUD.
     * If the player is not yet available the message is silently dropped.
     *
     * <p>Used by chat rewriters to dispatch rebuilt messages directly
     * without going through the high-level message-building API.</p>
     *
     * @param message      the full message component
     * @param prependStyle the badge style used for continuation-line block markers
     */
    public static void dispatchToChat(Component message, Style prependStyle) {
        Minecraft minecraft = McUtils.mc();
        if (minecraft.player == null) {
            return;
        }

        minecraft.execute(
                () -> {
                    if (minecraft.player != null) {
                        Component wrapped = wrapBlockMessage(message, prependStyle);
                        countRenderedLines(wrapped);
                        displayInternal(minecraft, wrapped, false);
                    }
                });
    }

    /**
     * Dispatches a line that opens with {@code prepend}'s badge, choosing the badge on the render
     * thread. The single {@code minecraft.execute} task resets the dedup first when
     * {@code newBlock} is set, then takes {@link Prepend#get()}, builds {@code badge + body},
     * wraps it, counts its rendered lines and displays it, so the dedup decision, the count and
     * the display are one step in task order and {@link Prepend}'s static state is only touched on
     * the render thread. If there is no player the line is dropped without touching the dedup
     * state.
     *
     * @param prepend  the badge to open the line with
     * @param body     the line after the badge
     * @param animated whether to display it with the animated gradient context set
     *                 (see {@link #dispatchAnimatedChat})
     * @param newBlock whether to {@link Prepend#resetDedup()} first, so this line gets the full
     *                 badge
     */
    public static void dispatchBadged(
            Prepend prepend, Component body, boolean animated, boolean newBlock) {
        Minecraft minecraft = McUtils.mc();
        if (minecraft.player == null) {
            return;
        }

        minecraft.execute(
                () -> {
                    if (minecraft.player != null) {
                        if (newBlock) {
                            Prepend.resetDedup();
                        }
                        MutableComponent badge = prepend.get();
                        Component full = Component.empty().append(badge).append(body);
                        Component wrapped = wrapBlockMessage(full, badge.getStyle());
                        countRenderedLines(wrapped);
                        displayInternal(minecraft, wrapped, animated);
                    }
                });
    }

    /**
     * Displays {@code wrapped} with the internal-dispatch flag set, and, when {@code animated},
     * the animated gradient context. Both are restored in the {@code finally} block. Render
     * thread only, with a non-null player.
     */
    private static void displayInternal(Minecraft minecraft, Component wrapped, boolean animated) {
        boolean previous = INTERNAL_CHAT_DISPATCH.get();
        INTERNAL_CHAT_DISPATCH.set(true);
        if (animated) {
            AnimatedGradientSequence.beginAnimation(
                    AnimatedGradientSequence.effectiveDefaultStart(),
                    AnimatedGradientSequence.effectiveDefaultEnd(),
                    AnimatedGradientSequence.DEFAULT_CYCLE_TIME_MS);
        }
        try {
            minecraft.player.displayClientMessage(wrapped, false);
        } finally {
            if (animated) {
                AnimatedGradientSequence.endAnimation();
            }
            INTERNAL_CHAT_DISPATCH.set(previous);
        }
    }
}
