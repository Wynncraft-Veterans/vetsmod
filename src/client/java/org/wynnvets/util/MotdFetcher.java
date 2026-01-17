package org.wynnvets.util;

import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import org.wynnvets.constants.WCApi;
import org.wynnvets.constants.WVApi;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class MotdFetcher {
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
                .uri(WVApi.Motd)
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

    public static CompletableFuture<MutableComponent> getPlayerInformation(UUID playerUUID) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(WCApi.PlayerInfo(playerUUID))
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
