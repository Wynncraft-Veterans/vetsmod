package org.wynnvets.commands;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.api.MojangApi;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.api.WynnCraftApi;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.datamodels.MembershipSnapshot;
import org.wynnvets.datamodels.User;
import org.wynnvets.datamodels.UserUUID;
import org.wynnvets.guild.GuildStateManager;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Pre-dispatch gate for {@code /gu invite} and {@code /guild invite}.
 *
 * <p>Only fires for Returners members — vets-mod users in other guilds
 * (waitlist, honourary, etc.) get pass-through behaviour because they
 * have no guild-invite permission server-side.</p>
 *
 * <p>For <b>non-staff</b> Returners members the gate blocks with a
 * deliberately vague "please ask staff" message when the target is
 * dazebot-blocklisted, joined Wynncraft on or after 2020, or the guild
 * is so full that the next slot is reserved for the waitlist queue.</p>
 *
 * <p>For <b>staff</b> (confirmed-staff via the vetsmod token) the gate
 * surfaces the exact reason and offers a clickable
 * {@code /wv invite-force <name>} bypass. The slot-pressure check is
 * skipped for staff entirely — they're trusted to make the call.</p>
 */
public final class InviteGate {

  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private static final Gson GSON = new Gson();

  /** Players who joined Wynncraft on or after this date do not qualify
   *  under the "vet" criterion (legacy account requirement). */
  private static final LocalDate VET_JOIN_CUTOFF = LocalDate.of(2020, 1, 1);

  private static final String GENERIC_REJECTION =
      "Please ask staff before inviting this user as they do not qualify "
      + "under the usual criteria.";

  private InviteGate() {}

  /**
   * Entry point: runs the gate for {@code target}. On allow, dispatches
   * {@code /gu invite <target>} via the local game connection. On
   * block, renders an explanation in chat. On staff warn, renders the
   * reason plus a clickable override.
   */
  public static void checkInvite(String target) {
    if (target == null || target.isBlank()) return;
    boolean isStaff = GuildStateManager.isConfirmedStaff();

    HttpRequest uuidReq = HttpRequest.newBuilder()
        .uri(MojangApi.getUserUUID(target))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    HTTP_CLIENT.sendAsync(uuidReq, HttpResponse.BodyHandlers.ofString())
        .thenAccept(resp -> {
          if (resp.statusCode() != HttpURLConnection.HTTP_OK) {
            renderError("Could not resolve '" + target + "' on Mojang (status "
                + resp.statusCode() + "). Invite cancelled."
                + (isStaff ? " Staff: use /wv invite-force to override." : ""));
            return;
          }
          UserUUID uuid = GSON.fromJson(resp.body(), UserUUID.class);
          gatherAndDecide(target, uuid, isStaff);
        })
        .exceptionally(e -> {
          renderError("Mojang lookup failed: " + e.getMessage());
          return null;
        });
  }

  /**
   * Bypass entry point used by {@code /wv invite-force}. Skips all
   * vetsmod-side checks and sends {@code /gu invite <target>} directly
   * to the server via {@link GuildChatDispatcher#sendCommandBypassed},
   * which suppresses the chat-command mixin so this won't re-trigger
   * {@link #checkInvite}.
   */
  public static void forceDispatch(String target) {
    GuildChatDispatcher.sendCommandBypassed("gu invite " + target);
  }

  // ── Async fan-out + decision ────────────────────────────────────────

  private static void gatherAndDecide(String target, UserUUID uuid, boolean isStaff) {
    HttpRequest wynnReq = HttpRequest.newBuilder()
        .uri(WynnCraftApi.playerInfo(formatUuid(uuid.id)))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    CompletableFuture<User> wynnFuture = HTTP_CLIENT
        .sendAsync(wynnReq, HttpResponse.BodyHandlers.ofString())
        .thenApply(resp -> resp.statusCode() == HttpURLConnection.HTTP_OK
            ? GSON.fromJson(resp.body(), User.class)
            : null)
        .exceptionally(e -> null);

    CompletableFuture<MembershipSnapshot> snapFuture = membershipSnapshot(target);

    // Staff skip the slot-pressure check entirely (#5), so no roster fetch.
    CompletableFuture<GuildSnapshot> guildFuture = isStaff
        ? CompletableFuture.completedFuture(null)
        : fetchReturnersSnapshot();

    CompletableFuture.allOf(wynnFuture, snapFuture, guildFuture).thenAccept(_ignore -> {
      User wynn = wynnFuture.join();
      MembershipSnapshot snap = snapFuture.join();
      GuildSnapshot guildSnap = guildFuture.join();
      Minecraft.getInstance().execute(
          () -> decide(target, wynn, snap, guildSnap, isStaff));
    });
  }

  private static void decide(
      String target,
      User wynn,
      MembershipSnapshot snap,
      GuildSnapshot guildSnap,
      boolean isStaff) {

    String canonicalName = (wynn != null && wynn.getUsername() != null)
        ? wynn.getUsername()
        : target;

    // Both checks depend on data we may have failed to fetch. Safety-block.
    if (snap == null || snap.getDiscord() == null) {
      renderError("Membership snapshot lookup failed for '" + canonicalName
          + "'. Invite cancelled."
          + (isStaff ? " Use /wv invite-force to override." : ""));
      return;
    }
    if (wynn == null) {
      renderError("Wynncraft profile lookup failed for '" + canonicalName
          + "'. Invite cancelled."
          + (isStaff ? " Use /wv invite-force to override." : ""));
      return;
    }

    boolean blocked = snap.isBlocklisted();

    // firstJoin may be hidden by Wynncraft privacy; treat hidden as a
    // soft "we can't verify they're a vet" -> staff get warn, non-staff
    // get blocked. (Non-vets shouldn't be invited blind.)
    String firstJoinText = wynn.getFirstJoinDate();
    LocalDate firstJoin = null;
    if (firstJoinText != null) {
      try {
        firstJoin = LocalDate.parse(firstJoinText);
      } catch (RuntimeException ignored) {
        firstJoin = null;
      }
    }
    boolean tooRecent = firstJoin != null && !firstJoin.isBefore(VET_JOIN_CUTOFF);
    boolean joinHidden = firstJoin == null;

    if (blocked || tooRecent || joinHidden) {
      if (isStaff) {
        renderStaffWarning(canonicalName, blocked, snap, tooRecent, joinHidden, firstJoin);
      } else {
        // Non-staff get the generic line regardless of which check failed.
        ChatUtils.sendLocalMessage(
            Component.literal(GENERIC_REJECTION).withStyle(ChatFormatting.RED));
      }
      return;
    }

    // Slot-pressure check (non-staff only).
    if (!isStaff && guildSnap != null) {
      int waitlistCount = snap.getWaitlistCount();
      int openSlots = getMaxGuildMembers(guildSnap.level()) - guildSnap.total();
      if (waitlistCount > 0 && openSlots <= waitlistCount + 1) {
        ChatUtils.sendLocalMessage(
            Component.literal(
                    "Returners is near capacity (" + Math.max(0, openSlots)
                        + " open slot" + (openSlots == 1 ? "" : "s")
                        + " vs " + waitlistCount + " on the waitlist). "
                        + "Please ask " + canonicalName
                        + " to join the waitlist instead of inviting them directly.")
                .withStyle(ChatFormatting.YELLOW));
        return;
      }
    }

    // All gates passed -> dispatch the original /gu invite to the server.
    forceDispatch(canonicalName);
  }

  // ── Render helpers ──────────────────────────────────────────────────

  private static void renderError(String msg) {
    Minecraft.getInstance().execute(() ->
        ChatUtils.sendLocalMessage(
            Component.literal(msg).withStyle(ChatFormatting.RED)));
  }

  private static void renderStaffWarning(
      String canonicalName,
      boolean blocked,
      MembershipSnapshot snap,
      boolean tooRecent,
      boolean joinHidden,
      LocalDate firstJoin) {

    MutableComponent header = Component.literal("⚠ ")
        .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))
        .append(Component.literal(canonicalName).setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));

    if (blocked) {
      String reason = snap.getBlocklistReason();
      if (reason == null || reason.isEmpty()) reason = "(no reason on file)";
      header.append(Component.literal(" is on the dazebot blocklist: ")
              .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
          .append(Component.literal("\"" + reason + "\"")
              .setStyle(Style.EMPTY.withColor(ChatFormatting.RED).withItalic(true)));
    } else if (tooRecent) {
      header.append(Component.literal(" joined Wynncraft ")
              .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
          .append(Component.literal(firstJoin.toString())
              .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)))
          .append(Component.literal(" — not a vet (cutoff " + VET_JOIN_CUTOFF + ").")
              .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)));
    } else if (joinHidden) {
      header.append(Component.literal(" has a privacy-hidden Wynncraft profile — ")
              .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
          .append(Component.literal("cannot verify they're a vet.")
              .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)));
    }
    ChatUtils.sendLocalMessage(header);

    Style overrideStyle = Style.EMPTY
        .withColor(ChatFormatting.GREEN)
        .withClickEvent(new ClickEvent.RunCommand("/wv invite-force " + canonicalName))
        .withHoverEvent(new HoverEvent.ShowText(
            Component.literal("Bypass the vetsmod check and dispatch "
                + "/gu invite " + canonicalName + " directly.")));
    ChatUtils.sendLocalMessage(
        Component.literal("  ").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))
            .append(Component.literal("[Invite anyway]").setStyle(overrideStyle)));
  }

  // ── Fetch helpers (duplicated from UserInfoFetcher; small + private) ─

  private static CompletableFuture<MembershipSnapshot> membershipSnapshot(String target) {
    CompletableFuture<MembershipSnapshot> cf = new CompletableFuture<>();
    JsonObject fields = new JsonObject();
    fields.addProperty("target_username", target);
    V1ApiManager.sendStaffActionFrame("check_membership", fields, ack -> {
      try {
        String status = ack.has("status") && !ack.get("status").isJsonNull()
            ? ack.get("status").getAsString()
            : "error";
        if ("ok".equals(status)) {
          cf.complete(GSON.fromJson(ack, MembershipSnapshot.class));
        } else {
          cf.complete(null);
        }
      } catch (Exception e) {
        cf.complete(null);
      }
    });
    return cf;
  }

  private static CompletableFuture<GuildSnapshot> fetchReturnersSnapshot() {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(WynnCraftApi.guildInfo("Returners"))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();
    return HTTP_CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString())
        .thenApply(resp -> {
          if (resp.statusCode() != HttpURLConnection.HTTP_OK) return null;
          try {
            JsonElement payload = GSON.fromJson(resp.body(), JsonElement.class);
            if (payload == null || !payload.isJsonObject()) return null;
            JsonObject obj = payload.getAsJsonObject();
            JsonElement members = obj.get("members");
            JsonElement levelEl = obj.get("level");
            if (members == null || !members.isJsonObject()) return null;
            if (levelEl == null || !levelEl.isJsonPrimitive()) return null;
            JsonElement total = members.getAsJsonObject().get("total");
            if (total == null || !total.isJsonPrimitive()) return null;
            return new GuildSnapshot(total.getAsInt(), levelEl.getAsInt());
          } catch (Exception e) {
            return null;
          }
        })
        .exceptionally(e -> null);
  }

  private record GuildSnapshot(int total, int level) {}

  // Wynncraft V3 /guild exposes level but not the slot cap. Table mirrors Wynnpool's getMaxGuildMembers (github.com/AiverAiva/Wynnpool apps/web/src/lib/guildUtils.ts).
  private static int getMaxGuildMembers(int level) {
    if (level == 1) return 4;
    if (level <= 5) return 8;
    if (level <= 14) return 16;
    if (level <= 23) return 26;
    if (level <= 32) return 38;
    if (level <= 41) return 48;
    if (level <= 53) return 60;
    if (level <= 65) return 72;
    if (level <= 74) return 80;
    if (level <= 80) return 86;
    if (level <= 86) return 92;
    if (level <= 92) return 98;
    if (level <= 95) return 102;
    if (level <= 98) return 106;
    if (level <= 100) return 110;
    if (level <= 103) return 114;
    if (level <= 106) return 118;
    if (level <= 109) return 122;
    if (level <= 112) return 126;
    if (level <= 115) return 130;
    if (level <= 118) return 140;
    if (level >= 119) return 150;
    return 0;
  }

  private static UUID formatUuid(String raw) {
    String c = raw.replace("-", "");
    return UUID.fromString(
        c.substring(0, 8) + "-" + c.substring(8, 12) + "-"
            + c.substring(12, 16) + "-" + c.substring(16, 20) + "-"
            + c.substring(20));
  }
}
