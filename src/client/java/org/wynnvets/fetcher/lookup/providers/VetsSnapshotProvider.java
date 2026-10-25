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
 * count, etc.) — so a Vets hit lets a caller that needs the snapshot skip
 * its own snapshot fetch.</p>
 *
 * <p>This provider is not an independent existence check: dazebot resolves the name
 * through the same Wynncraft and Mojang/PlayerDB sources the cascade asks directly. Its job is
 * to supply a snapshot on a hit, so on any non-hit it
 * {@linkplain ProviderOutcome#abstain() abstains} and leaves PLAYER_NOT_FOUND to the other
 * providers. It abstains without sending when the inbound WebSocket isn't connected, saving
 * the round trip the connection-closed ack would cost; a malformed answer or a thrown error
 * stays a transient miss, since the server did speak.</p>
 */
public final class VetsSnapshotProvider implements LookupProvider {

    /**
     * Sends a {@code check_membership} frame and adapts the single-use ack
     * callback into a future. Used by this provider AND by InviteGate /
     * UserInfoFetcher for their secondary "snapshot only" fetch path (when
     * the cascade's hit didn't already provide one). temporary-server answers a confirmed-staff
     * session with the full snapshot and an authenticated non-staff Returners member with the
     * invite verdict only ({@code may_invite} and {@code waitlist_count}; see
     * {@link MembershipSnapshot#getMayInvite()}). Any other session gets an error ack, which
     * completes the future with {@code null}. So does a lost socket or a reply that does not come
     * in time ({@link V1ApiManager#sendStaffActionFrame}), so the future always completes.
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
        if (!sendsLookup(V1ApiManager.isInboundConnected(), V1ApiManager.isConfirmedStaff())) {
            return CompletableFuture.completedFuture(ProviderOutcome.abstain());
        }
        return requestSnapshot(name)
                .thenApply(VetsSnapshotProvider::translate)
                .exceptionally(
                        e -> {
                            VetsLogger.debug(
                                    "Vets snapshot lookup '{}' threw: {}", name, e.getMessage());
                            return ProviderOutcome.transientMiss();
                        });
    }

    /**
     * Whether the cascade's Vets step sends a frame at all. It needs a connected socket and a
     * confirmed-staff session: a non-staff session gets at most the invite verdict, which
     * InviteGate fetches itself, so for the cascade the step abstains without a round trip.
     */
    // Package-private for unit tests. See VetsSnapshotProviderTest.
    static boolean sendsLookup(boolean connected, boolean confirmedStaff) {
        return connected && confirmedStaff;
    }

    // Package-private for unit tests. See VetsSnapshotProviderTest.
    static ProviderOutcome translate(MembershipSnapshot snap) {
        if (snap == null) {
            // Could be "no such player", a rejection, or a WS failure; the ack
            // shape doesn't reliably distinguish. Abstain rather than miss
            // transiently, so the independent providers decide whether the
            // name exists and the cascade can still end PLAYER_NOT_FOUND.
            return ProviderOutcome.abstain();
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
