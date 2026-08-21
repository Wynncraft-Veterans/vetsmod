package org.wynnvets.fetcher.lookup.providers;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.datamodels.MembershipSnapshot;
import org.wynnvets.fetcher.lookup.LookupProvider;
import org.wynnvets.fetcher.lookup.LookupResult;
import org.wynnvets.fetcher.lookup.ProviderOutcome;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.Json;

/**
 * Resolver via the {@code check_membership} WebSocket frame sent to
 * {@code temporary-server} ({@code wss://api.wynnvets.org/v1/inbound}).
 *
 * <p>Routing: vetsmod talks <em>only</em> to temporary-server at
 * {@code api.wynnvets.org} — never to dazebot directly. Temporary-server
 * is the only vets-backend project authorised to forward this frame's
 * payload to dazebot's internal check-snapshot endpoint; the ack flows
 * back through the same WebSocket. This boundary is a hard rule (see
 * {@link org.wynnvets.api.V1ApiManager}'s INBOUND_BASE) — never add a
 * direct dazebot HTTP call here.</p>
 *
 * <p>The ack returns both the UUID and the full
 * {@link MembershipSnapshot} (Discord link, blocklist state, waitlist
 * count, etc.) — so a Vets hit lets the invite gate skip its secondary
 * snapshot fetch.</p>
 *
 * <p>Skipped when the inbound WebSocket isn't connected: the frame would
 * synthesise a connection-closed error ack and we'd treat that as
 * transient anyway, but short-circuiting saves the WS round-trip cost.</p>
 */
public final class VetsSnapshotProvider implements LookupProvider {

    /**
     * Sends a {@code check_membership} frame and adapts the single-use ack
     * callback into a future. Used by this provider AND by InviteGate /
     * UserInfoFetcher for their secondary "snapshot only" fetch path (when
     * the cascade's hit didn't already provide one).
     */
    public static CompletableFuture<MembershipSnapshot> requestSnapshot(String target) {
        CompletableFuture<MembershipSnapshot> cf = new CompletableFuture<>();
        JsonObject fields = new JsonObject();
        fields.addProperty("target_username", target);
        V1ApiManager.sendStaffActionFrame(
                "check_membership",
                fields,
                ack -> {
                    try {
                        String status =
                                ack.has("status") && !ack.get("status").isJsonNull()
                                        ? ack.get("status").getAsString()
                                        : "error";
                        if ("ok".equals(status)) {
                            cf.complete(Json.GSON.fromJson(ack, MembershipSnapshot.class));
                        } else {
                            cf.complete(null);
                        }
                    } catch (Exception e) {
                        cf.complete(null);
                    }
                });
        return cf;
    }

    @Override
    public String id() {
        return "vets";
    }

    @Override
    public CompletableFuture<ProviderOutcome> lookup(String name) {
        if (!V1ApiManager.isInboundConnected()) {
            return CompletableFuture.completedFuture(ProviderOutcome.transientMiss());
        }
        return requestSnapshot(name)
                .thenApply(this::translate)
                .exceptionally(
                        e -> {
                            VetsLogger.debug(
                                    "Vets snapshot lookup '{}' threw: {}", name, e.getMessage());
                            return ProviderOutcome.transientMiss();
                        });
    }

    private ProviderOutcome translate(MembershipSnapshot snap) {
        if (snap == null) {
            // Could be "no such player" or a transient WS failure; the ack
            // shape doesn't reliably distinguish, so call it transient and
            // let later providers settle the question.
            return ProviderOutcome.transientMiss();
        }
        String uuidStr = snap.getTargetUuid();
        if (uuidStr == null || uuidStr.isBlank()) {
            return ProviderOutcome.transientMiss();
        }
        try {
            UUID uuid = UUID.fromString(uuidStr);
            String canonical =
                    snap.getTargetUsername() != null && !snap.getTargetUsername().isBlank()
                            ? snap.getTargetUsername()
                            : uuidStr;
            return ProviderOutcome.hit(LookupResult.ofVets(canonical, uuid, snap));
        } catch (IllegalArgumentException e) {
            VetsLogger.debug("Vets snapshot returned non-UUID target_uuid: {}", uuidStr);
            return ProviderOutcome.transientMiss();
        }
    }
}
