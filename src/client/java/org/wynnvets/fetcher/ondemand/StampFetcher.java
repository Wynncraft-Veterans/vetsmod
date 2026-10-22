package org.wynnvets.fetcher.ondemand;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.api.VetsApi;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.fetcher.polling.AnniStampPoller;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.network.AnniQueryClient;
import org.wynnvets.mwe.anni.render.AnniCommandRenderer;
import org.wynnvets.mwe.anni.render.AnniMotdRenderer;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;
import org.wynnvets.util.HttpClients;

/**
 * On-demand fetcher for the annihilation event countdown timer.
 *
 * <p>Two callers:</p>
 * <ul>
 *   <li>{@link #fetchStampAndCreateMessage()} — invoked by the world-join
 *       auto-display path ({@link org.wynnvets.guild.WorldJoinAnnouncer#fetchAndDisplayStampMessage
 *       WorldJoinAnnouncer#fetchAndDisplayStampMessage}). Prefers {@link AnniMotdRenderer}
 *       (snapshot-driven, condensed) when a cached snapshot is present <em>and</em> {@code
 *       vetsAnniEnabled} is true; otherwise falls back to {@link #fetchSimple()} — the legacy
 *       stamp-only text. This keeps the world-join behaviour for external users completely
 *       unchanged.</li>
 *   <li>{@link #fetchStampAndCreateAnniCommandMessage()} — invoked by
 *       {@code /wv anni} (no args). With {@code vetsAnniEnabled} on it
 *       prefers {@link AnniCommandRenderer}, pulling a snapshot first
 *       when the cache is cold; otherwise, or when that yields nothing
 *       to render, it falls back to the legacy stamp message, with the
 *       past-stamp branch expanded to the legacy "not announced" string.
 *       Per spec the manual invocation never comes back {@code null}: it
 *       always has something to say. When the legacy fetch yields no
 *       countdown, the fallback shows {@link AnniStampPoller}'s cached
 *       stamp if it is still in the future.
 *       A cold-cache pull that hits its deadline resolves {@code null} and
 *       drops to the legacy fetch like any other failed pull.
 *       {@code vetsmod_mwe_anni.md} §"{@code /wv anni} render dispatch"
 *       owns the terminal states.</li>
 * </ul>
 *
 * <p>The snapshot path in both callers, cold-cache pull included, runs only while
 * {@link VetsConfigKeys#VETS_ANNI_ENABLED} is on. That key's Javadoc owns what it gates, when it is
 * switched on for vets-tier users, and what a manual opt-in gets without an {@code /unlock} key
 * ({@code vets-anni-enabled-to-be-retired}).</p>
 */
public final class StampFetcher {
    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    private StampFetcher() {}

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
                // window the push interval is 5 min, and today no anni_state
                // push reaches vetsmod at all (outbound-socket-never-authenticated).
                // That made the in-game experience: "I logged in inside the anni
                // zone, the legacy motd showed, but the bar didn't appear until
                // I ran /wv anni."
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
                // and render the rich motd directly, once a pull has returned a
                // snapshot. Today a player with vetsAnniEnabled on whose inbound
                // socket no /unlock key has authenticated (a manual opt-in, say)
                // gets none: the frame names no UUID, temporary-server (at
                // ffd8c17) answers "mc_uuid required", and the pull resolves
                // null, so each of their world joins takes the legacy text
                // (the key is slated for retirement: vets-anni-enabled-to-be-retired).
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
     * <p>Never resolves {@code null}. When the legacy stamp fetch yields
     * no countdown (parse failures, request errors, a past or missing
     * stamp), a future stamp cached by {@link AnniStampPoller} is shown as
     * the countdown; with a zero or past cached stamp it gives the "not
     * announced" line, even when the fetch failed, since
     * {@link #fetchSimple()} does not say which happened. A cold-cache
     * pull that hits its deadline resolves {@code null} and falls back to
     * legacy, as any other failed pull does. Empty
     * lists are never returned — the renderer either produces ≥1 element
     * or returns {@code null} to signal "fall back to legacy."</p>
     */
    public static CompletableFuture<List<MutableComponent>>
            fetchStampAndCreateAnniCommandMessage() {
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
            // command. Today no anni_state push reaches vetsmod at all
            // (outbound-socket-never-authenticated), so the cache stays cold
            // until something fills it, such as the post-connect re-pull
            // returning a snapshot. AnniQueryClient.query() resolves null when the
            // inbound WS is down, when no /unlock key has authenticated that
            // socket (the frame names no UUID; temporary-server at ffd8c17 answers
            // "mc_uuid required"), when the player isn't in vets-anni's DB (e.g.
            // an external user), or when the pull hits its 8 s deadline, among
            // other cases; each drops to legacy.
            return AnniQueryClient.query()
                    .thenCompose(
                            pulled -> {
                                if (pulled != null) {
                                    List<MutableComponent> rendered =
                                            AnniCommandRenderer.render(pulled);
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
     *  as a single-element list. When {@link #fetchSimple()} yields no
     *  countdown, a future {@link AnniStampPoller} stamp (its last-good
     *  cache) is shown as the countdown, and a zero or past one gives the
     *  red "not yet been announced" line. A failed fetch with an empty or
     *  past cache also reads as "not announced": the two differ only when
     *  no fetch has succeeded since the anni was announced. */
    private static CompletableFuture<List<MutableComponent>> legacyFallback() {
        return fetchSimple()
                .thenApply(
                        message -> {
                            if (message != null) {
                                return List.of(message);
                            }
                            // Built once: a stamp that elapses between a "still in the
                            // future?" check and the render would otherwise yield null.
                            MutableComponent cached =
                                    createMessageForStamp(AnniStampPoller.getLatestStamp());
                            if (cached != null) {
                                return List.of(cached);
                            }
                            return List.of(
                                    (MutableComponent)
                                            Component.literal(
                                                            "The next annihilation has not yet been announced")
                                                    .withStyle(ChatFormatting.RED));
                        });
    }

    /**
     * Legacy stamp-only fetcher (the pre-S2 behaviour). Issues a GET to
     * {@code /v1/outbound/stamp}, parses the epoch-seconds, and returns
     * either a long-form or short-form countdown Component. Null when
     * the stamp is past, missing, or the request errors. A missing stamp
     * (a 200 with an empty body) also resets {@link AnniStampPoller}'s
     * cache to {@code 0}, as its own sweep does.
     */
    public static CompletableFuture<MutableComponent> fetchSimple() {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(VetsApi.STAMP)
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

        return HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        response -> {
                            if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                                String body = response.body().trim();
                                if (body.isEmpty()) {
                                    // Empty body = no announced anni, as AnniStampPoller's
                                    // sweep reads it: reset the cache, show nothing.
                                    AnniStampPoller.updateFromExternalFetch(0L);
                                    return null;
                                }
                                try {
                                    long stamp = Long.parseLong(body);
                                    AnniStampPoller.updateFromExternalFetch(stamp);
                                    return createMessageForStamp(stamp);
                                } catch (NumberFormatException e) {
                                    VetsLogger.warn(
                                            "Failed to parse annihilation stamp: {}",
                                            e.getMessage());
                                    return null;
                                }
                            } else {
                                VetsLogger.warn(
                                        "Failed to fetch annihilation stamp (Status: {})",
                                        response.statusCode());
                                return null;
                            }
                        })
                .exceptionally(
                        e -> {
                            VetsLogger.error(
                                    "Error fetching annihilation stamp: {}", e.getMessage());
                            return null;
                        });
    }

    /** True when the S2 snapshot-driven path is allowed to short-circuit the legacy stamp call:
     *  reads {@link VetsConfigKeys#VETS_ANNI_ENABLED}, off by default, so both callers take the
     *  legacy path until a vets-tier auth ack or the user turns it on. */
    private static boolean anniIntegrationActive() {
        return VetsConfig.get(VetsConfigKeys.VETS_ANNI_ENABLED);
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

    /**
     * Creates the message for when annihilation is more than 1 hour away
     *
     * @param hours   Number of hours until annihilation
     * @param minutes Number of remaining minutes until annihilation
     * @return MutableComponent with the formatted message
     */
    private static MutableComponent createLongMessage(int hours, int minutes) {
        // Line 1: &c&lAnnhilation &4&lreturns in &c&l<int> hours <int> mins&4&l!
        MutableComponent line1 =
                Component.literal("Annihilation ")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                        .append(
                                Component.literal("returns in ")
                                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
                        .append(
                                Component.literal(hours + " hours " + minutes + " mins")
                                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                        .append(
                                Component.literal("!")
                                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));

        // Line 2: &7Please remember to show up one hour early or RSVP.
        MutableComponent line2 =
                Component.literal("\nPlease remember to show up one hour early or RSVP.")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false));

        // Line 3: &4For more info, visit &c&o&nthis page&4!
        MutableComponent line3 =
                Component.literal("\n")
                        .append(
                                Component.literal("For more info, visit ")
                                        .withStyle(
                                                Style.EMPTY
                                                        .withColor(ChatFormatting.DARK_RED)
                                                        .withBold(false)))
                        .append(
                                Component.literal("this page")
                                        .withStyle(
                                                Style.EMPTY
                                                        .withColor(ChatFormatting.RED)
                                                        .withItalic(true)
                                                        .withUnderlined(true)
                                                        .withBold(false)
                                                        .withClickEvent(
                                                                new ClickEvent.OpenUrl(
                                                                        URI.create(
                                                                                VetsApi.ANNI
                                                                                        .toString())))))
                        .append(
                                Component.literal("!")
                                        .withStyle(
                                                Style.EMPTY
                                                        .withColor(ChatFormatting.DARK_RED)
                                                        .withBold(false)));

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
        MutableComponent line1 =
                Component.literal("Annihilation is in ")
                        .withStyle(ChatFormatting.RED)
                        .append(Component.literal(minutes + " mins").withStyle(ChatFormatting.RED))
                        .append(Component.literal("!").withStyle(ChatFormatting.DARK_RED));

        // Line 2: &8Click [here](https://wynnvets.org/anni) for more info!
        MutableComponent line2 =
                Component.literal("\nClick ")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withBold(false))
                        .append(
                                Component.literal("here")
                                        .withStyle(
                                                Style.EMPTY
                                                        .withColor(ChatFormatting.DARK_GRAY)
                                                        .withBold(false)
                                                        .withClickEvent(
                                                                new ClickEvent.OpenUrl(
                                                                        URI.create(
                                                                                VetsApi.ANNI
                                                                                        .toString())))))
                        .append(
                                Component.literal(" for more info!")
                                        .withStyle(
                                                Style.EMPTY
                                                        .withColor(ChatFormatting.DARK_GRAY)
                                                        .withBold(false)));

        return line1.append(line2);
    }
}
