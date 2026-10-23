package org.wynnvets.api;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import org.wynnvets.chat.dispatcher.DispatchCoordinator;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.fetcher.polling.StaffRanksPoller;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.Json;

/**
 * Routes the inbound socket's untyped {@code {"status":...}} acks: staff-action acks, and an error
 * ack while a staff-action callback is pending and no auth ack is awaited, to the head of
 * {@link V1ApiManager}'s callback queue (a staff-action ack with nothing pending is dropped);
 * auth acks to {@link SessionAuthState} and {@link GuildStateManager}; and any other ack whose
 * status is not ok to the log. Any other ok ack is dropped.
 */
final class InboundAckRouter {

    private InboundAckRouter() {}

    /**
     * Routes one inbound frame that carries no {@code type}. It must run only after the typed
     * fan-out has passed the frame over: every typed frame but {@code anni_query_response} also
     * carries {@code status}, so routing a typed frame here would misread it as an ack. A frame
     * with no {@code status} is ignored.
     */
    static void route(JsonObject json) {
        // Acknowledgements from the server. Auth-success acks are
        // recognised by the `tier` key, which only they carry. A non-ok ack
        // that the staff-action branch below does not take (it takes every
        // staff-action-shaped ack, dropping one when no callback is pending)
        // counts as an auth failure when an auth frame is awaiting its ack
        // (expectingAuthAck) or its detail starts with an auth-failure prefix.
        if (!json.has("status")) return;
        String status = json.get("status").getAsString();
        boolean wasAuthAck = SessionAuthState.expectingAuthAck;

        // Staff-action ack shape: commits carry `kind`+`triggered`,
        // checks carry `total_points`, preflight carries
        // `status:"would_trigger"`, and membership-check acks match only
        // on `target_uuid` (which every successful staff-action ack
        // carries). We pop ONE pending callback per
        // matching ack so multiple in-flight staff actions resolve
        // in send order. Errors (status=="error") are also routed to
        // the callback queue when one is pending and we aren't
        // currently waiting on an auth ack -- the alternative would
        // strand the caller's UI in "loading" forever on a
        // server-side validation failure.
        //
        // Auth-success responses are explicitly excluded by the `tier` key
        // (only they carry it) so a future `total_points` collision can't
        // misroute auth into the staff queue; auth errors are kept out by
        // the expectingAuthAck test in staffOrphanError.
        boolean isAuthShaped = json.has("tier");
        boolean staffShaped =
                !isAuthShaped
                        && (json.has("kind")
                                || json.has("triggered")
                                || json.has("total_points")
                                || json.has("target_uuid")
                                || "would_trigger".equals(status));
        boolean staffOrphanError =
                "error".equals(status)
                        && !wasAuthAck
                        && !V1ApiManager.staffActionCallbacks.isEmpty();
        if (staffShaped || staffOrphanError) {
            Consumer<JsonObject> cb = V1ApiManager.staffActionCallbacks.pollFirst();
            if (cb != null) {
                try {
                    cb.accept(json);
                } catch (Exception e) {
                    VetsLogger.warn("Staff-action callback error: {}", e.getMessage());
                }
                return;
            }
            VetsLogger.debug("Staff-action ack with empty callback queue: {}", json);
            // Without a callback the response is unactionable; swallow it
            // so it doesn't leak into the auth/chat error path below.
            return;
        }

        // Auth success responses carry a `tier` key -- chat and control acks
        // don't -- so success is recognised by that key alone, whatever
        // expectingAuthAck says.
        if ("ok".equals(status) && json.has("tier")) {
            SessionAuthState.expectingAuthAck = false;
            // A null, non-string or blank tier reads as "other", UnlockManager's TIER_OTHER.
            String tier = Json.optString(json, "tier", "other");
            if (tier.isBlank()) tier = "other";
            long now = System.currentTimeMillis();
            VetsConfig.setString(VetsConfigKeys.VETS_AUTH_TIER, tier);
            VetsConfig.setLong(VetsConfigKeys.VETS_AUTH_VERIFIED_AT, now);
            // Capture server-confirmed staff status from the auth ack.
            // The roster lookup happens server-side; the client just
            // reads whatever the server resolved.
            boolean wasStaff = SessionAuthState.confirmedStaff;
            SessionAuthState.confirmedStaff =
                    json.has("is_staff")
                            && !json.get("is_staff").isJsonNull()
                            && json.get("is_staff").getAsBoolean();
            if (SessionAuthState.confirmedStaff
                    && json.has("staff_rank")
                    && !json.get("staff_rank").isJsonNull()) {
                SessionAuthState.confirmedStaffRank = json.get("staff_rank").getAsString();
            } else {
                SessionAuthState.confirmedStaffRank = "";
            }
            // Same-world demotion clears the eligibility cache, which
            // DispatchCoordinator's auth-ack fast path fills while we are
            // staff, so later gated calls don't keep treating us as
            // staff. World change already resets it via
            // GuildLifecycle.onEnteredWorld ->
            // resetStaffChatEligibilityCache.
            if (wasStaff && !SessionAuthState.confirmedStaff) {
                DispatchCoordinator.resetStaffChatEligibilityCache();
            }
            // Refresh ahead of schedule: the next scheduled poll may be up
            // to two minutes away, so trigger an immediate refresh of the
            // receiver-side staff cache on every successful auth ack.
            StaffRanksPoller.refreshNow();
            GuildStateManager.onAuthSuccess(tier);
            return;
        }
        if (!"ok".equals(status)) {
            String detail = json.has("detail") ? json.get("detail").getAsString() : "unknown";
            if (wasAuthAck
                    || detail.startsWith("auth rejected")
                    || detail.startsWith("Authentication required")) {
                SessionAuthState.expectingAuthAck = false;
                boolean wasStaff = SessionAuthState.confirmedStaff;
                SessionAuthState.confirmedStaff = false;
                SessionAuthState.confirmedStaffRank = "";
                if (wasStaff) {
                    DispatchCoordinator.resetStaffChatEligibilityCache();
                }
                GuildStateManager.onAuthFailure(detail);
            } else {
                VetsLogger.warn("Inbound API error: {}", detail);
            }
        }
    }
}
