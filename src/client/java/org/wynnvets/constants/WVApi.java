package org.wynnvets.constants;

import java.net.URI;

public class WVApi {
  public static String guildUUID = "a36bd64c-c053-4727-872d-b0d0729f474a";

  /**
   * The endpoint for the outbound stamp.
   */
  public static final URI Stamp = URI.create("http://api.wynnvets.org/v0/outbound/stamp");

  /**
   * Chat outbound endpoint
   */
  public static final URI ChatOutbound = URI.create("http://api.wynnvets.org/v0/outbound/chat");

  /**
   * Bridge outbound endpoint (for guildless+unlocked users)
   */
  public static final URI BridgeOutbound = URI.create("http://api.wynnvets.org/v0/outbound/bridge");

  /**
   * Chat inbound endpoint
   */
  public static final URI ChatInbound = URI.create("http://api.wynnvets.org/v0/inbound");

  /**
   * Endpoint for information about the returns.
   */
  public static final URI Return = URI.create("http://api.wynnvets.org/v0/outbound/return");

  /**
   * MOTD information.
   */
  public static final URI Motd = URI.create("http://api.wynnvets.org/v0/outbound/motd");

  /**
   * Link to the WV anni website.
   */
  public static final URI Anni = URI.create("https://wynnvets.org/anni");

  /**
   * Endpoint for the list of supporters (users who receive special pill styling).
   */
  public static final URI Supporters = URI.create("http://api.wynnvets.org/v0/outbound/supporters");

  /**
   * Endpoint for confirmed staff members and their ranks.
   */
  public static final URI Staff = URI.create("http://api.wynnvets.org/v0/outbound/staff");
}
