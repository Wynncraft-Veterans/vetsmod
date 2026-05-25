package org.wynnvets.commands;

import com.wynntils.core.components.Models;
import com.wynntils.models.guild.type.GuildRank;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.OutboundDisplayHandler;
import org.wynnvets.chat.spoiler.SpoilerCodec;
import org.wynnvets.chat.dispatcher.CommandDispatcher;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.fetcher.ondemand.UserInfoFetcher;
import org.wynnvets.fetcher.polling.StaffRanksPoller;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.queue.QueueStateManager;

/**
 * Handles outbound chat-command interception for guild chat ({@code /g}),
 * honourary chat ({@code /wg}), staff chat ({@code /v}), announcements
 * ({@code /a}), and the encourage command ({@code /encourage}).
 *
 * <p>Called from {@link org.wynnvets.mixin.client.GuildChatCommandMixin}
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
  private static final ThreadLocal<Boolean> BYPASS =
      ThreadLocal.withInitial(() -> Boolean.FALSE);

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
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null || minecraft.player.connection == null) return;
    BYPASS.set(Boolean.TRUE);
    try {
      minecraft.player.connection.sendCommand(command);
    } finally {
      BYPASS.set(Boolean.FALSE);
    }
  }

  /**
   * Attempts to intercept an outbound chat command.
   *
   * @param command the command string (without leading {@code /})
   * @return {@code true} if the command was handled and should be cancelled;
   *         {@code false} to let it pass through to the server
   */
  public static boolean intercept(String command) {
    if (BYPASS.get()) return false;
    // /g: handler already falls through to vanilla for non-vets users
    // via its internal isReturners / isWaitlistUnlocked / queue checks.
    if (command.regionMatches(true, 0, "g ", 0, 2)) {
      return handleGuildChat(command.substring(2));
    }

    // Past /g, vetsmod stays out of the way for users who aren't part
    // of the vets community at all -- Wynncraft handles these commands
    // as if the mod weren't installed.
    if (!GuildStateManager.isUnlocked()) {
      return false;
    }

    if (command.regionMatches(true, 0, "wg ", 0, 3)) {
      return handleHonouraryChat(command.substring(3));
    }

    // Client-side-staff-gated commands: passthrough silently when the
    // local rank cache doesn't show staff, opportunistically refreshing
    // so the next attempt has a chance to succeed.
    if (command.regionMatches(true, 0, "v ", 0, 2)) {
      if (!staffOrRefresh()) return false;
      return handleStaffChat(command.substring(2).trim());
    }
    if (command.regionMatches(true, 0, "a ", 0, 2)) {
      if (!staffOrRefresh()) return false;
      return handleAnnounce(command.substring(2).trim());
    }
    if (command.regionMatches(true, 0, "encourage ", 0, 10)) {
      if (!staffOrRefresh()) return false;
      return handleEncourage(command.substring(10).trim());
    }
    if (command.regionMatches(true, 0, "wv check ", 0, 9)) {
      if (!GuildStateManager.isConfirmedStaff()) {
        ChatUtils.sendLocalMessage(
            Component.literal("You must be confirmed staff (vetsmod authenticated) "
                    + "to use /wv check.")
                .withStyle(ChatFormatting.RED));
        return true;
      }
      return handleWvCheck(command.substring(9).trim());
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
    // These dispatch real /gu kick / /gu rank commands on success, so
    // they can't trust the local rank cache -- the WS-auth-derived
    // isConfirmedStaff() is the only signal that gates them.
    // Passthrough silently when not confirmed staff.
    if (command.regionMatches(true, 0, "caution-go ", 0, 11)) {
      if (!GuildStateManager.isConfirmedStaff()) return false;
      CautionCommands.runCautionGo(command.substring(11));
      return true;
    }
    if (command.regionMatches(true, 0, "caution ", 0, 8)) {
      if (!GuildStateManager.isConfirmedStaff()) return false;
      CautionCommands.runCaution(command.substring(8));
      return true;
    }
    if (command.regionMatches(true, 0, "warn ", 0, 5)) {
      if (!GuildStateManager.isConfirmedStaff()) return false;
      CautionCommands.runWarn(command.substring(5));
      return true;
    }
    if (command.regionMatches(true, 0, "eject ", 0, 6)) {
      if (!GuildStateManager.isConfirmedStaff()) return false;
      CautionCommands.runEject(command.substring(6));
      return true;
    }
    return false;
  }

  private static boolean staffOrRefresh() {
    if (GuildStateManager.isStaff()) return true;
    GuildStateManager.refreshStaffStatusIfNeeded(true);
    return false;
  }

  /** Returns the first whitespace-delimited token of {@code args}, or
   *  empty string if {@code args} has none. Used by the /gu invite
   *  intercept to pull the target name out of the raw command tail. */
  private static String extractFirstToken(String args) {
    String trimmed = args.trim();
    if (trimmed.isEmpty()) return "";
    int sp = trimmed.indexOf(' ');
    return sp < 0 ? trimmed : trimmed.substring(0, sp);
  }

  // ── /g — Guild chat ─────────────────────────────────────────────────

  /**
   * Handles {@code /g <message>}. For waitlist users the message is relayed
   * via WebSocket. For Returners, spoiler markers are encoded before sending.
   *
   * @return {@code true} to cancel the original command
   */
  private static boolean handleGuildChat(String message) {
    // Waitlist (guildless) relay
    if (GuildStateManager.isGuildless() && GuildStateManager.isWaitlistUnlocked()) {
      VetsLogger.debug("Intercepted /g for waitlist bridge relay");
      String outMessage = !Boolean.FALSE.equals(VetsConfig.getTriState(VetsConfig.HANDLE_SPOILERS))
          ? SpoilerCodec.encodeSpoilers(message) : message;
      relayWaitlistChat(outMessage);
      return true;
    }

    // Queued Returners relay: the game server drops /g while the client sits
    // in a world queue.  Route the message through the WebSocket bridge so
    // Discord and other vetsmod clients still see it.  See QueueDetector.
    if (GuildStateManager.isReturners() && QueueStateManager.isInQueue()) {
      VetsLogger.debug("Intercepted /g for queued-Returners bridge relay");
      String outMessage = !Boolean.FALSE.equals(VetsConfig.getTriState(VetsConfig.HANDLE_SPOILERS))
          ? SpoilerCodec.encodeSpoilers(message) : message;
      relayQueuedReturnersChat(outMessage);
      return true;
    }

    // Returners spoiler encoding
    if (GuildStateManager.isReturners()
        && !Boolean.FALSE.equals(VetsConfig.getTriState(VetsConfig.HANDLE_SPOILERS))
        && SpoilerCodec.containsPipeSpoiler(message)) {
      String encoded = SpoilerCodec.encodeSpoilers(message);
      if (encoded.length() > 253) {
        ChatUtils.sendLocalMessage(
            Component.literal("Encoding your spoilers would exceed Wynn's 253 character limit.")
                .withStyle(ChatFormatting.RED)
        );
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
      String outMessage = !Boolean.FALSE.equals(VetsConfig.getTriState(VetsConfig.HANDLE_SPOILERS))
          ? SpoilerCodec.encodeSpoilers(message) : message;
      relayHonouraryChat(outMessage);
    } else {
      ChatUtils.sendLocalMessage(
          Component.literal("You must unlock with an honourary password to use /wg.")
              .withStyle(ChatFormatting.RED)
      );
    }
    return true;
  }

  // ── /v — Staff chat ─────────────────────────────────────────────────

  private static boolean handleStaffChat(String message) {
    if (message.isEmpty()) {
      ChatUtils.sendLocalMessage(
          Component.literal("Usage: /v <message>").withStyle(ChatFormatting.RED)
      );
      return true;
    }

    if (message.length() > STAFF_CHAT_MAX_LENGTH) {
      ChatUtils.sendLocalMessage(
          Component.literal("/v messages are limited to " + STAFF_CHAT_MAX_LENGTH + " characters.")
              .withStyle(ChatFormatting.RED)
      );
      return true;
    }

    dispatchStaffChat(message);
    return true;
  }

  // ── /a — Staff announcement ─────────────────────────────────────────

  private static boolean handleAnnounce(String message) {
    if (message.isEmpty()) {
      ChatUtils.sendLocalMessage(
          Component.literal("Usage: /a <message>").withStyle(ChatFormatting.RED)
      );
      return true;
    }

    CommandDispatcher.executeWithStaffEligibilityGate(
        () -> sendCommandBypassed("g \u203C" + message));
    return true;
  }

  // ── /encourage — Encourage update ───────────────────────────────────

  private static boolean handleEncourage(String version) {
    if (version.isEmpty()) {
      ChatUtils.sendLocalMessage(
          Component.literal("Usage: /encourage <version>").withStyle(ChatFormatting.RED)
      );
      return true;
    }

    CommandDispatcher.executeWithStaffEligibilityGate(
        () -> sendCommandBypassed(
            "g \u26A0\u26A0\u26A0 If you are using vetsmod, it's outdated (current version "
                + version + ") \u26A0\u26A0\u26A0"));
    return true;
  }

  // ── /wv check — Player lookup ───────────────────────────────────────

  private static boolean handleWvCheck(String playerName) {
    if (playerName.isEmpty()) {
      ChatUtils.sendLocalMessage(
          Component.literal("Usage: /wv check <playerName>").withStyle(ChatFormatting.RED)
      );
      return true;
    }

    UserInfoFetcher.checkUser(playerName);
    // Append the caution history readout -- same data shown by Discord's
    // ~warnings command. Gated on confirmed-staff inside
    // CautionCommands.runCheckCautions so it is silently skipped for
    // non-confirmed users.
    CautionCommands.runCheckCautions(playerName);
    return true;
  }

  // ── Shared helpers ──────────────────────────────────────────────────

  private static void relayWaitlistChat(String message) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null) return;

    String username = resolveUsername(minecraft);
    OutboundDisplayHandler.queuePendingSelfMessage(username, message);
    ChatUtils.sendGuildChatMessage(GUILDLESS_SELF_RANK, username, message);
    V1ApiManager.sendInbound("waitlist", "Waitlist", username, message);
  }

  /**
   * Relays a Returners guild message over the WebSocket bridge while the
   * client is sitting in a world queue.  The Wynncraft game server ignores
   * {@code /g} commands in this state, so the message never touches the
   * guild chat channel — we instead mimic the game-server delivery locally
   * and push the message via the v1 inbound WebSocket.
   *
   * <p>The message is sent with {@code type="queue"}, a distinct protocol
   * type that semantically equals a guild message but skips the
   * in-game-guild suppression every client applies to {@code "guild"}
   * outbound echoes.  This lets <em>all</em> vetsmod clients — including
   * older ones that predate queue awareness — render queue-originated
   * messages in chat.</p>
   *
   * <p>Receivers:</p>
   * <ul>
   *   <li>Discord bridge: the server maps {@code queue} to the
   *       {@code [Guild]} prefix — indistinguishable from a normal guild
   *       message on the Discord side.</li>
   *   <li>All other vetsmod clients (any version): the {@code "queue"}
   *       type falls through the Returners {@code guild}-suppression gate,
   *       so the message renders as ordinary guild chat.</li>
   * </ul>
   */
  private static void relayQueuedReturnersChat(String message) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null) return;

    String username = resolveUsername(minecraft);
    String rank = resolveReturnersRank();
    OutboundDisplayHandler.queuePendingSelfMessage(username, message);
    ChatUtils.sendGuildChatMessage(rank, username, message);
    V1ApiManager.sendInbound("queue", rank, username, message);
  }

  /**
   * Resolves the local player's Wynncraft guild rank using the Wynntils
   * guild model.  Falls back to an empty string when the rank is not
   * available, which {@link ChatUtils#sendGuildChatMessage(String, String, String)}
   * handles gracefully (no pill rendered).
   */
  private static String resolveReturnersRank() {
    GuildRank guildRank = Models.Guild.getGuildRank();
    if (guildRank != null) {
      return guildRank.getName();
    }
    return "";
  }

  private static void relayHonouraryChat(String message) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null) return;

    String username = resolveUsername(minecraft);
    OutboundDisplayHandler.queuePendingSelfMessage(username, message);
    ChatUtils.sendHonouraryChatMessage(HONOURARY_SELF_RANK, username, message);
    V1ApiManager.sendInbound("honourary", "Honourary", username, message);
  }

  private static void dispatchStaffChat(String message) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null) return;

    String username = resolveUsername(minecraft);
    String rank = StaffRanksPoller.confirmedRankFor(username)
        .orElseGet(() -> {
          String selfRank = GuildStateManager.selfStaffRank();
          return (selfRank == null || selfRank.isEmpty()) ? "captain" : selfRank;
        });

    CommandDispatcher.dispatchStaffChatWithEligibilityGate(username, message, rank);
  }

  private static String resolveUsername(Minecraft minecraft) {
    String username = GuildStateManager.playerName();
    if (username == null || username.isEmpty()) {
      username = minecraft.player.getName().getString();
    }
    return username;
  }
}
