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
 * Third-party resolver: {@code https://playerdb.co/api/player/minecraft/{name}}.
 *
 * <p>Permissive rate limits relative to Mojang; used before falling back
 * to Mojang. Response shape:
 * {@code {success: true, data: {player: {id: "<dashed-uuid>", raw_id: "<undashed>"}}}}.
 * An unknown name answers HTTP 400 with {@code {code: "minecraft.invalid_username", success:
 * false}} (seen 2026-10-25), which is a definite miss; any other 400 is not.</p>
 */
public final class PlayerDbProvider implements LookupProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String USER_AGENT = "vetsmod (+https://wynnvets.org)";

    /** The 400 body's {@code code} for a name no Minecraft account holds. */
    private static final String UNKNOWN_NAME_CODE = "minecraft.invalid_username";

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
        return translate(resp.statusCode(), resp.body());
    }

    // Package-private for unit tests. See PlayerDbProviderTest.
    static ProviderOutcome translate(int code, String body) {
        if (code == HttpURLConnection.HTTP_NOT_FOUND) {
            return ProviderOutcome.notFound();
        }
        if (code == HttpURLConnection.HTTP_BAD_REQUEST) {
            return UNKNOWN_NAME_CODE.equals(errorCode(body))
                    ? ProviderOutcome.notFound()
                    : ProviderOutcome.transientMiss();
        }
        if (code != HttpURLConnection.HTTP_OK) {
            return ProviderOutcome.transientMiss();
        }
        try {
            JsonElement parsed = Json.GSON.fromJson(body, JsonElement.class);
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

    /** The {@code code} string of an error body, or {@code null} if it has none or is not JSON. */
    private static String errorCode(String body) {
        try {
            JsonElement parsed = Json.GSON.fromJson(body, JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) return null;
            JsonElement code = parsed.getAsJsonObject().get("code");
            return code != null && code.isJsonPrimitive() ? code.getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
