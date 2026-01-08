package org.wynnvets.util;

import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class MotdFetcher {
    private static final String MOTD_ENDPOINT = "http://api.wynnvets.org/v0/outbound/motd";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Fetches the MOTD from the API asynchronously
     * @return CompletableFuture containing the MOTD Component
     */
    public static CompletableFuture<MutableComponent> fetchMotd() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MOTD_ENDPOINT))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return Component.literal(response.body());
                    } else {
                        return Component.literal("Failed to fetch MOTD (Status: " + response.statusCode() + ")");
                    }
                })
                .exceptionally(e -> Component.literal("Error fetching MOTD: " + e.getMessage()));
    }
}
