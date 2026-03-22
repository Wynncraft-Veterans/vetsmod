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

  private static final Map<String, String> RANK_MAP = new LinkedHashMap<>();
    static {
        RANK_MAP.put("󏿠󐀂", "Chief");
        RANK_MAP.put("󏿢󐀂", "Chief");
        RANK_MAP.put("󏿄󐀂", "Strategist");
        RANK_MAP.put("󏿖󐀂", "Captain");
        RANK_MAP.put("󏿊󐀂", "Recruiter");
        RANK_MAP.put("󏿖󐀂", "Recruit");
    }

  public static void logMessage(String message) {
    if (!GuildStateManager.areFeaturesEnabled()) {
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

  private static String processTruncatedMessage(String message) {
    String processed = message
        .replace("\n" + GUILD_PREPEND_FULL, "\n" + PRIVATE_SEPARATOR_GLYPH + " ")
        .replace("\n" + GUILD_PREPEND_COMPACT, "\n" + PRIVATE_SEPARATOR_GLYPH + " ")
        .replace(GUILD_PREPEND_FULL + " ", "")
        .replace(GUILD_PREPEND_COMPACT + " ", "")
        .replace(GUILD_PREPEND_FULL, "")
        .replace(GUILD_PREPEND_COMPACT, "");

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
