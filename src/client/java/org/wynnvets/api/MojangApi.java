package org.wynnvets.api;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Mojang API endpoint builder for player UUID lookups.
 *
 * <p>Provides URI construction for the Mojang username-to-UUID endpoint,
 * used to resolve player names into UUIDs for further WynnCraft API calls.</p>
 */
public final class MojangApi {

    private MojangApi() {}

    /**
     * Builds a URI to look up a player's UUID by their Minecraft username. The name is
     * URL-encoded, as the other lookup providers encode theirs: raw, a space or {@code |} made
     * {@code URI.create} throw, and a {@code #} or {@code ?} cut the name short, so the lookup
     * asked about a different player.
     *
     * @param userName the Minecraft username to look up
     * @return a URI targeting the Mojang profile endpoint
     */
    public static URI getUserUUID(String userName) {
        return URI.create(
                "https://api.mojang.com/users/profiles/minecraft/"
                        + URLEncoder.encode(userName, StandardCharsets.UTF_8));
    }
}
