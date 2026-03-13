package org.wynnvets.constants;

import java.net.URI;
import java.net.URLEncoder;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

public final class WCApi {
  /**
   * Returns information about the user from WynnCraft.
   * <a href="https://docs.wynncraft.com/docs/modules/player.html#player-main-stats">WynnCraft API Docs</a>
   *
   * @param uuid - The UUID to get information from.
   * @return - Information about the user.
   */
  public static URI PlayerInfo(UUID uuid) {
    return URI.create(String.format("https://api.wynncraft.com/v3/player/%s", uuid));
  }

  /**
   * Returns information about a guild from WynnCraft.
   *
   * @param guildName - The guild name to get information from.
   * @return - Information about the guild.
   */
  public static URI GuildInfo(String guildName) {
    return URI.create(String.format(
        "https://api.wynncraft.com/v3/guild/%s",
        URLEncoder.encode(guildName, StandardCharsets.UTF_8)
    ));
  }
}
