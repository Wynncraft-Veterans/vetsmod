package org.wynnvets.mwe.anni.network;

import com.google.gson.JsonObject;

import org.wynnvets.api.V1ApiManager;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

/**
 * WebSocket frame router for the MWE/anni subsystem.
 *
 * <p>Subscribes to {@link V1ApiManager#addOutboundListener(java.util.function.Consumer)}
 * and to the inbound channel, and demultiplexes one outbound and three
 * inbound frame types plus a fourth it only logs — every other type is
 * left for other listeners to handle (the V1 API outbound channel is a
 * fan-out, not an exclusive consumer model):</p>
 *
 * <ul>
 *   <li>{@code anni_state} (outbound channel): server-initiated push.
 *       Snapshot is dropped into
 *       {@link AnniSnapshotCache#update(AnniSnapshot)} so all subscribed
 *       surfaces re-render.</li>
 *   <li>{@code anni_query_response} (inbound channel): ack for an
 *       {@link AnniQueryClient#query()} call. Routed to the next pending
 *       future in the query client's single-flight queue. Lives on the
 *       inbound channel because it's a request/response pair on the
 *       same WS the request went out on.</li>
 *   <li>{@code anni_scrollspot_response} and {@code anni_rsvp_response}
 *       (inbound channel): the same request/response shape, routed to
 *       {@link AnniScrollspotClient#onResponse} and
 *       {@link AnniRsvpClient#onResponse}.</li>
 *   <li>{@code anni_party_observation_response} (inbound channel):
 *       debug-logged inline. The report is fire-and-forget, so there is
 *       no single-flight queue and no consumer state.</li>
 * </ul>
 *
 * <p>{@link #register()} additionally installs a post-connect
 * {@link AnniQueryClient#query()} re-pull, so a reconnect warms the
 * cache without waiting for the next push.</p>
 *
 * <p>Idempotent registration via the static {@link #register()} method —
 * call from {@link org.wynnvets.VetsmodClient#onInitializeClient()} after
 * {@code V1ApiManager.connect()}. Calling twice is a no-op. Subscribes
 * to both inbound and outbound channels via
 * {@link org.wynnvets.api.V1ApiManager#addInboundListener(java.util.function.Consumer)}
 * and {@code addOutboundListener(...)}.</p>
 */
public final class AnniWsHandler {

    private static volatile boolean registered = false;

    private AnniWsHandler() {
    }

    /** Wire up the inbound + outbound listeners. Safe to call repeatedly. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        V1ApiManager.addInboundListener(AnniWsHandler::onInbound);
        V1ApiManager.addOutboundListener(AnniWsHandler::onOutbound);
        // Re-pull a fresh snapshot on every inbound (re)connect — the
        // server only pushes anni_state on certain events, so a world
        // transfer that drops the socket would otherwise leave the
        // cache frozen on whatever was current at the previous connect
        // until the next server-initiated push (which may never come
        // for the user's own RSVP/role edits). Cheap on cold start
        // (single-flight queue collapses with the StampFetcher cold
        // path), correct on reconnect.
        V1ApiManager.addInboundPostConnectListener(AnniQueryClient::query);
        VetsLogger.debug("AnniWsHandler registered");
    }

    private static void onOutbound(JsonObject json) {
        if (json == null || !json.has("type")) {
            return;
        }
        if ("anni_state".equals(json.get("type").getAsString())) {
            handleAnniState(json);
        }
    }

    private static void onInbound(JsonObject json) {
        if (json == null || !json.has("type")) {
            return;
        }
        String type = json.get("type").getAsString();
        if ("anni_query_response".equals(type)) {
            AnniQueryClient.onResponse(json);
        } else if ("anni_scrollspot_response".equals(type)) {
            AnniScrollspotClient.onResponse(json);
        } else if ("anni_rsvp_response".equals(type)) {
            AnniRsvpClient.onResponse(json);
        } else if ("anni_party_observation_response".equals(type)) {
            // S7 ack — debug-logged only; no client-side single-flight queue.
            // The frame fires fire-and-forget from PartyRosterListener; if a
            // future debug surface needs ack diagnostics, mirror S6's
            // AnniRsvpClient (lastAck / pendingCount).
            String status = json.has("status") ? json.get("status").getAsString() : "?";
            String detail = json.has("detail") && !json.get("detail").isJsonNull()
                    ? json.get("detail").getAsString() : null;
            if ("ok".equals(status)) {
                VetsLogger.debug("anni_party_observation_response: ok");
            } else {
                VetsLogger.debug(
                        "anni_party_observation_response: {} — {}", status, detail);
            }
        }
    }

    private static void handleAnniState(JsonObject json) {
        if (!json.has("snapshot") || json.get("snapshot").isJsonNull()) {
            VetsLogger.debug("anni_state with null snapshot — ignoring");
            return;
        }
        JsonObject snapJson = json.getAsJsonObject("snapshot");
        AnniSnapshot snapshot;
        try {
            snapshot = AnniSnapshot.fromJson(snapJson);
        } catch (Exception e) {
            VetsLogger.warn("anni_state parse failed: {}", e.getMessage());
            return;
        }
        AnniSnapshotCache.update(snapshot);
    }
}
