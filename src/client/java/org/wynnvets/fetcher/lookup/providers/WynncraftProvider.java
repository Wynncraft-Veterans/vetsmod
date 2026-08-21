package org.wynnvets.fetcher.lookup.providers;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.wynnvets.api.WynnCraftApi;
import org.wynnvets.datamodels.User;
import org.wynnvets.fetcher.lookup.LookupProvider;
import org.wynnvets.fetcher.lookup.LookupResult;
import org.wynnvets.fetcher.lookup.ProviderOutcome;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.Json;

/**
 * Primary resolver: Wynncraft's {@code /v3/player/{nameOrUuid}}.
 *
 * <p>This is the most valuable provider in the cascade: it returns both
 * the UUID and the full {@link User} profile in one call, so a Wynncraft
 * hit lets the invite gate skip its secondary Wynn fetch entirely.</p>
 */
public final class WynncraftProvider implements LookupProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;

    public WynncraftProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String id() {
        return "wynncraft";
    }

    @Override
    public CompletableFuture<ProviderOutcome> lookup(String name) {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(WynnCraftApi.playerInfo(name))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
        return httpClient
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::translate)
                .exceptionally(
                        e -> {
                            VetsLogger.debug(
                                    "Wynncraft lookup '{}' threw: {}", name, e.getMessage());
                            return ProviderOutcome.transientMiss();
                        });
    }

    private ProviderOutcome translate(HttpResponse<String> resp) {
        int code = resp.statusCode();
        if (code == HttpURLConnection.HTTP_OK) {
            try {
                User profile = Json.GSON.fromJson(resp.body(), User.class);
                if (profile == null || profile.getUuid() == null || profile.getUuid().isBlank()) {
                    // Malformed 200 — treat as transient rather than "doesn't exist".
                    return ProviderOutcome.transientMiss();
                }
                UUID uuid = UUID.fromString(profile.getUuid());
                String canonical =
                        profile.getUsername() != null ? profile.getUsername() : profile.getUuid();
                return ProviderOutcome.hit(LookupResult.ofWynncraft(canonical, uuid, profile));
            } catch (RuntimeException e) {
                VetsLogger.debug("Wynncraft 200 parse failed: {}", e.getMessage());
                return ProviderOutcome.transientMiss();
            }
        }
        if (code == HttpURLConnection.HTTP_NOT_FOUND) {
            return ProviderOutcome.notFound();
        }
        return ProviderOutcome.transientMiss();
    }
}
