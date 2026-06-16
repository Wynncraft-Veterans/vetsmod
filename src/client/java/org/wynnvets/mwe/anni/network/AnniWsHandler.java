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
 * and demultiplexes two frame types — every other type is left for other
 * listeners to handle (the V1 API outbound channel is a fan-out, not an
 * exclusive consumer model):</p>
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
 * </ul>
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
        if ("anni_query_response".equals(json.get("type").getAsString())) {
            AnniQueryClient.onResponse(json);
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
