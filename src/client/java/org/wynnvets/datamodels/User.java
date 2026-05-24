package org.wynnvets.datamodels;

import org.wynnvets.api.VetsApi;

/**
 * GSON-deserialisable model for a WynnCraft player.
 *
 * <p>Fields are populated by GSON from the WynnCraft API response JSON.
 * Provides convenience accessors for guild membership, veteran status,
 * and formatted join dates used by the {@code /wv check} command.</p>
 */
public class User {
  private String username;
  private String uuid;
  private Guild guild;
  private String firstJoin;
  private String lastJoin;
  private Boolean veteran;
  private Float playtime;
  private Boolean online;
  private String server;

  @Override
  public String toString() {
    return "USER INFO:\n" + username + "\n" + uuid + "\n" + guild;
  }

  public String getUsername() {
    return username;
  }

  public String getUuid() {
    return uuid;
  }

  public Guild getGuild() {
    return guild;
  }

  public String getFirstJoinDate() {
    return firstJoin == null ? null : firstJoin.split("T")[0];
  }

  public String getLastJoinDate() {
    return lastJoin == null ? null : lastJoin.split("T")[0];
  }

  public boolean isInVets() {
    if (guild == null) {
      return false;
    }

    return guild.getUuid().equals(VetsApi.GUILD_UUID);
  }

  /**
   * Wynncraft veteran badge state. The API returns {@code true} when the
   * player has the in-game veteran badge and omits the field (→ {@code null})
   * otherwise; an explicit {@code false} is never sent. Renderers should
   * treat {@code null} as "not a veteran", not as "hidden".
   */
  public Boolean getVeteran() {
    return veteran;
  }

  public boolean isVeteran() {
    if (veteran == null) {
      return false;
    }

    return veteran;
  }

  public boolean isInGuild() {
    return guild != null;
  }

  /**
   * Cumulative playtime in hours as reported by the Wynncraft API.
   * {@code null} when hidden by player privacy settings.
   */
  public Float getPlaytime() {
    return playtime;
  }

  /**
   * Live online state from Wynncraft's {@code /v3/player/{uuid}} response.
   * {@code null} when the player has API privacy enabled (the field is
   * omitted entirely). Renderers should distinguish null (unknown) from
   * {@code false} (definitely offline).
   */
  public Boolean getOnline() {
    return online;
  }

  /**
   * Current server name (e.g. {@code "NA35"}) when {@link #getOnline()}
   * is {@code true}. {@code null} when offline or hidden by privacy.
   */
  public String getServer() {
    return server;
  }
}