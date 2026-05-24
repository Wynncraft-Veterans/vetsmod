package org.wynnvets.datamodels;

/**
 * GSON-deserialisable model for the {@code check_membership} ack frame
 * (returned by {@code temporary-server} forwarding dazebot's
 * {@code GET /api/internal/check-snapshot/{name_or_uuid}} endpoint).
 *
 * <p>Carries the Discord-link / tier / blocklist / vetsmod-key snapshot
 * needed by the {@code /wv check} renderer to display items 6, 7, and 8
 * (blocklist state, Discord identity + tier, stage-2 vetsmod-key
 * boolean). Mirrors the JSON shape produced by dazebot one-to-one;
 * adjust both ends together if the wire format changes.</p>
 */
public class MembershipSnapshot {
  private String target_uuid;
  private String target_username;
  private Discord discord;
  private Boolean stage_2_active;
  private Boolean blocklisted;
  private String blocklist_reason;
  private Boolean in_returners_guild;
  private Integer waitlist_count;

  public String getTargetUuid() {
    return target_uuid;
  }

  public String getTargetUsername() {
    return target_username;
  }

  public Discord getDiscord() {
    return discord;
  }

  public boolean isStage2Active() {
    return Boolean.TRUE.equals(stage_2_active);
  }

  public boolean isBlocklisted() {
    return Boolean.TRUE.equals(blocklisted);
  }

  public String getBlocklistReason() {
    return blocklist_reason;
  }

  public boolean isInReturnersGuild() {
    return Boolean.TRUE.equals(in_returners_guild);
  }

  /**
   * Total rows in dazebot's {@code Waitlist} table. Guild-wide stat
   * (does not depend on the snapshot's target). Used by the
   * {@code /gu invite} gate to predict whether the target should be
   * routed through the waitlist instead.
   */
  public int getWaitlistCount() {
    return waitlist_count == null ? 0 : waitlist_count;
  }

  /**
   * Discord-link sub-object. {@code linked == false} means dazebot has no
   * {@code DiscordAccount} row pointing at this MC account; all other
   * fields are {@code null}/{@code false} in that case.
   */
  public static class Discord {
    private Boolean linked;
    private String disc_uuid;
    private String disc_display;
    private String tier;
    private Boolean waitlisted_modifier;
    private Boolean hiatus;
    private Boolean in_guild;

    public boolean isLinked() {
      return Boolean.TRUE.equals(linked);
    }

    public String getDiscUuid() {
      return disc_uuid;
    }

    public String getDiscDisplay() {
      return disc_display;
    }

    public String getTier() {
      return tier;
    }

    public boolean isWaitlistedModifier() {
      return Boolean.TRUE.equals(waitlisted_modifier);
    }

    public boolean isHiatus() {
      return Boolean.TRUE.equals(hiatus);
    }

    public boolean isInGuild() {
      return Boolean.TRUE.equals(in_guild);
    }
  }
}
