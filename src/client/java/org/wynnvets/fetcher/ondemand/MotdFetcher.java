package org.wynnvets.fetcher.ondemand;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.api.VetsApi;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.util.HttpClients;

/**
 * On-demand fetcher for the guild Message of the Day.
 *
 * <p>Provides asynchronous methods that return {@link CompletableFuture} results,
 * suitable for use from command handlers and lifecycle hooks.</p>
 */
public class MotdFetcher {
    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

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
        HttpRequest request =
                HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(5)).GET().build();

        return HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        response -> {
                            if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                                return ChatUtils.literalWithUrls(response.body(), Style.EMPTY);
                            } else {
                                return Component.literal(
                                        "Failed to fetch MOTD (Status: "
                                                + response.statusCode()
                                                + ")");
                            }
                        })
                .exceptionally(e -> Component.literal("Error fetching MOTD: " + e.getMessage()));
    }
}
