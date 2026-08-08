package org.wynnvets.fetcher.lookup.providers;

import com.google.gson.Gson;
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

/**
 * Third-party resolver: {@code https://playerdb.co/api/player/minecraft/{name}}.
 *
 * <p>Permissive rate limits relative to Mojang; used before falling back
 * to Mojang. Response shape:
 * {@code {success: true, data: {player: {id: "<dashed-uuid>", raw_id: "<undashed>"}}}}.</p>
 */
public final class PlayerDbProvider implements LookupProvider {

    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String USER_AGENT = "vetsmod (+https://wynnvets.org)";

    private final HttpClient httpClient;

    public PlayerDbProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String id() {
        return "playerdb";
    }

    @Override
    public CompletableFuture<ProviderOutcome> lookup(String name) {
        URI uri =
                URI.create(
                        "https://playerdb.co/api/player/minecraft/"
                                + URLEncoder.encode(name, StandardCharsets.UTF_8));
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(uri)
                        .header("User-Agent", USER_AGENT)
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
        return httpClient
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::translate)
                .exceptionally(
                        e -> {
                            VetsLogger.debug(
                                    "PlayerDB lookup '{}' threw: {}", name, e.getMessage());
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
            JsonElement parsed = GSON.fromJson(resp.body(), JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) return ProviderOutcome.transientMiss();
            JsonObject obj = parsed.getAsJsonObject();
            boolean success =
                    obj.has("success")
                            && !obj.get("success").isJsonNull()
                            && obj.get("success").getAsBoolean();
            if (!success) return ProviderOutcome.notFound();
            JsonObject data =
                    obj.has("data") && obj.get("data").isJsonObject()
                            ? obj.getAsJsonObject("data")
                            : null;
            JsonObject player =
                    data != null && data.has("player") && data.get("player").isJsonObject()
                            ? data.getAsJsonObject("player")
                            : null;
            if (player == null) return ProviderOutcome.transientMiss();
            String id =
                    player.has("id") && !player.get("id").isJsonNull()
                            ? player.get("id").getAsString()
                            : null;
            String username =
                    player.has("username") && !player.get("username").isJsonNull()
                            ? player.get("username").getAsString()
                            : null;
            if (id == null || id.isBlank()) return ProviderOutcome.transientMiss();
            UUID uuid = UUID.fromString(id);
            String canonical = username != null && !username.isBlank() ? username : id;
            return ProviderOutcome.hit(LookupResult.ofGeneric("playerdb", canonical, uuid));
        } catch (RuntimeException e) {
            VetsLogger.debug("PlayerDB 200 parse failed: {}", e.getMessage());
            return ProviderOutcome.transientMiss();
        }
    }
}
