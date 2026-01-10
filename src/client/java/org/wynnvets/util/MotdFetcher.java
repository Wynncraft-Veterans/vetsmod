package org.wynnvets.util;

import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;
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


    public static HashMap<String, String> cachedNames = new HashMap<>();

    public static HashMap<String, String> cachedUUIDs = new HashMap<>();

    public static CompletableFuture<MutableComponent> getUUID(String name) {
        //if(cachedUUIDs.get(name.toLowerCase()) != null) return cachedUUIDs.get(name.toLowerCase());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("https://api.mojang.com/users/profiles/minecraft/%s", name)))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return Component.literal(response.body());
                    } else {
                        return Component.literal("Failed to fetch UUID (Status: " + response.statusCode() + ")");
                    }
                })
                .exceptionally(e -> Component.literal("Error fetching UUID: " + e.getMessage()));
    }

    public static CompletableFuture<MutableComponent> getPlayerInformation(UUID uuid) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("https://api.wynncraft.com/v3/player/%s", uuid.toString())))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return Component.literal(response.body());
                    } else {
                        return Component.literal("Failed to fetch player information (Status: " + response.statusCode() + ")");
                    }
                })
                .exceptionally(e -> Component.literal("Error fetching player information: " + e.getMessage()));
    }
}
