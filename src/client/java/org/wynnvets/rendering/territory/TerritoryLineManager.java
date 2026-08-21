package org.wynnvets.rendering.territory;

import com.google.gson.JsonObject;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.HttpClients;
import org.wynnvets.util.Json;

/**
 * Manages territory line toggle states and territory coordinate data
 * fetched from the Wynncraft API.
 *
 * <p><b>Credit:</b> The territory data format (Wynncraft API
 * {@code /v3/guild/list/territory}, {@code location.start}/{@code location.end}
 * coordinate structure) and the bounds normalisation strategy are borrowed from
 * avomod2's {@code TerritoryData} and {@code TerritoryOutlineRenderer} by
 * Avicia ({@code cf.avicia.avomod2}).</p>
 */
public final class TerritoryLineManager {

    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    /** Maps command alias → Wynncraft territory name. */
    private static final Map<String, String> LINE_ALIASES =
            Map.of(
                    "church", "Forest of Eyes",
                    "scrap", "Corkus Sea Cove",
                    "bat", "Royal Barracks",
                    "hegea", "Fort Hegea",
                    "lighthouse", "Contested District");

    /** Active toggle state per alias. */
    private static final ConcurrentHashMap<String, Boolean> activeLines = new ConcurrentHashMap<>();

    /** Territory data from the Wynncraft API (all territories with location bounds). */
    private static volatile JsonObject territoryData = null;

    private static volatile boolean fetchInProgress = false;

    private TerritoryLineManager() {}

    /**
     * Toggle the display of a territory boundary line.
     *
     * @param alias the shorthand name ("church", "scrap", "bat", "hegea", or "lighthouse")
     */
    public static void toggle(String alias) {
        String territoryName = LINE_ALIASES.get(alias);
        if (territoryName == null) return;

        boolean nowActive = !activeLines.getOrDefault(alias, false);
        activeLines.put(alias, nowActive);

        if (nowActive && territoryData == null && !fetchInProgress) {
            fetchTerritoryData();
        }

        ChatUtils.sendLocalMessage(
                Component.literal("Territory line for ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(
                                Component.literal(territoryName)
                                        .withStyle(
                                                nowActive
                                                        ? ChatFormatting.GREEN
                                                        : ChatFormatting.RED))
                        .append(
                                Component.literal(nowActive ? " Enabled" : " Disabled")
                                        .withStyle(
                                                nowActive
                                                        ? ChatFormatting.GREEN
                                                        : ChatFormatting.RED)));
    }

    public static boolean isActive(String alias) {
        return activeLines.getOrDefault(alias, false);
    }

    public static boolean hasAnyActive() {
        for (Boolean v : activeLines.values()) {
            if (v) return true;
        }
        return false;
    }

    public static Map<String, String> getAliases() {
        return LINE_ALIASES;
    }

    /**
     * Returns the territory bounds as {@code [startX, startZ, endX, endZ]}
     * (min/max normalised), or {@code null} if data is unavailable.
     */
    public static int[] getBounds(String alias) {
        String territoryName = LINE_ALIASES.get(alias);
        if (territoryName == null || territoryData == null) return null;
        if (!territoryData.has(territoryName)) return null;

        JsonObject locationObject =
                territoryData.get(territoryName).getAsJsonObject().getAsJsonObject("location");
        int apiStartX = locationObject.get("start").getAsJsonArray().get(0).getAsInt();
        int apiStartZ = locationObject.get("start").getAsJsonArray().get(1).getAsInt();
        int apiEndX = locationObject.get("end").getAsJsonArray().get(0).getAsInt();
        int apiEndZ = locationObject.get("end").getAsJsonArray().get(1).getAsInt();

        return new int[] {
            Math.min(apiStartX, apiEndX),
            Math.min(apiStartZ, apiEndZ),
            Math.max(apiStartX, apiEndX),
            Math.max(apiStartZ, apiEndZ)
        };
    }

    private static void fetchTerritoryData() {
        fetchInProgress = true;
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("https://api.wynncraft.com/v3/guild/list/territory"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

        HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(
                        response -> {
                            if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                                territoryData =
                                        Json.GSON.fromJson(response.body(), JsonObject.class);
                                VetsLogger.info(
                                        "Territory data loaded ({} territories)",
                                        territoryData.size());
                            } else {
                                VetsLogger.warn(
                                        "Failed to fetch territory data (Status: {})",
                                        response.statusCode());
                            }
                            fetchInProgress = false;
                        })
                .exceptionally(
                        e -> {
                            VetsLogger.error("Error fetching territory data: {}", e.getMessage());
                            fetchInProgress = false;
                            return null;
                        });
    }
}
