package org.wynnvets.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChatMessageFetcher {
    private static final String CHAT_ENDPOINT = "http://api.wynnvets.org/v0/outbound/chat";
    private static final int FETCH_INTERVAL_SECONDS = 3;
    
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    
    private static final Gson GSON = new Gson();
    private static final Set<String> displayedMessageIds = new HashSet<>();
    private static ScheduledExecutorService scheduler;
    private static boolean isRunning = false;
    
    /**
     * Starts the periodic fetching of chat messages
     */
    public static void start() {
        if (isRunning) {
            return;
        }
        
        isRunning = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "VetsMod-ChatFetcher");
            thread.setDaemon(true);
            return thread;
        });
        
        // Schedule the fetch task to run every 3 seconds
        scheduler.scheduleAtFixedRate(() -> {
            try {
                fetchAndDisplayMessages();
            } catch (Exception e) {
                System.err.println("Error fetching chat messages: " + e.getMessage());
            }
        }, 0, FETCH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }
    
    /**
     * Stops the periodic fetching
     */
    public static void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }
        isRunning = false;
    }
    
    /**
     * Fetches messages from the API and displays new ones in chat
     */
    private static void fetchAndDisplayMessages() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CHAT_ENDPOINT))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                processMessages(response.body());
            }
        } catch (Exception e) {
            // Silently fail to avoid spamming console
        }
    }
    
    /**
     * Processes the JSON response and displays new messages
     */
    private static void processMessages(String jsonResponse) {
        try {
            JsonArray messages = GSON.fromJson(jsonResponse, JsonArray.class);
            
            for (int i = 0; i < messages.size(); i++) {
                JsonObject messageObj = messages.get(i).getAsJsonObject();
                
                String id = messageObj.get("id").getAsString();
                
                // Only display messages we haven't seen before
                if (!displayedMessageIds.contains(id)) {
                    displayedMessageIds.add(id);
                    
                    String displayName = messageObj.get("display_name").getAsString();
                    String message = messageObj.get("message").getAsString();
                    String rank = messageObj.get("rank").getAsString();
                    
                    // Format: Rank DisplayName: Message
                    String formattedMessage = String.format("§b█▶ §3%s: §b%s", rank, displayName, message);
                    
                    displayInChat(formattedMessage);
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing chat messages: " + e.getMessage());
        }
    }
    
    /**
     * Displays a message in the Minecraft chat
     */
    private static void displayInChat(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            // Execute on the main Minecraft thread to avoid threading issues
            minecraft.execute(() -> {
                minecraft.player.displayClientMessage(Component.literal(message), false);
            });
        }
    }
    
    /**
     * Clears the cache of displayed message IDs
     */
    public static void clearCache() {
        displayedMessageIds.clear();
    }
}
