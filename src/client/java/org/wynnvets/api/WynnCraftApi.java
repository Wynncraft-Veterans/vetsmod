package org.wynnvets.api;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * WynnCraft API (v3) endpoint builder for player and guild lookups.
 *
 * <p>Constructs URIs for the public WynnCraft REST API used to retrieve
 * player statistics, guild rosters, and related data.</p>
 *
 * @see <a href="https://docs.wynncraft.com/docs/modules/player.html">WynnCraft API Docs</a>
 */
public final class WynnCraftApi {

    private WynnCraftApi() {}

    /**
     * Builds a URI to fetch player statistics and guild membership.
     *
     * @param uuid the player's UUID
     * @return a URI targeting the WynnCraft player endpoint
     */
    public static URI playerInfo(UUID uuid) {
        return URI.create(String.format("https://api.wynncraft.com/v3/player/%s", uuid));
    }

    /**
     * Builds a URI to fetch player statistics by name or dashed UUID string. The v3 endpoint
     * accepts both; this overload is used by the {@link org.wynnvets.fetcher.lookup.PlayerLookup
     * PlayerLookup} cascade's Wynncraft provider, which queries by name so Wynncraft can act as a
     * primary UUID source and replace the separate Mojang round-trip.
     *
     * @param nameOrUuid the player's Minecraft username or dashed UUID
     * @return a URI targeting the WynnCraft player endpoint
     */
    public static URI playerInfo(String nameOrUuid) {
        return URI.create("https://api.wynncraft.com/v3/player/"
                + URLEncoder.encode(nameOrUuid, StandardCharsets.UTF_8));
    }

    /**
     * Builds a URI to fetch information about a guild by name.
     *
     * @param guildName the guild name to look up (will be URL-encoded)
     * @return a URI targeting the WynnCraft guild endpoint
     */
    public static URI guildInfo(String guildName) {
        return URI.create(String.format(
                "https://api.wynncraft.com/v3/guild/%s",
                // URLEncoder.encode uses form-encoding (space → '+'); URL path
                // segments require '%20' per RFC 3986. Wynncraft's guild endpoint
                // 404s on '+', so translate. Literal '+' inputs are pre-encoded
                // as '%2B' by URLEncoder and are unaffected by this replace.
                URLEncoder.encode(guildName, StandardCharsets.UTF_8).replace("+", "%20")));
    }
}
