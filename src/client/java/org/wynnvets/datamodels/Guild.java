package org.wynnvets.datamodels;

/**
 * GSON-deserialisable model for a WynnCraft guild.
 *
 * <p>Fields are populated by GSON from the WynnCraft API response JSON.
 * Only the subset of guild properties needed by VetsMod is mapped.</p>
 */
public class Guild {
  private String uuid;
  private String name;
  private String prefix;
  private String rank;

  @Override
  public String toString() {
    return "GUILD INFO:\n" + uuid + "\n" + name + "\n" + prefix + "\n" + rank;
  }

  public String getUuid() {
    return uuid;
  }
}
