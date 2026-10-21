package org.wynnvets.fetcher.ondemand;

import com.google.gson.JsonObject;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.wynnvets.api.WynnCraftApi;
import org.wynnvets.datamodels.Guild;
import org.wynnvets.datamodels.MembershipSnapshot;
import org.wynnvets.datamodels.User;
import org.wynnvets.datamodels.UserUUID;
import org.wynnvets.fetcher.lookup.FailureReason;
import org.wynnvets.fetcher.lookup.LookupResult;
import org.wynnvets.fetcher.lookup.PlayerLookup;
import org.wynnvets.fetcher.lookup.providers.VetsSnapshotProvider;
import org.wynnvets.util.HttpClients;
import org.wynnvets.util.Json;
import org.wynnvets.util.Uuids;

/**
 * On-demand fetcher for the {@code /wv check} command.
 *
 * <p>Resolves the name through {@link PlayerLookup}, gathers the Wynncraft
 * player profile and the dazebot membership snapshot (reusing either when
 * the cascade's hit already carries it; the snapshot travels over the WS
 * bridge), and, once there is a profile, has {@link ReturnersRosterProbe}
 * cross-check guild membership against Wynncraft's guild rosters (see
 * {@link #fetchAndRender}). It hands the combined result to
 * {@link CheckReportRenderer}, which renders it as a multi-line block in the
 * local chat HUD, with its date labels from {@link TimeLabels}.</p>
 */
public final class UserInfoFetcher {
    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    private UserInfoFetcher() {}

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
     * not already carry (Wynn player profile, dazebot snapshot), has
     * {@link ReturnersRosterProbe} cross-check guild rosters once there is a profile, and has
     * {@link CheckReportRenderer} render the combined block line by line.
     *
     * <p>Failures are surfaced as a single error line; partial results
     * (e.g. Wynn fetch succeeds but the snapshot doesn't) still render the
     * portions that resolved. Today, while a snapshot request's callback goes
     * unanswered, the whole block stays unrendered, with no error line, since
     * nothing bounds that wait (see
     * {@code staff-action-queue-not-drained-on-socket-reconnect}).</p>
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
                                CheckReportRenderer.renderError(
                                        "No player named '"
                                                + playerName
                                                + "' on any lookup source.");
                                return;
                            }
                            CheckReportRenderer.renderError(
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
                    // Imports used only by this workaround: java.util.Optional and
                    // org.wynnvets.datamodels.Guild.
                    // Original gated on `user.isInVets()` (payload claims Returners).
                    // Drop the isInVets() gate so we crosscheck the Returners roster
                    // whenever there is a Wynncraft profile -- needed to catch the
                    // rejoin case where the payload transitions through a
                    // non-Returners guild name mid-invalidation. Cost: one extra
                    // /v3/guild/Returners fetch per /wv check whose payload does not
                    // claim Returners; /wv check cadence is low.
                    CompletableFuture<ReturnersRosterProbe.ReturnersMembership> returnersFuture =
                            (user != null)
                                    ? ReturnersRosterProbe.returnersMembership(synthUuid.id)
                                    : CompletableFuture.completedFuture(
                                            ReturnersRosterProbe.ReturnersMembership.notInGuild());
                    // <<< PATCH END: WYNN-STALE-WORKAROUND
                    // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
                    // Reason/Remove: see the first workaround block in this class.
                    // Fire the roster crosscheck alongside returners + snapshot only when
                    // the payload names a non-Returners guild (Returners is already
                    // crosschecked via returnersFuture above).
                    Guild payloadGuild = user != null ? user.getGuild() : null;
                    CompletableFuture<Optional<String>> confirmedNonReturnersRankFuture =
                            (payloadGuild != null
                                            && payloadGuild.getName() != null
                                            && !"Returners".equals(payloadGuild.getName()))
                                    ? ReturnersRosterProbe.confirmArbitraryGuildRank(
                                            payloadGuild.getName(), synthUuid.id)
                                    : CompletableFuture.completedFuture(Optional.<String>empty());
                    // <<< PATCH END: WYNN-STALE-WORKAROUND
                    return returnersFuture.thenCombine(
                            snapFuture,
                            (returners, snapshot) -> {
                                // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
                                // Reason/Remove: see the first workaround block in this class.
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
                                                        CheckReportRenderer.renderCheck(
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
        u.id = Uuids.toUndashed(uuid);
        u.name = name;
        return u;
    }
}
