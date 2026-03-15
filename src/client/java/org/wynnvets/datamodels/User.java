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

  @Override
  public String toString() {
    return "USER INFO:\n" + username + "\n" + uuid + "\n" + guild;
  }

  public String getUsername() {
    return username;
  }

  public String getFirstJoinDate() {
    return String.format("%s", firstJoin.split("T")[0]);
  }

  public String getLastJoinDate() {
    return String.format("%s", lastJoin.split("T")[0]);
  }

  public boolean isInVets() {
    if (guild == null) {
      return false;
    }

    return guild.getUuid().equals(VetsApi.GUILD_UUID);
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
}