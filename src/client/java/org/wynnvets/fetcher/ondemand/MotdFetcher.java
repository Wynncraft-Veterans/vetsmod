package org.wynnvets.fetcher.ondemand;

import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import org.wynnvets.api.WynnCraftApi;
import org.wynnvets.api.VetsApi;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * On-demand fetcher for the guild Message of the Day and player information.
 *
 * <p>Provides asynchronous methods that return {@link CompletableFuture} results,
 * suitable for use from command handlers and lifecycle hooks.</p>
 */
public class MotdFetcher {
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  /**
   * Fetches the MOTD from the API asynchronously
   *
   * @return CompletableFuture containing the MOTD Component
   */
  public static CompletableFuture<MutableComponent> fetchMotd() {
    return fetchFromUri(VetsApi.MOTD);
  }

  /**
   * Fetches the guild MOTD from the API asynchronously.
   *
   * @return CompletableFuture containing the guild MOTD Component, or empty
   *         literal if the server returned an empty body
   */
  public static CompletableFuture<MutableComponent> fetchGuildMotd() {
    return fetchFromUri(VetsApi.GUILD_MOTD);
  }

  private static CompletableFuture<MutableComponent> fetchFromUri(java.net.URI uri) {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(uri)
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() == HttpURLConnection.HTTP_OK) {
            return Component.literal(response.body());
          } else {
            return Component.literal("Failed to fetch MOTD (Status: " + response.statusCode() + ")");
          }
        })
        .exceptionally(e -> Component.literal("Error fetching MOTD: " + e.getMessage()));
  }

  /**
   * Fetches raw player information from the WynnCraft API.
   *
   * @param playerUUID the UUID of the player to look up
   * @return a future resolving to the raw API response as a text component
   */
  public static CompletableFuture<MutableComponent> getPlayerInformation(UUID playerUUID) {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(WynnCraftApi.playerInfo(playerUUID))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() == HttpURLConnection.HTTP_OK) {
            return Component.literal(response.body());
          } else {
            return Component.literal("Failed to fetch player information (Status: " + response.statusCode() + ")");
          }
        })
        .exceptionally(e -> Component.literal("Error fetching player information: " + e.getMessage()));
  }
}
