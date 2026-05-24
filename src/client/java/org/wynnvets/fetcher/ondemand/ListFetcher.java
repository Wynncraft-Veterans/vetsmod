package org.wynnvets.fetcher.ondemand;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.fetcher.polling.StaffRanksPoller;
import org.wynnvets.fetcher.polling.SupportersPoller;
import org.wynnvets.rendering.colors.AnimatedGradientSequence;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * On-demand fetcher for the {@code /wv list} command.
 *
 * <p>Delegates data collection to {@link OnlineMemberService}, then partitions
 * results by tier and VetsMod status for display.</p>
 */
public final class ListFetcher {

  private ListFetcher() {
  }

  // ── Public entry point ────────────────────────────────────────────

  /**
   * Fetch online member data and return a formatted chat component.
   */
  public static CompletableFuture<MutableComponent> fetchList() {
    return OnlineMemberService.gatherOnlinePlayers()
        .thenApply(ListFetcher::formatList);
  }

  // ── Format ────────────────────────────────────────────────────────

  private static MutableComponent formatList(OnlineMemberService.GatherResult result) {
    List<OnlineMemberService.OnlinePlayer> players = result.players();

    // Build a username→uuid lookup for queued checking.
    java.util.Map<String, String> usernameToUuid = new java.util.HashMap<>();
    for (OnlineMemberService.OnlinePlayer p : players) {
      if (!p.uuid().isEmpty()) {
        usernameToUuid.put(p.username(), p.uuid());
      }
    }

    List<String> withMod = players.stream()
        .filter(p -> OnlineMemberService.TIER_GUILD.equals(p.tier())
                     && result.modGuildUuids().contains(p.uuid())
                     && !result.queuedUuids().contains(p.uuid()))
        .map(OnlineMemberService.OnlinePlayer::username)
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .collect(Collectors.toList());

    List<String> withModQueued = players.stream()
        .filter(p -> OnlineMemberService.TIER_GUILD.equals(p.tier())
                     && result.modGuildUuids().contains(p.uuid())
                     && result.queuedUuids().contains(p.uuid()))
        .map(OnlineMemberService.OnlinePlayer::username)
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .collect(Collectors.toList());

    List<String> withoutMod = players.stream()
        .filter(p -> OnlineMemberService.TIER_GUILD.equals(p.tier())
                     && !result.modGuildUuids().contains(p.uuid()))
        .map(OnlineMemberService.OnlinePlayer::username)
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .collect(Collectors.toList());

    List<String> honourary = players.stream()
        .filter(p -> OnlineMemberService.TIER_HONOURARY.equals(p.tier()))
        .map(OnlineMemberService.OnlinePlayer::username)
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .collect(Collectors.toList());

    List<String> waitlist = players.stream()
        .filter(p -> OnlineMemberService.TIER_WAITLIST.equals(p.tier()))
        .map(OnlineMemberService.OnlinePlayer::username)
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .collect(Collectors.toList());

    // Count only UUID-backed guild members for the header (matches original behavior).
    long totalGuild = players.stream()
        .filter(p -> OnlineMemberService.TIER_GUILD.equals(p.tier()) && !p.uuid().isEmpty())
        .count();

    // ── Build the chat component ──────────────────────────────────────

    MutableComponent msg = Component.empty();

    msg.append(Component.literal("Online Members\n")
        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

    msg.append(Component.literal(String.format(
            "%d guild, %d honourary, %d waitlist\n",
            totalGuild, honourary.size(), waitlist.size()))
        .withStyle(ChatFormatting.GRAY));

    // Guild with vetsmod
    if (!withMod.isEmpty()) {
      msg.append(Component.literal("\nGuild — with VetsMod (" + withMod.size() + "):\n")
          .withStyle(ChatFormatting.GREEN));
      appendPlayerList(msg, withMod, ChatFormatting.AQUA, false);
    }

    // Guild with vetsmod, in queue
    if (!withModQueued.isEmpty()) {
      msg.append(Component.literal("\nGuild — in Queue (" + withModQueued.size() + "):\n")
          .withStyle(ChatFormatting.GREEN));
      appendPlayerList(msg, withModQueued, ChatFormatting.LIGHT_PURPLE, false);
    }

    // Guild without vetsmod
    if (!withoutMod.isEmpty()) {
      msg.append(Component.literal("\nGuild — without VetsMod (" + withoutMod.size() + "):\n")
          .withStyle(ChatFormatting.YELLOW));
      appendPlayerList(msg, withoutMod, ChatFormatting.GRAY, false);
    }

    if (totalGuild == 0) {
      msg.append(Component.literal("\nNo guild members detected online.\n")
          .withStyle(ChatFormatting.GRAY));
    }

    // Honourary
    if (!honourary.isEmpty()) {
      msg.append(Component.literal("\nHonourary (" + honourary.size() + "):\n")
          .withStyle(ChatFormatting.LIGHT_PURPLE));
      appendPlayerList(msg, honourary, ChatFormatting.AQUA, true);
    }

    // Waitlist
    if (!waitlist.isEmpty()) {
      msg.append(Component.literal("\nWaitlist (" + waitlist.size() + "):\n")
          .withStyle(ChatFormatting.BLUE));
      appendPlayerList(msg, waitlist, ChatFormatting.AQUA, true);
    }

    if (!result.wynntilsAvailable()) {
      msg.append(Component.literal("\n⚠ Wynncraft API unavailable — guild list may be incomplete.")
          .withStyle(ChatFormatting.YELLOW));
    }

    return msg;
  }

  /**
   * Appends a comma-separated list of clickable player names.
   */
  private static void appendPlayerList(
      MutableComponent parent, List<String> names, ChatFormatting color, boolean italic) {
    boolean glintEnabled = VetsConfig.get(VetsConfig.SHOW_SUPPORTER_GLINTS);
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      boolean supporter = glintEnabled && SupportersPoller.isSupporter(name);
      boolean staff = StaffRanksPoller.confirmedRankFor(name).isPresent();

      Style base;
      if (supporter) {
        int marker = color == ChatFormatting.GRAY
            ? AnimatedGradientSequence.GREY_MARKER_COLOR
            : AnimatedGradientSequence.MARKER_COLOR;
        base = Style.EMPTY.withColor(TextColor.fromRgb(marker));
      } else {
        base = Style.EMPTY.withColor(color);
      }
      if (italic) {
        base = base.withItalic(true);
      }
      if (staff) {
        base = base.withUnderlined(true);
      }

      MutableComponent nameComp = Component.literal(name).withStyle(base);
      nameComp.withStyle(style -> style
          .withHoverEvent(new HoverEvent.ShowText(
              Component.literal("Click to message " + name)
                  .withStyle(ChatFormatting.GRAY)))
          .withClickEvent(new ClickEvent.SuggestCommand("/msg " + name + " ")));
      parent.append(nameComp);
      if (i < names.size() - 1) {
        parent.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
      }
    }
    parent.append(Component.literal("\n").withStyle(ChatFormatting.RESET));
  }

}
