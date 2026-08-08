package org.wynnvets.fetcher.ondemand;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.api.VetsApi;
import org.wynnvets.chat.RankDisplayMap;
import org.wynnvets.logging.VetsLogger;

/**
 * On-demand fetcher that retrieves the list of currently online staff members.
 *
 * <p>Formats the result as a styled chat component for display via
 * {@code /wv staff}. Staff names are colour-coded by rank.</p>
 */
public final class StaffFetcher {
    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

    private static final Gson GSON = new Gson();

    private StaffFetcher() {}

    /**
     * Fetches online guild staff from the WV API.
     */
    public static CompletableFuture<MutableComponent> fetchOnlineStaff() {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(VetsApi.STAFF)
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

        return HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        response -> {
                            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                                VetsLogger.debug("Staff fetch failed: {}", response.statusCode());
                                return Component.literal(
                                                "Failed to fetch staff (Status: "
                                                        + response.statusCode()
                                                        + ")")
                                        .withStyle(ChatFormatting.RED);
                            }

                            try {
                                List<StaffEntry> onlineStaff = parseOnlineStaff(response.body());
                                if (onlineStaff.isEmpty()) {
                                    return Component.literal("No guild staff are currently online.")
                                            .withStyle(ChatFormatting.YELLOW);
                                }

                                return formatOnlineStaff(onlineStaff);
                            } catch (Exception e) {
                                return Component.literal(
                                                "Error parsing staff data: " + e.getMessage())
                                        .withStyle(ChatFormatting.RED);
                            }
                        })
                .exceptionally(
                        e ->
                                Component.literal("Error fetching staff: " + e.getMessage())
                                        .withStyle(ChatFormatting.RED));
    }

    private static List<StaffEntry> parseOnlineStaff(String responseBody) {
        JsonArray staffMembers = GSON.fromJson(responseBody, JsonArray.class);
        if (staffMembers == null) {
            return List.of();
        }

        List<StaffEntry> parsed = new ArrayList<>();
        for (JsonElement element : staffMembers) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject staffMember = element.getAsJsonObject();
            String username = stringOrNull(staffMember, "username");
            if (username == null || username.isBlank()) {
                continue;
            }

            if (!isOnline(staffMember)) {
                continue;
            }

            String rank = normalizeRank(stringOrNull(staffMember, "rank"));
            String world =
                    firstNonBlank(
                            stringOrNull(staffMember, "world"),
                            stringOrNull(staffMember, "server"));

            parsed.add(new StaffEntry(username, rank, world));
        }

        parsed.sort(
                Comparator.comparingInt((StaffEntry entry) -> rankOrder(entry.rank()))
                        .thenComparing(entry -> entry.username().toLowerCase(Locale.ROOT)));

        return parsed;
    }

    private static MutableComponent formatOnlineStaff(List<StaffEntry> onlineStaff) {
        MutableComponent message =
                Component.literal("Online guild staff (" + onlineStaff.size() + "): ")
                        .withStyle(ChatFormatting.GREEN);

        for (int i = 0; i < onlineStaff.size(); i++) {
            StaffEntry entry = onlineStaff.get(i);

            message.append(Component.literal(entry.username()).withStyle(ChatFormatting.AQUA));

            if (!entry.rank().isBlank()) {
                // Show the client-facing display label ("Steward"/"Returner")
                // rather than the raw Wynn rank. Sort order still uses the
                // raw rank via rankOrder() so priority is preserved.
                String label = RankDisplayMap.displayFor(entry.rank()).toUpperCase(Locale.ROOT);
                message.append(
                        Component.literal(" [" + label + "]").withStyle(ChatFormatting.GOLD));
            }

            if (entry.world() != null && !entry.world().isBlank()) {
                message.append(
                        Component.literal(" (" + entry.world() + ")")
                                .withStyle(ChatFormatting.GRAY));
            }

            if (i < onlineStaff.size() - 1) {
                message.append(Component.literal(", ").withStyle(ChatFormatting.GREEN));
            }
        }

        return message;
    }

    private static boolean isOnline(JsonObject staffMember) {
        if (staffMember.has("online") && !staffMember.get("online").isJsonNull()) {
            return staffMember.get("online").getAsBoolean();
        }

        if (staffMember.has("isOnline") && !staffMember.get("isOnline").isJsonNull()) {
            return staffMember.get("isOnline").getAsBoolean();
        }

        String status = stringOrNull(staffMember, "status");
        if (status != null) {
            if (status.equalsIgnoreCase("online")) {
                return true;
            }
            if (status.equalsIgnoreCase("offline")) {
                return false;
            }
        }

        String world =
                firstNonBlank(
                        stringOrNull(staffMember, "world"), stringOrNull(staffMember, "server"));
        if (world != null) {
            return true;
        }

        return true;
    }

    private static String normalizeRank(String rank) {
        if (rank == null) {
            return "";
        }
        // Pass-through toLowerCase; the display-label mapping happens at
        // render time via RankDisplayMap so we keep the raw Wynn rank for
        // sort priority. Captain retained here as a legacy alias for stray
        // captains (they sort below strategists via rankOrder).
        return rank.trim().toLowerCase(Locale.ROOT);
    }

    private static int rankOrder(String rank) {
        switch (rank) {
            case "owner":
                return 0;
            case "chief":
                return 1;
            case "strategist":
                return 2;
            // Captain was retired in the 2026-07 permission restructure; a
            // stray captain sorts below strategists but above unknowns so the
            // /wv staff listing still renders them in a sensible slot.
            case "captain":
                return 3;
            default:
                return 4;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static String stringOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }

        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private record StaffEntry(String username, String rank, String world) {}
}
