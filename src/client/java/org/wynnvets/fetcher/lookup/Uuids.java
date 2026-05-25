package org.wynnvets.fetcher.lookup;

import java.util.UUID;

/** Small UUID helpers shared by providers that receive undashed IDs. */
public final class Uuids {

  private Uuids() {}

  /**
   * Parses either a dashed or undashed UUID string. Returns null if the
   * input is null, blank, or not parseable as 32 or 36 hex characters.
   */
  public static UUID parse(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.isEmpty()) return null;
    try {
      if (s.length() == 32) {
        s = s.substring(0, 8) + "-"
            + s.substring(8, 12) + "-"
            + s.substring(12, 16) + "-"
            + s.substring(16, 20) + "-"
            + s.substring(20);
      }
      return UUID.fromString(s);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
