package org.wynnvets.fetcher.ondemand;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wynntils.core.components.Models;
import com.wynntils.models.guild.type.GuildInfo;
import com.wynntils.models.guild.type.GuildMemberInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.api.VetsApi;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * On-demand fetcher for the {@code /wv list} command.
 *
 * <p>Combines two data sources to build a comprehensive online-member view:
 * <ol>
 *   <li><b>VetsMod server</b> ({@code GET /v1/outbound/list}) — returns
 *       UUIDs of users currently connected via vetsmod, with their tier
 *       (guild / waitlist / honourary).</li>
 *   <li><b>Wynntils guild model</b> ({@code Models.Guild.getGuild()}) —
 *       fetches all guild members and their online status from the
 *       Wynncraft API, providing UUID↔username mappings.</li>
 * </ol>
 *
 * <p>VetsMod presence data is <b>prioritised</b> over the Wynncraft API's
 * {@code online} flag, which can be inaccurate. A guild member connected
 * via vetsmod is always considered online even if the API disagrees.</p>
 */
public final class ListFetcher {

  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private static final Gson GSON = new Gson();

  private ListFetcher() {
  }

  // ── Data holder for a connected vetsmod user ──────────────────────

  private record ConnectedUser(String uuid, String username, String tier) {
  }

  // ── Public entry point ────────────────────────────────────────────

  /**
   * Fetch online member data and return a formatted chat component.
   */
  public static CompletableFuture<MutableComponent> fetchList() {
    // 1. Fetch connected vetsmod users from the server.
    CompletableFuture<List<ConnectedUser>> serverFuture = fetchConnectedUsers()
        .exceptionally(e -> {
          VetsLogger.debug("Failed to fetch connected users: {}", e.getMessage());
          return List.of();
        });

    // 2. Fetch guild info from Wynntils (Wynncraft API).
    CompletableFuture<GuildInfo> guildFuture;
    if (GuildStateManager.isWynntilsReady()) {
      guildFuture = Models.Guild.getGuild("Returners")
          .exceptionally(e -> {
            VetsLogger.debug("Failed to fetch guild info: {}", e.getMessage());
            return null;
          });
    } else {
      guildFuture = CompletableFuture.completedFuture(null);
    }

    // 3. Combine both results.
    return serverFuture.thenCombine(guildFuture, ListFetcher::mergeAndFormat);
  }

  // ── Server fetch ──────────────────────────────────────────────────

  private static CompletableFuture<List<ConnectedUser>> fetchConnectedUsers() {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(VetsApi.LIST)
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            VetsLogger.debug("List fetch failed: {}", response.statusCode());
            return List.<ConnectedUser>of();
          }
          return parseConnectedUsers(response.body());
        });
  }

  private static List<ConnectedUser> parseConnectedUsers(String body) {
    try {
      JsonObject root = GSON.fromJson(body, JsonObject.class);
      if (root == null || !root.has("connected")) {
        return List.of();
      }
      JsonArray arr = root.getAsJsonArray("connected");
      List<ConnectedUser> result = new ArrayList<>();
      for (JsonElement el : arr) {
        if (!el.isJsonObject()) continue;
        JsonObject obj = el.getAsJsonObject();
        String uuid = stringOrEmpty(obj, "uuid");
        String username = stringOrEmpty(obj, "username");
        String tier = stringOrEmpty(obj, "tier");
        if (!uuid.isEmpty() && !username.isEmpty()) {
          result.add(new ConnectedUser(uuid, username, tier));
        }
      }
      return result;
    } catch (Exception e) {
      VetsLogger.debug("Error parsing connected users: {}", e.getMessage());
      return List.of();
    }
  }

  // ── Merge + format ────────────────────────────────────────────────

  private static MutableComponent mergeAndFormat(
      List<ConnectedUser> connected, GuildInfo guildInfo) {

    // Index connected users by UUID.
    Map<String, ConnectedUser> modByUuid = new LinkedHashMap<>();
    for (ConnectedUser cu : connected) {
      modByUuid.put(cu.uuid(), cu);
    }

    Set<String> modGuildUuids = modByUuid.values().stream()
        .filter(cu -> "guild".equals(cu.tier()))
        .map(ConnectedUser::uuid)
        .collect(Collectors.toSet());

    // Build the master UUID→username map from Wynntils (authoritative).
    Map<String, String> uuidToUsername = new LinkedHashMap<>();
    if (guildInfo != null) {
      for (GuildMemberInfo m : guildInfo.guildMembers()) {
        uuidToUsername.put(m.uuid().toString(), m.username());
      }
    }
    // Supplement with vetsmod usernames for UUIDs the API doesn't know.
    for (ConnectedUser cu : connected) {
      uuidToUsername.putIfAbsent(cu.uuid(), cu.username());
    }

    // Collect online guild member UUIDs from the Wynncraft API.
    Set<String> apiOnlineUuids = new TreeSet<>();
    if (guildInfo != null) {
      for (GuildMemberInfo m : guildInfo.guildMembers()) {
        if (m.online()) {
          apiOnlineUuids.add(m.uuid().toString());
        }
      }
    }

    // Merged online guild = API online ∪ vetsmod guild users.
    // VetsMod presence is prioritised: if the API missed them, add them.
    Set<String> mergedOnlineGuild = new TreeSet<>(apiOnlineUuids);
    mergedOnlineGuild.addAll(modGuildUuids);

    // Partition guild members.
    List<String> withMod = mergedOnlineGuild.stream()
        .filter(modGuildUuids::contains)
        .map(uuid -> uuidToUsername.getOrDefault(uuid, uuid.substring(0, 8) + "…"))
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .collect(Collectors.toList());

    List<String> withoutMod = mergedOnlineGuild.stream()
        .filter(uuid -> !modGuildUuids.contains(uuid))
        .map(uuid -> uuidToUsername.getOrDefault(uuid, uuid.substring(0, 8) + "…"))
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .collect(Collectors.toList());

    // Honourary + waitlist (always vetsmod users).
    List<String> honourary = modByUuid.values().stream()
        .filter(cu -> "honourary".equals(cu.tier()))
        .map(cu -> uuidToUsername.getOrDefault(cu.uuid(), cu.username()))
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .collect(Collectors.toList());

    List<String> waitlist = modByUuid.values().stream()
        .filter(cu -> "waitlist".equals(cu.tier()))
        .map(cu -> uuidToUsername.getOrDefault(cu.uuid(), cu.username()))
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .collect(Collectors.toList());

    // ── Build the chat component ──────────────────────────────────────

    MutableComponent msg = Component.empty();

    msg.append(Component.literal("——— Online Members ———\n")
        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

    int totalGuild = mergedOnlineGuild.size();
    msg.append(Component.literal(String.format(
            "%d guild, %d honourary, %d waitlist\n",
            totalGuild, honourary.size(), waitlist.size()))
        .withStyle(ChatFormatting.GRAY));

    // Guild with vetsmod
    if (!withMod.isEmpty()) {
      msg.append(Component.literal("\nGuild — with VetsMod (" + withMod.size() + "):\n")
          .withStyle(ChatFormatting.GREEN));
      appendPlayerList(msg, withMod, ChatFormatting.AQUA);
    }

    // Guild without vetsmod
    if (!withoutMod.isEmpty()) {
      msg.append(Component.literal("\nGuild — without VetsMod (" + withoutMod.size() + "):\n")
          .withStyle(ChatFormatting.YELLOW));
      appendPlayerList(msg, withoutMod, ChatFormatting.GRAY);
    }

    if (totalGuild == 0) {
      msg.append(Component.literal("\nNo guild members detected online.\n")
          .withStyle(ChatFormatting.GRAY));
    }

    // Honourary
    if (!honourary.isEmpty()) {
      msg.append(Component.literal("\nHonourary (" + honourary.size() + "):\n")
          .withStyle(ChatFormatting.LIGHT_PURPLE));
      appendPlayerList(msg, honourary, ChatFormatting.AQUA);
    }

    // Waitlist
    if (!waitlist.isEmpty()) {
      msg.append(Component.literal("\nWaitlist (" + waitlist.size() + "):\n")
          .withStyle(ChatFormatting.BLUE));
      appendPlayerList(msg, waitlist, ChatFormatting.AQUA);
    }

    if (guildInfo == null) {
      msg.append(Component.literal("\n⚠ Wynncraft API unavailable — guild list may be incomplete.")
          .withStyle(ChatFormatting.YELLOW));
    }

    return msg;
  }

  /**
   * Appends a comma-separated list of clickable player names.
   */
  private static void appendPlayerList(
      MutableComponent parent, List<String> names, ChatFormatting color) {
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      parent.append(Component.literal(name)
          .withStyle(color)
          .withStyle(style -> style
              .withHoverEvent(new HoverEvent.ShowText(
                  Component.literal("Click to message " + name)
                      .withStyle(ChatFormatting.GRAY)))
              .withClickEvent(new ClickEvent.SuggestCommand("/msg " + name + " "))));
      if (i < names.size() - 1) {
        parent.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
      }
    }
    parent.append(Component.literal("\n").withStyle(ChatFormatting.RESET));
  }

  private static String stringOrEmpty(JsonObject obj, String key) {
    if (obj.has(key) && !obj.get(key).isJsonNull()) {
      return obj.get(key).getAsString();
    }
    return "";
  }
}
