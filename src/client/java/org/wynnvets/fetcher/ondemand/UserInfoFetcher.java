package org.wynnvets.fetcher.ondemand;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.api.WynnCraftApi;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.Prepend;
import org.wynnvets.datamodels.Guild;
import org.wynnvets.datamodels.MembershipSnapshot;
import org.wynnvets.datamodels.User;
import org.wynnvets.datamodels.UserUUID;
import org.wynnvets.fetcher.lookup.FailureReason;
import org.wynnvets.fetcher.lookup.LookupResult;
import org.wynnvets.fetcher.lookup.PlayerLookup;
import org.wynnvets.fetcher.lookup.providers.VetsSnapshotProvider;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.util.HttpClients;
import org.wynnvets.util.Json;

/**
 * On-demand fetcher for the {@code /wv check} command.
 *
 * <p>Resolves the name through {@link PlayerLookup}, gathers the Wynncraft
 * player profile and the dazebot membership snapshot (reusing either when
 * the cascade's hit already carries it; the snapshot travels over the WS
 * bridge), and, once there is a profile, cross-checks guild membership
 * against Wynncraft's guild rosters (see {@link #fetchAndRender}). It
 * renders the combined output as a multi-line block in the local chat HUD.
 * Each logical line is dispatched via its own {@link ChatUtils#sendLocalMessage}
 * call so the {@link org.wynnvets.chat.Prepend#DEFAULT} dedup auto-demotes
 * the badge to the compact block-indicator on continuation lines.</p>
 */
public class UserInfoFetcher {
    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    private static final Style LABEL_STYLE = Style.EMPTY.withColor(ChatFormatting.GRAY);
    private static final Style VALUE_STYLE = Style.EMPTY.withColor(ChatFormatting.AQUA);
    private static final Style MUTED_STYLE = Style.EMPTY.withColor(ChatFormatting.DARK_GRAY);
    private static final Style HIDDEN_STYLE = Style.EMPTY.withColor(ChatFormatting.YELLOW);

    /**
     * Lightweight result of a guild-roster cross-check; {@link #analyzeRoster}
     * also produces it for guilds other than Returners.
     */
    private record ReturnersMembership(boolean inGuild, String legacyName, String rank) {
        static ReturnersMembership notInGuild() {
            return new ReturnersMembership(false, null, null);
        }
    }

    private static String toRelativeDateLabel(String dateText) {
        LocalDate date;

        try {
            date = LocalDate.parse(dateText);
        } catch (RuntimeException ignored) {
            return dateText;
        }

        long days = ChronoUnit.DAYS.between(date, LocalDate.now());
        if (days == 0) {
            return "today";
        }
        if (days == 1) {
            return "yesterday";
        }
        if (days > 1) {
            return days + " days ago";
        }

        long daysUntil = Math.abs(days);
        if (daysUntil == 1) {
            return "tomorrow";
        }
        return "in " + daysUntil + " days";
    }

    /**
     * Coarse-grained relative date label: years > months > days, picking the
     * coarsest unit that yields a value ≥ 1. Falls back to {@link
     * #toRelativeDateLabel} for sub-month spans. Used for "first seen" where
     * "4342 days ago" is unreadable but "11 years ago" is at-a-glance useful.
     */
    private static String toCoarseRelativeDateLabel(String dateText) {
        LocalDate date;
        try {
            date = LocalDate.parse(dateText);
        } catch (RuntimeException ignored) {
            return dateText;
        }
        LocalDate now = LocalDate.now();
        long years = ChronoUnit.YEARS.between(date, now);
        if (years >= 1) {
            return years == 1 ? "1 year ago" : years + " years ago";
        }
        long months = ChronoUnit.MONTHS.between(date, now);
        if (months >= 1) {
            return months == 1 ? "1 month ago" : months + " months ago";
        }
        return toRelativeDateLabel(dateText);
    }

    /**
     * Coarse "age" formatter for "{@code N ago}" presentations of a duration
     * in seconds. Picks the coarsest unit that yields ≥ 1: years > months >
     * days > hours > minutes > seconds. Used for best-guess timestamps where
     * we have unix seconds (not an ISO date) — e.g. dazebot's
     * {@code last_seen_observed.ts}.
     */
    private static String humanizeAge(long secs) {
        if (secs < 0) secs = 0;
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m";
        if (secs < 86400) return (secs / 3600) + "h";
        if (secs < 86400L * 30) return (secs / 86400) + "d";
        if (secs < 86400L * 365) {
            long m = secs / (86400L * 30);
            return m + (m == 1 ? " month" : " months");
        }
        long y = secs / (86400L * 365);
        return y + (y == 1 ? " year" : " years");
    }

    /**
     * Strips dashes and lowercases, so a dashed and a dashless UUID compare equal. Twin of
     * {@link org.wynnvets.distribute.utils.NameResolver#normalizeUuid
     * NameResolver#normalizeUuid} &mdash; identical behaviour down to the
     * null-becomes-empty-string contract, and that one has always pointed
     * here (as a live link since 5fb6f26).
     * The pairing now reads both ways, so whichever copy a future consolidation opens
     * first names the other.
     */
    private static String normalizeUuidText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isUuidLike(String value) {
        return value != null && value.length() == 32 && value.matches("[0-9a-f]{32}");
    }

    private static boolean containsUuid(JsonElement element, String normalizedUuid) {
        if (element == null || element.isJsonNull()) {
            return false;
        }

        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String normalized = normalizeUuidText(element.getAsString());
            return isUuidLike(normalized) && normalized.equals(normalizedUuid);
        }

        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                if (containsUuid(child, normalizedUuid)) {
                    return true;
                }
            }
            return false;
        }

        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String normalizedKey = normalizeUuidText(entry.getKey());
                if (isUuidLike(normalizedKey) && normalizedKey.equals(normalizedUuid)) {
                    return true;
                }

                if (containsUuid(entry.getValue(), normalizedUuid)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Fetches the Returners roster from the Wynncraft API and reports
     * membership plus a best-effort {@code legacyName} and rank. Membership
     * is {@link #containsUuid}'s answer: a shape-agnostic walk of the whole
     * payload, which is what defends against Wynncraft API shape changes.
     * {@link #analyzeRoster}'s structured {@code members.<rank>.<name>}
     * walk only supplies the rank and {@code legacyName}. Today a non-200,
     * failed or unparseable fetch also yields
     * {@link ReturnersMembership#notInGuild()}, indistinguishable from
     * absence (see
     * {@code wv-check-guild-line-conflates-roster-failure-with-absence}).
     */
    private static CompletableFuture<ReturnersMembership> returnersMembership(String uuid) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(WynnCraftApi.guildInfo("Returners"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

        String normalizedUuid = normalizeUuidText(uuid);

        return HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        response -> {
                            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                                return ReturnersMembership.notInGuild();
                            }

                            JsonElement payload =
                                    Json.GSON.fromJson(response.body(), JsonElement.class);
                            return analyzeRoster(payload, normalizedUuid);
                        })
                .exceptionally(e -> ReturnersMembership.notInGuild());
    }

    // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
    // Reason: /v3/player/{uuid}.guild ~12h stale for offline players.
    // Remove: when Wynncraft invalidates the player endpoint on
    //   officer-write events, or exposes a webhook, or an offline-player
    //   spot-check shows <10min staleness.
    // Imports used only by this workaround: java.util.Optional and
    // org.wynnvets.guild.GuildStateManager.
    /**
     * Fetches {@code guildName}'s roster and returns the rank it lists for
     * {@code normalizedUuid}. Empty on a non-200, a timeout, a malformed
     * response or an absent UUID. It is also empty when the UUID is present
     * but no rank could be read, so an empty result does not by itself mean
     * "not a member"; today {@link #renderGuildLine} reads it as one (see
     * {@code wv-check-guild-line-conflates-roster-failure-with-absence}).
     * Mirrors {@link #returnersMembership} and reuses {@link #analyzeRoster}.
     */
    private static CompletableFuture<Optional<String>> confirmArbitraryGuildRank(
            String guildName, String normalizedUuid) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(WynnCraftApi.guildInfo(guildName))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
        return HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        response -> {
                            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                                return Optional.<String>empty();
                            }
                            JsonElement payload =
                                    Json.GSON.fromJson(response.body(), JsonElement.class);
                            ReturnersMembership hit = analyzeRoster(payload, normalizedUuid);
                            return hit.inGuild()
                                    ? Optional.ofNullable(hit.rank())
                                    : Optional.<String>empty();
                        })
                .exceptionally(e -> Optional.<String>empty());
    }

    // <<< PATCH END: WYNN-STALE-WORKAROUND

    private static ReturnersMembership analyzeRoster(JsonElement payload, String normalizedUuid) {
        if (!containsUuid(payload, normalizedUuid)) {
            return ReturnersMembership.notInGuild();
        }
        // Try to extract rank + legacyName from the V3-typical shape:
        //   { "members": { "<rank>": { "<username>": { "uuid": ..., "legacyName": ... } } } }
        String rank = null;
        String legacyName = null;
        if (payload != null && payload.isJsonObject()) {
            JsonElement membersEl = payload.getAsJsonObject().get("members");
            if (membersEl != null && membersEl.isJsonObject()) {
                outer:
                for (Map.Entry<String, JsonElement> rankBucket :
                        membersEl.getAsJsonObject().entrySet()) {
                    String rankKey = rankBucket.getKey();
                    if ("total".equals(rankKey)) continue;
                    JsonElement bucketEl = rankBucket.getValue();
                    if (bucketEl == null || !bucketEl.isJsonObject()) continue;
                    for (Map.Entry<String, JsonElement> memberEntry :
                            bucketEl.getAsJsonObject().entrySet()) {
                        JsonElement memberEl = memberEntry.getValue();
                        if (memberEl == null || !memberEl.isJsonObject()) continue;
                        JsonObject member = memberEl.getAsJsonObject();
                        JsonElement uuidEl = member.get("uuid");
                        if (uuidEl == null || !uuidEl.isJsonPrimitive()) continue;
                        String memberUuid = normalizeUuidText(uuidEl.getAsString());
                        if (memberUuid.equals(normalizedUuid)) {
                            rank = capitalizeRank(rankKey);
                            if (member.has("legacyName")
                                    && !member.get("legacyName").isJsonNull()) {
                                legacyName = member.get("legacyName").getAsString();
                            }
                            break outer;
                        }
                    }
                }
            }
        }
        return new ReturnersMembership(true, legacyName, rank);
    }

    private static String capitalizeRank(String rank) {
        if (rank == null || rank.isEmpty()) return rank;
        return Character.toUpperCase(rank.charAt(0)) + rank.substring(1);
    }

    private static MembershipSnapshot snapshotUnavailable(String detail) {
        // GSON-deserialise from a synthetic JSON object so optional fields
        // default cleanly. The renderer keys off `discord == null` to decide
        // whether the snapshot succeeded.
        JsonObject stub = new JsonObject();
        stub.addProperty("target_uuid", "");
        stub.addProperty("target_username", "");
        stub.addProperty("stage_2_active", false);
        stub.addProperty("blocklisted", false);
        stub.addProperty("blocklist_reason", detail == null ? "snapshot unavailable" : detail);
        stub.addProperty("in_returners_guild", false);
        // discord intentionally omitted -> null
        return Json.GSON.fromJson(stub, MembershipSnapshot.class);
    }

    /**
     * Orchestrates the full {@code /wv check} readout for {@code playerName}:
     * resolves name → UUID via {@link PlayerLookup} (Wynncraft, temporary-server,
     * playerdb, ashcon, Mojang), then gathers whatever the cascade's hit did
     * not already carry (Wynn player profile, dazebot snapshot), cross-checks
     * guild rosters once there is a profile, and renders the combined block
     * line-by-line.
     *
     * <p>Failures are surfaced as a single error line; partial results
     * (e.g. Wynn fetch succeeds but the snapshot doesn't) still render the
     * portions that resolved.</p>
     */
    public static void checkUser(String playerName) {
        PlayerLookup.resolve(playerName)
                .thenAccept(
                        lookup -> {
                            if (lookup.isSuccess()) {
                                fetchAndRender(playerName, lookup);
                                return;
                            }
                            if (lookup.failureReason() == FailureReason.PLAYER_NOT_FOUND) {
                                renderError(
                                        "No player named '"
                                                + playerName
                                                + "' on any lookup source.");
                                return;
                            }
                            renderError(
                                    "Could not look up '"
                                            + playerName
                                            + "' — all UUID sources unavailable (Mojang rate-limited / Wynncraft "
                                            + "down). Try again shortly.");
                        });
    }

    private static void fetchAndRender(String requestedName, LookupResult lookup) {
        UUID uuid = lookup.uuid();
        String canonical = lookup.canonicalName() != null ? lookup.canonicalName() : requestedName;
        UserUUID synthUuid = makeUserUUID(uuid, canonical);

        // Reuse the cascade's attached payload when present; fetch only what's
        // missing. Only a Vets-provider hit carries a snapshot (the Wynncraft
        // provider, which PlayerLookup tries first, carries none), so a missing
        // one is requested afresh through VetsSnapshotProvider.requestSnapshot, a
        // new check_membership frame. A null result becomes the
        // snapshotUnavailable sentinel, which the renderer shows as "(snapshot
        // unavailable)"; today the Blocklist line doubles that phrase (see
        // wv-check-blocklist-line-doubles-snapshot-unavailable).
        CompletableFuture<User> wynnFuture =
                lookup.wynnProfile() != null
                        ? CompletableFuture.completedFuture(lookup.wynnProfile())
                        : fetchWynnProfileByUuid(uuid);

        CompletableFuture<MembershipSnapshot> snapFuture =
                lookup.vetsSnapshot() != null
                        ? CompletableFuture.completedFuture(lookup.vetsSnapshot())
                        : VetsSnapshotProvider.requestSnapshot(canonical)
                                .thenApply(
                                        s ->
                                                s != null
                                                        ? s
                                                        : snapshotUnavailable(
                                                                "snapshot unavailable"));

        wynnFuture.thenCompose(
                user -> {
                    // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
                    // Reason: /v3/player/{uuid}.guild ~12h stale for offline players.
                    // Remove: when Wynncraft invalidates the player endpoint on
                    //   officer-write events, or exposes a webhook, or an offline-player
                    //   spot-check shows <10min staleness.
                    // Original gated on `user.isInVets()` (payload claims Returners).
                    // Drop the isInVets() gate so we crosscheck the Returners roster
                    // whenever there is a Wynncraft profile -- needed to catch the
                    // rejoin case where the payload transitions through a
                    // non-Returners guild name mid-invalidation. Cost: one extra
                    // /v3/guild/Returners fetch per such /wv check. Guild bucket is
                    // 50 req/s and /wv check cadence is low.
                    CompletableFuture<ReturnersMembership> returnersFuture =
                            (user != null)
                                    ? returnersMembership(synthUuid.id)
                                    : CompletableFuture.completedFuture(
                                            ReturnersMembership.notInGuild());
                    // <<< PATCH END: WYNN-STALE-WORKAROUND
                    // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
                    // Reason: /v3/player/{uuid}.guild ~12h stale for offline players.
                    // Remove: when Wynncraft invalidates the player endpoint on
                    //   officer-write events, or exposes a webhook, or an offline-player
                    //   spot-check shows <10min staleness.
                    // Fire the roster crosscheck alongside returners + snapshot only when
                    // the payload names a non-Returners guild (Returners is already
                    // crosschecked via returnersFuture above).
                    Guild payloadGuild = user != null ? user.getGuild() : null;
                    CompletableFuture<Optional<String>> confirmedNonReturnersRankFuture =
                            (payloadGuild != null
                                            && payloadGuild.getName() != null
                                            && !"Returners".equals(payloadGuild.getName()))
                                    ? confirmArbitraryGuildRank(
                                            payloadGuild.getName(), synthUuid.id)
                                    : CompletableFuture.completedFuture(Optional.<String>empty());
                    // <<< PATCH END: WYNN-STALE-WORKAROUND
                    return returnersFuture.thenCombine(
                            snapFuture,
                            (returners, snapshot) -> {
                                // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
                                // Reason: /v3/player/{uuid}.guild ~12h stale for offline players.
                                // Remove: when Wynncraft invalidates the player endpoint on
                                //   officer-write events, or exposes a webhook, or an
                                // offline-player
                                //   spot-check shows <10min staleness.
                                // Join the roster crosscheck before render. The helper's 5s
                                // request timeout bounds the wait for response headers (in JDK
                                // 21's implementation, not the body read), and it returns
                                // Optional.empty() on failure. Today, while the crosscheck is
                                // still pending, the join blocks whichever thread completed the
                                // later of returnersFuture and snapFuture, such as a
                                // CompletableFuture default-pool thread or the thread that
                                // delivers inbound WebSocket frames (see
                                // user-info-fetcher-join-blocks-completing-thread).
                                Optional<String> confirmedNonReturnersRank =
                                        confirmedNonReturnersRankFuture.join();
                                // <<< PATCH END: WYNN-STALE-WORKAROUND
                                Minecraft.getInstance()
                                        .execute(
                                                () ->
                                                        renderCheck(
                                                                requestedName,
                                                                synthUuid,
                                                                user,
                                                                returners,
                                                                snapshot,
                                                                confirmedNonReturnersRank));
                                return null;
                            });
                });
    }

    private static CompletableFuture<User> fetchWynnProfileByUuid(UUID uuid) {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(WynnCraftApi.playerInfo(uuid))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
        return HTTP_CLIENT
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        resp ->
                                resp.statusCode() == HttpURLConnection.HTTP_OK
                                        ? Json.GSON.fromJson(resp.body(), User.class)
                                        : null)
                .exceptionally(e -> null);
    }

    private static UserUUID makeUserUUID(UUID uuid, String name) {
        UserUUID u = new UserUUID();
        u.id = uuid.toString().replace("-", "");
        u.name = name;
        return u;
    }

    // ── Rendering ──────────────────────────────────────────────────────

    private static void renderError(String message) {
        Minecraft.getInstance()
                .execute(
                        () ->
                                ChatUtils.sendLocalMessage(
                                        Component.literal(message).withStyle(ChatFormatting.RED)));
    }

    private static void renderCheck(
            String requestedName,
            UserUUID uuid,
            User user,
            ReturnersMembership returners,
            MembershipSnapshot snapshot,
            // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
            // Reason: /v3/player/{uuid}.guild ~12h stale for offline players.
            // Remove: when Wynncraft invalidates the player endpoint on
            //   officer-write events, or exposes a webhook, or an offline-player
            //   spot-check shows <10min staleness.
            Optional<String> confirmedNonReturnersRank
            // <<< PATCH END: WYNN-STALE-WORKAROUND
            ) {
        // Mark the start of a new render block so the first line re-extends
        // the badge — this is what gives staff a visual divider between two
        // back-to-back /wv check responses (and between this snapshot block
        // and the preceding caution-history block, which arrive on separate
        // WS frames).
        Prepend.resetDedup();

        String displayName =
                (user != null && user.getUsername() != null) ? user.getUsername() : requestedName;

        // Tab list is our freshest signal — local Minecraft state, no HTTP
        // round-trip, no CDN cache. It only covers the local player's guild, and
        // only as many online members as the tab list's guild column holds (see
        // TabListGuildParser), so a miss is normal — for cross-guild targets and
        // in a busy guild alike; a hit is authoritative. Cheap O(N) scan over
        // <100 entries, always run.
        String tabListServer = OnlineMemberService.getCurrentServer(displayName);

        // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
        // Reason: /v3/player/{uuid}.guild ~12h stale for offline players.
        // Remove: when Wynncraft invalidates the player endpoint on
        //   officer-write events, or exposes a webhook, or an offline-player
        //   spot-check shows <10min staleness.
        // Same-tick evidence the target is in Returners: they appear in the
        // local player's guild tab bucket AND the local player is in
        // Returners. Mirrors the guard OnlineMemberService.gatherOnlinePlayers
        // uses before forwarding the tab list (avoids treating a non-Returners
        // local guild's tab hit as Returners confirmation).
        boolean tabListConfirmsReturners = tabListServer != null && GuildStateManager.isReturners();
        // <<< PATCH END: WYNN-STALE-WORKAROUND

        // Self-check: the local player is treated as online, since they are
        // the one running /wv check (normally from Wynncraft; the command does
        // not check which server the client is on). Skips the whole
        // signal-aggregation dance below and avoids the "Wynn's cached
        // online=false for the local player" trap.
        String localName =
                (Minecraft.getInstance().player != null)
                        ? Minecraft.getInstance().player.getName().getString()
                        : null;
        boolean isSelf = localName != null && localName.equalsIgnoreCase(displayName);

        boolean currentlyOnline = isCurrentlyOnline(user, snapshot, tabListServer, isSelf);

        // Item 0: username + UUID
        ChatUtils.sendLocalMessage(
                Component.literal(displayName)
                        .withStyle(VALUE_STYLE)
                        .append(Component.literal("  (" + uuid.id + ")").setStyle(MUTED_STYLE)));

        // Item 0a: legacy name (only if Returners roster gave us one)
        if (returners != null
                && returners.legacyName() != null
                && !returners.legacyName().equalsIgnoreCase(displayName)) {
            ChatUtils.sendLocalMessage(
                    label("Legacy name: ")
                            .append(
                                    Component.literal(returners.legacyName())
                                            .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))));
        }

        if (user == null) {
            // The profile items (first seen, last seen, playtime, online) collapse
            // into this one line when there's no Wynn profile; the guild and
            // veteran lines below still render, each with its own placeholder.
            ChatUtils.sendLocalMessage(
                    label("Wynncraft profile: ")
                            .append(
                                    Component.literal("(no Wynncraft data)")
                                            .setStyle(HIDDEN_STYLE)));
        } else {
            renderWynnLines(user, snapshot, tabListServer, isSelf);
        }

        renderGuildLine(
                user,
                returners,
                snapshot,
                // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
                confirmedNonReturnersRank,
                tabListConfirmsReturners
                // <<< PATCH END: WYNN-STALE-WORKAROUND
                );
        renderVeteranLine(user);
        renderBlocklistLine(snapshot);
        renderDiscordLinkLine(snapshot, currentlyOnline);
        renderStage2Line(snapshot);
        renderCultLine(snapshot);
    }

    /**
     * Aggregates "is this player on Wynncraft right now?" across four
     * signals, in order of authoritativeness: true for self (the local
     * player is treated as online), then local tab list (live), then Wynn's
     * {@code online} field (2-min CDN cache, can lag), then a recent
     * ({@literal <}10 min) {@code last_seen_observed.ts}: dazebot's
     * best-guess last-online time, which several dazebot paths keep
     * current, its {@code server_watcher} among them. Today this fourth
     * signal cannot fire, because temporary-server's ack does not carry
     * {@code last_seen_observed} (see
     * {@code check-membership-ack-drops-last-seen-observed}). Used to
     * qualify the {@code (hiatus)} discord-line annotation: a positive
     * signal is meant to tell staff the role state may be stale. Today
     * being online is not evidence of that, since dazebot's HIATUS means a
     * guildless ex-member (see
     * {@code wv-check-hiatus-stale-hint-keys-off-online}).
     */
    private static boolean isCurrentlyOnline(
            User user, MembershipSnapshot snapshot, String tabListServer, boolean isSelf) {
        if (isSelf) {
            return true;
        }
        if (tabListServer != null) {
            return true;
        }
        if (user != null && Boolean.TRUE.equals(user.getOnline())) {
            return true;
        }
        MembershipSnapshot.LastSeenObserved obs =
                snapshot != null ? snapshot.getLastSeenObserved() : null;
        if (obs != null && obs.getTs() > 0) {
            long ageSec = Instant.now().getEpochSecond() - obs.getTs();
            return ageSec >= 0 && ageSec < 600;
        }
        return false;
    }

    private static void renderWynnLines(
            User user, MembershipSnapshot snapshot, String tabListServer, boolean isSelf) {
        // Item 1: first seen. Show coarse "N years ago" as the headline value
        // (raw days are unreadable for old accounts) with the ISO date as the
        // muted parenthetical.
        String firstJoin = user.getFirstJoinDate();
        if (firstJoin == null) {
            ChatUtils.sendLocalMessage(label("First seen on Wynncraft: ").append(hidden()));
        } else {
            ChatUtils.sendLocalMessage(
                    label("First seen on Wynncraft: ")
                            .append(
                                    Component.literal(toCoarseRelativeDateLabel(firstJoin))
                                            .setStyle(VALUE_STYLE))
                            .append(
                                    Component.literal("  (" + firstJoin + ")")
                                            .setStyle(MUTED_STYLE)));
        }

        // Item 2: last seen. When the Wynn API hides lastJoin (privacy on), fall
        // back to self, then the tab list, then dazebot's last_seen_observed
        // best guess (several dazebot paths keep it current); `(hidden)` only
        // when we have nothing at all. Today the dazebot fallback never fires
        // because temporary-server drops last_seen_observed (see
        // check-membership-ack-drops-last-seen-observed).
        String lastJoin = user.getLastJoinDate();
        if (lastJoin != null) {
            ChatUtils.sendLocalMessage(
                    label("Last seen on Wynncraft: ")
                            .append(
                                    Component.literal(toRelativeDateLabel(lastJoin))
                                            .setStyle(VALUE_STYLE))
                            .append(
                                    Component.literal("  (" + lastJoin + ")")
                                            .setStyle(MUTED_STYLE)));
        } else {
            renderLastSeenFallback(snapshot, tabListServer, isSelf);
        }

        // Item 3: playtime
        Float playtime = user.getPlaytime();
        if (playtime == null) {
            ChatUtils.sendLocalMessage(label("Playtime: ").append(hidden()));
        } else {
            ChatUtils.sendLocalMessage(
                    label("Playtime: ")
                            .append(
                                    Component.literal(formatHours(playtime) + " hours")
                                            .setStyle(VALUE_STYLE)));
        }

        // Item 3b: currently online. Surfaced between playtime and guild because
        // it's the most actionable signal for a staff vetting decision. The
        // Discord line's `(hiatus)` qualifier asks a related question through
        // isCurrentlyOnline, which weighs the signals differently.
        renderOnlineLine(user, snapshot, tabListServer, isSelf);
    }

    private static void renderLastSeenFallback(
            MembershipSnapshot snapshot, String tabListServer, boolean isSelf) {
        MutableComponent line = label("Last seen on Wynncraft: ");
        if (isSelf) {
            line.append(Component.literal("now").setStyle(HIDDEN_STYLE))
                    .append(Component.literal("  (self)").setStyle(MUTED_STYLE));
            ChatUtils.sendLocalMessage(line);
            return;
        }
        if (tabListServer != null) {
            // Tab list is "right now" by construction.
            line.append(Component.literal("now").setStyle(HIDDEN_STYLE))
                    .append(
                            Component.literal("  (tab-list: " + tabListServer + ")")
                                    .setStyle(MUTED_STYLE));
            ChatUtils.sendLocalMessage(line);
            return;
        }
        MembershipSnapshot.LastSeenObserved obs =
                snapshot != null ? snapshot.getLastSeenObserved() : null;
        if (obs != null && obs.getTs() > 0) {
            long ageSec = Instant.now().getEpochSecond() - obs.getTs();
            line.append(Component.literal(humanizeAge(ageSec) + " ago").setStyle(HIDDEN_STYLE))
                    .append(
                            Component.literal("  (best guess — server-change observed")
                                    .setStyle(MUTED_STYLE));
            if (obs.getServerAtObservation() != null && !obs.getServerAtObservation().isEmpty()) {
                line.append(
                        Component.literal(" on " + obs.getServerAtObservation())
                                .setStyle(MUTED_STYLE));
            }
            line.append(Component.literal(")").setStyle(MUTED_STYLE));
        } else {
            line.append(hidden());
        }
        ChatUtils.sendLocalMessage(line);
    }

    private static void renderOnlineLine(
            User user, MembershipSnapshot snapshot, String tabListServer, boolean isSelf) {
        MutableComponent line = label("Currently online: ");

        // Self is treated as online: the local player is the one running /wv
        // check, normally from Wynncraft (the command does not check which
        // server the client is on).
        if (isSelf) {
            line.append(
                    Component.literal("yes").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
            if (tabListServer != null) {
                line.append(Component.literal("  (" + tabListServer + ")").setStyle(MUTED_STYLE));
            } else {
                line.append(Component.literal("  (self)").setStyle(MUTED_STYLE));
            }
            ChatUtils.sendLocalMessage(line);
            return;
        }

        // Tab list beats Wynn's /v3/player.online (the latter is CDN-cached
        // for up to 2 min and routinely says "false" for a player who joined
        // seconds ago — matches the /wv list behaviour staff are used to).
        if (tabListServer != null) {
            line.append(
                            Component.literal("yes")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)))
                    .append(
                            Component.literal("  (" + tabListServer + ", tab-list)")
                                    .setStyle(MUTED_STYLE));
            ChatUtils.sendLocalMessage(line);
            return;
        }

        Boolean wynnOnline = user != null ? user.getOnline() : null;
        String wynnServer = user != null ? user.getServer() : null;

        if (wynnOnline != null) {
            if (wynnOnline) {
                line.append(
                        Component.literal("yes")
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
                if (wynnServer != null && !wynnServer.isEmpty()) {
                    line.append(Component.literal("  (" + wynnServer + ")").setStyle(MUTED_STYLE));
                }
            } else {
                line.append(Component.literal("no").setStyle(LABEL_STYLE));
            }
            ChatUtils.sendLocalMessage(line);
            return;
        }

        // Wynn hidden — try the last_seen_observed best guess (today never
        // present; see check-membership-ack-drops-last-seen-observed).
        MembershipSnapshot.LastSeenObserved obs =
                snapshot != null ? snapshot.getLastSeenObserved() : null;
        if (obs != null && obs.getTs() > 0) {
            long ageSec = Instant.now().getEpochSecond() - obs.getTs();
            if (ageSec >= 0 && ageSec < 600) {
                line.append(
                        Component.literal("~" + humanizeAge(ageSec) + " ago")
                                .setStyle(HIDDEN_STYLE));
                if (obs.getServerAtObservation() != null
                        && !obs.getServerAtObservation().isEmpty()) {
                    line.append(
                            Component.literal(
                                            "  (" + obs.getServerAtObservation() + ", best guess)")
                                    .setStyle(MUTED_STYLE));
                }
                ChatUtils.sendLocalMessage(line);
                return;
            }
        }
        line.append(Component.literal("(unknown)").setStyle(HIDDEN_STYLE));
        ChatUtils.sendLocalMessage(line);
    }

    private static String formatHours(float hours) {
        // Show one decimal place; users care about the order of magnitude.
        return String.format(Locale.ROOT, "%,.1f", hours);
    }

    private static void renderGuildLine(
            User user,
            ReturnersMembership returners,
            MembershipSnapshot snapshot,
            // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
            // Reason: /v3/player/{uuid}.guild ~12h stale for offline players.
            // Remove: when Wynncraft invalidates the player endpoint on
            //   officer-write events, or exposes a webhook, or an offline-player
            //   spot-check shows <10min staleness.
            Optional<String> confirmedNonReturnersRank,
            boolean tabListConfirmsReturners
            // <<< PATCH END: WYNN-STALE-WORKAROUND
            ) {
        MutableComponent line = label("Guild: ");

        if (user == null) {
            line.append(Component.literal("(no Wynncraft profile)").setStyle(HIDDEN_STYLE));
            ChatUtils.sendLocalMessage(line);
            return;
        }

        Guild guild = user.getGuild();
        boolean inVets = user.isInVets() && (returners == null || returners.inGuild());
        // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
        // Reason: /v3/player/{uuid}.guild ~12h stale for offline players.
        // Remove: when Wynncraft invalidates the player endpoint on
        //   officer-write events, or exposes a webhook, or an offline-player
        //   spot-check shows <10min staleness.
        // Any single fresh signal is enough to render Returners: the roster
        // (invalidated on officer-write) OR the local tab list (Minecraft
        // client state, same-tick). Catches the rejoin case where the payload
        // briefly names a non-Returners guild but the target is provably back
        // in Returners.
        boolean rosterConfirmsReturners = returners != null && returners.inGuild();
        boolean confirmedReturners = rosterConfirmsReturners || tabListConfirmsReturners;
        // <<< PATCH END: WYNN-STALE-WORKAROUND

        if (inVets
                // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
                || confirmedReturners
        // <<< PATCH END: WYNN-STALE-WORKAROUND
        ) {
            line.append(Component.literal("Returners").setStyle(VALUE_STYLE));
            String rank = returners != null ? returners.rank() : null;
            // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
            // Reason: /v3/player/{uuid}.guild ~12h stale for offline players.
            // Remove: when Wynncraft invalidates the player endpoint on
            //   officer-write events, or exposes a webhook, or an offline-player
            //   spot-check shows <10min staleness.
            // Suppressed original fallback: `if (rank == null && guild != null) rank =
            // guild.getRank();`
            // — guild.getRank() from the stale payload can silently re-introduce
            // staleness (payload can claim RECRUITER for a former member who was
            // already kicked). Roster-only until upstream fixes invalidation.
            // <<< PATCH END: WYNN-STALE-WORKAROUND
            if (rank != null && !rank.isEmpty()) {
                line.append(Component.literal("  —  ").setStyle(LABEL_STYLE))
                        .append(
                                Component.literal(rank)
                                        .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)));
            }
            ChatUtils.sendLocalMessage(line);
            return;
        }

        // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
        // Reason: /v3/player/{uuid}.guild ~12h stale for offline players.
        // Remove: when Wynncraft invalidates the player endpoint on
        //   officer-write events, or exposes a webhook, or an offline-player
        //   spot-check shows <10min staleness.
        // Cross-signal mismatch: either (a) Wynncraft's payload says Returners
        // but the roster disagrees (falls out of the inVets branch above with
        // guild.name == "Returners"), or (b) Wynncraft names some non-Returners
        // guild but that guild's own roster doesn't list the UUID. Both mean
        // the payload is behind — render "guildless" + a hint rather than the
        // misleading stale value.
        boolean returnersMismatch = user.isInVets() && returners != null && !returners.inGuild();
        boolean otherGuildMismatch =
                guild != null
                        && guild.getName() != null
                        && !"Returners".equals(guild.getName())
                        && confirmedNonReturnersRank.isEmpty();
        if (returnersMismatch || otherGuildMismatch) {
            line.append(Component.literal("guildless").setStyle(HIDDEN_STYLE))
                    .append(
                            Component.literal("  —  Wynncraft profile still catching up")
                                    .setStyle(HIDDEN_STYLE));
            ChatUtils.sendLocalMessage(line);
            return;
        }
        // <<< PATCH END: WYNN-STALE-WORKAROUND

        if (guild != null && guild.getName() != null) {
            // Player is in a non-Returners guild.
            line.append(Component.literal(guild.getName()).setStyle(VALUE_STYLE));
            if (guild.getPrefix() != null && !guild.getPrefix().isEmpty()) {
                line.append(Component.literal("  [").setStyle(LABEL_STYLE))
                        .append(
                                Component.literal(guild.getPrefix())
                                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)))
                        .append(Component.literal("]").setStyle(LABEL_STYLE));
            }
            // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
            // Reason: /v3/player/{uuid}.guild ~12h stale for offline players.
            // Remove: when Wynncraft invalidates the player endpoint on
            //   officer-write events, or exposes a webhook, or an offline-player
            //   spot-check shows <10min staleness.
            // Original block used guild.getRank() directly; now prefer the
            // confirming roster's rank when present (the payload rank can lag
            // even for a still-in-guild member after a promotion/demotion).
            // Today the payload fallback is effectively unreachable: for any
            // guild not named Returners, the mismatch block above has already
            // returned when that rank is absent (see
            // wv-check-guild-line-conflates-roster-failure-with-absence).
            String rankText = confirmedNonReturnersRank.orElse(guild.getRank());
            if (rankText != null && !rankText.isEmpty()) {
                line.append(Component.literal("  —  ").setStyle(LABEL_STYLE))
                        .append(
                                Component.literal(rankText)
                                        .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)));
            }
            // <<< PATCH END: WYNN-STALE-WORKAROUND
            ChatUtils.sendLocalMessage(line);
            return;
        }

        // Player is guildless according to Wynn — annotate with dazebot's
        // membership state so we surface "guildless but on the vets roster".
        MembershipSnapshot.Discord disc = snapshot != null ? snapshot.getDiscord() : null;
        String tier = disc != null ? disc.getTier() : null;
        boolean hiatus = disc != null && disc.isHiatus();
        if ("member".equals(tier)) {
            line.append(Component.literal("guildless").setStyle(HIDDEN_STYLE))
                    .append(
                            Component.literal("  —  appears in vets roster (cached)")
                                    .setStyle(HIDDEN_STYLE));
        } else if ("waitlist".equals(tier)) {
            line.append(Component.literal("guildless").setStyle(VALUE_STYLE))
                    .append(Component.literal("  —  on waitlist").setStyle(VALUE_STYLE));
        } else if ("honourary".equals(tier)) {
            line.append(Component.literal("guildless").setStyle(VALUE_STYLE))
                    .append(Component.literal("  —  honourary").setStyle(VALUE_STYLE));
        } else if (hiatus) {
            line.append(
                            Component.literal("guildless")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)))
                    .append(
                            Component.literal("  —  hiatus")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)));
        } else {
            line.append(Component.literal("guildless").setStyle(LABEL_STYLE));
        }
        ChatUtils.sendLocalMessage(line);
    }

    private static void renderVeteranLine(User user) {
        // Only an explicit `veteran: true` counts. What Wynncraft sends for
        // non-vets isn't pinned here — absent, null and false are all possible
        // shapes (Wynntils' own parser allows for null) — and absent and null
        // both arrive as null. Treat anything but TRUE as "no"; the
        // privacy-hidden interpretation is wrong here.
        MutableComponent line = label("Veteran tag: ");
        if (user == null) {
            line.append(Component.literal("(no Wynncraft data)").setStyle(HIDDEN_STYLE));
        } else if (Boolean.TRUE.equals(user.getVeteran())) {
            line.append(
                    Component.literal("yes").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
        } else {
            line.append(Component.literal("no").setStyle(LABEL_STYLE));
        }
        ChatUtils.sendLocalMessage(line);
    }

    private static void renderBlocklistLine(MembershipSnapshot snapshot) {
        MutableComponent line = label("Blocklist: ");
        if (snapshot == null || snapshot.getDiscord() == null) {
            line.append(Component.literal("(snapshot unavailable").setStyle(MUTED_STYLE));
            String detail = snapshot != null ? snapshot.getBlocklistReason() : null;
            if (detail != null && !detail.isEmpty()) {
                line.append(Component.literal(": " + detail).setStyle(MUTED_STYLE));
            }
            line.append(Component.literal(")").setStyle(MUTED_STYLE));
        } else if (snapshot.isBlocklisted()) {
            line.append(
                    Component.literal("BLOCKED")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            String reason = snapshot.getBlocklistReason();
            if (reason != null && !reason.isEmpty()) {
                line.append(Component.literal("  —  \"").setStyle(LABEL_STYLE))
                        .append(
                                Component.literal(reason)
                                        .setStyle(
                                                Style.EMPTY
                                                        .withColor(ChatFormatting.RED)
                                                        .withItalic(true)))
                        .append(Component.literal("\"").setStyle(LABEL_STYLE));
            }
        } else {
            line.append(
                    Component.literal("clean")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
        }
        ChatUtils.sendLocalMessage(line);
    }

    private static void renderDiscordLinkLine(
            MembershipSnapshot snapshot, boolean currentlyOnline) {
        MutableComponent line = label("Discord link: ");
        MembershipSnapshot.Discord disc = snapshot != null ? snapshot.getDiscord() : null;
        if (disc == null) {
            line.append(Component.literal("(snapshot unavailable)").setStyle(MUTED_STYLE));
            ChatUtils.sendLocalMessage(line);
            return;
        }
        if (!disc.isLinked()) {
            line.append(Component.literal("not linked").setStyle(LABEL_STYLE));
            ChatUtils.sendLocalMessage(line);
            return;
        }

        String display = disc.getDiscDisplay();
        String discUuid = disc.getDiscUuid();
        if (display == null || display.isEmpty()) {
            display = discUuid != null ? discUuid : "<unknown>";
        }
        String tier = disc.getTier();

        Style tierStyle;
        String tierLabel;
        if (!disc.isInGuild()) {
            // Linked but the Discord user has left vetscord.
            tierStyle = Style.EMPTY.withColor(ChatFormatting.YELLOW);
            tierLabel = "(left server)";
        } else {
            switch (tier == null ? "" : tier) {
                case "member" -> {
                    tierStyle = Style.EMPTY.withColor(ChatFormatting.GREEN);
                    tierLabel = "member";
                }
                case "honourary" -> {
                    tierStyle = Style.EMPTY.withColor(ChatFormatting.GOLD);
                    tierLabel = "honourary";
                }
                case "waitlist" -> {
                    tierStyle = Style.EMPTY.withColor(ChatFormatting.AQUA);
                    tierLabel = "waitlist";
                }
                default -> {
                    tierStyle = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA);
                    tierLabel = "registered";
                }
            }
        }

        line.append(Component.literal("@" + display).setStyle(VALUE_STYLE));
        if (discUuid != null && !discUuid.isEmpty()) {
            line.append(Component.literal("  (" + discUuid + ")").setStyle(MUTED_STYLE));
        }
        line.append(Component.literal("  —  ").setStyle(LABEL_STYLE))
                .append(Component.literal(tierLabel).setStyle(tierStyle));

        if (disc.isWaitlistedModifier() && !"waitlist".equals(tier)) {
            line.append(
                    Component.literal("  +waitlist")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
        }
        if (disc.isHiatus()) {
            // dazebot attempts (best-effort) a guild re-read and role
            // reconciliation during the snapshot fetch, before it reports
            // HIATUS. The qualifier below is meant to flag a HIATUS role that
            // is stale; today it keys off being online, which dazebot's HIATUS
            // (a guildless ex-member) does not rule out, so it is a weak hint,
            // not evidence (see wv-check-hiatus-stale-hint-keys-off-online).
            Style hiatusStyle = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA);
            line.append(Component.literal("  (hiatus").setStyle(hiatusStyle));
            if (currentlyOnline) {
                line.append(
                        Component.literal(" — but currently online; role may be stale")
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)));
            }
            line.append(Component.literal(")").setStyle(hiatusStyle));
        }
        ChatUtils.sendLocalMessage(line);
    }

    private static void renderStage2Line(MembershipSnapshot snapshot) {
        MutableComponent line = label("Vetsmod client key: ");
        if (snapshot == null || snapshot.getDiscord() == null) {
            line.append(Component.literal("(snapshot unavailable)").setStyle(MUTED_STYLE));
        } else if (snapshot.isStage2Active()) {
            line.append(
                    Component.literal("active")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
        } else {
            line.append(Component.literal("inactive").setStyle(LABEL_STYLE));
        }
        ChatUtils.sendLocalMessage(line);
    }

    private static void renderCultLine(MembershipSnapshot snapshot) {
        // Suppressed when the snapshot names no cult, including when the
        // snapshot is unavailable.
        if (snapshot == null
                || snapshot.getCult() == null
                || snapshot.getCult().getName() == null) {
            return;
        }
        MembershipSnapshot.Cult cult = snapshot.getCult();
        MutableComponent line =
                label("Cult: ").append(Component.literal(cult.getName()).setStyle(VALUE_STYLE));
        if (cult.isFigurehead()) {
            line.append(
                    Component.literal("  (figurehead)")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)));
        }
        ChatUtils.sendLocalMessage(line);
    }

    private static MutableComponent label(String text) {
        return Component.literal(text).setStyle(LABEL_STYLE);
    }

    private static MutableComponent hidden() {
        return Component.literal("(hidden)").setStyle(HIDDEN_STYLE);
    }
}
