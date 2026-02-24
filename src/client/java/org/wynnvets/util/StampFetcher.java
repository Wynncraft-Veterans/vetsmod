package org.wynnvets.util;

import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wynnvets.constants.WVApi;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class StampFetcher {
  private static final Logger LOGGER = LoggerFactory.getLogger("vetsmod");
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  /**
   * Fetches the stamp from the API and returns an appropriate message component
   *
   * @return CompletableFuture containing the message Component, or null if no message should be displayed
   */
  public static CompletableFuture<MutableComponent> fetchStampAndCreateMessage() {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(WVApi.Stamp)
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() == HttpURLConnection.HTTP_OK) {
            try {
              long stamp = Long.parseLong(response.body().trim());
              return createMessageForStamp(stamp);
            } catch (NumberFormatException e) {
              LOGGER.warn("Failed to parse annihilation stamp: {}", e.getMessage());
              return null;
            }
          } else {
            LOGGER.warn("Failed to fetch annihilation stamp (Status: {})", response.statusCode());
            return null;
          }
        })
        .exceptionally(e -> {
          LOGGER.error("Error fetching annihilation stamp: {}", e.getMessage());
          return null;
        });
  }

  /**
   * Fetches the stamp from the API and returns a message for /wv anni.
   * If the stamp is in the past, returns a specific "not announced" message.
   *
   * @return CompletableFuture containing the message Component, or null when unavailable
   */
  public static CompletableFuture<MutableComponent> fetchStampAndCreateAnniCommandMessage() {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(WVApi.Stamp)
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() == HttpURLConnection.HTTP_OK) {
            try {
              long stamp = Long.parseLong(response.body().trim());
              MutableComponent message = createMessageForStamp(stamp);
              if (message != null) {
                return message;
              }

              if (isStampInPast(stamp)) {
                return Component.literal("The time for the next annihilation has not yet been announced");
              }

              return null;
            } catch (NumberFormatException e) {
              LOGGER.warn("Failed to parse annihilation stamp: {}", e.getMessage());
              return null;
            }
          } else {
            LOGGER.warn("Failed to fetch annihilation stamp (Status: {})", response.statusCode());
            return null;
          }
        })
        .exceptionally(e -> {
          LOGGER.error("Error fetching annihilation stamp: {}", e.getMessage());
          return null;
        });
  }

  /**
   * Creates the appropriate message based on the stamp timestamp
   *
   * @param stamp Unix timestamp from the API
   * @return MutableComponent message, or null if timestamp is in the past
   */
  private static MutableComponent createMessageForStamp(long stamp) {
    long currentTime = System.currentTimeMillis() / 1000; // Convert to seconds
    long timeDiff = stamp - currentTime;

    // If in the past, don't display anything
    if (timeDiff <= 0) {
      return null;
    }

    // Convert time difference to minutes and hours
    long totalMinutes = timeDiff / 60;
    long hours = totalMinutes / 60;
    long minutes = totalMinutes % 60;

    // If less than 1 hour
    if (totalMinutes < 60) {
      return createShortMessage((int) totalMinutes);
    } else {
      return createLongMessage((int) hours, (int) minutes);
    }
  }

  private static boolean isStampInPast(long stamp) {
    long currentTime = System.currentTimeMillis() / 1000;
    return stamp <= currentTime;
  }

  /**
   * Creates the message for when annihilation is more than 1 hour away
   *
   * @param hours   Number of hours until annihilation
   * @param minutes Number of remaining minutes until annihilation
   * @return MutableComponent with the formatted message
   */
  private static MutableComponent createLongMessage(int hours, int minutes) {
    // Line 1: &c&lAnnhilation &4&lreturns in &c&l<int> hours <int> mins&4&l!
    MutableComponent line1 = Component.literal("Annihilation ")
        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
        .append(Component.literal("returns in ")
            .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
        .append(Component.literal(hours + " hours " + minutes + " mins")
            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
        .append(Component.literal("!")
            .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));

    // Line 2: &7Please remember to show up one hour early or RSVP.
    MutableComponent line2 = Component.literal("\nPlease remember to show up one hour early or RSVP.")
        .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false));

    // Line 3: &4For more info, visit &c&o&nthis page&4!
    MutableComponent line3 = Component.literal("\n")
        .append(Component.literal("For more info, visit ")
            .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_RED).withBold(false)))
        .append(Component.literal("this page")
            .withStyle(Style.EMPTY
                .withColor(ChatFormatting.RED)
                .withItalic(true)
                .withUnderlined(true)
                .withBold(false)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(WVApi.Anni.toString())))))
        .append(Component.literal("!")
            .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_RED).withBold(false)));

    return line1.append(line2).append(line3);
  }

  /**
   * Creates the message for when annihilation is less than 1 hour away
   *
   * @param minutes Number of minutes until annihilation
   * @return MutableComponent with the formatted message
   */
  private static MutableComponent createShortMessage(int minutes) {
    // Line 1: &cAnnhilation is in <int> mins&4!
    MutableComponent line1 = Component.literal("Annihilation is in ")
        .withStyle(ChatFormatting.RED)
        .append(Component.literal(minutes + " mins")
            .withStyle(ChatFormatting.RED))
        .append(Component.literal("!")
            .withStyle(ChatFormatting.DARK_RED));

    // Line 2: &8Click [here](https://wynnvets.org/anni) for more info!
    MutableComponent line2 = Component.literal("\nClick ")
        .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withBold(false))
        .append(Component.literal("here")
            .withStyle(Style.EMPTY
                .withColor(ChatFormatting.DARK_GRAY)
                .withBold(false)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(WVApi.Anni.toString())))))
        .append(Component.literal(" for more info!")
            .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withBold(false)));

    return line1.append(line2);
  }
}
