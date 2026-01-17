package org.wynnvets.util;

import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.constants.MJApi;
import org.wynnvets.constants.WCApi;
import org.wynnvets.datamodels.User;
import org.wynnvets.datamodels.UserUUID;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

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

    public static CompletableFuture<MutableComponent> wynnAge(String playerName) {
        HttpRequest USER_UUID_REQUEST = HttpRequest.newBuilder()
                .uri(MJApi.GetUserUUID(playerName))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(USER_UUID_REQUEST, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                        UserUUID userUUID = new Gson().fromJson(response.body(), UserUUID.class);
                        return playerInfo(userUUID);
                    } else {
                        return CompletableFuture.completedFuture(
                                Component.literal("Failed to fetch player info (Status: " + response.statusCode() + ")\n" + MJApi.GetUserUUID(playerName))
                        );
                    }
                })
                .exceptionally(e -> Component.literal("Error fetching player info: " + e.getMessage()));
    }

    private static final Pattern UUID_FIX = Pattern.compile("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})");

    private static UUID formatFromInput(String uuid) {
      return UUID.fromString(UUID_FIX.matcher(uuid.replace("-", "")).replaceAll("$1-$2-$3-$4-$5"));
    }

    private static CompletableFuture<MutableComponent> playerInfo(UserUUID uuid) {
        HttpRequest USER_AGE_REQUEST = HttpRequest.newBuilder()
            .uri(WCApi.PlayerInfo(formatFromInput(uuid.id)))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        return HTTP_CLIENT.sendAsync(USER_AGE_REQUEST, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
              if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                User user = new Gson().fromJson(response.body(), User.class);
                return Component.literal(user.getFirstJoinDate());
              } else {
                return Component.literal("Failed to fetch player info (Status: " + response.statusCode() + ")\n" + WCApi.PlayerInfo(formatFromInput(uuid.id)));
              }
            })
            .exceptionally(e -> Component.literal("Error fetching player info: " + e.getMessage()));
    }
}
