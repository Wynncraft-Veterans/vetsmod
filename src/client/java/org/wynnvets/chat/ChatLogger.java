package org.wynnvets.chat;

import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logs incoming chat messages to a local debug file.
 *
 * <p>Guild chat relay to the API is now handled by the Wynntils event
 * listener via the v1 WebSocket inbound endpoint. This class only
 * performs local file logging for debugging purposes.</p>
 */
public class ChatLogger {
  private static final String LOG_FILE = "vetsmod/debug.log";
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final long DUPLICATE_THRESHOLD_MS = 1000;
  private static final String GUILD_PREPEND_FULL = "\uDAFF\uDFFC\uE006\uDAFF\uDFFF\uE002\uDAFF\uDFFE";
  private static final String GUILD_PREPEND_COMPACT = "\uDAFF\uDFFC\uE001\uDB00\uDC06";
  private static final String PRIVATE_SEPARATOR_GLYPH = "\uE003";

  private static final Map<String, Long> recentMessages = new LinkedHashMap<String, Long>(100, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
      return size() > 100 || (System.currentTimeMillis() - eldest.getValue()) > 5000;
    }
  };

  // PUA rank-pill → raw Wynn rank name. Wynncraft prefixes every guild
  // chat message with an invisible PUA sequence encoding both the rank
  // marker AND the letters of the rank name; the whole sequence is used
  // as the key so message.contains(indicator) matches only the exact
  // rank pill.
  //
  // The sequence decodes as:
  //
  //   U+E062                  pill opener
  //   U+CFFxx                 negative-advance marker sizing the pill
  //                           background; 0xD0000 - marker is the label's
  //                           rendered width in px (owner=32, chief=30,
  //                           strategist=60, captain=42, recruiter=54,
  //                           recruit=42)
  //   U+E000 + (c - 'a') ...  the rank name, one glyph per letter
  //   U+D0002                 pill terminator
  //
  // Captain and Recruit share a marker because both labels happen to
  // render 42px wide — but the letter glyphs differ, so the two keys are
  // distinct strings and both bindings are live. (An earlier comment here
  // claimed the second put overwrote the first; it does not. All six keys
  // are distinct and none is a substring of another, so the iteration
  // order below carries no meaning.)
  //
  // The first entry is labelled Owner, not Chief — its glyphs spell
  // "owner". Both land on "Steward" today, so that is presently cosmetic,
  // but the label matters if Chief and Owner ever get separate display
  // labels (see RankDisplayMap).
  //
  // This map is NOT debug-only: the rewriter reads it via rankMap() to
  // decode incoming pills and route them through RankDisplayMap.
  private static final Map<String, String> RANK_MAP = new LinkedHashMap<>();
    static {
        RANK_MAP.put("󏿠󐀂", "Owner");
        RANK_MAP.put("󏿢󐀂", "Chief");
        RANK_MAP.put("󏿄󐀂", "Strategist");
        RANK_MAP.put("󏿖󐀂", "Captain");
        RANK_MAP.put("󏿊󐀂", "Recruiter");
        RANK_MAP.put("󏿖󐀂", "Recruit");
    }

  /**
   * Read-only view of the PUA-pill → raw-Wynn-rank map.
   *
   * <p>Exposed so the guild-chat rewriter can decode the pill of an
   * incoming message and route it through
   * {@link RankDisplayMap#displayFor(String)} for the client-facing
   * rewrite (Strategist/Chief/Owner → "Steward", Recruiter/Captain
   * → "Returner"). Kept as a method rather than a public field so
   * callers can't mutate the LinkedHashMap.</p>
   */
  public static Map<String, String> rankMap() {
    return java.util.Collections.unmodifiableMap(RANK_MAP);
  }

  public static void logMessage(String message) {
    if (!GuildStateManager.areFeaturesEnabled()) {
      return;
    }

    if (!VetsLogger.isDebugEnabled()) {
      return;
    }

    String processedMessage = processTruncatedMessage(message);
    ParsedMessage parsed = parseMessage(processedMessage);
    String dedupKey = (parsed != null) ? parsed.message : processedMessage;
    long currentTime = System.currentTimeMillis();
    synchronized (recentMessages) {
      Long lastTime = recentMessages.get(dedupKey);
      if (lastTime != null && (currentTime - lastTime) < DUPLICATE_THRESHOLD_MS) {
        return;
      }
      recentMessages.put(dedupKey, currentTime);
    }

    if (containsFilterString(message) && parsed != null) {
      String timestamp = LocalDateTime.now(ZoneOffset.UTC).format(TIME_FORMATTER);
      String jsonEntry = createJsonLogEntry(timestamp, parsed);
      try {
        File logFile = new File(LOG_FILE);
        File parentDir = logFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
          parentDir.mkdirs();
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
          writer.write(jsonEntry);
          writer.newLine();
        }
      } catch (IOException e) {
        VetsLogger.warn("Failed to write to filtered chat log: {}", e.getMessage());
      }
    }
  }

  /**
   * Normalizes a raw chat buffer dump for logging by removing guild prefix
   * glyphs and joining continuation lines.
   *
   * <p>Wynncraft's chat buffer often contains multiple concatenated guild
   * messages.  Each continuation line is prefixed with invisible PUA rank
   * glyphs ({@code GUILD_PREPEND_FULL} / {@code GUILD_PREPEND_COMPACT}).
   * This method:
   * <ol>
   *   <li>Replaces continuation-line prefixes ({@code \n + glyph}) with
   *       the visible separator glyph so the rank boundary is preserved.</li>
   *   <li>Strips standalone guild prepend glyphs (not preceded by newline)
   *       that appear at the start of the first message or after joins.</li>
   *   <li>Joins all remaining lines into a single string for dedup/parse.</li>
   * </ol></p>
   */
  private static String processTruncatedMessage(String message) {
    String processed = message
        // Step 1: continuation-line prefixes → visible separator
        .replace("\n" + GUILD_PREPEND_FULL, "\n" + PRIVATE_SEPARATOR_GLYPH + " ")
        .replace("\n" + GUILD_PREPEND_COMPACT, "\n" + PRIVATE_SEPARATOR_GLYPH + " ")
        // Step 2: strip standalone prepend glyphs (with/without trailing space)
        .replace(GUILD_PREPEND_FULL + " ", "")
        .replace(GUILD_PREPEND_COMPACT + " ", "")
        .replace(GUILD_PREPEND_FULL, "")
        .replace(GUILD_PREPEND_COMPACT, "");

    // Step 3: join continuation lines into a single string
    String[] lines = processed.split("\n");
    if (lines.length == 1) {
      return processed;
    }
    return String.join("", lines);
  }

  private static boolean containsFilterString(String message) {
    for (String rankIndicator : RANK_MAP.keySet()) {
      if (message.contains(rankIndicator)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Parses a processed chat message into its structured parts (rank, username,
   * message body) by scanning for PUA rank indicator sequences.
   *
   * <p>Guild chat messages contain invisible PUA rank indicators (defined in
   * {@link #RANK_MAP}) that identify the sender's guild rank.  This method
   * scans for the first matching indicator, then extracts the username
   * (text between the indicator and {@code ':'}) and the message body
   * (text after {@code ':'}, truncated at the next concatenated message
   * boundary via {@link #stripConcatenatedContent}).</p>
   */
  private static ParsedMessage parseMessage(String message) {
    for (Map.Entry<String, String> entry : RANK_MAP.entrySet()) {
      String rankIndicator = entry.getKey();
      String rankName = entry.getValue();

      if (message.contains(rankIndicator)) {
        int rankIndex = message.indexOf(rankIndicator);
        String afterRank = message.substring(rankIndex + rankIndicator.length()).trim();
        int colonIndex = afterRank.indexOf(':');
        if (colonIndex > 0) {
          String username = afterRank.substring(0, colonIndex).trim();
          String messageContent = stripConcatenatedContent(afterRank.substring(colonIndex + 1).trim());
          return new ParsedMessage(rankName, username, messageContent);
        }
      }
    }
    return null;
  }

  /**
   * Truncate message content at the first Wynncraft custom-glyph character.
   * The game uses codepoints from Planes 12-13 (U+C0000-U+DFFFF, guild prefix
   * marks, rank badges) and the Supplementary Private Use Areas in Planes 15-16
   * (U+F0000-U+10FFFD).  These mark the start of the next guild message's rank
   * indicator when multiple chat messages have been concatenated into a single
   * buffer dump.
   */
  private static String stripConcatenatedContent(String message) {
    for (int i = 0; i < message.length(); ) {
      int cp = message.codePointAt(i);
      if (cp >= 0xC0000 && cp <= 0x10FFFD) {
        return message.substring(0, i).trim();
      }
      i += Character.charCount(cp);
    }
    return message;
  }

  private static String createJsonLogEntry(String timestamp, ParsedMessage parsed) {
    String escapedMessage = escapeJson(parsed.message);
    String escapedUsername = escapeJson(parsed.username);

    return String.format("{\"timestamp\":\"%s\",\"rank\":\"%s\",\"username\":\"%s\",\"message\":\"%s\"}",
        timestamp, parsed.rank, escapedUsername, escapedMessage);
  }

  private static String escapeJson(String str) {
    return str.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private static class ParsedMessage {
    final String rank;
    final String username;
    final String message;

    ParsedMessage(String rank, String username, String message) {
      this.rank = rank;
      this.username = username;
      this.message = message;
    }
  }
}
