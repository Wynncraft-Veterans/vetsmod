package org.wynnvets.constants;

import java.net.URI;
import java.util.UUID;

public final class WCApi {
    /**
     * Returns information about the user from WynnCraft.
     * <a href="https://docs.wynncraft.com/docs/modules/player.html#player-main-stats">WynnCraft API Docs</a>
     * @param uuid - The UUID to get information from.
     * @return - Information about the user.
     */
    public static URI PlayerInfo(UUID uuid) {
        return URI.create(String.format("https://api.wynncraft.com/v3/player/%s", uuid));
    }
}
