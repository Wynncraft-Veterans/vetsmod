package org.wynnvets.fetcher.ondemand;

import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.api.VetsApi;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.fetcher.polling.AnniStampPoller;
import org.wynnvets.mwe.anni.network.AnniQueryClient;
import org.wynnvets.mwe.anni.render.AnniCommandRenderer;
import org.wynnvets.mwe.anni.render.AnniMotdRenderer;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * On-demand fetcher for the annihilation event countdown timer.
 *
 * <p>Two callers:</p>
 * <ul>
 *   <li>{@link #fetchStampAndCreateMessage()} — invoked by the world-join
 *       auto-display path ({@link org.wynnvets.guild.GuildStateManager#fetchAndDisplayStampMessage
 *       GuildStateManager#fetchAndDisplayStampMessage}). Prefers {@link AnniMotdRenderer}
 *       (snapshot-driven, condensed) when a cached snapshot is present <em>and</em> {@code
 *       vetsAnniEnabled} is true; otherwise falls back to {@link #fetchSimple()} — the legacy
 *       stamp-only text. This keeps the world-join behaviour for external users completely
 *       unchanged.</li>
 *   <li>{@link #fetchStampAndCreateAnniCommandMessage()} — invoked by
 *       {@code /wv anni} (no args). Prefers {@link AnniCommandRenderer}
 *       when a cached snapshot is present; otherwise falls back to the
 *       legacy stamp message, with the past-stamp branch expanded to
 *       the legacy "not announced" string. Per spec, the manual
 *       invocation never returns null — it always has something to say.</li>
 * </ul>
 *
 * <p>S2's snapshot path requires the MWE master toggle to be on. S1
 * auto-enables {@link VetsConfig#VETS_ANNI_ENABLED} for vets-tier users on
 * the first auth ack; everyone else can opt in via
 * {@code /wv config vetsAnniEnabled true} if they want the enriched
 * view (Hard Rule #3: pulls are open to anyone).</p>
 */
public class StampFetcher {
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  /**
   * Auto-display path (world-join). Returns the snapshot-driven motd
   * line when available, else the legacy stamp-only message.
   *
   * @return CompletableFuture containing the message Component, or null
   *         if nothing should be displayed (e.g. stamp in past + no
   *         snapshot)
   */
  public static CompletableFuture<MutableComponent> fetchStampAndCreateMessage() {
    if (anniIntegrationActive()) {
      AnniSnapshot snapshot = AnniSnapshotCache.latest();
      if (snapshot != null) {
        MutableComponent line = AnniMotdRenderer.render(snapshot);
        if (line != null) {
          return CompletableFuture.completedFuture(line);
        }
        // The renderer suppresses itself when no confirmed future stamp
        // exists (spec §"For external users"). Fall through to the
        // legacy stamp path, which also suppresses past stamps —
        // matches the spec exactly.
      } else {
        // Cache cold — kick a fire-and-forget snapshot pull so the boss
        // bar (S3) and the aggressive components (S5) light up without
        // waiting for the next push-poller tick. Outside the T-2h hot
        // window the push interval is 5 min, which made the in-game
        // experience: "I logged in inside the anni zone, the legacy
        // motd showed, but the bar didn't appear until I ran /wv anni."
        // That manual command incidentally triggered the same query
        // and unblocked the bar — symptoms identical to a missing
        // listener, but the actual cause was that nothing on world-
        // join asked the server for the snapshot. The pull populates
        // the cache via AnniQueryClient.onResponse → AnniSnapshotCache
        // .update, which the per-tick boss-bar gate picks up next
        // frame. We do NOT await it — the motd auto-print can't wait
        // for the round-trip, so on the very first cold-start world
        // join the user sees the legacy text and the rich bar appears
        // a moment later. Subsequent world joins find the cache warm
        // and render the rich motd directly.
        AnniQueryClient.query();
      }
    }
    return fetchSimple();
  }

  /**
   * /wv anni (manual). Returns one or more snapshot-driven blocks, or
   * the legacy stamp message + "not announced" fallback as a single
   * block. Each list element is rendered as its own {@code [VETSMOD]}
   * chat block by the caller — the imminent (within-2h) render uses
   * this to break the mode-switch UI out into its own prefixed block.
   *
   * <p>Returns {@code null} from the future when nothing is available
   * (parse failures, request errors). Empty lists are never returned —
   * the renderer either produces ≥1 element or returns {@code null} to
   * signal "fall back to legacy."</p>
   */
  public static CompletableFuture<List<MutableComponent>> fetchStampAndCreateAnniCommandMessage() {
    if (anniIntegrationActive()) {
      AnniSnapshot cached = AnniSnapshotCache.latest();
      if (cached != null) {
        List<MutableComponent> rendered = AnniCommandRenderer.render(cached);
        if (rendered != null) {
          return CompletableFuture.completedFuture(rendered);
        }
        // The renderer returns null for external + announced (the
        // anni.wynnvets.org dashboard is reserved for vets-anni users);
        // fall through to the legacy stamp text, which matches the
        // spec's "keep wv anni as is" for external users.
        return legacyFallback();
      }
      // Cache cold — try a fresh on-demand pull before giving up.
      // This is the standard cold-start path: vetsmod just logged in,
      // the push poller hasn't ticked yet, but the user invoked the
      // command. AnniQueryClient.query() resolves null when the
      // inbound WS is down or the player isn't in vets-anni's DB
      // (e.g. an external user); both cases drop to legacy.
      return AnniQueryClient.query().thenCompose(pulled -> {
        if (pulled != null) {
          List<MutableComponent> rendered = AnniCommandRenderer.render(pulled);
          if (rendered != null) {
            return CompletableFuture.completedFuture(rendered);
          }
        }
        return legacyFallback();
      });
    }
    return legacyFallback();
  }

  /** Shared legacy fallback returning the stamp/"not announced" line
   *  as a single-element list. {@code null} from the future when the
   *  stamp is missing AND the cache poller has nothing either (true
   *  cold start with network errors). */
  private static CompletableFuture<List<MutableComponent>> legacyFallback() {
    return fetchSimple().thenApply(message -> {
      if (message != null) {
        return List.of(message);
      }
      long stamp = AnniStampPoller.getLatestStamp();
      if (stamp == 0L || isStampInPast(stamp)) {
        return List.of((MutableComponent) Component.literal(
                "The time for the next annihilation has not yet been announced")
            .withStyle(ChatFormatting.RED));
      }
      return null;
    });
  }

  /**
   * Legacy stamp-only fetcher (the pre-S2 behaviour). Issues a GET to
   * {@code /v1/outbound/stamp}, parses the epoch-seconds, and returns
   * either a long-form or short-form countdown Component. Null when
   * the stamp is past, missing, or the request errors.
   */
  public static CompletableFuture<MutableComponent> fetchSimple() {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(VetsApi.STAMP)
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() == HttpURLConnection.HTTP_OK) {
            try {
              long stamp = Long.parseLong(response.body().trim());
              AnniStampPoller.updateFromExternalFetch(stamp);
              return createMessageForStamp(stamp);
            } catch (NumberFormatException e) {
              VetsLogger.warn("Failed to parse annihilation stamp: {}", e.getMessage());
              return null;
            }
          } else {
            VetsLogger.warn("Failed to fetch annihilation stamp (Status: {})", response.statusCode());
            return null;
          }
        })
        .exceptionally(e -> {
          VetsLogger.error("Error fetching annihilation stamp: {}", e.getMessage());
          return null;
        });
  }

  /** True when the S2 snapshot-driven path is allowed to short-circuit
   *  the legacy stamp call. Gated by the master MWE toggle so non-vets
   *  users keep the unchanged legacy experience until they opt in. */
  private static boolean anniIntegrationActive() {
    return VetsConfig.get(VetsConfig.VETS_ANNI_ENABLED);
  }

  /**
   * Creates the appropriate message based on the stamp timestamp
   *
   * @param stamp Unix timestamp from the API
   * @return MutableComponent message, or null if timestamp is in the past
   */
  private static MutableComponent createMessageForStamp(long stamp) {
    long currentTime = System.currentTimeMillis() / 1000; // Convert to seconds
    long timeDiff = stamp - currentTime;

    // If in the past, don't display anything
    if (timeDiff <= 0) {
      return null;
    }

    // Convert time difference to minutes and hours
    long totalMinutes = timeDiff / 60;
    long hours = totalMinutes / 60;
    long minutes = totalMinutes % 60;

    // If less than 1 hour
    if (totalMinutes < 60) {
      return createShortMessage((int) totalMinutes);
    } else {
      return createLongMessage((int) hours, (int) minutes);
    }
  }

  private static boolean isStampInPast(long stamp) {
    long currentTime = System.currentTimeMillis() / 1000;
    return stamp <= currentTime;
  }

  /**
   * Creates the message for when annihilation is more than 1 hour away
   *
   * @param hours   Number of hours until annihilation
   * @param minutes Number of remaining minutes until annihilation
   * @return MutableComponent with the formatted message
   */
  private static MutableComponent createLongMessage(int hours, int minutes) {
    // Line 1: &c&lAnnhilation &4&lreturns in &c&l<int> hours <int> mins&4&l!
    MutableComponent line1 = Component.literal("Annihilation ")
        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
        .append(Component.literal("returns in ")
            .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
        .append(Component.literal(hours + " hours " + minutes + " mins")
            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
        .append(Component.literal("!")
            .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));

    // Line 2: &7Please remember to show up one hour early or RSVP.
    MutableComponent line2 = Component.literal("\nPlease remember to show up one hour early or RSVP.")
        .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false));

    // Line 3: &4For more info, visit &c&o&nthis page&4!
    MutableComponent line3 = Component.literal("\n")
        .append(Component.literal("For more info, visit ")
            .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_RED).withBold(false)))
        .append(Component.literal("this page")
            .withStyle(Style.EMPTY
                .withColor(ChatFormatting.RED)
                .withItalic(true)
                .withUnderlined(true)
                .withBold(false)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(VetsApi.ANNI.toString())))))
        .append(Component.literal("!")
            .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_RED).withBold(false)));

    return line1.append(line2).append(line3);
  }

  /**
   * Creates the message for when annihilation is less than 1 hour away
   *
   * @param minutes Number of minutes until annihilation
   * @return MutableComponent with the formatted message
   */
  private static MutableComponent createShortMessage(int minutes) {
    // Line 1: &cAnnhilation is in <int> mins&4!
    MutableComponent line1 = Component.literal("Annihilation is in ")
        .withStyle(ChatFormatting.RED)
        .append(Component.literal(minutes + " mins")
            .withStyle(ChatFormatting.RED))
        .append(Component.literal("!")
            .withStyle(ChatFormatting.DARK_RED));

    // Line 2: &8Click [here](https://wynnvets.org/anni) for more info!
    MutableComponent line2 = Component.literal("\nClick ")
        .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withBold(false))
        .append(Component.literal("here")
            .withStyle(Style.EMPTY
                .withColor(ChatFormatting.DARK_GRAY)
                .withBold(false)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(VetsApi.ANNI.toString())))))
        .append(Component.literal(" for more info!")
            .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withBold(false)));

    return line1.append(line2);
  }
}
