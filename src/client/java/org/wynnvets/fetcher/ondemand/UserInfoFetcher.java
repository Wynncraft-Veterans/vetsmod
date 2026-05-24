package org.wynnvets.fetcher.ondemand;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.api.MojangApi;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.api.WynnCraftApi;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.datamodels.Guild;
import org.wynnvets.datamodels.MembershipSnapshot;
import org.wynnvets.datamodels.User;
import org.wynnvets.datamodels.UserUUID;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * On-demand fetcher for the {@code /wv check} command.
 *
 * <p>Fans out three concurrent lookups for an arbitrary Minecraft player
 * (Mojang UUID → Wynncraft player profile, Returners roster cross-check,
 * dazebot membership snapshot via the WS bridge) and renders the
 * combined output as a multi-line block in the local chat HUD. Each
 * logical line is dispatched via its own {@link ChatUtils#sendLocalMessage}
 * call so the {@link org.wynnvets.chat.Prepend#DEFAULT} dedup auto-demotes
 * the badge to the compact block-indicator on continuation lines.</p>
 */
public class UserInfoFetcher {
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private static final Pattern UUID_FIX = Pattern.compile("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})");

  private static final Gson GSON = new Gson();

  private static final Style LABEL_STYLE = Style.EMPTY.withColor(ChatFormatting.GRAY);
  private static final Style VALUE_STYLE = Style.EMPTY.withColor(ChatFormatting.AQUA);
  private static final Style MUTED_STYLE = Style.EMPTY.withColor(ChatFormatting.DARK_GRAY);
  private static final Style HIDDEN_STYLE = Style.EMPTY.withColor(ChatFormatting.YELLOW);

  /** Lightweight result of the Returners roster cross-check. */
  private record ReturnersMembership(boolean inGuild, String legacyName, String rank) {
    static ReturnersMembership notInGuild() {
      return new ReturnersMembership(false, null, null);
    }
  }

  private static String toRelativeDateLabel(String dateText) {
    LocalDate date;

    try {
      date = LocalDate.parse(dateText);
    } catch (RuntimeException ignored) {
      return dateText;
    }

    long days = ChronoUnit.DAYS.between(date, LocalDate.now());
    if (days == 0) {
      return "today";
    }
    if (days == 1) {
      return "yesterday";
    }
    if (days > 1) {
      return days + " days ago";
    }

    long daysUntil = Math.abs(days);
    if (daysUntil == 1) {
      return "tomorrow";
    }
    return "in " + daysUntil + " days";
  }

  private static UUID formatFromInput(String uuid) {
    return UUID.fromString(UUID_FIX.matcher(uuid.replace("-", "")).replaceAll("$1-$2-$3-$4-$5"));
  }

  private static String normalizeUuidText(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.replace("-", "").toLowerCase(Locale.ROOT);
  }

  private static boolean isUuidLike(String value) {
    return value != null && value.length() == 32 && value.matches("[0-9a-f]{32}");
  }

  private static boolean containsUuid(JsonElement element, String normalizedUuid) {
    if (element == null || element.isJsonNull()) {
      return false;
    }

    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
      String normalized = normalizeUuidText(element.getAsString());
      return isUuidLike(normalized) && normalized.equals(normalizedUuid);
    }

    if (element.isJsonArray()) {
      JsonArray array = element.getAsJsonArray();
      for (JsonElement child : array) {
        if (containsUuid(child, normalizedUuid)) {
          return true;
        }
      }
      return false;
    }

    if (element.isJsonObject()) {
      JsonObject object = element.getAsJsonObject();
      for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
        String normalizedKey = normalizeUuidText(entry.getKey());
        if (isUuidLike(normalizedKey) && normalizedKey.equals(normalizedUuid)) {
          return true;
        }

        if (containsUuid(entry.getValue(), normalizedUuid)) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Fetches the Returners roster from the Wynncraft API and returns
   * membership status, plus best-effort {@code legacyName} and rank
   * extraction. Falls back to {@link #containsUuid} for the boolean if
   * the structured walk misses (defends against Wynncraft API shape
   * changes).
   */
  private static CompletableFuture<ReturnersMembership> returnersMembership(String uuid) {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(WynnCraftApi.guildInfo("Returners"))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    String normalizedUuid = normalizeUuidText(uuid);

    return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            return ReturnersMembership.notInGuild();
          }

          JsonElement payload = GSON.fromJson(response.body(), JsonElement.class);
          return analyzeRoster(payload, normalizedUuid);
        })
        .exceptionally(e -> ReturnersMembership.notInGuild());
  }

  private static ReturnersMembership analyzeRoster(JsonElement payload, String normalizedUuid) {
    if (!containsUuid(payload, normalizedUuid)) {
      return ReturnersMembership.notInGuild();
    }
    // Try to extract rank + legacyName from the V3-typical shape:
    //   { "members": { "<rank>": { "<username>": { "uuid": ..., "legacyName": ... } } } }
    String rank = null;
    String legacyName = null;
    if (payload != null && payload.isJsonObject()) {
      JsonElement membersEl = payload.getAsJsonObject().get("members");
      if (membersEl != null && membersEl.isJsonObject()) {
        outer:
        for (Map.Entry<String, JsonElement> rankBucket : membersEl.getAsJsonObject().entrySet()) {
          String rankKey = rankBucket.getKey();
          if ("total".equals(rankKey)) continue;
          JsonElement bucketEl = rankBucket.getValue();
          if (bucketEl == null || !bucketEl.isJsonObject()) continue;
          for (Map.Entry<String, JsonElement> memberEntry : bucketEl.getAsJsonObject().entrySet()) {
            JsonElement memberEl = memberEntry.getValue();
            if (memberEl == null || !memberEl.isJsonObject()) continue;
            JsonObject member = memberEl.getAsJsonObject();
            JsonElement uuidEl = member.get("uuid");
            if (uuidEl == null || !uuidEl.isJsonPrimitive()) continue;
            String memberUuid = normalizeUuidText(uuidEl.getAsString());
            if (memberUuid.equals(normalizedUuid)) {
              rank = capitalizeRank(rankKey);
              if (member.has("legacyName") && !member.get("legacyName").isJsonNull()) {
                legacyName = member.get("legacyName").getAsString();
              }
              break outer;
            }
          }
        }
      }
    }
    return new ReturnersMembership(true, legacyName, rank);
  }

  private static String capitalizeRank(String rank) {
    if (rank == null || rank.isEmpty()) return rank;
    return Character.toUpperCase(rank.charAt(0)) + rank.substring(1);
  }

  /**
   * Sends a {@code check_membership} WS frame and exposes the parsed
   * response as a future. Server-side errors complete the future with a
   * sentinel snapshot whose {@code discord} field is {@code null} so
   * the renderer can show "(snapshot unavailable: ...)" while still
   * rendering the Wynncraft-derived lines.
   */
  private static CompletableFuture<MembershipSnapshot> membershipSnapshot(String playerName) {
    CompletableFuture<MembershipSnapshot> cf = new CompletableFuture<>();
    JsonObject fields = new JsonObject();
    fields.addProperty("target_username", playerName);
    V1ApiManager.sendStaffActionFrame("check_membership", fields, ack -> {
      try {
        cf.complete(parseSnapshotAck(ack));
      } catch (Exception e) {
        cf.complete(snapshotUnavailable(e.getMessage()));
      }
    });
    return cf;
  }

  private static MembershipSnapshot parseSnapshotAck(JsonObject ack) {
    if (ack == null) {
      return snapshotUnavailable("empty ack");
    }
    String status = ack.has("status") && !ack.get("status").isJsonNull()
        ? ack.get("status").getAsString()
        : "error";
    if (!"ok".equals(status)) {
      String detail = ack.has("detail") && !ack.get("detail").isJsonNull()
          ? ack.get("detail").getAsString()
          : "unknown error";
      return snapshotUnavailable(detail);
    }
    return GSON.fromJson(ack, MembershipSnapshot.class);
  }

  private static MembershipSnapshot snapshotUnavailable(String detail) {
    // GSON-deserialise from a synthetic JSON object so optional fields
    // default cleanly. The renderer keys off `discord == null` to decide
    // whether the snapshot succeeded.
    JsonObject stub = new JsonObject();
    stub.addProperty("target_uuid", "");
    stub.addProperty("target_username", "");
    stub.addProperty("stage_2_active", false);
    stub.addProperty("blocklisted", false);
    stub.addProperty("blocklist_reason", detail == null ? "snapshot unavailable" : detail);
    stub.addProperty("in_returners_guild", false);
    // discord intentionally omitted -> null
    return GSON.fromJson(stub, MembershipSnapshot.class);
  }

  /**
   * Orchestrates the full {@code /wv check} readout for {@code playerName}:
   * Mojang lookup → Wynncraft player profile → Returners roster + dazebot
   * snapshot, then renders the combined block line-by-line.
   *
   * <p>Failures are surfaced as a single error line; partial results
   * (e.g. Wynn fetch succeeds but the snapshot doesn't) still render the
   * portions that resolved.</p>
   */
  public static void checkUser(String playerName) {
    HttpRequest uuidRequest = HttpRequest.newBuilder()
        .uri(MojangApi.getUserUUID(playerName))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    HTTP_CLIENT.sendAsync(uuidRequest, HttpResponse.BodyHandlers.ofString())
        .thenCompose(response -> {
          if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            renderError("Mojang lookup failed for '" + playerName + "' (status "
                + response.statusCode() + ")");
            return CompletableFuture.completedFuture(null);
          }
          UserUUID userUUID = GSON.fromJson(response.body(), UserUUID.class);
          return fetchAndRender(playerName, userUUID);
        })
        .exceptionally(e -> {
          renderError("Lookup failed: " + e.getMessage());
          return null;
        });
  }

  private static CompletableFuture<Void> fetchAndRender(String playerName, UserUUID uuid) {
    HttpRequest wynnRequest = HttpRequest.newBuilder()
        .uri(WynnCraftApi.playerInfo(formatFromInput(uuid.id)))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    // Three independent fetches: Wynn player, Returners roster (only if
    // Wynn says they're in vets), dazebot snapshot. We wait for all three
    // before rendering so the block is contiguous in chat.
    CompletableFuture<User> wynnFuture = HTTP_CLIENT
        .sendAsync(wynnRequest, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            return null;
          }
          return GSON.fromJson(response.body(), User.class);
        })
        .exceptionally(e -> null);

    CompletableFuture<MembershipSnapshot> snapFuture = membershipSnapshot(playerName);

    return wynnFuture.thenCompose(user -> {
      CompletableFuture<ReturnersMembership> returnersFuture =
          (user != null && user.isInVets())
              ? returnersMembership(uuid.id)
              : CompletableFuture.completedFuture(ReturnersMembership.notInGuild());
      return returnersFuture.thenCombine(snapFuture,
          (returners, snapshot) -> {
            Minecraft.getInstance().execute(
                () -> renderCheck(playerName, uuid, user, returners, snapshot));
            return null;
          });
    });
  }

  // ── Rendering ──────────────────────────────────────────────────────

  private static void renderError(String message) {
    Minecraft.getInstance().execute(() ->
        ChatUtils.sendLocalMessage(
            Component.literal(message).withStyle(ChatFormatting.RED)));
  }

  private static void renderCheck(
      String requestedName,
      UserUUID uuid,
      User user,
      ReturnersMembership returners,
      MembershipSnapshot snapshot) {
    String displayName = (user != null && user.getUsername() != null)
        ? user.getUsername()
        : requestedName;

    // Item 0: username + UUID
    ChatUtils.sendLocalMessage(
        Component.literal(displayName).withStyle(VALUE_STYLE)
            .append(Component.literal("  (" + uuid.id + ")").setStyle(MUTED_STYLE)));

    // Item 0a: legacy name (only if Returners roster gave us one)
    if (returners != null && returners.legacyName() != null
        && !returners.legacyName().equalsIgnoreCase(displayName)) {
      ChatUtils.sendLocalMessage(label("Legacy name: ")
          .append(Component.literal(returners.legacyName())
              .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))));
    }

    if (user == null) {
      // Items 1–5 collapse when there's no Wynn profile.
      ChatUtils.sendLocalMessage(label("Wynncraft profile: ")
          .append(Component.literal("(no Wynncraft data)").setStyle(HIDDEN_STYLE)));
    } else {
      renderWynnLines(user);
    }

    renderGuildLine(user, returners, snapshot);
    renderVeteranLine(user);
    renderBlocklistLine(snapshot);
    renderDiscordLinkLine(snapshot);
    renderStage2Line(snapshot);
  }

  private static void renderWynnLines(User user) {
    // Item 1: first seen
    String firstJoin = user.getFirstJoinDate();
    if (firstJoin == null) {
      ChatUtils.sendLocalMessage(label("First seen on Wynncraft: ").append(hidden()));
    } else {
      ChatUtils.sendLocalMessage(label("First seen on Wynncraft: ")
          .append(Component.literal(firstJoin).setStyle(VALUE_STYLE))
          .append(Component.literal("  (" + toRelativeDateLabel(firstJoin) + ")")
              .setStyle(MUTED_STYLE)));
    }

    // Item 2: last seen
    String lastJoin = user.getLastJoinDate();
    if (lastJoin == null) {
      ChatUtils.sendLocalMessage(label("Last seen on Wynncraft: ").append(hidden()));
    } else {
      ChatUtils.sendLocalMessage(label("Last seen on Wynncraft: ")
          .append(Component.literal(toRelativeDateLabel(lastJoin)).setStyle(VALUE_STYLE))
          .append(Component.literal("  (" + lastJoin + ")").setStyle(MUTED_STYLE)));
    }

    // Item 3: playtime
    Float playtime = user.getPlaytime();
    if (playtime == null) {
      ChatUtils.sendLocalMessage(label("Playtime: ").append(hidden()));
    } else {
      ChatUtils.sendLocalMessage(label("Playtime: ")
          .append(Component.literal(formatHours(playtime) + " hours").setStyle(VALUE_STYLE)));
    }
  }

  private static String formatHours(float hours) {
    // Show one decimal place; users care about the order of magnitude.
    return String.format(Locale.ROOT, "%,.1f", hours);
  }

  private static void renderGuildLine(
      User user, ReturnersMembership returners, MembershipSnapshot snapshot) {
    MutableComponent line = label("Guild: ");

    if (user == null) {
      line.append(Component.literal("(no Wynncraft profile)").setStyle(HIDDEN_STYLE));
      ChatUtils.sendLocalMessage(line);
      return;
    }

    Guild guild = user.getGuild();
    boolean inVets = user.isInVets() && (returners == null || returners.inGuild());

    if (inVets) {
      line.append(Component.literal("Returners").setStyle(VALUE_STYLE));
      String rank = returners != null ? returners.rank() : null;
      if (rank == null && guild != null) rank = guild.getRank();
      if (rank != null && !rank.isEmpty()) {
        line.append(Component.literal("  —  ").setStyle(LABEL_STYLE))
            .append(Component.literal(rank).setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)));
      }
      ChatUtils.sendLocalMessage(line);
      return;
    }

    if (guild != null && guild.getName() != null) {
      // Player is in a non-Returners guild.
      line.append(Component.literal(guild.getName()).setStyle(VALUE_STYLE));
      if (guild.getPrefix() != null && !guild.getPrefix().isEmpty()) {
        line.append(Component.literal("  [").setStyle(LABEL_STYLE))
            .append(Component.literal(guild.getPrefix())
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)))
            .append(Component.literal("]").setStyle(LABEL_STYLE));
      }
      if (guild.getRank() != null && !guild.getRank().isEmpty()) {
        line.append(Component.literal("  —  ").setStyle(LABEL_STYLE))
            .append(Component.literal(guild.getRank())
                .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)));
      }
      ChatUtils.sendLocalMessage(line);
      return;
    }

    // Player is guildless according to Wynn — annotate with dazebot's
    // membership state so we surface "guildless but on the vets roster".
    MembershipSnapshot.Discord disc = snapshot != null ? snapshot.getDiscord() : null;
    String tier = disc != null ? disc.getTier() : null;
    boolean hiatus = disc != null && disc.isHiatus();
    if ("member".equals(tier)) {
      line.append(Component.literal("guildless").setStyle(HIDDEN_STYLE))
          .append(Component.literal("  —  appears in vets roster (cached)").setStyle(HIDDEN_STYLE));
    } else if ("waitlist".equals(tier)) {
      line.append(Component.literal("guildless").setStyle(VALUE_STYLE))
          .append(Component.literal("  —  on waitlist").setStyle(VALUE_STYLE));
    } else if ("honourary".equals(tier)) {
      line.append(Component.literal("guildless").setStyle(VALUE_STYLE))
          .append(Component.literal("  —  honourary").setStyle(VALUE_STYLE));
    } else if (hiatus) {
      line.append(Component.literal("guildless").setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)))
          .append(Component.literal("  —  hiatus").setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)));
    } else {
      line.append(Component.literal("guildless").setStyle(LABEL_STYLE));
    }
    ChatUtils.sendLocalMessage(line);
  }

  private static void renderVeteranLine(User user) {
    MutableComponent line = label("Veteran tag: ");
    if (user == null) {
      line.append(Component.literal("(no Wynncraft data)").setStyle(HIDDEN_STYLE));
    } else {
      Boolean vet = user.getVeteran();
      if (vet == null) {
        line.append(hidden());
      } else if (vet) {
        line.append(Component.literal("yes").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
      } else {
        line.append(Component.literal("no").setStyle(LABEL_STYLE));
      }
    }
    ChatUtils.sendLocalMessage(line);
  }

  private static void renderBlocklistLine(MembershipSnapshot snapshot) {
    MutableComponent line = label("Blocklist: ");
    if (snapshot == null || snapshot.getDiscord() == null) {
      line.append(Component.literal("(snapshot unavailable").setStyle(MUTED_STYLE));
      String detail = snapshot != null ? snapshot.getBlocklistReason() : null;
      if (detail != null && !detail.isEmpty()) {
        line.append(Component.literal(": " + detail).setStyle(MUTED_STYLE));
      }
      line.append(Component.literal(")").setStyle(MUTED_STYLE));
    } else if (snapshot.isBlocklisted()) {
      line.append(Component.literal("BLOCKED").setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
      String reason = snapshot.getBlocklistReason();
      if (reason != null && !reason.isEmpty()) {
        line.append(Component.literal("  —  \"").setStyle(LABEL_STYLE))
            .append(Component.literal(reason)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.RED).withItalic(true)))
            .append(Component.literal("\"").setStyle(LABEL_STYLE));
      }
    } else {
      line.append(Component.literal("clean").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
    }
    ChatUtils.sendLocalMessage(line);
  }

  private static void renderDiscordLinkLine(MembershipSnapshot snapshot) {
    MutableComponent line = label("Discord link: ");
    MembershipSnapshot.Discord disc = snapshot != null ? snapshot.getDiscord() : null;
    if (disc == null) {
      line.append(Component.literal("(snapshot unavailable)").setStyle(MUTED_STYLE));
      ChatUtils.sendLocalMessage(line);
      return;
    }
    if (!disc.isLinked()) {
      line.append(Component.literal("not linked").setStyle(LABEL_STYLE));
      ChatUtils.sendLocalMessage(line);
      return;
    }

    String display = disc.getDiscDisplay();
    String discUuid = disc.getDiscUuid();
    if (display == null || display.isEmpty()) {
      display = discUuid != null ? discUuid : "<unknown>";
    }
    String tier = disc.getTier();

    Style tierStyle;
    String tierLabel;
    if (!disc.isInGuild()) {
      // Linked but the Discord user has left vetscord.
      tierStyle = Style.EMPTY.withColor(ChatFormatting.YELLOW);
      tierLabel = "(left server)";
    } else {
      switch (tier == null ? "" : tier) {
        case "member" -> {
          tierStyle = Style.EMPTY.withColor(ChatFormatting.GREEN);
          tierLabel = "member";
        }
        case "honourary" -> {
          tierStyle = Style.EMPTY.withColor(ChatFormatting.GOLD);
          tierLabel = "honourary";
        }
        case "waitlist" -> {
          tierStyle = Style.EMPTY.withColor(ChatFormatting.AQUA);
          tierLabel = "waitlist";
        }
        default -> {
          tierStyle = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA);
          tierLabel = "registered";
        }
      }
    }

    line.append(Component.literal("@" + display).setStyle(VALUE_STYLE));
    if (discUuid != null && !discUuid.isEmpty()) {
      line.append(Component.literal("  (" + discUuid + ")").setStyle(MUTED_STYLE));
    }
    line.append(Component.literal("  —  ").setStyle(LABEL_STYLE))
        .append(Component.literal(tierLabel).setStyle(tierStyle));

    if (disc.isWaitlistedModifier() && !"waitlist".equals(tier)) {
      line.append(Component.literal("  +waitlist").setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
    }
    if (disc.isHiatus()) {
      line.append(Component.literal("  (hiatus)").setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)));
    }
    ChatUtils.sendLocalMessage(line);
  }

  private static void renderStage2Line(MembershipSnapshot snapshot) {
    MutableComponent line = label("Vetsmod client key: ");
    if (snapshot == null || snapshot.getDiscord() == null) {
      line.append(Component.literal("(snapshot unavailable)").setStyle(MUTED_STYLE));
    } else if (snapshot.isStage2Active()) {
      line.append(Component.literal("active").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
    } else {
      line.append(Component.literal("inactive").setStyle(LABEL_STYLE));
    }
    ChatUtils.sendLocalMessage(line);
  }

  private static MutableComponent label(String text) {
    return Component.literal(text).setStyle(LABEL_STYLE);
  }

  private static MutableComponent hidden() {
    return Component.literal("(hidden)").setStyle(HIDDEN_STYLE);
  }
}
