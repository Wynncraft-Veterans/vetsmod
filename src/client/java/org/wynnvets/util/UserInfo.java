package org.wynnvets.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.constants.MJApi;
import org.wynnvets.constants.WCApi;
import org.wynnvets.datamodels.User;
import org.wynnvets.datamodels.UserUUID;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public class UserInfo {
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private static final Pattern UUID_FIX = Pattern.compile("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})");

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
    return raw.replace("-", "").toLowerCase();
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

  private static CompletableFuture<Boolean> isInReturnersGuild(String uuid) {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(WCApi.GuildInfo("Returners"))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    String normalizedUuid = normalizeUuidText(uuid);

    return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            return false;
          }

          JsonElement payload = new Gson().fromJson(response.body(), JsonElement.class);
          return containsUuid(payload, normalizedUuid);
        })
        .exceptionally(e -> false);
  }


  public static CompletableFuture<MutableComponent> userInfo() {
    Minecraft minecraft = Minecraft.getInstance();
    LocalPlayer player = minecraft.player;

    if (player == null) {
      return null;
    }

    HttpRequest USER_INFO_REQUEST = HttpRequest.newBuilder()
        .uri(WCApi.PlayerInfo(player.getUUID()))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    return HTTP_CLIENT.sendAsync(USER_INFO_REQUEST, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() == HttpURLConnection.HTTP_OK) {
            User user = new Gson().fromJson(response.body(), User.class);
            return Component.literal(user.toString());
          } else {
            return Component.literal("Failed to fetch player information (Status: " + response.statusCode() + ")");
          }
        })
        .exceptionally(e -> Component.literal("Error fetching player info: " + e.getMessage()));
  }

  public static CompletableFuture<MutableComponent> checkUser(String playerName) {
    HttpRequest USER_UUID_REQUEST = HttpRequest.newBuilder()
        .uri(MJApi.GetUserUUID(playerName))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    return HTTP_CLIENT.sendAsync(USER_UUID_REQUEST, HttpResponse.BodyHandlers.ofString())
        .thenCompose(response -> {
          if (response.statusCode() == HttpURLConnection.HTTP_OK) {
            UserUUID userUUID = new Gson().fromJson(response.body(), UserUUID.class);
            return playerInfo(userUUID);
          } else {
            return CompletableFuture.completedFuture(
                Component.literal("Failed to fetch player info (Status: " + response.statusCode() + ")\n" + MJApi.GetUserUUID(playerName))
            );
          }
        })
        .exceptionally(e -> Component.literal("Error fetching player info: " + e.getMessage()));
  }


  private static CompletableFuture<MutableComponent> playerInfo(UserUUID uuid) {
    HttpRequest USER_AGE_REQUEST = HttpRequest.newBuilder()
        .uri(WCApi.PlayerInfo(formatFromInput(uuid.id)))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    return HTTP_CLIENT.sendAsync(USER_AGE_REQUEST, HttpResponse.BodyHandlers.ofString())
        .thenCompose(response -> {
          if (response.statusCode() == HttpURLConnection.HTTP_OK) {
            User user = new Gson().fromJson(response.body(), User.class);

            CompletableFuture<Boolean> returnersCheckFuture = user.isInVets()
                ? isInReturnersGuild(uuid.id)
                : CompletableFuture.completedFuture(false);

            return returnersCheckFuture.thenApply(isInReturners -> {
              StringBuilder sb = new StringBuilder();
              sb.append(String.format("%s's automated eligibility: [TODO]", user.getUsername()));
              sb.append(String.format("\nThis account joined on: %s", user.getFirstJoinDate()));
              sb.append(String.format("\nThis account was last seen on: %s", toRelativeDateLabel(user.getLastJoinDate())));

              // User's current guild information. If player API says vets, verify against
              // the Returners roster endpoint; missing UUID means guildless.
              sb.append("\nThis account is currently ");
              if (!user.isInGuild()) {
                sb.append("guildless");
              } else if (user.isInVets()) {
                sb.append(isInReturners ? "in vets" : "guildless");
              } else {
                sb.append("in another guild");
              }

              sb.append(String.format("\nThis account %s have the veteran tag", user.isVeteran() ? "does" : "doesn't"));
              sb.append("\nThis account is [TODO] subject to special criteria");

              return Component.literal(sb.toString());
            });
          } else {
            return CompletableFuture.completedFuture(
                Component.literal("Failed to fetch player info (Status: " + response.statusCode() + ")\n" + WCApi.PlayerInfo(formatFromInput(uuid.id)))
            );
          }
        })
        .exceptionally(e -> Component.literal("Error fetching player info: " + e.getMessage()));
  }
}
