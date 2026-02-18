package org.wynnvets.datamodels;

import org.wynnvets.constants.WVApi;

public class User {
  private String username;
  private String uuid;
  private Guild guild;
  private String firstJoin;
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

  public boolean isInVets() {
    if (guild == null) {
      return false;
    }

    return guild.getUuid().equals(WVApi.guildUUID);
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