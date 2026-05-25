package org.wynnvets.fetcher.lookup.providers;

import com.google.gson.Gson;
import org.wynnvets.api.MojangApi;
import org.wynnvets.datamodels.UserUUID;
import org.wynnvets.fetcher.lookup.LookupProvider;
import org.wynnvets.fetcher.lookup.LookupResult;
import org.wynnvets.fetcher.lookup.MojangCooldown;
import org.wynnvets.fetcher.lookup.PlayerLookup;
import org.wynnvets.fetcher.lookup.ProviderOutcome;
import org.wynnvets.fetcher.lookup.Uuids;
import org.wynnvets.logging.VetsLogger;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Last-resort resolver: legacy {@code api.mojang.com/users/profiles/minecraft/{name}}.
 *
 * <p>Self-gates via {@link MojangCooldown}: returns transient-miss without
 * any HTTP work when the cooldown is active, so a 429-triggered cooldown
 * naturally lets the cascade settle on
 * {@link org.wynnvets.fetcher.lookup.FailureReason#ALL_PROVIDERS_TRANSIENT}
 * (fail open) rather than burning the same Mojang quota repeatedly.</p>
 *
 * <p>On 429, parses {@code Retry-After} (seconds or HTTP-date) into
 * {@link MojangCooldown#record429(Duration)} so future requests skip this
 * provider for the appropriate window.</p>
 */
public final class MojangLegacyProvider implements LookupProvider {

  private static final Gson GSON = new Gson();
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient httpClient;

  public MojangLegacyProvider(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  @Override
  public String id() {
    return "mojang-legacy";
  }

  @Override
  public CompletableFuture<ProviderOutcome> lookup(String name) {
    if (PlayerLookup.isMojangSkipped() || MojangCooldown.isCoolingDown()) {
      return CompletableFuture.completedFuture(ProviderOutcome.transientMiss());
    }
    HttpRequest req = HttpRequest.newBuilder()
        .uri(MojangApi.getUserUUID(name))
        .timeout(TIMEOUT)
        .GET()
        .build();
    return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
        .thenApply(this::translate)
        .exceptionally(e -> {
          VetsLogger.debug("Mojang legacy lookup '{}' threw: {}", name, e.getMessage());
          return ProviderOutcome.transientMiss();
        });
  }

  private ProviderOutcome translate(HttpResponse<String> resp) {
    int code = resp.statusCode();
    if (code == HttpURLConnection.HTTP_OK) {
      try {
        UserUUID body = GSON.fromJson(resp.body(), UserUUID.class);
        if (body == null || body.id == null || body.id.isBlank()) {
          return ProviderOutcome.transientMiss();
        }
        UUID uuid = Uuids.parse(body.id);
        if (uuid == null) return ProviderOutcome.transientMiss();
        String canonical = body.name != null && !body.name.isBlank() ? body.name : body.id;
        return ProviderOutcome.hit(LookupResult.ofGeneric("mojang-legacy", canonical, uuid));
      } catch (RuntimeException e) {
        VetsLogger.debug("Mojang legacy 200 parse failed: {}", e.getMessage());
        return ProviderOutcome.transientMiss();
      }
    }
    if (code == HttpURLConnection.HTTP_NO_CONTENT
        || code == HttpURLConnection.HTTP_NOT_FOUND) {
      return ProviderOutcome.notFound();
    }
    if (code == 429) {
      Duration retryAfter = parseRetryAfter(resp);
      MojangCooldown.record429(retryAfter);
      VetsLogger.warn("Mojang legacy 429 — cooldown {}s",
          retryAfter == null ? 60 : retryAfter.toSeconds());
      return ProviderOutcome.transientMiss();
    }
    return ProviderOutcome.transientMiss();
  }

  private static Duration parseRetryAfter(HttpResponse<String> resp) {
    return resp.headers().firstValue("Retry-After")
        .map(MojangLegacyProvider::parseRetryAfterValue)
        .orElse(null);
  }

  private static Duration parseRetryAfterValue(String value) {
    String trimmed = value.trim();
    if (trimmed.isEmpty()) return null;
    // Seconds form
    try {
      long seconds = Long.parseLong(trimmed);
      if (seconds <= 0) return null;
      return Duration.ofSeconds(seconds);
    } catch (NumberFormatException ignored) {
      // fall through to HTTP-date
    }
    // HTTP-date form (RFC 7231 §7.1.1.1 — IMF-fixdate)
    try {
      ZonedDateTime when = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
      long deltaMs = when.toInstant().toEpochMilli() - System.currentTimeMillis();
      return deltaMs > 0 ? Duration.ofMillis(deltaMs) : null;
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }
}
