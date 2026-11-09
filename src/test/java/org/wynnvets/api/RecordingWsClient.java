package org.wynnvets.api;

import com.google.gson.JsonObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A socket that never connects, standing in for {@link V1ApiManager}'s inbound or outbound
 * connection in {@code V1ApiManagerFramesTest} and {@code V1ApiManagerOutboundAuthTest}. It
 * records each frame as {@link WsClient#sendAsync(JsonObject)} would write it
 * ({@link WsClient#toWire}), and reports the connection state, send outcome and socket number the
 * test sets.
 */
final class RecordingWsClient extends WsClient {

    /** Each frame sent, in order, as the socket would serialise it. */
    final List<String> wire = new ArrayList<>();

    /** What {@link #isConnected()} reports. */
    volatile boolean connected = true;

    /** What each send reports: true as if handed to the socket, false as if it failed. */
    volatile boolean sends = true;

    /** The socket number {@link #generation()} reports. */
    volatile long generation = 1;

    /**
     * @param uri the endpoint the socket names, which it never contacts
     */
    RecordingWsClient(URI uri) {
        super(uri, "test", json -> {});
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public CompletableFuture<Boolean> sendAsync(JsonObject json) {
        wire.add(WsClient.toWire(json));
        return CompletableFuture.completedFuture(sends);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }
}
