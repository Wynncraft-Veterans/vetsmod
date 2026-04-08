package org.wynnvets.fetcher.ondemand;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wynntils.core.components.Models;
import com.wynntils.models.guild.type.GuildInfo;
import com.wynntils.models.guild.type.GuildMemberInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.api.VetsApi;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.StaffOutboundMessenger;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * On-demand fetcher for {@code /wv list world}.
 *
 * <p>Combines the same data sources as {@link ListFetcher} to determine online
 * members, then fans out {@code /find <username>} commands through
 * {@link StaffOutboundMessenger}'s shared single-threaded dispatcher to
 * discover each player's current Wynncraft server.  Results are grouped by
 * region and server, sorted by member count (descending).</p>
 */
public final class WorldListFetcher {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Gson GSON = new Gson();

    // Tier constants matching the server's outbound/list response.
    private static final String TIER_GUILD = "guild";
    private static final String TIER_WAITLIST = "waitlist";
    private static final String TIER_HONOURARY = "honourary";

    // Styling for each tier.
    private static final Style MEMBER_STYLE = Style.EMPTY.withColor(ChatFormatting.AQUA);
    private static final Style WAITLIST_STYLE = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA).withItalic(true);
    private static final Style HONOURARY_STYLE = Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(true);

    // GeoLite2 / GeoIP2 continent code → display name.
    private static final Map<String, String> CONTINENT_NAMES = Map.of(
            "AF", "Africa",
            "AS", "Asia",
            "EU", "Europe",
            "NA", "North America",
            "OC", "Oceania",
            "SA", "South America"
    );

    private WorldListFetcher() {
    }

    // ── Data holders ────────────────────────────────────────────────

    private record OnlinePlayer(String username, String uuid, String tier) {
    }

    // ── Public entry point ──────────────────────────────────────────

    /**
     * Gathers online members, locates each via {@code /find}, and returns a
     * formatted world-grouped listing.
     */
    public static void fetchWorldList() {
        ChatUtils.sendLocalMessage(
                Component.literal("Looking up online members...")
                        .withStyle(ChatFormatting.GREEN));

        CompletableFuture<List<OnlinePlayer>> playersFuture = gatherOnlinePlayers();
        CompletableFuture<Set<String>> staffFuture = fetchStaffUsernames();

        playersFuture.thenCombine(staffFuture, (players, staffNames) -> {
            if (players.isEmpty()) {
                ChatUtils.sendLocalMessage(
                        Component.literal("No members are currently online.")
                                .withStyle(ChatFormatting.YELLOW));
                return null;
            }

            ChatUtils.sendLocalMessage(
                    Component.literal("Finding " + players.size() + " members across worlds... (this may take a moment)")
                            .withStyle(ChatFormatting.GRAY));

            List<String> usernames = players.stream()
                    .map(OnlinePlayer::username)
                    .collect(Collectors.toList());

            CompletableFuture<Map<String, String>> findFuture = new CompletableFuture<>();
            StaffOutboundMessenger.enqueueFindBatch(usernames, findFuture);

            findFuture.thenAccept(worldMap -> {
                MutableComponent result = formatWorldList(players, worldMap, staffNames);
                ChatUtils.sendLocalMessage(result);
            }).exceptionally(e -> {
                VetsLogger.warn("World list find batch failed: {}", e.getMessage());
                ChatUtils.sendLocalMessage(
                        Component.literal("Failed to locate players: " + e.getMessage())
                                .withStyle(ChatFormatting.RED));
                return null;
            });

            return null;
        }).exceptionally(e -> {
            VetsLogger.warn("World list data fetch failed: {}", e.getMessage());
            ChatUtils.sendLocalMessage(
                    Component.literal("Failed to fetch member data: " + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return null;
        });
    }

    // ── Gather online players (same merge logic as ListFetcher) ─────

    private static CompletableFuture<List<OnlinePlayer>> gatherOnlinePlayers() {
        CompletableFuture<List<ConnectedUser>> serverFuture = fetchConnectedUsers()
                .exceptionally(e -> {
                    VetsLogger.debug("Failed to fetch connected users: {}", e.getMessage());
                    return List.of();
                });

        CompletableFuture<GuildInfo> guildFuture;
        if (GuildStateManager.isWynntilsReady()) {
            guildFuture = Models.Guild.getGuild("Returners")
                    .exceptionally(e -> {
                        VetsLogger.debug("Failed to fetch guild info: {}", e.getMessage());
                        return null;
                    });
        } else {
            guildFuture = CompletableFuture.completedFuture(null);
        }

        return serverFuture.thenCombine(guildFuture, WorldListFetcher::mergeOnlinePlayers);
    }

    private static List<OnlinePlayer> mergeOnlinePlayers(
            List<ConnectedUser> connected, GuildInfo guildInfo) {

        Map<String, ConnectedUser> modByUuid = new LinkedHashMap<>();
        for (ConnectedUser cu : connected) {
            modByUuid.put(cu.uuid, cu);
        }

        Set<String> modGuildUuids = modByUuid.values().stream()
                .filter(cu -> TIER_GUILD.equals(cu.tier))
                .map(cu -> cu.uuid)
                .collect(Collectors.toSet());

        // Build authoritative UUID → username map from Wynntils.
        Map<String, String> uuidToUsername = new LinkedHashMap<>();
        if (guildInfo != null) {
            for (GuildMemberInfo m : guildInfo.guildMembers()) {
                uuidToUsername.put(m.uuid().toString(), m.username());
            }
        }
        for (ConnectedUser cu : connected) {
            uuidToUsername.putIfAbsent(cu.uuid, cu.username);
        }

        // Online guild members = API online ∪ vetsmod guild users.
        Set<String> apiOnlineUuids = new TreeSet<>();
        if (guildInfo != null) {
            for (GuildMemberInfo m : guildInfo.guildMembers()) {
                if (m.online()) {
                    apiOnlineUuids.add(m.uuid().toString());
                }
            }
        }
        Set<String> mergedOnlineGuild = new TreeSet<>(apiOnlineUuids);
        mergedOnlineGuild.addAll(modGuildUuids);

        List<OnlinePlayer> result = new ArrayList<>();

        for (String uuid : mergedOnlineGuild) {
            String username = uuidToUsername.getOrDefault(uuid, uuid.substring(0, 8) + "…");
            result.add(new OnlinePlayer(username, uuid, TIER_GUILD));
        }

        for (ConnectedUser cu : modByUuid.values()) {
            if (TIER_WAITLIST.equals(cu.tier)) {
                String username = uuidToUsername.getOrDefault(cu.uuid, cu.username);
                result.add(new OnlinePlayer(username, cu.uuid, TIER_WAITLIST));
            } else if (TIER_HONOURARY.equals(cu.tier)) {
                String username = uuidToUsername.getOrDefault(cu.uuid, cu.username);
                result.add(new OnlinePlayer(username, cu.uuid, TIER_HONOURARY));
            }
        }

        return result;
    }

    // ── Server fetch (identical to ListFetcher) ─────────────────────

    private record ConnectedUser(String uuid, String username, String tier) {
    }

    private static CompletableFuture<List<ConnectedUser>> fetchConnectedUsers() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(VetsApi.LIST)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                        return List.<ConnectedUser>of();
                    }
                    return parseConnectedUsers(response.body());
                });
    }

    private static List<ConnectedUser> parseConnectedUsers(String body) {
        try {
            JsonObject root = GSON.fromJson(body, JsonObject.class);
            if (root == null || !root.has("connected")) {
                return List.of();
            }
            JsonArray arr = root.getAsJsonArray("connected");
            List<ConnectedUser> result = new ArrayList<>();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String uuid = stringOrEmpty(obj, "uuid");
                String username = stringOrEmpty(obj, "username");
                String tier = stringOrEmpty(obj, "tier");
                if (!uuid.isEmpty() && !username.isEmpty()) {
                    result.add(new ConnectedUser(uuid, username, tier));
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Staff fetch ─────────────────────────────────────────────────

    private static CompletableFuture<Set<String>> fetchStaffUsernames() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(VetsApi.STAFF)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                        return Set.<String>of();
                    }
                    return parseStaffUsernames(response.body());
                })
                .exceptionally(e -> {
                    VetsLogger.debug("Failed to fetch staff list: {}", e.getMessage());
                    return Set.of();
                });
    }

    private static Set<String> parseStaffUsernames(String body) {
        try {
            JsonArray arr = GSON.fromJson(body, JsonArray.class);
            if (arr == null) {
                return Set.of();
            }
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String username = stringOrEmpty(obj, "username");
                if (!username.isEmpty()) {
                    names.add(username);
                }
            }
            return names;
        } catch (Exception e) {
            return Set.of();
        }
    }

    // ── Formatting ──────────────────────────────────────────────────

    private static MutableComponent formatWorldList(
            List<OnlinePlayer> players,
            Map<String, String> worldMap,
            Set<String> staffNames) {

        // Build server → list of players.
        Map<String, List<OnlinePlayer>> serverToPlayers = new LinkedHashMap<>();
        List<OnlinePlayer> notFound = new ArrayList<>();

        for (OnlinePlayer p : players) {
            String server = worldMap.get(p.username());
            if (server != null && !server.isEmpty()) {
                serverToPlayers.computeIfAbsent(server.toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(p);
            } else {
                notFound.add(p);
            }
        }

        // Group servers by continent/region.
        Map<String, Map<String, List<OnlinePlayer>>> regionToServers = new TreeMap<>();
        for (Map.Entry<String, List<OnlinePlayer>> entry : serverToPlayers.entrySet()) {
            String server = entry.getKey();
            String region = classifyRegion(server);
            regionToServers.computeIfAbsent(region, k -> new LinkedHashMap<>())
                    .put(server, entry.getValue());
        }

        // Sort regions by total member count descending.
        List<Map.Entry<String, Map<String, List<OnlinePlayer>>>> sortedRegions =
                new ArrayList<>(regionToServers.entrySet());
        sortedRegions.sort(Comparator.<Map.Entry<String, Map<String, List<OnlinePlayer>>>, Integer>comparing(
                e -> e.getValue().values().stream().mapToInt(List::size).sum()).reversed());

        // Sort servers within each region by member count descending.
        for (Map.Entry<String, Map<String, List<OnlinePlayer>>> regionEntry : sortedRegions) {
            List<Map.Entry<String, List<OnlinePlayer>>> sorted =
                    new ArrayList<>(regionEntry.getValue().entrySet());
            sorted.sort(Comparator.<Map.Entry<String, List<OnlinePlayer>>, Integer>comparing(
                    e -> e.getValue().size()).reversed());
            LinkedHashMap<String, List<OnlinePlayer>> reordered = new LinkedHashMap<>();
            for (Map.Entry<String, List<OnlinePlayer>> s : sorted) {
                reordered.put(s.getKey(), s.getValue());
            }
            regionEntry.setValue(reordered);
        }

        // ── Build the component ──────────────────────────────────────

        int foundCount = players.size() - notFound.size();
        int serverCount = serverToPlayers.size();

        MutableComponent msg = Component.empty();
        msg.append(Component.literal("——— World List ———\n")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        msg.append(Component.literal(String.format(
                        "%d members found across %d servers\n", foundCount, serverCount))
                .withStyle(ChatFormatting.GRAY));

        for (Map.Entry<String, Map<String, List<OnlinePlayer>>> regionEntry : sortedRegions) {
            String region = regionEntry.getKey();
            Map<String, List<OnlinePlayer>> servers = regionEntry.getValue();

            msg.append(Component.literal("\n" + region + " Servers:\n")
                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

            for (Map.Entry<String, List<OnlinePlayer>> serverEntry : servers.entrySet()) {
                String server = serverEntry.getKey();
                List<OnlinePlayer> serverPlayers = serverEntry.getValue();

                msg.append(Component.literal(server)
                        .withStyle(ChatFormatting.YELLOW));
                msg.append(Component.literal(" (" + serverPlayers.size() + "): ")
                        .withStyle(ChatFormatting.GRAY));

                for (int i = 0; i < serverPlayers.size(); i++) {
                    OnlinePlayer p = serverPlayers.get(i);
                    msg.append(styledPlayerName(p, staffNames));
                    if (i < serverPlayers.size() - 1) {
                        msg.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                    }
                }
                msg.append(Component.literal("\n"));
            }
        }

        if (!notFound.isEmpty()) {
            msg.append(Component.literal("\nOffline / Not Found (" + notFound.size() + "):\n")
                    .withStyle(ChatFormatting.GRAY));
            for (int i = 0; i < notFound.size(); i++) {
                OnlinePlayer p = notFound.get(i);
                msg.append(styledPlayerName(p, staffNames));
                if (i < notFound.size() - 1) {
                    msg.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                }
            }
            msg.append(Component.literal("\n"));
        }

        return msg;
    }

    /**
     * Returns a styled, clickable player name.  Colour is determined by tier,
     * and staff members receive an underline overlay.
     */
    private static MutableComponent styledPlayerName(OnlinePlayer player, Set<String> staffNames) {
        Style base;
        switch (player.tier()) {
            case TIER_WAITLIST:
                base = WAITLIST_STYLE;
                break;
            case TIER_HONOURARY:
                base = HONOURARY_STYLE;
                break;
            default:
                base = MEMBER_STYLE;
                break;
        }

        boolean isStaff = staffNames.contains(player.username());
        if (isStaff) {
            base = base.withUnderlined(true);
        }

        Style finalStyle = base;
        return Component.literal(player.username())
                .withStyle(finalStyle)
                .withStyle(style -> style
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("Click to message " + player.username())
                                        .withStyle(ChatFormatting.GRAY)))
                        .withClickEvent(new ClickEvent.SuggestCommand("/msg " + player.username() + " ")));
    }

    // ── Server classification ───────────────────────────────────────

    /**
     * Classifies a server name into a display region.  Standard Wynncraft servers
     * use GeoLite2 / GeoIP2 continent codes ({@code AF, AS, EU, NA, OC, SA}) followed
     * by a numeric ID.  Special servers (e.g. {@code MEDIA1}) are grouped separately.
     */
    private static String classifyRegion(String server) {
        String upper = server.toUpperCase(Locale.ROOT);

        // Try known 2-letter continent prefixes first.
        if (upper.length() >= 2) {
            String prefix = upper.substring(0, 2);
            String continent = CONTINENT_NAMES.get(prefix);
            if (continent != null) {
                return continent;
            }
        }

        // Extract alphabetic prefix for special servers (MEDIA, LOBBY, etc.)
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (Character.isLetter(c)) {
                prefix.append(c);
            } else {
                break;
            }
        }

        if (prefix.length() > 0) {
            return prefix.toString();
        }

        return "Other";
    }

    private static String stringOrEmpty(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return "";
    }
}
