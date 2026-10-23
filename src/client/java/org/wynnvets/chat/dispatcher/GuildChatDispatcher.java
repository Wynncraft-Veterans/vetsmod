package org.wynnvets.chat.dispatcher;

import com.wynntils.core.components.Models;
import com.wynntils.models.guild.type.GuildRank;
import com.wynntils.utils.mc.McUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.BannedChars;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.OutboundDisplayHandler;
import org.wynnvets.chat.spoiler.SpoilerCodec;
import org.wynnvets.commands.CautionCommands;
import org.wynnvets.commands.InviteGate;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.fetcher.ondemand.UserInfoFetcher;
import org.wynnvets.fetcher.polling.StaffRanksPoller;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.listeners.ServerConnectionListener;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.queue.QueueStateManager;

/**
 * Handles outbound chat-command interception for guild chat ({@code /g}),
 * honourary chat ({@code /wg}), staff chat ({@code /v}), announcements
 * ({@code /a}), and the encourage command ({@code /encourage}).
 *
 * <p><b>Not exhaustive</b> — {@link #intercept} matches twelve prefixes; read
 * it for the full list. {@code /msg} is not one of them. All of them are
 * intercepted only on Wynncraft.</p>
 *
 * <p>Called from {@link org.wynnvets.mixin.client.chat.GuildChatCommandMixin}
 * to keep mixin code minimal. Each handler returns {@code true} when the
 * original command should be cancelled (intercepted), or {@code false} to
 * let it pass through to the server normally.</p>
 */
public final class GuildChatDispatcher {

    private static final int STAFF_CHAT_MAX_LENGTH = 234;
    private static final String GUILDLESS_SELF_RANK =
            "\uE010\uE056\uE040\uE048\uE053\uE04B\uE048\uE052\uE053\uE011";
    private static final String HONOURARY_SELF_RANK =
            "\uE010\uE047\uE04E\uE04D\uE04E\uE054\uE051\uE040\uE051\uE058\uE011";

    // The mixin intercepts every ClientPacketListener.sendCommand call, not
    // just typed ones, so vetsmod-issued sendCommands would otherwise bounce
    // back through intercept() and re-enter the same handler that issued
    // them (e.g. /wv invite-force \u2192 forceDispatch \u2192 "gu invite ..." \u2192
    // re-intercepted as a typed /gu invite \u2192 InviteGate runs the gate again
    // and blocks the very dispatch the bypass was meant to perform).
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private GuildChatDispatcher() {}

    /**
     * Sends {@code command} via the local player's connection while
     * suppressing this dispatcher for the duration of the call, so the
     * mixin re-entry never reaches the prefix handlers. Use this anywhere
     * vetsmod itself needs to issue a command that would otherwise match
     * one of the intercept patterns.
     *
     * <p>No-op when the player or connection is missing.</p>
     */
    public static void sendCommandBypassed(String command) {
        if (McUtils.player() == null || McUtils.player().connection == null) return;
        BYPASS.set(Boolean.TRUE);
        try {
            McUtils.player().connection.sendCommand(command);
        } finally {
            BYPASS.set(Boolean.FALSE);
        }
    }

    /**
     * Attempts to intercept an outbound chat command. Off Wynncraft (by
     * {@link org.wynnvets.listeners.ServerConnectionListener#onWynncraft()
     * ServerConnectionListener#onWynncraft()}) every command passes
     * through to the server, so the prefixes keep their meaning there.
     *
     * @param command the command string (without leading {@code /})
     * @return {@code true} if the command was handled and should be cancelled;
     *         {@code false} to let it pass through to the server
     */
    public static boolean intercept(String command) {
        // Off Wynncraft (another server, singleplayer) these prefixes belong to that server,
        // so nothing is intercepted.
        if (BYPASS.get() || !ServerConnectionListener.onWynncraft()) return false;
        // /g: on Wynncraft, the handler falls through to vanilla for non-vets users
        // via its internal isReturners / isWaitlistUnlocked / queue checks.
        if (command.regionMatches(true, 0, "g ", 0, 2)) {
            return handleGuildChat(command.substring(2));
        }

        // Past /g, vetsmod stays out of the way on Wynncraft for users who aren't
        // part of the vets community at all -- Wynncraft handles these commands
        // as if the mod weren't installed.
        if (!GuildStateManager.isUnlocked()) {
            return false;
        }

        if (command.regionMatches(true, 0, "wg ", 0, 3)) {
            return handleHonouraryChat(command.substring(3));
        }

        // /v, /a and /encourage are gated on the local rank cache: passthrough
        // silently when it doesn't show staff, opportunistically refreshing
        // so the next attempt has a chance to succeed.
        // The staff commands below match their bare word too (argsAfter): typed
        // chat arrives trimmed, so a bare /v must reach its handler's usage line.
        String args;
        if ((args = argsAfter(command, "v")) != null) {
            if (!staffOrRefresh()) return false;
            return handleStaffChat(args.trim());
        }
        if ((args = argsAfter(command, "a")) != null) {
            if (!staffOrRefresh()) return false;
            return handleAnnounce(args.trim());
        }
        if ((args = argsAfter(command, "encourage")) != null) {
            if (!staffOrRefresh()) return false;
            return handleEncourage(args.trim());
        }
        // The chat-path /wv check is gated on isConfirmedStaff() instead, and
        // refuses with a message rather than passing through.
        if ((args = argsAfter(command, "wv check")) != null) {
            if (!GuildStateManager.isConfirmedStaff()) {
                ChatUtils.sendLocalMessage(
                        Component.literal("/wv check is restricted to authenticated stewards.")
                                .withStyle(ChatFormatting.RED));
                return true;
            }
            return handleWvCheck(args.trim());
        }

        // /gu invite + /guild invite gate. Only Returners members can
        // actually issue invites server-side, so we pass non-Returners
        // typing this command straight through (the server will reject).
        // For Returners members, hand off to InviteGate which fans out the
        // pre-checks and either dispatches the invite or renders a
        // block/warn explanation. Returning true cancels the typed command
        // so the server doesn't see a duplicate when the gate dispatches
        // via sendCommand on the allow path.
        if (command.regionMatches(true, 0, "gu invite ", 0, 10)) {
            if (!GuildStateManager.isReturners()) return false;
            String target = extractFirstToken(command.substring(10));
            if (target.isEmpty()) return false;
            InviteGate.checkInvite(target);
            return true;
        }
        if (command.regionMatches(true, 0, "guild invite ", 0, 13)) {
            if (!GuildStateManager.isReturners()) return false;
            String target = extractFirstToken(command.substring(13));
            if (target.isEmpty()) return false;
            InviteGate.checkInvite(target);
            return true;
        }

        // Caution / warning / eject system. The /caution path runs a
        // server-side preflight that may reply "would_trigger" with a
        // clickable confirm prompt rendered by CautionCommands; the
        // /caution-go path skips the preflight (used by the click
        // handler) and is also the manual-bypass entry point.
        //
        // These can dispatch real /gu kick / /gu rank commands (when a commit's
        // ack reports an eject), so they can't trust the local rank cache -- the
        // WS-auth-derived
        // isConfirmedStaff() is the only signal that gates them.
        // Passthrough silently when not confirmed staff.
        if ((args = argsAfter(command, "caution-go")) != null) {
            if (!GuildStateManager.isConfirmedStaff()) return false;
            CautionCommands.runCautionGo(args);
            return true;
        }
        if ((args = argsAfter(command, "caution")) != null) {
            if (!GuildStateManager.isConfirmedStaff()) return false;
            CautionCommands.runCaution(args);
            return true;
        }
        if ((args = argsAfter(command, "warn")) != null) {
            if (!GuildStateManager.isConfirmedStaff()) return false;
            CautionCommands.runWarn(args);
            return true;
        }
        if ((args = argsAfter(command, "eject")) != null) {
            if (!GuildStateManager.isConfirmedStaff()) return false;
            CautionCommands.runEject(args);
            return true;
        }
        return false;
    }

    /**
     * The arguments after {@code word} in a typed command, ignoring case: {@code ""} when the
     * command is the bare word, the rest after {@code word + " "}, or {@code null} when it is
     * some other command. {@code "caution-go x"} is therefore not {@code "caution"}, and
     * {@code "vote"} is not {@code "v"}.
     */
    // Package-private for unit tests. See GuildChatDispatcherTest.
    static String argsAfter(String command, String word) {
        if (command.equalsIgnoreCase(word)) return "";
        int n = word.length();
        if (command.length() > n
                && command.charAt(n) == ' '
                && command.regionMatches(true, 0, word, 0, n)) {
            return command.substring(n + 1);
        }
        return null;
    }

    private static boolean staffOrRefresh() {
        if (GuildStateManager.isStaff()) return true;
        GuildStateManager.refreshStaffStatusIfNeeded(true);
        return false;
    }

    /** Returns the first whitespace-delimited token of {@code args}, or
     *  empty string if {@code args} has none. Two call sites — the /gu invite
     *  and /guild invite
     *  intercept to pull the target name out of the raw command tail. */
    private static String extractFirstToken(String args) {
        String trimmed = args.trim();
        if (trimmed.isEmpty()) return "";
        int sp = trimmed.indexOf(' ');
        return sp < 0 ? trimmed : trimmed.substring(0, sp);
    }

    // ── /g — Guild chat ─────────────────────────────────────────────────

    /**
     * Handles {@code /g <message>}. Waitlist users, and Returners sitting in a
     * world queue, are relayed over the WebSocket, or refused in red while it is
     * down ({@link #relayConnected()}) or, for a queued Returner, while the guild
     * rank is unknown; any other Returner's line goes
     * to the game server (refused if encoding its spoilers would pass 253
     * characters). Unless spoiler handling is off, spoiler markers are encoded on
     * all three paths. The two relays also strip the banned characters
     * ({@link #relayText(String)}).
     *
     * @return {@code true} to cancel the original command
     */
    private static boolean handleGuildChat(String message) {
        // Waitlist (guildless) relay
        if (GuildStateManager.isGuildless() && GuildStateManager.isWaitlistUnlocked()) {
            VetsLogger.debug("Intercepted /g for waitlist bridge relay");
            String outMessage =
                    !Boolean.FALSE.equals(VetsConfig.getTriState(VetsConfigKeys.HANDLE_SPOILERS))
                            ? SpoilerCodec.encodeSpoilers(message)
                            : message;
            relayWaitlistChat(outMessage);
            return true;
        }

        // Queued Returners relay: the game server drops /g while the client sits
        // in a world queue.  Route the message through the WebSocket bridge so
        // Discord and other vetsmod clients still see it.  See QueueDetector.
        if (GuildStateManager.isReturners() && QueueStateManager.isInQueue()) {
            VetsLogger.debug("Intercepted /g for queued-Returners bridge relay");
            String outMessage =
                    !Boolean.FALSE.equals(VetsConfig.getTriState(VetsConfigKeys.HANDLE_SPOILERS))
                            ? SpoilerCodec.encodeSpoilers(message)
                            : message;
            relayQueuedReturnersChat(outMessage);
            return true;
        }

        // Returners spoiler encoding
        if (GuildStateManager.isReturners()
                && !Boolean.FALSE.equals(VetsConfig.getTriState(VetsConfigKeys.HANDLE_SPOILERS))
                && SpoilerCodec.containsPipeSpoiler(message)) {
            String encoded = SpoilerCodec.encodeSpoilers(message);
            if (encoded.length() > 253) {
                ChatUtils.sendLocalMessage(
                        Component.literal(
                                        "Encoding your spoilers would exceed Wynn's 253 character limit.")
                                .withStyle(ChatFormatting.RED));
                return true;
            }
            sendCommandBypassed("g " + encoded);
            return true;
        }

        return false; // let normal /g pass through
    }

    // ── /wg — Honourary guild chat ──────────────────────────────────────

    private static boolean handleHonouraryChat(String message) {
        if (GuildStateManager.isHonouraryUnlocked()) {
            VetsLogger.debug("Intercepted /wg for honourary bridge relay");
            String outMessage =
                    !Boolean.FALSE.equals(VetsConfig.getTriState(VetsConfigKeys.HANDLE_SPOILERS))
                            ? SpoilerCodec.encodeSpoilers(message)
                            : message;
            relayHonouraryChat(outMessage);
        } else {
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    "This feature is restricted to honourary users. If you think you"
                                            + " should have /wg, follow ~vetsmod in #bot-commands"
                                            + " to fix your perms.")
                            .withStyle(ChatFormatting.RED));
        }
        return true;
    }

    // ── /v — Staff chat ─────────────────────────────────────────────────

    private static boolean handleStaffChat(String message) {
        if (Models.StreamerMode.isInStream()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Cannot send /v messages while in /stream mode.")
                            .withStyle(ChatFormatting.RED));
            return true;
        }

        if (message.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Usage: /v <message>").withStyle(ChatFormatting.RED));
            return true;
        }

        if (message.length() > STAFF_CHAT_MAX_LENGTH) {
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    "/v messages are limited to "
                                            + STAFF_CHAT_MAX_LENGTH
                                            + " characters.")
                            .withStyle(ChatFormatting.RED));
            return true;
        }

        dispatchStaffChat(message);
        return true;
    }

    // ── /a — Staff announcement ─────────────────────────────────────────

    private static boolean handleAnnounce(String message) {
        if (message.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Usage: /a <message>").withStyle(ChatFormatting.RED));
            return true;
        }

        DispatchCoordinator.executeWithStaffEligibilityGate(
                () -> sendCommandBypassed("g \u203C" + message));
        return true;
    }

    // ── /encourage — Encourage update ───────────────────────────────────

    private static boolean handleEncourage(String version) {
        if (version.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Usage: /encourage <version>").withStyle(ChatFormatting.RED));
            return true;
        }

        DispatchCoordinator.executeWithStaffEligibilityGate(
                () ->
                        sendCommandBypassed(
                                "g \u26A0\u26A0\u26A0 If you had vetsmod, you would see a rainbow! For more info, use ~vetsmod in #bot-commands on vetscord. (v"
                                        + version
                                        + ") \u26A0\u26A0\u26A0"));
        return true;
    }

    // ── /wv check — Player lookup ───────────────────────────────────────

    private static boolean handleWvCheck(String playerName) {
        if (playerName.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Usage: /wv check <playerName>")
                            .withStyle(ChatFormatting.RED));
            return true;
        }

        UserInfoFetcher.checkUser(playerName);
        // Append the caution history readout -- same data shown by Discord's
        // ~warnings command. intercept has already refused non-confirmed
        // staff; CautionCommands.runCheckCautions re-checks the same flag
        // defensively and prints nothing.
        CautionCommands.runCheckCautions(playerName);
        return true;
    }

    // ── Shared helpers ──────────────────────────────────────────────────

    /**
     * Strips {@link org.wynnvets.chat.BannedChars BannedChars}' hard and soft sets from a relay's
     * typed text. A relay line skips Wynncraft's chat filter, and its local echo skips the
     * server's too, so vetsmod is the only filter the echo has. Unless spoiler handling is off,
     * the text arrives already spoiler-encoded: this strips the plain parts, and
     * {@code SpoilerCodec} strips spoiler content as it encodes, so keep this after
     * {@code encodeSpoilers}. With spoiler handling off, the raw text, spoilers included, is
     * stripped here whole. Never applied to the rank, whose pill legitimately uses soft-set
     * glyphs.
     *
     * @return the text to relay, or {@code null} when nothing but whitespace is left, in which
     *     case the relay sends and echoes nothing
     */
    private static String relayText(String message) {
        String text = BannedChars.stripAll(message);
        if (text.isBlank()) {
            VetsLogger.debug("Dropped a relay line left blank by the banned-character strip");
            return null;
        }
        return text;
    }

    /**
     * Refuses a relay while the inbound socket is down, with a red line saying the message was not
     * sent. {@link org.wynnvets.api.V1ApiManager#sendInbound V1ApiManager#sendInbound} drops a
     * frame silently then, so without this check the local echo would show a line nobody
     * received. The check is of the socket only. The queue relay also refuses while the guild rank
     * is unknown, but a frame the server rejects for the sender's tier still echoes.
     *
     * @return {@code true} when the relay may go ahead
     */
    private static boolean relayConnected() {
        if (V1ApiManager.isInboundConnected()) return true;
        ChatUtils.sendLocalMessage(
                Component.literal(
                                "Not sent: vetsmod is not connected to the vets API right now."
                                        + " Try again in a few seconds.")
                        .withStyle(ChatFormatting.RED));
        return false;
    }

    private static void relayWaitlistChat(String message) {
        Minecraft minecraft = McUtils.mc();
        if (minecraft.player == null) return;
        if (!relayConnected()) return;
        String text = relayText(message);
        if (text == null) return;

        String username = resolveUsername(minecraft);
        OutboundDisplayHandler.queuePendingSelfMessage(username, text);
        ChatUtils.sendGuildChatMessage(GUILDLESS_SELF_RANK, username, text);
        V1ApiManager.sendInbound("waitlist", "Waitlist", username, text);
    }

    /**
     * Relays a Returners guild message over the WebSocket bridge while the
     * client is sitting in a world queue.  The Wynncraft game server ignores
     * {@code /g} commands in this state, so the message never touches the
     * guild chat channel — we instead mimic the game-server delivery locally
     * and push the message via the v1 inbound WebSocket, only while that
     * socket is up ({@link #relayConnected()}) and the player's guild rank is
     * known: temporary-server rejects a {@code queue} frame without one, so an
     * unknown rank gets a red "Not sent" line and no echo.
     *
     * <p>The message is sent with {@code type="queue"}, a distinct protocol
     * type that semantically equals a guild message but skips the
     * in-game-guild suppression every client applies to {@code "guild"}
     * outbound echoes, so other vetsmod clients — including older ones that
     * predate queue awareness — can render queue-originated messages
     * wherever they render relayed guild chat at all (see
     * {@link OutboundDisplayHandler}'s display gates). Per
     * temporary-server's outbound tier filter ({@code app/chat/outbound.py};
     * v1_protocol.md §2.4 agrees) an authenticated waitlist or honourary socket
     * would get no queue frames; today, because vetsmod never authenticates its
     * outbound socket ({@code outbound-socket-never-authenticated}), that gate
     * does not apply: while the server's {@code unauth} toggle is on, a waitlist
     * or honourary viewer receives and renders them too (with it off, no chat
     * arrives).</p>
     *
     * <p>Receivers:</p>
     * <ul>
     *   <li>Discord bridge: the server maps {@code queue} to the
     *       {@code [Guild]} prefix — indistinguishable from a normal guild
     *       message on the Discord side.</li>
     *   <li>Other vetsmod clients, wherever they render relayed guild chat at
     *       all: the {@code "queue"} type falls through the Returners
     *       {@code guild}-suppression gate and renders like any other
     *       relayed guild line — as a staff ALERT box when it leads with ‼
     *       and {@code StaffRanksPoller} confirms the sender as staff (checked
     *       first), otherwise with honourary styling for an honourary-unlocked
     *       viewer.</li>
     * </ul>
     */
    private static void relayQueuedReturnersChat(String message) {
        Minecraft minecraft = McUtils.mc();
        if (minecraft.player == null) return;
        if (!relayConnected()) return;
        String text = relayText(message);
        if (text == null) return;

        String rank = resolveReturnersRank();
        if (rank.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    "Not sent: your guild rank isn't known yet."
                                            + " Try again in a few seconds.")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        String username = resolveUsername(minecraft);
        OutboundDisplayHandler.queuePendingSelfMessage(username, text);
        ChatUtils.sendGuildChatMessage(rank, username, text);
        V1ApiManager.sendInbound("queue", rank, username, text);
    }

    /**
     * Resolves the local player's Wynncraft guild rank using the Wynntils
     * guild model.  Falls back to an empty string when the rank is not
     * available yet, on which {@link #relayQueuedReturnersChat} refuses.
     */
    private static String resolveReturnersRank() {
        GuildRank guildRank = Models.Guild.getGuildRank();
        if (guildRank != null) {
            return guildRank.getName();
        }
        return "";
    }

    private static void relayHonouraryChat(String message) {
        Minecraft minecraft = McUtils.mc();
        if (minecraft.player == null) return;
        if (!relayConnected()) return;
        String text = relayText(message);
        if (text == null) return;

        String username = resolveUsername(minecraft);
        OutboundDisplayHandler.queuePendingSelfMessage(username, text);
        ChatUtils.sendHonouraryChatMessage(HONOURARY_SELF_RANK, username, text);
        V1ApiManager.sendInbound("honourary", "Honourary", username, text);
    }

    private static void dispatchStaffChat(String typed) {
        Minecraft minecraft = McUtils.mc();
        if (minecraft.player == null) return;
        // The /v echo renders this text locally, so drop the hard set here too; the /msg
        // payload and the echo then agree. DispatchCoordinator refuses a blank result.
        String message = BannedChars.stripHard(typed);

        String username = resolveUsername(minecraft);
        // Captain was retired in the 2026-07 permission restructure; fall
        // back to "strategist" (the baseline staff rank) when the local
        // self-rank cache is empty, so /v self-echo still renders a
        // sensible staff-tier pill for known-staff users.
        String rank =
                StaffRanksPoller.confirmedRankFor(username)
                        .orElseGet(
                                () -> {
                                    String selfRank = GuildStateManager.selfStaffRank();
                                    return (selfRank == null || selfRank.isEmpty())
                                            ? "strategist"
                                            : selfRank;
                                });

        DispatchCoordinator.dispatchStaffChatWithEligibilityGate(username, message, rank);
    }

    private static String resolveUsername(Minecraft minecraft) {
        String username = GuildStateManager.playerName();
        if (username == null || username.isEmpty()) {
            username = minecraft.player.getName().getString();
        }
        return username;
    }
}
