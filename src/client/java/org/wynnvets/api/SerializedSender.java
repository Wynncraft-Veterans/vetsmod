package org.wynnvets.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.wynnvets.logging.VetsLogger;

/**
 * Serialises the text writes on one WebSocket, in submit order.
 *
 * <p>The JDK {@code WebSocket} allows one outstanding {@code sendText}: a second call before the
 * first one's future completes throws or fails with {@code IllegalStateException}. vetsmod sends
 * from several threads (the render thread, the party-roster scheduler, the socket's own threads),
 * so each write here waits for the previous one to complete, whether it succeeded or failed. The
 * future {@link #submit} returns completes {@code true} once the write completed, which means the
 * text was handed to the socket, not that the server received it, and {@code false} if it
 * failed.</p>
 *
 * <p>At most {@link #MAX_IN_FLIGHT} writes may be queued or in progress; beyond that a submit
 * fails at once with a warning instead of growing a backlog behind a stalled socket, which is
 * dying anyway. {@link WsClient} makes one sender per socket, so a dead socket's queue is never
 * replayed on its replacement: its writes just fail.</p>
 */
final class SerializedSender {

    /** The most writes that may be queued or in progress at once. */
    static final int MAX_IN_FLIGHT = 64;

    private final String label;
    private final Function<String, CompletionStage<?>> writer;
    private final int maxInFlight;
    private final AtomicInteger inFlight = new AtomicInteger();

    /** Completes when the last submitted write has completed; the next one starts then. Guarded by
     *  {@code this}. */
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    /**
     * @param label  names the socket in log lines
     * @param writer starts one write and returns its completion, e.g.
     *               {@code text -> ws.sendText(text, true)}
     */
    SerializedSender(String label, Function<String, CompletionStage<?>> writer) {
        this(label, writer, MAX_IN_FLIGHT);
    }

    // Package-private for unit tests. See SerializedSenderTest.
    SerializedSender(String label, Function<String, CompletionStage<?>> writer, int maxInFlight) {
        this.label = label;
        this.writer = writer;
        this.maxInFlight = maxInFlight;
    }

    /**
     * Queues {@code text} to be written after every earlier submit has completed.
     *
     * @return completes {@code true} when the write completed, {@code false} when it failed or
     *     the queue was full
     */
    CompletableFuture<Boolean> submit(String text) {
        if (inFlight.incrementAndGet() > maxInFlight) {
            inFlight.decrementAndGet();
            VetsLogger.warn("[{}] Send queue full ({}); dropping a frame", label, maxInFlight);
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        // Swap in this write's completion under the lock, then chain outside it: a write that
        // completes at once runs the handler below on this thread, and the caller's dependents of
        // `result` with it. A submit from one of those dependents must find `tail` already set.
        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture<Void> previous;
        synchronized (this) {
            previous = tail;
            tail = done;
        }
        previous.thenCompose(ignored -> write(text))
                .handle(
                        (ignored, failure) -> {
                            inFlight.decrementAndGet();
                            if (failure != null) {
                                VetsLogger.debug(
                                        "[{}] WebSocket send failed: {}",
                                        label,
                                        failure.getMessage());
                            }
                            done.complete(null);
                            result.complete(failure == null);
                            return null;
                        });
        return result;
    }

    private CompletionStage<?> write(String text) {
        try {
            return writer.apply(text);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
