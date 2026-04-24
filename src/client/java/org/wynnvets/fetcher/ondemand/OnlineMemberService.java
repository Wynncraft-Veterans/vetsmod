package org.wynnvets.fetcher.ondemand;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wynntils.core.components.Models;
import com.wynntils.models.guild.type.GuildInfo;
import com.wynntils.models.guild.type.GuildMemberInfo;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.api.VetsApi;
import org.wynnvets.fetcher.polling.GuildRosterCache;
import org.wynnvets.fetcher.polling.WynnAliasCache;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.guild.OnlineGuildCache;
import org.wynnvets.guild.TabListGuildParser;
import org.wynnvets.logging.VetsLogger;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap; // LEGACY-NAME-PATCH
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Shared service for fetching and merging online guild member data.
 *
 * <p>Combines three sources — tab list, VetsMod server, Wynntils guild API —
 * into a unified online member list.  Used by both {@link ListFetcher}
 * and {@link WorldListFetcher}.
 */
final class OnlineMemberService {

    static final String TIER_GUILD = "guild";
    static final String TIER_WAITLIST = "waitlist";
    static final String TIER_HONOURARY = "honourary";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Gson GSON = new Gson();

    private OnlineMemberService() {
    }

    // ── Data holders ────────────────────────────────────────────────

    record ConnectedUser(String uuid, String username, String tier, boolean queued) {
    }

    record OnlinePlayer(String username, String uuid, String tier) {
    }

    record GatherResult(List<OnlinePlayer> players, Set<String> modGuildUuids,
                         Set<String> queuedUuids, boolean wynntilsAvailable) {
    }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Gathers online players from all three data sources, merges them,
     * and returns the result with Wynntils availability info and the set
     * of UUIDs belonging to connected VetsMod guild members.
     */
    static CompletableFuture<GatherResult> gatherOnlinePlayers() {
        // 1. Read tab list immediately (synchronous, local state).
        List<TabListGuildParser.GuildEntry> tabEntries = TabListGuildParser.parseOnlineGuildMembers();
        VetsLogger.debug("Tab list returned {} guild entries", tabEntries.size());

        // Forward tab list to the server so !list can use it too.
        if (!tabEntries.isEmpty()) {
            V1ApiManager.sendTabList(tabEntries.stream()
                    .map(e -> new V1ApiManager.TabListEntry(e.server(), e.username()))
                    .toList());
        }

        // 2. Fetch connected vetsmod users from the server.
        CompletableFuture<List<ConnectedUser>> serverFuture = fetchConnectedUsers()
                .exceptionally(e -> {
                    VetsLogger.debug("Failed to fetch connected users: {}", e.getMessage());
                    return List.of();
                });

        // 3. Fetch guild info from Wynntils (Wynncraft API).
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

        // 4. Combine all three results.
        return serverFuture.thenCombine(guildFuture,
                (connected, guildInfo) -> merge(connected, guildInfo, tabEntries));
    }

    // ── Server fetch ────────────────────────────────────────────────

    private static CompletableFuture<List<ConnectedUser>> fetchConnectedUsers() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(VetsApi.LIST)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                        VetsLogger.debug("List fetch failed: {}", response.statusCode());
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
                    boolean queued = obj.has("queued") && obj.get("queued").getAsBoolean();
                    result.add(new ConnectedUser(uuid, username, tier, queued));
                }
            }
            return result;
        } catch (Exception e) {
            VetsLogger.debug("Error parsing connected users: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Merge ───────────────────────────────────────────────────────

    private static GatherResult merge(
            List<ConnectedUser> connected, GuildInfo guildInfo,
            List<TabListGuildParser.GuildEntry> tabEntries) {

        // Index connected users by UUID.
        Map<String, ConnectedUser> modByUuid = new LinkedHashMap<>();
        for (ConnectedUser cu : connected) {
            modByUuid.put(cu.uuid(), cu);
        }

        Set<String> modGuildUuids = modByUuid.values().stream()
                .filter(cu -> TIER_GUILD.equals(cu.tier()))
                .map(ConnectedUser::uuid)
                .collect(Collectors.toSet());

        Set<String> queuedUuids = modByUuid.values().stream()
                .filter(ConnectedUser::queued)
                .map(ConnectedUser::uuid)
                .collect(Collectors.toSet());

        // Build the master UUID→username map from Wynntils (authoritative).
        Map<String, String> uuidToUsername = new LinkedHashMap<>();
        Map<String, String> usernameToUuid = new LinkedHashMap<>();
        if (guildInfo != null) {
            for (GuildMemberInfo m : guildInfo.guildMembers()) {
                String uuid = m.uuid().toString();
                uuidToUsername.put(uuid, m.username());
                usernameToUuid.put(m.username().toLowerCase(Locale.ROOT), uuid);
            }
        }
        // Overlay resolved roster names (from Minecraft Services API via tempserver).
        // These are authoritative and override potentially stale Wynncraft API names.
        Map<String, String> resolvedRoster = GuildRosterCache.getRoster();
        for (Map.Entry<String, String> entry : resolvedRoster.entrySet()) {
            uuidToUsername.put(entry.getKey(), entry.getValue());
            usernameToUuid.put(entry.getValue().toLowerCase(Locale.ROOT), entry.getKey());
        }
        // Supplement with vetsmod usernames for UUIDs the API doesn't know.
        for (ConnectedUser cu : connected) {
            uuidToUsername.putIfAbsent(cu.uuid(), cu.username());
            usernameToUuid.putIfAbsent(cu.username().toLowerCase(Locale.ROOT), cu.uuid());
        }

        // v3.8 legacyName: the server's GuildRosterPoller reads legacyName from the
        // guild payload and populates /v1/outbound/aliases.  WynnAliasCache polls that
        // endpoint, so WynnAliasCache.getUuid() in the first loop below resolves all
        // renamed players without any change on this side.
        //
        // Once v3.8 is confirmed stable, remove the LEGACY-NAME-PATCH begin/end block
        // below (the per-server correlation fallback) and its HashMap import.
        // WynnAliasCache, VetsApi.ALIASES, and WynnAliasCache.start() all stay.
        //
        // Optional further cleanup if Wynntils ever exposes GuildMemberInfo#legacyName():
        // replace WynnAliasCache.getUuid() in the first loop with this, and then
        // WynnAliasCache + the server alias infrastructure can also be removed.
        //
        // if (guildInfo != null) {
        //     for (GuildMemberInfo m : guildInfo.guildMembers()) {
        //         String legacy = m.legacyName();
        //         if (legacy != null && !legacy.isEmpty()) {
        //             usernameToUuid.putIfAbsent(
        //                     legacy.toLowerCase(Locale.ROOT), m.uuid().toString());
        //         }
        //     }
        // }

        // Collect online guild member UUIDs from the Wynncraft API.
        Set<String> apiOnlineUuids = new TreeSet<>();
        if (guildInfo != null) {
            for (GuildMemberInfo m : guildInfo.guildMembers()) {
                if (m.online()) {
                    apiOnlineUuids.add(m.uuid().toString());
                }
            }
        }

        // Merged online guild = API online ∪ vetsmod guild users ∪ tab list.
        Set<String> mergedOnlineGuild = new TreeSet<>(apiOnlineUuids);
        mergedOnlineGuild.addAll(modGuildUuids);

        // LEGACY-NAME-PATCH begin ── stale rename workaround; see skeleton above ──
        // Tab list entries: resolve to UUID by current username, falling back to
        // per-server correlation against the API online list. Wynncraft's server-
        // side tab list can show stale names (frozen when a player renamed on
        // Mojang), while the v3 guild API returns the current name — so direct
        // name-matching fails for renamed users. When exactly one API member on
        // a server is unclaimed after name matching, pair it with the unresolved
        // tab entry on that server (their Wynncraft-stale alias).
        Map<String, List<String>> unclaimedApiByServer = new HashMap<>();
        if (guildInfo != null) {
            for (GuildMemberInfo m : guildInfo.guildMembers()) {
                if (!m.online() || m.server() == null || m.server().isEmpty()) continue;
                unclaimedApiByServer.computeIfAbsent(
                        m.server().toUpperCase(Locale.ROOT),
                        k -> new ArrayList<>()).add(m.uuid().toString());
            }
        }

        List<TabListGuildParser.GuildEntry> unmatchedTabs = new ArrayList<>();
        for (TabListGuildParser.GuildEntry entry : tabEntries) {
            String key = entry.username().toLowerCase(Locale.ROOT);
            String uuid = usernameToUuid.get(key);
            if (uuid == null) {
                // Server-learned alias (tab list shows Wynncraft's stale name).
                uuid = WynnAliasCache.getUuid(entry.username());
            }
            if (uuid != null) {
                mergedOnlineGuild.add(uuid);
                List<String> slot = unclaimedApiByServer.get(entry.server().toUpperCase(Locale.ROOT));
                if (slot != null) slot.remove(uuid);
            } else {
                unmatchedTabs.add(entry);
            }
        }

        Set<String> tabOnlyUsernames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, List<TabListGuildParser.GuildEntry>> unmatchedByServer = new HashMap<>();
        for (TabListGuildParser.GuildEntry entry : unmatchedTabs) {
            unmatchedByServer.computeIfAbsent(
                    entry.server().toUpperCase(Locale.ROOT),
                    k -> new ArrayList<>()).add(entry);
        }
        for (Map.Entry<String, List<TabListGuildParser.GuildEntry>> e : unmatchedByServer.entrySet()) {
            List<TabListGuildParser.GuildEntry> tabs = e.getValue();
            List<String> candidates = unclaimedApiByServer.getOrDefault(e.getKey(), List.of());
            if (tabs.size() == 1 && candidates.size() == 1) {
                String uuid = candidates.get(0);
                mergedOnlineGuild.add(uuid);
                VetsLogger.debug("Tab-list stale-name correlation: [{}] {} -> {} ({})",
                        e.getKey(), tabs.get(0).username(),
                        uuidToUsername.getOrDefault(uuid, uuid), uuid);
            } else {
                for (TabListGuildParser.GuildEntry t : tabs) {
                    tabOnlyUsernames.add(t.username());
                }
            }
        }
        // LEGACY-NAME-PATCH end ────────────────────────────────────────────────

        // Build the result list.
        List<OnlinePlayer> result = new ArrayList<>();

        for (String uuid : mergedOnlineGuild) {
            String username = uuidToUsername.getOrDefault(uuid, uuid.substring(0, 8) + "…");
            result.add(new OnlinePlayer(username, uuid, TIER_GUILD));
        }

        for (ConnectedUser cu : modByUuid.values()) {
            if (TIER_WAITLIST.equals(cu.tier())) {
                String username = uuidToUsername.getOrDefault(cu.uuid(), cu.username());
                result.add(new OnlinePlayer(username, cu.uuid(), TIER_WAITLIST));
            } else if (TIER_HONOURARY.equals(cu.tier())) {
                String username = uuidToUsername.getOrDefault(cu.uuid(), cu.username());
                result.add(new OnlinePlayer(username, cu.uuid(), TIER_HONOURARY));
            }
        }

        // Add tab-only usernames (no UUID match) as guild-tier players.
        Set<String> allResolvedUsernames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (OnlinePlayer p : result) {
            allResolvedUsernames.add(p.username());
        }
        for (String tabName : tabOnlyUsernames) {
            if (!allResolvedUsernames.contains(tabName)) {
                result.add(new OnlinePlayer(tabName, "", TIER_GUILD));
            }
        }

        // Record all currently-known online names, then pull in grace-period names.
        Set<String> allCurrentlyOnline = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (OnlinePlayer p : result) {
            allCurrentlyOnline.add(p.username());
        }
        OnlineGuildCache.markSeen(allCurrentlyOnline);

        for (String graceName : OnlineGuildCache.getGracePeriodNames(allCurrentlyOnline)) {
            if (!allCurrentlyOnline.contains(graceName)) {
                result.add(new OnlinePlayer(graceName, "", TIER_GUILD));
            }
        }

        return new GatherResult(result, modGuildUuids, queuedUuids, guildInfo != null);
    }

    static String stringOrEmpty(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return "";
    }
}
