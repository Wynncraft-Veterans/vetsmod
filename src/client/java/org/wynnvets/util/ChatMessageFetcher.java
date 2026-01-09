package org.wynnvets.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChatMessageFetcher {
    private static final String CHAT_ENDPOINT = "http://api.wynnvets.org/v0/outbound/chat";
    private static final int FETCH_INTERVAL_SECONDS = 3;
    private static final int MAX_CACHED_MESSAGE_IDS = 1000;
    private static final String GUILD_BANNER_SYMBOL = "\uDAFF\uDFFC\uE006\uDAFF\uDFFF\uE002\uDAFF\uDFFE";
    private static final ResourceLocation CHAT_PREFIX_FONT = ResourceLocation.parse("chat/prefix");
    
    // Cache Style objects to avoid creating new ones every message
    private static final Style BANNER_STYLE = Style.EMPTY.withFont(CHAT_PREFIX_FONT).withColor(ChatFormatting.AQUA);
    private static final Style RANK_STYLE = Style.EMPTY.withColor(ChatFormatting.AQUA);
    private static final Style NAME_STYLE = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA);
    
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    
    private static final HttpRequest HTTP_REQUEST = HttpRequest.newBuilder()
            .uri(URI.create(CHAT_ENDPOINT))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    
    private static final Gson GSON = new Gson();
    private static final Set<String> displayedMessageIds = new LinkedHashSet<>();
    private static ScheduledExecutorService scheduler;
    private static boolean isRunning = false;
    private static volatile int pendingDisplayTasks = 0;
    private static final int MAX_PENDING_TASKS = 50;
    
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
        // Only fetch messages if features are enabled (guild is Returners)
        if (!GuildInfoListener.areFeaturesEnabled()) {
            return;
        }
        
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(HTTP_REQUEST, HttpResponse.BodyHandlers.ofString());
            
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
            
            // Limit processing to prevent overwhelming the system
            int processedCount = 0;
            int maxPerBatch = 10;
            
            for (int i = 0; i < messages.size() && processedCount < maxPerBatch; i++) {
                JsonObject messageObj = messages.get(i).getAsJsonObject();
                
                String id = messageObj.get("id").getAsString();
                
                // Only display messages we haven't seen before
                if (!displayedMessageIds.contains(id)) {
                    // Prevent unbounded growth - remove oldest entry if cache is full
                    if (displayedMessageIds.size() >= MAX_CACHED_MESSAGE_IDS) {
                        String firstId = displayedMessageIds.iterator().next();
                        displayedMessageIds.remove(firstId);
                    }
                    displayedMessageIds.add(id);
                    
                    // Extract strings from JSON
                    String displayName = messageObj.get("display_name").getAsString();
                    String message = messageObj.get("message").getAsString();
                    String rank = messageObj.get("rank").getAsString();
                    
                    // Create the guild banner with proper font - use cached style
                    MutableComponent banner = Component.literal(GUILD_BANNER_SYMBOL)
                            .setStyle(BANNER_STYLE);
                    
                    // Build message separately with default font (no style inheritance from banner)
                    MutableComponent textComponent = Component.literal(" ");
                    
                    if (!rank.isEmpty()) {
                        textComponent.append(Component.literal(rank).setStyle(RANK_STYLE))
                                    .append(" ");
                    }
                    
                    textComponent.append(Component.literal(displayName).setStyle(NAME_STYLE))
                                .append(Component.literal(": ").setStyle(RANK_STYLE))
                                .append(Component.literal(message).setStyle(RANK_STYLE));
                    
                    // Combine as separate component trees
                    Component formattedMessage = Component.empty()
                            .append(banner)
                            .append(textComponent);
                    
                    displayInChat(formattedMessage);
                    processedCount++;
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing chat messages: " + e.getMessage());
        }
    }
    
    /**
     * Displays a message in the Minecraft chat
     */
    private static void displayInChat(Component message) {
        if (pendingDisplayTasks >= MAX_PENDING_TASKS) {
            return;
        }
        
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        
        pendingDisplayTasks++;
        minecraft.execute(() -> {
            try {
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(message, false);
                }
            } finally {
                pendingDisplayTasks--;
            }
        });
    }
    
    /**
     * Clears the cache of displayed message IDs
     */
    public static void clearCache() {
        displayedMessageIds.clear();
    }
}
