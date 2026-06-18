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
 * into a unified online member list. Used by {@link ListFetcher},
 * {@link WorldListFetcher}, and {@link
 * org.wynnvets.fetcher.ondemand.UserInfoFetcher}.</p>
 */
public final class OnlineMemberService {

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
        // Only Returners members are safe to forward from: the tab list
        // reflects whichever guild the local player is in, so honourary or
        // waitlist users who happen to be in some other guild would
        // otherwise leak that guild's roster into the vets dataset.
        if (!tabEntries.isEmpty() && GuildStateManager.isReturners()) {
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

    /**
     * Looks up the current server name (e.g. {@code "NA35"}) for
     * {@code username} by scanning the local Minecraft tab list.
     * Returns {@code null} when the player is not visible — either
     * offline, in a different guild, or the local player isn't in
     * any guild.
     *
     * <p>Cheap synchronous call: does not trigger any of the three
     * async fetches used by {@link #gatherOnlinePlayers()}. Safe to
     * call inline from per-player commands like {@code /wv check}.
     */
    static String getCurrentServer(String username) {
        if (username == null || username.isEmpty()) {
            return null;
        }
        for (TabListGuildParser.GuildEntry entry : TabListGuildParser.parseOnlineGuildMembers()) {
            if (entry.username().equalsIgnoreCase(username)) {
                return entry.server();
            }
        }
        return null;
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

        // Renamed players: the server's GuildRosterPoller reads legacyName from the
        // v3.8 guild payload and populates /v1/outbound/aliases; WynnAliasCache polls
        // that endpoint, so WynnAliasCache.getUuid() below resolves stale tab names.

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

        // Tab list entries: resolve to UUID by current username, falling back to
        // the server-learned legacyName alias (v3.8 populates WynnAliasCache via
        // /v1/outbound/aliases).  Unresolved names are kept as tab-only entries.
        Set<String> tabOnlyUsernames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (TabListGuildParser.GuildEntry entry : tabEntries) {
            String key = entry.username().toLowerCase(Locale.ROOT);
            String uuid = usernameToUuid.get(key);
            if (uuid == null) {
                uuid = WynnAliasCache.getUuid(entry.username());
            }
            if (uuid != null) {
                mergedOnlineGuild.add(uuid);
            } else {
                tabOnlyUsernames.add(entry.username());
            }
        }

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
