package org.wynnvets.fetcher.polling;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.wynnvets.api.VetsApi;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polling fetcher that retrieves bridge chat messages from the VetsMod API.
 *
 * <p>The bridge serves two purposes: it allows guildless-but-unlocked users to
 * read guild chat, and it provides Fruma-mode support where server guild chat
 * is replaced by bridge messages to avoid duplicates in the Fruma region.
 * Duplicate detection, version negotiation, and self-message queueing are
 * all handled internally.</p>
 */
public class BridgeMessageFetcher {
  private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)");
  private static final int FETCH_INTERVAL_SECONDS = 3;
  private static final int MAX_CACHED_MESSAGE_IDS = 1000;
  private static final int MAX_PENDING_SELF_MESSAGES = 50;
  private static final long SELF_MESSAGE_TTL_MS = TimeUnit.SECONDS.toMillis(30);
  private static final long SERVER_MESSAGE_DEDUP_WINDOW_MS = TimeUnit.SECONDS.toMillis(10);
  private static final int MAX_RECENT_SERVER_MESSAGES = 200;
  private static final int MAX_RECENT_BRIDGE_MESSAGES = 200;

  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private static final HttpRequest BRIDGE_REQUEST = HttpRequest.newBuilder()
      .uri(VetsApi.BRIDGE_OUTBOUND)
      .timeout(Duration.ofSeconds(5))
      .GET()
      .build();

  private static final Gson GSON = new Gson();
  private static final Set<String> displayedMessageIds = new LinkedHashSet<>();
  private static final Deque<PendingSelfMessage> pendingSelfMessages = new ArrayDeque<>();
  private static final Object pendingSelfMessagesLock = new Object();
  private static final Deque<RecentServerMessage> recentServerMessages = new ArrayDeque<>();
  private static final Object recentServerMessagesLock = new Object();
  private static final Deque<RecentBridgeMessage> recentBridgeMessages = new ArrayDeque<>();
  private static final Object recentBridgeMessagesLock = new Object();
  private static ScheduledExecutorService scheduler;
  private static boolean isRunning = false;
  private static volatile boolean frumaModeEnabled = shouldEnableFrumaModeByDefault();

  private static boolean shouldEnableFrumaModeByDefault() {
    try {
      Optional<String> friendlyVersion = FabricLoader.getInstance()
          .getModContainer("wynntils")
          .map(modContainer -> modContainer.getMetadata().getVersion().getFriendlyString());

      if (friendlyVersion.isEmpty()) {
        return false;
      }

      Matcher matcher = VERSION_PATTERN.matcher(friendlyVersion.get());
      if (!matcher.find()) {
        return false;
      }

      int major = Integer.parseInt(matcher.group(1));
      int minor = Integer.parseInt(matcher.group(2));
      return major > 4 || (major == 4 && minor >= 1);
    } catch (Exception ignored) {
      return false;
    }
  }

  private static final class PendingSelfMessage {
    private final String displayName;
    private final String message;
    private final long createdAtMs;

    private PendingSelfMessage(String displayName, String message, long createdAtMs) {
      this.displayName = displayName;
      this.message = message;
      this.createdAtMs = createdAtMs;
    }
  }

  private static final class RecentServerMessage {
    private final String displayName;
    private final String message;
    private final long createdAtMs;

    private RecentServerMessage(String displayName, String message, long createdAtMs) {
      this.displayName = displayName;
      this.message = message;
      this.createdAtMs = createdAtMs;
    }
  }

  private static final class RecentBridgeMessage {
    private final String displayName;
    private final String message;
    private final long createdAtMs;

    private RecentBridgeMessage(String displayName, String message, long createdAtMs) {
      this.displayName = displayName;
      this.message = message;
      this.createdAtMs = createdAtMs;
    }
  }

  /**
   * Starts the periodic fetching of bridge messages
   */
  public static void start() {
    if (isRunning) {
      return;
    }

    isRunning = true;
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "VetsMod-BridgeFetcher");
      thread.setDaemon(true);
      return thread;
    });

    // Schedule the fetch task to run every 3 seconds
    scheduler.scheduleAtFixedRate(() -> {
      try {
        fetchAndDisplayMessages();
      } catch (Exception e) {
        VetsLogger.warn("Error fetching bridge messages: {}", e.getMessage());
      }
    }, 0, FETCH_INTERVAL_SECONDS, TimeUnit.SECONDS);
  }

  /**
   * Stops the periodic fetching
   */
  public static void stop() {
    if (scheduler != null && !scheduler.isShutdown()) {
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduler.shutdownNow();
      }
    }
    isRunning = false;
  }

  /**
   * Fetches messages from the API and displays new ones in chat
   */
  private static void fetchAndDisplayMessages() {
    boolean isWaitlistBridgeEnabled = GuildStateManager.isGuildless() && GuildStateManager.isUnlocked();
    boolean isFrumaBridgeEnabled = frumaModeEnabled && GuildStateManager.canExecuteCommands() && !GuildStateManager.isGuildless();

    if (!isWaitlistBridgeEnabled && !isFrumaBridgeEnabled) {
      return;
    }

    try {
      HttpResponse<String> response = HTTP_CLIENT.send(BRIDGE_REQUEST, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == HttpURLConnection.HTTP_OK) {
        processMessages(response.body());
      }
    } catch (Exception e) {
      VetsLogger.debug("Failed to fetch bridge messages: {}", e.getMessage());
    }
  }

  /**
   * Processes the JSON response and displays new messages
   */
  private static void processMessages(String jsonResponse) {
    try {
      JsonArray messages = GSON.fromJson(jsonResponse, JsonArray.class);

      // Limit processing to prevent overwhelming the system
      int processedCount = 0;
      int maxPerBatch = 10;

      for (int i = 0; i < messages.size() && processedCount < maxPerBatch; i++) {
        JsonObject messageObj = messages.get(i).getAsJsonObject();

        String id = messageObj.get("id").getAsString();

        // Only display messages we haven't seen before
        if (!displayedMessageIds.contains(id)) {
          // Prevent unbounded growth - remove oldest entry if cache is full
          if (displayedMessageIds.size() >= MAX_CACHED_MESSAGE_IDS) {
            String firstId = displayedMessageIds.iterator().next();
            displayedMessageIds.remove(firstId);
          }
          displayedMessageIds.add(id);

          // Extract strings from JSON
          String displayName = messageObj.get("display_name").getAsString();
          String message = messageObj.get("message").getAsString();
          String rank = messageObj.get("rank").getAsString();
          String source = messageObj.has("source") && !messageObj.get("source").isJsonNull()
              ? messageObj.get("source").getAsString()
              : "";

          if (shouldSuppressSelfMessage(displayName, message, source)) {
            continue;
          }

          if (shouldSuppressDuplicateApiMessage(displayName, message)) {
            continue;
          }

          if (frumaModeEnabled && wasServerMessageRecentlySent(displayName, message)) {
            continue;
          }

          ChatUtils.sendGuildChatMessage(rank, displayName, message);
          processedCount++;
        }
      }
    } catch (Exception e) {
      VetsLogger.warn("Error processing bridge messages: {}", e.getMessage());
    }
  }

  /**
   * Clears the cache of displayed message IDs
   */
  public static void clearCache() {
    displayedMessageIds.clear();
    synchronized (recentBridgeMessagesLock) {
      recentBridgeMessages.clear();
    }
  }

  /**
   * Queues a message the local player just sent so it can be matched against
   * incoming bridge messages and suppressed as a duplicate.
   *
   * @param displayName the sender's display name
   * @param message     the message content
   */
  public static void queuePendingSelfMessage(String displayName, String message) {
    if (displayName == null || displayName.isEmpty() || message == null || message.isEmpty()) {
      return;
    }

    synchronized (pendingSelfMessagesLock) {
      long now = System.currentTimeMillis();
      pruneExpiredPendingSelfMessages(now);

      if (pendingSelfMessages.size() >= MAX_PENDING_SELF_MESSAGES) {
        pendingSelfMessages.pollFirst();
      }

      pendingSelfMessages.addLast(new PendingSelfMessage(displayName, message, now));
    }
  }

  private static boolean shouldSuppressSelfMessage(String displayName, String message, String source) {
    if (displayName == null || message == null || displayName.isEmpty() || message.isEmpty()) {
      return false;
    }

    if (!"game".equalsIgnoreCase(source)) {
      return false;
    }

    synchronized (pendingSelfMessagesLock) {
      long now = System.currentTimeMillis();
      pruneExpiredPendingSelfMessages(now);

      Iterator<PendingSelfMessage> iterator = pendingSelfMessages.iterator();
      while (iterator.hasNext()) {
        PendingSelfMessage pending = iterator.next();
        if (pending.displayName.equalsIgnoreCase(displayName) && pending.message.equals(message)) {
          iterator.remove();
          return true;
        }
      }
    }

    return false;
  }

  private static void pruneExpiredPendingSelfMessages(long nowMs) {
    while (!pendingSelfMessages.isEmpty()) {
      PendingSelfMessage pending = pendingSelfMessages.peekFirst();
      if (pending == null || nowMs - pending.createdAtMs <= SELF_MESSAGE_TTL_MS) {
        return;
      }
      pendingSelfMessages.pollFirst();
    }
  }

  /**
   * TEMPORARY utility state for /frumamode bridge mirroring.
   */
  public static void setFrumaModeEnabled(boolean enabled) {
    frumaModeEnabled = enabled;
  }

  /**
   * TEMPORARY utility state for /frumamode bridge mirroring.
   */
  public static boolean isFrumaModeEnabled() {
    return frumaModeEnabled;
  }

  /**
   * Records a guild chat message received from the server for cross-source
   * deduplication with bridge messages.
   *
   * @param displayName the sender's display name
   * @param message     the message content
   */
  public static void recordServerGuildMessage(String displayName, String message) {
    if (displayName == null || displayName.isEmpty() || message == null || message.isEmpty()) {
      return;
    }

    synchronized (recentServerMessagesLock) {
      long now = System.currentTimeMillis();
      pruneExpiredServerMessages(now);

      if (recentServerMessages.size() >= MAX_RECENT_SERVER_MESSAGES) {
        recentServerMessages.pollFirst();
      }

      recentServerMessages.addLast(new RecentServerMessage(displayName, message, now));
    }
  }

  private static boolean wasServerMessageRecentlySent(String displayName, String message) {
    if (message == null || message.isEmpty()) {
      return false;
    }

    // Match on message content only — the server-side name may be a nickname
    // that bears no resemblance to the API display_name, so name comparison
    // is unreliable.  If the message text was recorded from the server at all,
    // the bridge echo is always redundant.
    String normalizedMessage = normalizeForDedup(message);

    synchronized (recentServerMessagesLock) {
      long now = System.currentTimeMillis();
      pruneExpiredServerMessages(now);

      for (RecentServerMessage recent : recentServerMessages) {
        if (normalizeForDedup(recent.message).equals(normalizedMessage)) {
          return true;
        }
      }
    }

    return false;
  }

  private static void pruneExpiredServerMessages(long nowMs) {
    while (!recentServerMessages.isEmpty()) {
      RecentServerMessage recent = recentServerMessages.peekFirst();
      if (recent == null || nowMs - recent.createdAtMs <= SERVER_MESSAGE_DEDUP_WINDOW_MS) {
        return;
      }
      recentServerMessages.pollFirst();
    }
  }

  /**
   * Checks whether a bridge message was recently displayed in chat.
   * Used by Fruma mode to suppress the corresponding server guild message.
   *
   * @param displayName the sender's display name
   * @param message     the message content
   * @return {@code true} if a matching bridge message was recently shown
   */
  public static boolean wasBridgeMessageRecentlyDisplayed(String displayName, String message) {
    if (message == null || message.isEmpty()) {
      return false;
    }

    // Match on message content only — same rationale as wasServerMessageRecentlySent:
    // the bridge records the real username while the server shows a nickname,
    // so name comparison is unreliable for cross-source dedup.
    String normalizedMessage = normalizeForDedup(message);

    synchronized (recentBridgeMessagesLock) {
      long now = System.currentTimeMillis();
      pruneExpiredBridgeMessages(now);

      for (RecentBridgeMessage recent : recentBridgeMessages) {
        if (normalizeForDedup(recent.message).equals(normalizedMessage)) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Determines whether an API-sourced message should be suppressed because
   * it duplicates one already displayed from the bridge or server.
   *
   * @param displayName the sender's display name
   * @param message     the message content
   * @return {@code true} if the message should be suppressed
   */
  public static boolean shouldSuppressDuplicateApiMessage(String displayName, String message) {
    if (displayName == null || displayName.isEmpty() || message == null || message.isEmpty()) {
      return false;
    }

    String normalizedMessage = normalizeForDedup(message);

    synchronized (recentBridgeMessagesLock) {
      long now = System.currentTimeMillis();
      pruneExpiredBridgeMessages(now);

      for (RecentBridgeMessage recent : recentBridgeMessages) {
        if (recent.displayName.equalsIgnoreCase(displayName)
            && normalizeForDedup(recent.message).equals(normalizedMessage)) {
          return true;
        }
      }

      if (recentBridgeMessages.size() >= MAX_RECENT_BRIDGE_MESSAGES) {
        recentBridgeMessages.pollFirst();
      }

      recentBridgeMessages.addLast(new RecentBridgeMessage(displayName, message, now));
      return false;
    }
  }

  private static void pruneExpiredBridgeMessages(long nowMs) {
    while (!recentBridgeMessages.isEmpty()) {
      RecentBridgeMessage recent = recentBridgeMessages.peekFirst();
      if (recent == null || nowMs - recent.createdAtMs <= SERVER_MESSAGE_DEDUP_WINDOW_MS) {
        return;
      }
      recentBridgeMessages.pollFirst();
    }
  }

  /**
   * Normalize a message for dedup comparison by stripping all custom-font
   * glyphs (private-use and unassigned supplementary-plane characters) and
   * collapsing line-wrapping differences so the same logical text matches
   * regardless of how it was wrapped or which glyphs the server injected.
   */
  private static String normalizeForDedup(String message) {
    if (message == null) return "";
    StringBuilder sb = new StringBuilder(message.length());
    int i = 0;
    while (i < message.length()) {
      int cp = message.codePointAt(i);
      int charCount = Character.charCount(cp);
      if (cp == '\n') {
        sb.append(' ');
      } else {
        int type = Character.getType(cp);
        boolean isCustomGlyph = type == Character.PRIVATE_USE
            || (type == Character.UNASSIGNED && cp > 0xFFFF);
        if (!isCustomGlyph) {
          sb.appendCodePoint(cp);
        }
      }
      i += charCount;
    }
    return sb.toString().replaceAll("  +", " ").trim();
  }
}
