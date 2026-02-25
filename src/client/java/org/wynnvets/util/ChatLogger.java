package org.wynnvets.util;

import org.wynnvets.constants.WVApi;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ChatLogger {
  private static final String LOG_FILE = "vetsmod/debug.log";
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final long DUPLICATE_THRESHOLD_MS = 1000; // 1-second window for duplicates
  private static final String GUILD_PREPEND_FULL = "\uDAFF\uDFFC\uE006\uDAFF\uDFFF\uE002\uDAFF\uDFFE";
  private static final String GUILD_PREPEND_COMPACT = "\uDAFF\uDFFC\uE001\uDB00\uDC06";
  private static final String PRIVATE_SEPARATOR_GLYPH = "\uE003";

  // HTTP client for sending data to API
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)  // Use HTTP/1.1 for better compatibility
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  // Cache to track recently logged messages and prevent duplicates
  private static final Map<String, Long> recentMessages = new LinkedHashMap<String, Long>(100, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
      // Remove entries older than 5 seconds or if cache exceeds 100 entries
      return size() > 100 || (System.currentTimeMillis() - eldest.getValue()) > 5000;
    }
  };

    // Rank mapping - unicode indicators to rank names
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
    // Only log messages if features are enabled (guild is Returners)
    if (!GuildInfoListener.areFeaturesEnabled()) {
      return;
    }

    // Parse the message to extract rank, username, and message content
    String processedMessage = processTruncatedMessage(message);
    ParsedMessage parsed = parseMessage(processedMessage);
    String dedupKey = (parsed != null) ? parsed.message : processedMessage;
    long currentTime = System.currentTimeMillis();
    synchronized (recentMessages) {
      Long lastTime = recentMessages.get(dedupKey);
      if (lastTime != null && (currentTime - lastTime) < DUPLICATE_THRESHOLD_MS) {
        // Duplicate message within threshold, skip logging
        return;
      }
      recentMessages.put(dedupKey, currentTime);
    }

    String timestamp = LocalDateTime.now(ZoneOffset.UTC).format(TIME_FORMATTER);

    // Log to temporary.log if message contains a filter string
    if (containsFilterString(message)) {
      if (parsed != null) {
        String jsonEntry = createJsonLogEntry(timestamp, parsed);
        try {
          // Ensure directory exists
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
          System.err.println("Failed to write to filtered chat log: " + e.getMessage());
        }

        // Send to API asynchronously
        sendToApi(jsonEntry);
      }
    }
  }

  private static void sendToApi(String jsonData) {
    // Send asynchronously to avoid blocking the game thread
    CompletableFuture.runAsync(() -> {
      try {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(WVApi.ChatInbound)
            .version(HttpClient.Version.HTTP_1_1)  // Use HTTP/1.1 for compatibility
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonData))
            .timeout(Duration.ofSeconds(5))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        // Log if the request failed
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          System.err.println("Failed to send data to API: HTTP " + response.statusCode());
        }
      } catch (Exception e) {
        System.err.println("Error sending data to API: " + e.getMessage());
      }
    });
  }

  private static String processTruncatedMessage(String message) {
    // Convert wrapped prepend glyphs to a separator and remove remaining prepend markers
    String processed = message
        .replace("\n" + GUILD_PREPEND_FULL, "\n" + PRIVATE_SEPARATOR_GLYPH + " ")
        .replace("\n" + GUILD_PREPEND_COMPACT, "\n" + PRIVATE_SEPARATOR_GLYPH + " ")
        .replace(GUILD_PREPEND_FULL + " ", "")
        .replace(GUILD_PREPEND_COMPACT + " ", "")
        .replace(GUILD_PREPEND_FULL, "")
        .replace(GUILD_PREPEND_COMPACT, "");

    // Split message into lines and join with spaces
    String[] lines = processed.split("\n");
    if (lines.length == 1) {
      return processed;
    }

    // Join multiple lines with spaces
    return String.join(" ", lines);
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
    // Try to find rank indicator, username, and message
    for (Map.Entry<String, String> entry : RANK_MAP.entrySet()) {
      String rankIndicator = entry.getKey();
      String rankName = entry.getValue();

      if (message.contains(rankIndicator)) {
        // Find the rank indicator and extract what comes after
        int rankIndex = message.indexOf(rankIndicator);
        String afterRank = message.substring(rankIndex + rankIndicator.length()).trim();

        // Extract username and message (format: "Username: Message")
        int colonIndex = afterRank.indexOf(':');
        if (colonIndex > 0) {
          String username = afterRank.substring(0, colonIndex).trim();
          String messageContent = afterRank.substring(colonIndex + 1).trim();
          return new ParsedMessage(rankName, username, messageContent);
        }
      }
    }
    return null;
  }

  private static String createJsonLogEntry(String timestamp, ParsedMessage parsed) {
    // Escape special characters in JSON strings
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
