package org.wynnvets.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.wynnvets.fetcher.polling.SupportersPoller;

/**
 * Centralized utility for sending formatted chat messages to the local player.
 * Heavily inspired by pixlze's guild-api
 *
 * <p>Mod-initiated chat output goes through this class's send methods, which prefix a
 * {@link Prepend} badge and hand the line to {@link LocalChatDispatch}; a chat rewriter that has
 * rebuilt a whole line calls {@link LocalChatDispatch} itself. The guild-style send methods
 * produce {@code <badge> <pill> <username>: <message>}.</p>
 */
public final class ChatUtils {

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

        LocalChatDispatch.dispatchToChat(full, badge.getStyle());
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
            LocalChatDispatch.dispatchAnimatedChat(full, badgeStyle);
        } else {
            LocalChatDispatch.dispatchToChat(full, badgeStyle);
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
            LocalChatDispatch.dispatchAnimatedChat(full, badgeStyle);
        } else {
            LocalChatDispatch.dispatchToChat(full, badgeStyle);
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

        LocalChatDispatch.dispatchToChat(full, badge.getStyle());
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

        LocalChatDispatch.dispatchToChat(full, badge.getStyle());
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
}
