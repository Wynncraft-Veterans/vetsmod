package org.wynnvets.chat;

import java.util.List;
import net.minecraft.ChatFormatting;
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
import org.wynnvets.fetcher.polling.SupportersPoller;
import org.wynnvets.rendering.colors.AnimatedGradientSequence;

/**
 * Centralized utility for sending formatted chat messages to the local player.
 * Heavily inspired by pixlze's guild-api
 *
 * <p>All mod-initiated chat output should go through this class. Its send methods
 * prefix a {@link Prepend} badge; the guild-style ones produce
 * {@code <badge> <pill> <username>: <message>}.</p>
 */
public final class ChatUtils {

    private static final ThreadLocal<Boolean> INTERNAL_CHAT_DISPATCH =
            ThreadLocal.withInitial(() -> false);

    /**
     * Style used for the rank pill, separator and message body of guild chat (the
     * counterpart of {@link #HONOURARY_RANK_STYLE}).
     */
    public static final Style RANK_STYLE = Style.EMPTY.withColor(ChatFormatting.AQUA);

    /** Style used for the display-name portion of guild chat. */
    public static final Style NAME_STYLE = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA);

    /** Style used for the rank pill / separator / message body of honourary chat. */
    public static final Style HONOURARY_RANK_STYLE =
            Style.EMPTY.withColor(ChatFormatting.DARK_AQUA);

    /** Style used for the display-name portion of honourary chat.
     *  Off-by-one from ChatFormatting.AQUA (0x55FFFF) so hspmod's outer color
     *  gate (Set.of(5636095)) doesn't match this component when an HSP-guild
     *  honourary user runs both vetsmod and hspmod simultaneously — otherwise
     *  vets bridge lines leak into HSP's chat bridge on the compact
     *  continuation indicator (which shares its PUA with Wynn's guild-cont
     *  glyph). Visually indistinguishable to the eye; preserves the rank/name
     *  inversion this class's honourary-render docstring calls out. */
    public static final Style HONOURARY_NAME_STYLE =
            Style.EMPTY.withColor(TextColor.fromRgb(0x56FFFF));

    /** Red style for the {@code /v} staff-channel pill frame, separator and message body. */
    public static final Style ADMIN_RANK_STYLE = Style.EMPTY.withColor(ChatFormatting.RED);

    /** Dark-red style for {@code /v} staff-channel display names. */
    public static final Style ADMIN_NAME_STYLE = Style.EMPTY.withColor(ChatFormatting.DARK_RED);

    private ChatUtils() {}

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
     * Same as {@link #sendLocalMessage(Component)} but marks this as the
     * start of a new render block first ({@link Prepend#resetDedup()}).
     * The next badge dedup hit re-extends to the full banner, giving the player a visual
     * divider between back-to-back command outputs.
     * Use for the *first* line of any new command response; subsequent
     * lines in the same block should keep using plain
     * {@link #sendLocalMessage(Component)} so they demote to the compact
     * indicator as usual.
     */
    public static void sendLocalMessageNewBlock(Component message) {
        Prepend.resetDedup();
        sendLocalMessage(message, Prepend.DEFAULT);
    }

    /**
     * Sends a message to the player's chat with the given badge prepended.
     *
     * @param message the message component to display
     * @param prepend the badge to prepend
     */
    public static void sendLocalMessage(Component message, Prepend prepend) {
        MutableComponent badge = prepend.get();
        MutableComponent full = Component.empty().append(badge).append(message);

        dispatchToChat(full, badge.getStyle());
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

        // Convert an ASCII rank label (e.g. "Returner", as OutboundDisplayHandler passes it) to PUA
        // pill characters. A rank that is already PUA-encoded (the waitlist self-echo's) passes
        // through.
        String pillText = normalizedRank.isEmpty() ? "" : encodePillIfAscii(normalizedRank);

        MutableComponent body = Component.empty();

        boolean isSupporter = !pillText.isEmpty() && SupportersPoller.isSupporter(displayName);

        if (!pillText.isEmpty()) {
            body.append(PillFormatter.formatPill(pillText, displayName, isSupporter)).append(" ");
        }

        body.append(Component.literal(displayName).setStyle(NAME_STYLE))
                .append(Component.literal(": ").setStyle(RANK_STYLE))
                .append(MessageBodyFormatter.formatMessageBody(message, RANK_STYLE));

        MutableComponent full = Component.empty().append(badge).append(body);

        Style badgeStyle = badge.getStyle();
        if (isSupporter) {
            dispatchAnimatedChat(full, badgeStyle);
        } else {
            dispatchToChat(full, badgeStyle);
        }
    }

    /**
     * Sends an honourary-chat–style message:
     * {@code &9<guild badge> &3<rank> <displayName>&3: <message>}, with the name in
     * {@link #HONOURARY_NAME_STYLE}: a near-{@code &b} that deliberately is not {@code &b}
     * (see that field).
     *
     * <p>Used for outgoing {@code /wg} echoes and, while this client is
     * honourary-unlocked, for the relayed chat lines
     * {@link OutboundDisplayHandler} renders as ordinary chat &mdash; a choice
     * keyed on the viewer's own unlock state, not on the frame's
     * {@code type}. An honourary socket would get only {@code honourary} and
     * {@code bridge} chat from temporary-server's tier filter. Today no filter
     * applies, because vetsmod never authenticates its outbound socket
     * ({@code outbound-socket-never-authenticated}): while the server's
     * {@code unauth} toggle is on, an honourary-unlocked client can render any
     * relayed chat type here, and an {@code honourary} frame that another
     * viewer displays goes through {@link #sendGuildChatMessage} instead. The
     * badge uses the {@code U+E013} glyph instead of the regular
     * {@code U+E006}, and the colour scheme inverts the rank/name relationship
     * so honourary chat is distinguishable from Returners guild chat.</p>
     */
    public static void sendHonouraryChatMessage(String rank, String displayName, String message) {
        MutableComponent badge = Prepend.GUILD_HONOURARY.get();
        String normalizedRank = rank == null ? "" : rank.trim();

        String pillText = normalizedRank.isEmpty() ? "" : encodePillIfAscii(normalizedRank);

        MutableComponent body = Component.empty();

        boolean isSupporter = !pillText.isEmpty() && SupportersPoller.isSupporter(displayName);

        if (!pillText.isEmpty()) {
            body.append(
                            PillFormatter.formatPill(
                                    pillText, displayName, HONOURARY_RANK_STYLE, isSupporter))
                    .append(" ");
        }

        body.append(Component.literal(displayName).setStyle(HONOURARY_NAME_STYLE))
                .append(Component.literal(": ").setStyle(HONOURARY_RANK_STYLE))
                .append(MessageBodyFormatter.formatMessageBody(message, HONOURARY_RANK_STYLE));

        MutableComponent full = Component.empty().append(badge).append(body);

        Style badgeStyle = badge.getStyle();
        if (isSupporter) {
            dispatchAnimatedChat(full, badgeStyle);
        } else {
            dispatchToChat(full, badgeStyle);
        }
    }

    /**
     * Sends a guild-chat–style message in the red {@code /v} staff-channel styling with a
     * custom pre-styled rank pill component.
     */
    public static void sendGuildChatMessageRed(
            Component rankComponent, String displayName, String message) {
        MutableComponent badge =
                Prepend.GUILD.get().withStyle(style -> style.withColor(ChatFormatting.RED));

        MutableComponent body = Component.empty();

        if (rankComponent != null && !rankComponent.getString().trim().isEmpty()) {
            body.append(rankComponent).append(" ");
        }

        body.append(Component.literal(displayName).setStyle(ADMIN_NAME_STYLE))
                .append(Component.literal(": ").setStyle(ADMIN_RANK_STYLE))
                .append(MessageBodyFormatter.formatMessageBody(message, ADMIN_RANK_STYLE));

        MutableComponent full = Component.empty().append(badge).append(body);

        dispatchToChat(full, badge.getStyle());
    }

    /**
     * Sends a guild-chat–style message where the body is a pre-built component.
     * Used when the message body requires special formatting (e.g. animated text)
     * that cannot be expressed as a plain string.
     *
     * @param rank        the rank text (pill); may be empty
     * @param displayName the player display name
     * @param bodyComponent the pre-built message body component
     */
    public static void sendGuildChatMessageWithBody(
            String rank, String displayName, Component bodyComponent) {
        MutableComponent badge = Prepend.GUILD.get();
        String normalizedRank = rank == null ? "" : rank.trim();

        String pillText = normalizedRank.isEmpty() ? "" : encodePillIfAscii(normalizedRank);

        MutableComponent body = Component.empty();

        boolean isSupporter = !pillText.isEmpty() && SupportersPoller.isSupporter(displayName);

        if (!pillText.isEmpty()) {
            body.append(PillFormatter.formatPill(pillText, displayName, isSupporter)).append(" ");
        }

        body.append(Component.literal(displayName).setStyle(NAME_STYLE))
                .append(Component.literal(": ").setStyle(RANK_STYLE))
                .append(bodyComponent);

        MutableComponent full = Component.empty().append(badge).append(body);

        dispatchToChat(full, badge.getStyle());
    }

    /**
     * Renders a /v staff-channel line; both the sender's own echo and received /v lines
     * come through here. With no known staff rank the pill reads {@code "staff"}, the
     * Strategist tag (Captain was retired in the 2026-07 permission restructure).
     */
    public static void sendStaffChannelMessage(String displayName, String message, String rank) {
        sendGuildChatMessageRed(buildStaffPillComponent(rank), displayName, message);
    }

    /**
     * Builds a mixed red/dark staff pill glyph sequence.
     *
     * <p>2026-07 permission restructure: the pill label is now the
     * {@link RankDisplayMap#vTagFor(String) /v tag} for the given rank
     * ({@code staff} for strategists, {@code owner} for chiefs / owners).
     * Falls back to {@code "staff"} for any rank that isn't a known staff tier: {@code /v}
     * chat is meant to be staff-only, so every participant should qualify for at least the
     * baseline Staff tag. Today the receive path does not check that a lock-prefixed sender
     * is staff; see {@code staff-channel-rewriter-renders-unverified-senders-as-staff}.</p>
     */
    public static Component buildStaffPillComponent(String rank) {
        String vTag = RankDisplayMap.vTagFor(rank);
        String label = vTag != null ? vTag : "staff";
        return buildFramedPill(label, ADMIN_RANK_STYLE.withoutShadow());
    }

    /**
     * Builds a dark-on-light pill using the frame + letter glyphs in
     * the default font: a coloured frame with black letters inside. This is the pill
     * {@link #buildStaffPillComponent(String)} uses; {@link Prepend#DEFAULT}'s [Vetsmod] pill is
     * among those that build the same frame by hand (with dark-grey letters).
     *
     * <p>Introduced in the 2026-07 permission restructure so the guild
     * chat rewriter can produce a local dark-on-light pill for chat
     * that arrived via Wynncraft's actual guild channel (as opposed to the light-on-dark remote
     * pill, also PUA-encoded, that {@link #encodePillIfAscii(String)} builds for chat relayed over
     * vetsmod's WebSocket; see {@link PillCodec} for both styles). Today
     * {@link org.wynnvets.chat.rewriter.EncourageUpdateRewriter EncourageUpdateRewriter} gives a
     * guild-channel line that pill too ({@code raw-rank-pills-bypass-display-remap}).</p>
     *
     * @param label       the ASCII label rendered inside the frame (case-insensitive)
     * @param frameStyle  the style applied to the frame glyphs; the
     *                    letters are always styled BLACK for legibility
     * @return a styled pill component
     */
    public static MutableComponent buildFramedPill(String label, Style frameStyle) {
        return PillCodec.encodeLocal(label, frameStyle);
    }

    /**
     * Encodes an ASCII rank name to PUA pill characters (E040 letter range with E06B/E06C frame):
     * {@link PillCodec}'s remote style, which renders in the default font. Strings that already
     * contain PUA codepoints are returned as-is.
     *
     * <p>Within vetsmod only this class calls it, from the send methods that turn a rank string
     * into a remote-style pill. {@link org.wynnvets.chat.rewriter.ServerGuildChatRewriter
     * ServerGuildChatRewriter} does not: it builds a local dark-on-light pill with
     * {@link #buildFramedPill(String, Style)} instead.</p>
     */
    public static String encodePillIfAscii(String text) {
        return PillCodec.encodeRemote(text);
    }

    // ── Legacy-text formatting ─────────────────────────────────────────

    /**
     * Builds a fresh {@link MutableComponent} from text containing legacy
     * {@code §}-formatting codes, detecting URLs and making them clickable, and
     * rendering PUA-encoded spoiler blocks as spoiler labels (see
     * {@link MessageBodyFormatter#formatMessageBody}).
     * Bare hosts (e.g. {@code example.com/path}) are displayed as-typed but
     * linked to {@code https://}-prefixed URIs. URL segments inherit the
     * accumulated legacy style (color, bold, underline, …) from the codes
     * preceding them, since vanilla {@code §}-state does not carry across
     * sibling components.
     */
    public static MutableComponent literalWithUrls(String text, Style baseStyle) {
        MutableComponent parent = Component.empty();
        if (text.isEmpty()) {
            return parent;
        }
        Style currentStyle = baseStyle;
        StringBuilder buffer = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                if (buffer.length() > 0) {
                    MessageBodyFormatter.appendTextWithUrls(
                            parent, buffer.toString(), currentStyle);
                    buffer.setLength(0);
                }
                currentStyle =
                        applyLegacyCode(
                                baseStyle, currentStyle, Character.toLowerCase(text.charAt(i + 1)));
                i += 2;
                continue;
            }
            buffer.append(c);
            i++;
        }
        if (buffer.length() > 0) {
            MessageBodyFormatter.appendTextWithUrls(parent, buffer.toString(), currentStyle);
        }
        return parent;
    }

    /**
     * Apply a single legacy {@code §X} code to the accumulating style. Color
     * codes reset to {@code baseStyle} plus the new colour (vanilla clears the format
     * flags instead; the two agree when {@code baseStyle} carries none, as
     * {@code Style.EMPTY} does); format codes (k/l/m/n/o) add to whatever
     * {@code currentStyle} already has; {@code §r} resets to {@code baseStyle}.
     */
    private static Style applyLegacyCode(Style baseStyle, Style currentStyle, char code) {
        switch (code) {
            case '0':
                return baseStyle.withColor(ChatFormatting.BLACK);
            case '1':
                return baseStyle.withColor(ChatFormatting.DARK_BLUE);
            case '2':
                return baseStyle.withColor(ChatFormatting.DARK_GREEN);
            case '3':
                return baseStyle.withColor(ChatFormatting.DARK_AQUA);
            case '4':
                return baseStyle.withColor(ChatFormatting.DARK_RED);
            case '5':
                return baseStyle.withColor(ChatFormatting.DARK_PURPLE);
            case '6':
                return baseStyle.withColor(ChatFormatting.GOLD);
            case '7':
                return baseStyle.withColor(ChatFormatting.GRAY);
            case '8':
                return baseStyle.withColor(ChatFormatting.DARK_GRAY);
            case '9':
                return baseStyle.withColor(ChatFormatting.BLUE);
            case 'a':
                return baseStyle.withColor(ChatFormatting.GREEN);
            case 'b':
                return baseStyle.withColor(ChatFormatting.AQUA);
            case 'c':
                return baseStyle.withColor(ChatFormatting.RED);
            case 'd':
                return baseStyle.withColor(ChatFormatting.LIGHT_PURPLE);
            case 'e':
                return baseStyle.withColor(ChatFormatting.YELLOW);
            case 'f':
                return baseStyle.withColor(ChatFormatting.WHITE);
            case 'k':
                return currentStyle.withObfuscated(true);
            case 'l':
                return currentStyle.withBold(true);
            case 'm':
                return currentStyle.withStrikethrough(true);
            case 'n':
                return currentStyle.withUnderlined(true);
            case 'o':
                return currentStyle.withItalic(true);
            case 'r':
                return baseStyle;
            default:
                return currentStyle;
        }
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
        Minecraft minecraft = Minecraft.getInstance();
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
        Minecraft minecraft = Minecraft.getInstance();
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
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        minecraft.execute(
                () -> {
                    if (minecraft.player != null) {
                        Component wrapped = wrapBlockMessage(message, prependStyle);
                        countRenderedLines(wrapped);
                        boolean previous = INTERNAL_CHAT_DISPATCH.get();
                        INTERNAL_CHAT_DISPATCH.set(true);
                        AnimatedGradientSequence.beginAnimation(
                                AnimatedGradientSequence.effectiveDefaultStart(),
                                AnimatedGradientSequence.effectiveDefaultEnd(),
                                AnimatedGradientSequence.DEFAULT_CYCLE_TIME_MS);
                        try {
                            minecraft.player.displayClientMessage(wrapped, false);
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
     * <p>Used by chat rewriters to dispatch rebuilt messages directly
     * without going through the high-level message-building API.</p>
     *
     * @param message      the full message component
     * @param prependStyle the badge style used for continuation-line block markers
     */
    public static void dispatchToChat(Component message, Style prependStyle) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        minecraft.execute(
                () -> {
                    if (minecraft.player != null) {
                        Component wrapped = wrapBlockMessage(message, prependStyle);
                        countRenderedLines(wrapped);
                        boolean previous = INTERNAL_CHAT_DISPATCH.get();
                        INTERNAL_CHAT_DISPATCH.set(true);
                        try {
                            minecraft.player.displayClientMessage(wrapped, false);
                        } finally {
                            INTERNAL_CHAT_DISPATCH.set(previous);
                        }
                    }
                });
    }
}
