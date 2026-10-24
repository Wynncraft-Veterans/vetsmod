package org.wynnvets.api;

import com.google.gson.JsonObject;
import org.wynnvets.chat.dispatcher.DispatchCoordinator;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.fetcher.polling.StaffRanksPoller;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.Json;

/**
 * Routes the inbound socket's untyped {@code {"status":...}} acks. An ack that echoes a request id
 * ({@code req}) goes to the callback registered under it in {@link V1ApiManager#pendingAcks}, or
 * is dropped when none is (answered already, or a frame sent without a callback); a chat
 * relay's auth-failure error also runs the auth-failure handling. Against a server that does not
 * echo ids, a staff-action-shaped ack, and an error ack while a staff-action callback is pending
 * and no auth ack is awaited, go to the eldest pending staff-action callback instead (a
 * staff-action-shaped ack with nothing pending is dropped). An id-less staff-action-shaped ack
 * while the client believes the server echoes proves it does not, and switches it back. Auth
 * acks go to {@link SessionAuthState} and {@link GuildStateManager}, and any other ack whose
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

        // An echoed request id names the frame this ack answers, so it goes to that frame's
        // callback and nowhere else. vetsmod sends no req on auth frames, so this is never an
        // auth ack. An id with no callback (already answered, or a frame sent without one) is
        // dropped rather than handed to anyone else.
        String req = Json.stringOrNull(json, "req");
        if (req != null) {
            V1ApiManager.ackEchoedReq = true;
            PendingAcks.Entry entry = V1ApiManager.pendingAcks.complete(req);
            if (entry == null) {
                VetsLogger.debug("Ack for an unknown or answered request {}: {}", req, json);
                return;
            }
            V1ApiManager.answer(entry, json);
            // A relay refused because the session's auth is gone says so about the session, not
            // just the line, as it did before relays had callbacks. A staff-action callback keeps
            // its error to itself.
            if (entry.kind() == PendingAcks.Kind.RELAY && !"ok".equals(status)) {
                String detail = Json.optString(json, "detail", "unknown");
                if (isAuthFailureDetail(detail)) {
                    onAuthFailureAck(detail);
                }
            }
            return;
        }

        // From here the ack carries no id. A server that echoes ids gives every staff-action ack
        // one, so the eldest-first pairing below runs only against one that does not.
        boolean legacy = !V1ApiManager.serverEchoesReq();

        // Staff-action ack shape: commits carry `kind`+`triggered`,
        // checks carry `total_points`, preflight carries
        // `status:"would_trigger"`, and membership-check acks match only
        // on `target_uuid` (which every successful staff-action ack
        // carries). Without ids we answer ONE pending callback per
        // matching ack, eldest first, so multiple in-flight staff actions
        // resolve in send order. Errors (status=="error") are also routed
        // to the eldest callback when one is pending and we aren't
        // currently waiting on an auth ack -- the alternative would
        // strand the caller's UI in "loading" forever on a
        // server-side validation failure. That rule also lets an error
        // ack to some other frame claim a staff command's callback, which
        // is why it is off once the server echoes ids.
        //
        // Auth-success responses are explicitly excluded by the `tier` key
        // (only they carry it) so a future `total_points` collision can't
        // misroute auth into the staff path; auth errors are kept out by
        // the expectingAuthAck test in staffOrphanError.
        boolean isAuthShaped = json.has("tier");
        boolean staffShaped =
                !isAuthShaped
                        && (json.has("kind")
                                || json.has("triggered")
                                || json.has("total_points")
                                || json.has("target_uuid")
                                || "would_trigger".equals(status));
        if (staffShaped && !legacy) {
            // An echoing server puts req on every staff-action reply, and vetsmod sends one on
            // every staff-action frame, so an id-less staff-action ack proves this server does
            // not echo: the flags are stale (a rollback to an older server before its hello
            // arrived). Believe the ack and pair it eldest first, as against any older server.
            VetsLogger.info("Staff-action ack without a request id: the server does not echo ids");
            V1ApiManager.helloEchoesReq = false;
            V1ApiManager.ackEchoedReq = false;
            legacy = true;
        }
        boolean staffOrphanError =
                legacy
                        && "error".equals(status)
                        && !wasAuthAck
                        && V1ApiManager.pendingAcks.hasStaff();
        if (staffShaped || staffOrphanError) {
            PendingAcks.Entry entry = legacy ? V1ApiManager.pendingAcks.pollEldestStaff() : null;
            if (entry != null) {
                V1ApiManager.answer(entry, json);
                return;
            }
            VetsLogger.debug("Staff-action ack with no callback to answer: {}", json);
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
            // A member tier also authenticates the outbound socket (V1ApiManager's class doc).
            V1ApiManager.onInboundAuthOk(tier);
            return;
        }
        if (!"ok".equals(status)) {
            String detail = json.has("detail") ? json.get("detail").getAsString() : "unknown";
            if (wasAuthAck || isAuthFailureDetail(detail)) {
                onAuthFailureAck(detail);
            } else {
                VetsLogger.warn("Inbound API error: {}", detail);
            }
        }
    }

    /** Whether an error detail is one of the auth-failure replies: a refused key, or a frame
     *  refused because the session holds no auth. */
    private static boolean isAuthFailureDetail(String detail) {
        return detail.startsWith("auth rejected") || detail.startsWith("Authentication required");
    }

    /** Clears the confirmed-staff state and reports an auth failure to {@link V1ApiManager} and
     *  {@link GuildStateManager}. */
    private static void onAuthFailureAck(String detail) {
        SessionAuthState.expectingAuthAck = false;
        boolean wasStaff = SessionAuthState.confirmedStaff;
        SessionAuthState.confirmedStaff = false;
        SessionAuthState.confirmedStaffRank = "";
        if (wasStaff) {
            DispatchCoordinator.resetStaffChatEligibilityCache();
        }
        V1ApiManager.onInboundAuthFailed(detail);
        GuildStateManager.onAuthFailure(detail);
    }
}
