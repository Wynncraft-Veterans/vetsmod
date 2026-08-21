package org.wynnvets.fetcher.lookup.providers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import org.wynnvets.fetcher.lookup.LookupProvider;
import org.wynnvets.fetcher.lookup.LookupResult;
import org.wynnvets.fetcher.lookup.ProviderOutcome;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.Json;

/**
 * Third-party resolver: {@code https://api.ashcon.app/mojang/v2/user/{name}}.
 *
 * <p>Returns {@code {uuid: "<dashed>", username: "..."}}. Permissive rate
 * limits.</p>
 */
public final class AshconProvider implements LookupProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;

    public AshconProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String id() {
        return "ashcon";
    }

    @Override
    public CompletableFuture<ProviderOutcome> lookup(String name) {
        URI uri =
                URI.create(
                        "https://api.ashcon.app/mojang/v2/user/"
                                + URLEncoder.encode(name, StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder().uri(uri).timeout(TIMEOUT).GET().build();
        return httpClient
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::translate)
                .exceptionally(
                        e -> {
                            VetsLogger.debug("Ashcon lookup '{}' threw: {}", name, e.getMessage());
                            return ProviderOutcome.transientMiss();
                        });
    }

    private ProviderOutcome translate(HttpResponse<String> resp) {
        int code = resp.statusCode();
        if (code == HttpURLConnection.HTTP_NOT_FOUND) {
            return ProviderOutcome.notFound();
        }
        if (code != HttpURLConnection.HTTP_OK) {
            return ProviderOutcome.transientMiss();
        }
        try {
            JsonElement parsed = Json.GSON.fromJson(resp.body(), JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) return ProviderOutcome.transientMiss();
            JsonObject obj = parsed.getAsJsonObject();
            String id =
                    obj.has("uuid") && !obj.get("uuid").isJsonNull()
                            ? obj.get("uuid").getAsString()
                            : null;
            String username =
                    obj.has("username") && !obj.get("username").isJsonNull()
                            ? obj.get("username").getAsString()
                            : null;
            if (id == null || id.isBlank()) return ProviderOutcome.transientMiss();
            UUID uuid = UUID.fromString(id);
            String canonical = username != null && !username.isBlank() ? username : id;
            return ProviderOutcome.hit(LookupResult.ofGeneric("ashcon", canonical, uuid));
        } catch (RuntimeException e) {
            VetsLogger.debug("Ashcon 200 parse failed: {}", e.getMessage());
            return ProviderOutcome.transientMiss();
        }
    }
}
