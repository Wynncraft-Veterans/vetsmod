package org.wynnvets.fetcher.polling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.wynnvets.api.VetsApi;

/**
 * The snapshot and error contract of {@link PolledJson}.
 *
 * <p>{@code PolledJson} is what {@link PolledJsonMap} and {@link SupportersPoller} stand on, so
 * the rule they both rely on is pinned here once: a snapshot is replaced only by a parse that
 * returns a value, and a parse that returns {@code null} or throws leaves it alone. Only
 * {@link PolledJson#publish(String)} and {@link PolledJson#snapshot()} are exercised. The network
 * half is the same untestable shape the rest of the fetcher tree has, and nothing is started.</p>
 */
class PolledJsonTest {

    private static PolledJson<List<String>> poller(
            List<String> initial, Function<String, List<String>> parser) {
        return new PolledJson<>(
                VetsApi.ROSTER, "VetsMod-Test-PolledJson", "test", 5, 5, initial, parser);
    }

    @Test
    void aColdInstanceHoldsTheInitialValue() {
        List<String> initial = List.of("initial");

        assertSame(initial, poller(initial, body -> List.of(body)).snapshot());
    }

    @Test
    void aColdInstanceMayHoldNull() {
        // A holder can use null for "not fetched yet", which an empty value cannot say.
        PolledJson<List<String>> cold = poller(null, body -> List.of(body));

        assertNull(cold.snapshot());
    }

    @Test
    void aParsedBodyIsPublished() {
        PolledJson<List<String>> poll = poller(List.of(), body -> List.of(body));

        poll.publish("first");
        assertEquals(List.of("first"), poll.snapshot());

        poll.publish("second");
        assertEquals(List.of("second"), poll.snapshot());
    }

    @Test
    void aNullResultKeepsTheSnapshot() {
        PolledJson<List<String>> poll =
                poller(List.of(), body -> body.equals("reject") ? null : List.of(body));
        poll.publish("good");
        List<String> published = poll.snapshot();

        poll.publish("reject");

        assertSame(published, poll.snapshot());
    }

    @Test
    void aThrowingParserKeepsTheSnapshotAndRecovers() {
        PolledJson<List<String>> poll =
                poller(
                        List.of(),
                        body -> {
                            if (body.equals("boom")) {
                                throw new IllegalStateException("boom");
                            }
                            return List.of(body);
                        });
        poll.publish("good");
        List<String> published = poll.snapshot();

        poll.publish("boom");

        assertSame(published, poll.snapshot());
        poll.publish("recovered");
        assertEquals(List.of("recovered"), poll.snapshot());
    }

    @Test
    void snapshotHandsBackOneStableReference() {
        PolledJson<List<String>> poll = poller(List.of(), body -> List.of(body));
        poll.publish("first");
        List<String> held = poll.snapshot();

        assertSame(held, poll.snapshot(), "two reads between polls must hand back the same value");

        poll.publish("second");
        assertEquals(List.of("first"), held, "a held snapshot was changed by the next poll");
    }
}
