package org.wynnvets.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class ReturnFetcher {
    private static final String RETURN_ENDPOINT = "http://api.wynnvets.org/v0/outbound/return";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Fetches the Return information from the API asynchronously
     * @return CompletableFuture containing the Return Component
     */
    public static CompletableFuture<MutableComponent> fetchReturn() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RETURN_ENDPOINT))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .<MutableComponent>thenApply(response -> {
                    if (response.statusCode() == 200) {
                        try {
                            // Parse the JSON response and convert it to a Component
                            Minecraft minecraft = Minecraft.getInstance();
                            MutableComponent component = Component.Serializer.fromJson(
                                response.body(), 
                                minecraft.level.registryAccess()
                            );
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
