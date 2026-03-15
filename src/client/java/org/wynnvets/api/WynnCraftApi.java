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
     * Builds a URI to fetch information about a guild by name.
     *
     * @param guildName the guild name to look up (will be URL-encoded)
     * @return a URI targeting the WynnCraft guild endpoint
     */
    public static URI guildInfo(String guildName) {
        return URI.create(String.format(
                "https://api.wynncraft.com/v3/guild/%s",
                URLEncoder.encode(guildName, StandardCharsets.UTF_8)));
    }
}
