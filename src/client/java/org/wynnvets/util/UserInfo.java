package org.wynnvets.util;

import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.constants.WCApi;
import org.wynnvets.datamodels.User;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class UserInfo {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static CompletableFuture<MutableComponent> userInfo() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (player == null) {
            return null;
        }

        HttpRequest USER_INFO_REQUEST = HttpRequest.newBuilder()
                .uri(WCApi.PlayerInfo(player.getUUID()))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(USER_INFO_REQUEST, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                        User user = new Gson().fromJson(response.body(), User.class);
                        return Component.literal(user.toString());
                    } else {
                        return Component.literal("Failed to fetch player information (Status: " + response.statusCode() + ")");
                    }
                })
                .exceptionally(e -> Component.literal("Error fetching player info: " + e.getMessage()));
    }
}
