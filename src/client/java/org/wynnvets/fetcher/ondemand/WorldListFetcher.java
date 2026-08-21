package org.wynnvets.fetcher.ondemand;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.api.VetsApi;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.dispatcher.FindDispatcher;
import org.wynnvets.fetcher.lookup.PlayerLookup;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.Json;

/**
 * On-demand fetcher for {@code /wv list world}.
 *
 * <p>Combines the same data sources as {@link ListFetcher} to determine online
 * members, then fans out {@code /find <username>} commands through
 * {@link FindDispatcher}'s shared single-threaded dispatcher to
 * discover each player's current Wynncraft server.  Results are grouped by
 * region and server, sorted by member count (descending).</p>
 */
public final class WorldListFetcher {

    // Styling for each tier.
    private static final Style MEMBER_STYLE = Style.EMPTY.withColor(ChatFormatting.AQUA);
    private static final Style WAITLIST_STYLE =
            Style.EMPTY.withColor(ChatFormatting.DARK_AQUA).withItalic(true);
    private static final Style HONOURARY_STYLE =
            Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(true);

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

    // GeoLite2 / GeoIP2 continent code → display name.
    private static final Map<String, String> CONTINENT_NAMES =
            Map.of(
                    "AF", "Africa",
                    "AS", "Asia",
                    "EU", "Europe",
                    "NA", "North America",
                    "OC", "Oceania",
                    "SA", "South America");

    // Display label for the private-server group.
    private static final String PRIVATE_REGION = "Private";

    private WorldListFetcher() {}

    // ── Public entry point ──────────────────────────────────────────

    /**
     * Gathers online members, locates each via {@code /find}, and returns a
     * formatted world-grouped listing.
     */
    public static void fetchWorldList() {
        ChatUtils.sendLocalMessage(
                Component.literal("Looking up online members...").withStyle(ChatFormatting.GREEN));

        CompletableFuture<List<OnlineMemberService.OnlinePlayer>> playersFuture =
                OnlineMemberService.gatherOnlinePlayers()
                        .thenApply(OnlineMemberService.GatherResult::players);
        CompletableFuture<Set<String>> staffFuture = fetchStaffUsernames();

        playersFuture
                .thenCombine(
                        staffFuture,
                        (players, staffNames) -> {
                            if (players.isEmpty()) {
                                ChatUtils.sendLocalMessage(
                                        Component.literal("No members are currently online.")
                                                .withStyle(ChatFormatting.YELLOW));
                                return null;
                            }

                            ChatUtils.sendLocalMessage(
                                    Component.literal(
                                                    "Finding "
                                                            + players.size()
                                                            + " members across worlds... (this may take a moment)")
                                            .withStyle(ChatFormatting.GRAY));

                            List<String> usernames =
                                    players.stream()
                                            .map(OnlineMemberService.OnlinePlayer::username)
                                            .collect(Collectors.toList());

                            CompletableFuture<Map<String, String>> findFuture =
                                    new CompletableFuture<>();
                            FindDispatcher.enqueueFindBatch(usernames, findFuture);

                            findFuture
                                    .thenCompose(
                                            worldMap ->
                                                    retryStragglersUnderCanonicalNames(
                                                            players, worldMap))
                                    .thenAccept(
                                            finalMap -> {
                                                MutableComponent result =
                                                        formatWorldList(
                                                                players, finalMap, staffNames);
                                                ChatUtils.sendLocalMessageNewBlock(result);
                                            })
                                    .exceptionally(
                                            e -> {
                                                VetsLogger.warn(
                                                        "World list find batch failed: {}",
                                                        e.getMessage());
                                                ChatUtils.sendLocalMessage(
                                                        Component.literal(
                                                                        "Failed to locate players: "
                                                                                + e.getMessage())
                                                                .withStyle(ChatFormatting.RED));
                                                return null;
                                            });

                            return null;
                        })
                .exceptionally(
                        e -> {
                            VetsLogger.warn("World list data fetch failed: {}", e.getMessage());
                            ChatUtils.sendLocalMessage(
                                    Component.literal(
                                                    "Failed to fetch member data: "
                                                            + e.getMessage())
                                            .withStyle(ChatFormatting.RED));
                            return null;
                        });
    }

    // ── Straggler retry under canonical names ───────────────────────

    /**
     * Re-queries the {@code /find} dispatcher for any players the initial batch
     * could not place, using each player's canonical current name (resolved via
     * {@link PlayerLookup}).
     *
     * <p>Motivation: a player whose Mojang name has changed may still appear in
     * the merged online list under their *old* name (e.g. because temp-server's
     * Mojang-resolved roster is stale). Wynncraft's {@code /find <oldname>}
     * returns "not currently online", and the player buckets into "Offline /
     * Not Found" even though they are in fact online under their new name.
     * This pass detects those cases and re-queries under the canonical name.</p>
     *
     * <p>Strictly additive: only inspects players the first batch could not
     * place. A retry success moves the player from {@code notFound} into the
     * correct server bucket; a retry miss / timeout leaves the original
     * {@code worldMap} entry untouched (= original behaviour).</p>
     *
     * <p>Returns a copy of {@code worldMap} with augmented entries; never
     * mutates the input.</p>
     */
    private static CompletableFuture<Map<String, String>> retryStragglersUnderCanonicalNames(
            List<OnlineMemberService.OnlinePlayer> players, Map<String, String> worldMap) {

        List<OnlineMemberService.OnlinePlayer> stragglers = new ArrayList<>();
        for (var p : players) {
            String server = worldMap.get(p.username());
            if (server == null || server.isEmpty()) {
                stragglers.add(p);
            }
        }
        if (stragglers.isEmpty()) {
            return CompletableFuture.completedFuture(worldMap);
        }

        // originalUsername (case preserved) -> canonicalCurrentName.
        // ConcurrentHashMap because the resolve futures complete on arbitrary
        // pool threads.
        Map<String, String> renameMap = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> resolves = new ArrayList<>(stragglers.size());
        for (var p : stragglers) {
            String original = p.username();
            resolves.add(
                    PlayerLookup.resolve(original)
                            .thenAccept(
                                    result -> {
                                        if (!result.isSuccess()) return;
                                        String canonical = result.canonicalName();
                                        if (canonical == null
                                                || canonical.equalsIgnoreCase(original)) return;
                                        renameMap.put(original, canonical);
                                    }));
        }

        return CompletableFuture.allOf(resolves.toArray(new CompletableFuture[0]))
                .thenCompose(
                        v -> {
                            if (renameMap.isEmpty()) {
                                return CompletableFuture.completedFuture(worldMap);
                            }
                            // Dedup the canonical names sent to /find; order preserved
                            // for predictable dispatch.
                            List<String> canonicalNames =
                                    new ArrayList<>(new LinkedHashSet<>(renameMap.values()));
                            CompletableFuture<Map<String, String>> retryFuture =
                                    new CompletableFuture<>();
                            FindDispatcher.enqueueFindBatch(canonicalNames, retryFuture);
                            return retryFuture.thenApply(
                                    retryMap -> {
                                        // Defensive copy: enqueueFindBatch can return Map.of()
                                        // in some edge paths, and we don't want to surprise
                                        // downstream readers by mutating the input.
                                        Map<String, String> augmented =
                                                new LinkedHashMap<>(worldMap);
                                        for (var e : renameMap.entrySet()) {
                                            String server = retryMap.get(e.getValue());
                                            if (server != null && !server.isEmpty()) {
                                                augmented.put(e.getKey(), server);
                                            }
                                        }
                                        return augmented;
                                    });
                        });
    }

    // ── Staff fetch ─────────────────────────────────────────────────

    private static CompletableFuture<Set<String>> fetchStaffUsernames() {
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
                                return Set.<String>of();
                            }
                            return parseStaffUsernames(response.body());
                        })
                .exceptionally(
                        e -> {
                            VetsLogger.debug("Failed to fetch staff list: {}", e.getMessage());
                            return Set.of();
                        });
    }

    private static Set<String> parseStaffUsernames(String body) {
        try {
            JsonArray arr = Json.GSON.fromJson(body, JsonArray.class);
            if (arr == null) {
                return Set.of();
            }
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String username = OnlineMemberService.stringOrEmpty(obj, "username");
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
            List<OnlineMemberService.OnlinePlayer> players,
            Map<String, String> worldMap,
            Set<String> staffNames) {

        // Build server → list of players.
        Map<String, List<OnlineMemberService.OnlinePlayer>> serverToPlayers = new LinkedHashMap<>();
        List<OnlineMemberService.OnlinePlayer> notFound = new ArrayList<>();

        for (var p : players) {
            String server = worldMap.get(p.username());
            if (server != null && !server.isEmpty()) {
                serverToPlayers
                        .computeIfAbsent(server.toUpperCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(p);
            } else {
                notFound.add(p);
            }
        }

        // Group servers by continent/region.
        Map<String, Map<String, List<OnlineMemberService.OnlinePlayer>>> regionToServers =
                new TreeMap<>();
        for (Map.Entry<String, List<OnlineMemberService.OnlinePlayer>> entry :
                serverToPlayers.entrySet()) {
            String server = entry.getKey();
            String region = classifyRegion(server);
            regionToServers
                    .computeIfAbsent(region, k -> new LinkedHashMap<>())
                    .put(server, entry.getValue());
        }

        // Sort regions by total member count descending.
        List<Map.Entry<String, Map<String, List<OnlineMemberService.OnlinePlayer>>>> sortedRegions =
                new ArrayList<>(regionToServers.entrySet());
        sortedRegions.sort(
                Comparator
                        .<Map.Entry<String, Map<String, List<OnlineMemberService.OnlinePlayer>>>,
                                Integer>
                                comparing(
                                        e ->
                                                e.getValue().values().stream()
                                                        .mapToInt(List::size)
                                                        .sum())
                        .reversed());

        // Sort servers within each region by member count descending.
        for (Map.Entry<String, Map<String, List<OnlineMemberService.OnlinePlayer>>> regionEntry :
                sortedRegions) {
            List<Map.Entry<String, List<OnlineMemberService.OnlinePlayer>>> sorted =
                    new ArrayList<>(regionEntry.getValue().entrySet());
            sorted.sort(
                    Comparator
                            .<Map.Entry<String, List<OnlineMemberService.OnlinePlayer>>, Integer>
                                    comparing(e -> e.getValue().size())
                            .reversed());
            LinkedHashMap<String, List<OnlineMemberService.OnlinePlayer>> reordered =
                    new LinkedHashMap<>();
            for (Map.Entry<String, List<OnlineMemberService.OnlinePlayer>> s : sorted) {
                reordered.put(s.getKey(), s.getValue());
            }
            regionEntry.setValue(reordered);
        }

        // ── Build the component ──────────────────────────────────────

        int foundCount = players.size() - notFound.size();
        // Don't count the PRIVATE sentinel as a real server.
        int serverCount =
                (int)
                        serverToPlayers.keySet().stream()
                                .filter(s -> !FindDispatcher.PRIVATE_SERVER.equalsIgnoreCase(s))
                                .count();

        MutableComponent msg = Component.empty();
        msg.append(
                Component.literal("World List\n")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        msg.append(
                Component.literal(
                                String.format(
                                        "%d members found across %d servers\n",
                                        foundCount, serverCount))
                        .withStyle(ChatFormatting.GRAY));

        for (Map.Entry<String, Map<String, List<OnlineMemberService.OnlinePlayer>>> regionEntry :
                sortedRegions) {
            String region = regionEntry.getKey();
            Map<String, List<OnlineMemberService.OnlinePlayer>> servers = regionEntry.getValue();

            if (PRIVATE_REGION.equals(region)) {
                // Private server players are lumped together without individual server rows.
                List<OnlineMemberService.OnlinePlayer> allPrivate =
                        servers.values().stream().flatMap(List::stream).toList();
                msg.append(
                        Component.literal("\nPrivate Servers (" + allPrivate.size() + "):\n")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                for (int i = 0; i < allPrivate.size(); i++) {
                    msg.append(styledPlayerName(allPrivate.get(i), staffNames));
                    if (i < allPrivate.size() - 1) {
                        msg.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                    }
                }
                msg.append(Component.literal("\n"));
                continue;
            }

            msg.append(
                    Component.literal("\n" + region + " Servers:\n")
                            .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

            for (Map.Entry<String, List<OnlineMemberService.OnlinePlayer>> serverEntry :
                    servers.entrySet()) {
                String server = serverEntry.getKey();
                List<OnlineMemberService.OnlinePlayer> serverPlayers = serverEntry.getValue();

                msg.append(Component.literal(server).withStyle(ChatFormatting.YELLOW));
                msg.append(
                        Component.literal(" (" + serverPlayers.size() + "): ")
                                .withStyle(ChatFormatting.GRAY));

                for (int i = 0; i < serverPlayers.size(); i++) {
                    var p = serverPlayers.get(i);
                    msg.append(styledPlayerName(p, staffNames));
                    if (i < serverPlayers.size() - 1) {
                        msg.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                    }
                }
                msg.append(Component.literal("\n"));
            }
        }

        if (!notFound.isEmpty()) {
            msg.append(
                    Component.literal("\nOffline / Not Found (" + notFound.size() + "):\n")
                            .withStyle(ChatFormatting.GRAY));
            for (int i = 0; i < notFound.size(); i++) {
                var p = notFound.get(i);
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
    private static MutableComponent styledPlayerName(
            OnlineMemberService.OnlinePlayer player, Set<String> staffNames) {
        Style base;
        switch (player.tier()) {
            case "waitlist":
                base = WAITLIST_STYLE;
                break;
            case "honourary":
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
                .withStyle(
                        style ->
                                style.withHoverEvent(
                                                new HoverEvent.ShowText(
                                                        Component.literal(
                                                                        "Click to message "
                                                                                + player.username())
                                                                .withStyle(ChatFormatting.GRAY)))
                                        .withClickEvent(
                                                new ClickEvent.SuggestCommand(
                                                        "/msg " + player.username() + " ")));
    }

    // ── Server classification ───────────────────────────────────────

    /**
     * Classifies a server name into a display region.  Standard Wynncraft servers
     * use GeoLite2 / GeoIP2 continent codes ({@code AF, AS, EU, NA, OC, SA}) followed
     * by a numeric ID.  Players on private servers (media, dev, staff, etc.) are
     * identified by the {@link FindDispatcher#PRIVATE_SERVER} sentinel.
     */
    private static String classifyRegion(String server) {
        if (FindDispatcher.PRIVATE_SERVER.equalsIgnoreCase(server)) {
            return PRIVATE_REGION;
        }

        String upper = server.toUpperCase(Locale.ROOT);

        // Try known 2-letter continent prefixes.
        if (upper.length() >= 2) {
            String prefix = upper.substring(0, 2);
            String continent = CONTINENT_NAMES.get(prefix);
            if (continent != null) {
                return continent;
            }
        }

        return "Other";
    }
}
