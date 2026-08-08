package org.wynnvets.fetcher.lookup.providers;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.wynnvets.fetcher.lookup.LookupProvider;
import org.wynnvets.fetcher.lookup.LookupResult;
import org.wynnvets.fetcher.lookup.ProviderOutcome;
import org.wynnvets.fetcher.lookup.Uuids;
import org.wynnvets.logging.VetsLogger;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Mojang Services resolver:
 * {@code https://api.minecraftservices.com/minecraft/profile/lookup/name/{name}}.
 *
 * <p>Separate from legacy {@code api.mojang.com}; historically a different
 * rate-limit bucket.</p>
 */
public final class MojangServicesProvider implements LookupProvider {

  private static final Gson GSON = new Gson();
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient httpClient;

  public MojangServicesProvider(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  @Override
  public String id() {
    return "mojang-services";
  }

  @Override
  public CompletableFuture<ProviderOutcome> lookup(String name) {
    URI uri = URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/name/"
        + URLEncoder.encode(name, StandardCharsets.UTF_8));
    HttpRequest req = HttpRequest.newBuilder()
        .uri(uri)
        .timeout(TIMEOUT)
        .GET()
        .build();
    return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
        .thenApply(this::translate)
        .exceptionally(e -> {
          VetsLogger.debug("Mojang Services lookup '{}' threw: {}", name, e.getMessage());
          return ProviderOutcome.transientMiss();
        });
  }

  private ProviderOutcome translate(HttpResponse<String> resp) {
    int code = resp.statusCode();
    if (code == HttpURLConnection.HTTP_NOT_FOUND
        || code == HttpURLConnection.HTTP_NO_CONTENT) {
      return ProviderOutcome.notFound();
    }
    if (code != HttpURLConnection.HTTP_OK) {
      return ProviderOutcome.transientMiss();
    }
    try {
      JsonElement parsed = GSON.fromJson(resp.body(), JsonElement.class);
      if (parsed == null || !parsed.isJsonObject()) return ProviderOutcome.transientMiss();
      JsonObject obj = parsed.getAsJsonObject();
      String id = obj.has("id") && !obj.get("id").isJsonNull()
          ? obj.get("id").getAsString() : null;
      String username = obj.has("name") && !obj.get("name").isJsonNull()
          ? obj.get("name").getAsString() : null;
      UUID uuid = Uuids.parse(id);
      if (uuid == null) return ProviderOutcome.transientMiss();
      String canonical = username != null && !username.isBlank() ? username : id;
      return ProviderOutcome.hit(LookupResult.ofGeneric("mojang-services", canonical, uuid));
    } catch (RuntimeException e) {
      VetsLogger.debug("Mojang Services 200 parse failed: {}", e.getMessage());
      return ProviderOutcome.transientMiss();
    }
  }
}
