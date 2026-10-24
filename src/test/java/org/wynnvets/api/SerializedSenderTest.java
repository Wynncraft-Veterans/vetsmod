package org.wynnvets.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link SerializedSender}: one write at a time in submit order, a failed write that does
 * not block the next, and the in-flight bound. A fake writer records each text it is asked to
 * write and returns a future the test completes by hand, standing in for the JDK socket's
 * {@code sendText}.
 */
class SerializedSenderTest {

    /** Records each write and hands back a future the test completes. */
    private static final class FakeWriter {
        final List<String> written = new ArrayList<>();
        final List<CompletableFuture<Void>> pending = new ArrayList<>();

        CompletionStage<?> write(String text) {
            written.add(text);
            CompletableFuture<Void> f = new CompletableFuture<>();
            pending.add(f);
            return f;
        }
    }

    private final FakeWriter writer = new FakeWriter();
    private final SerializedSender sender = new SerializedSender("test", writer::write);

    @Test
    void framesGoOutInSubmitOrderOneAtATime() {
        CompletableFuture<Boolean> a = sender.submit("a");
        CompletableFuture<Boolean> b = sender.submit("b");
        CompletableFuture<Boolean> c = sender.submit("c");
        assertEquals(List.of("a"), writer.written, "b waits for a's write to complete");

        writer.pending.get(0).complete(null);
        assertEquals(List.of("a", "b"), writer.written);
        assertTrue(a.join());
        assertFalse(b.isDone());

        writer.pending.get(1).complete(null);
        writer.pending.get(2).complete(null);
        assertEquals(List.of("a", "b", "c"), writer.written);
        assertTrue(b.join());
        assertTrue(c.join());
    }

    @Test
    void aFailedWriteDoesNotBlockTheNextOne() {
        CompletableFuture<Boolean> a = sender.submit("a");
        CompletableFuture<Boolean> b = sender.submit("b");
        writer.pending.get(0).completeExceptionally(new IllegalStateException("Send pending"));
        assertFalse(a.join());
        assertEquals(List.of("a", "b"), writer.written);
        writer.pending.get(1).complete(null);
        assertTrue(b.join());
    }

    @Test
    void aWriterThatThrowsAtOnceCompletesFalseAndTheQueueGoesOn() {
        List<String> written = new ArrayList<>();
        SerializedSender throwing =
                new SerializedSender(
                        "test",
                        text -> {
                            written.add(text);
                            if (text.equals("a")) throw new IllegalStateException("closed");
                            return CompletableFuture.completedFuture(null);
                        });
        assertFalse(throwing.submit("a").join());
        assertTrue(throwing.submit("b").join());
        assertEquals(List.of("a", "b"), written);
    }

    @Test
    void aSubmitBeyondTheBoundCompletesFalseWithoutWriting() {
        SerializedSender bounded = new SerializedSender("test", writer::write, 2);
        CompletableFuture<Boolean> a = bounded.submit("a");
        CompletableFuture<Boolean> b = bounded.submit("b");
        CompletableFuture<Boolean> c = bounded.submit("c");
        assertTrue(c.isDone());
        assertFalse(c.join(), "two already queued");

        writer.pending.get(0).complete(null);
        writer.pending.get(1).complete(null);
        assertTrue(a.join());
        assertTrue(b.join());
        assertEquals(List.of("a", "b"), writer.written, "c was never written");

        CompletableFuture<Boolean> d = bounded.submit("d");
        writer.pending.get(2).complete(null);
        assertTrue(d.join(), "room again once the queue drained");
    }

    @Test
    void aSubmitFromAResultsDependentStillWaitsItsTurn() {
        // "a" completes at once, so the dependent attached to its result runs straight away and
        // submits "b" from inside the sender's completion; "b" and then "c" must still go out one
        // at a time, in that order.
        List<String> written = new ArrayList<>();
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        SerializedSender sender =
                new SerializedSender(
                        "test",
                        text -> {
                            written.add(text);
                            if (text.equals("a")) return CompletableFuture.completedFuture(null);
                            CompletableFuture<Void> f = new CompletableFuture<>();
                            pending.add(f);
                            return f;
                        });
        List<CompletableFuture<Boolean>> inner = new ArrayList<>();
        sender.submit("a").thenAccept(ok -> inner.add(sender.submit("b")));
        CompletableFuture<Boolean> c = sender.submit("c");
        assertEquals(List.of("a", "b"), written, "c waits for b");
        pending.get(0).complete(null);
        assertEquals(List.of("a", "b", "c"), written);
        pending.get(1).complete(null);
        assertTrue(inner.get(0).join());
        assertTrue(c.join());
    }

    @Test
    void theBoundIsSixtyFourByDefault() {
        assertEquals(64, SerializedSender.MAX_IN_FLIGHT);
    }
}
