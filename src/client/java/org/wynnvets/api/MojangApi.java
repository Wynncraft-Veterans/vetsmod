package org.wynnvets.api;

import java.net.URI;

/**
 * Mojang API endpoint builder for player UUID lookups.
 *
 * <p>Provides URI construction for the Mojang username-to-UUID endpoint,
 * used to resolve player names into UUIDs for further WynnCraft API calls.</p>
 */
public final class MojangApi {

    private MojangApi() {}

    /**
     * Builds a URI to look up a player's UUID by their Minecraft username.
     *
     * @param userName the Minecraft username to look up
     * @return a URI targeting the Mojang profile endpoint
     */
    public static URI getUserUUID(String userName) {
        return URI.create("https://api.mojang.com/users/profiles/minecraft/" + userName);
    }
}
