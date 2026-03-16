package org.wynnvets.fetcher.ondemand;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.api.VetsApi;
import org.wynnvets.logging.VetsLogger;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * On-demand fetcher for the guild's current return event information.
 *
 * <p>Queries the VetsMod API for the active return event and formats the
 * response as a chat component for display via {@code /wv return}.</p>
 */
public class ReturnFetcher {
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  /**
   * Fetches the Return information from the API asynchronously
   *
   * @return CompletableFuture containing the Return Component
   */
  public static CompletableFuture<MutableComponent> fetchReturn() {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(VetsApi.RETURN)
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .<MutableComponent>thenApply(response -> {
          VetsLogger.debug("Return fetch response: {}", response.statusCode());
          if (response.statusCode() == HttpURLConnection.HTTP_OK) {
            try {
              // Parse the JSON response and convert it to a Component
              Minecraft minecraft = Minecraft.getInstance();
              JsonElement json = JsonParser.parseString(response.body());
              MutableComponent component = (MutableComponent) ComponentSerialization.CODEC
                  .parse(minecraft.level.registryAccess().createSerializationContext(JsonOps.INSTANCE), json)
                  .getOrThrow();
              return component != null ? component : Component.literal("Error: Received null component from API");
            } catch (Exception e) {
              return Component.literal("Error parsing return data: " + e.getMessage());
            }
          } else {
            return Component.literal("Failed to fetch return (Status: " + response.statusCode() + ")");
          }
        })
        .exceptionally(e -> Component.literal("Error fetching return: " + e.getMessage()));
  }
}
