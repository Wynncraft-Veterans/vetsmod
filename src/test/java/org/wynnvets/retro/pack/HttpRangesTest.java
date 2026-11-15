package org.wynnvets.retro.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.wynnvets.util.LocalHttpServer;

/**
 * Tests for {@link HttpRanges#of}'s checks, against a server on this machine: only a
 * {@code 206} for exactly the range asked, of exactly its length, is read.
 */
class HttpRangesTest {

    private static final byte[] FILE = "0123456789".getBytes(StandardCharsets.UTF_8);

    /** Answers a {@code 206} that says it holds {@code range} and sends {@code from..to}. */
    private static void partial(HttpExchange exchange, String range, int from, int to)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Range", "bytes " + range + "/" + FILE.length);
        LocalHttpServer.send(exchange, 206, Arrays.copyOfRange(FILE, from, to), to - from);
    }

    @Test
    void onlyAPartialAnswerForExactlyTheRangeAskedIsRead() throws IOException {
        AtomicReference<String> asked = new AtomicReference<>();
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.answer(
                            "/ranged",
                            exchange -> {
                                asked.set(exchange.getRequestHeaders().getFirst("Range"));
                                partial(exchange, "2-5", 2, 6);
                            })
                    .answer(
                            "/whole",
                            exchange -> LocalHttpServer.send(exchange, 200, FILE, FILE.length))
                    .answer("/other", exchange -> partial(exchange, "3-6", 3, 7))
                    .answer("/short", exchange -> partial(exchange, "2-5", 2, 5))
                    .start();

            assertArrayEquals(
                    Arrays.copyOfRange(FILE, 2, 6), HttpRanges.of(server.uri("/ranged")).get(2, 4));
            assertEquals("bytes=2-5", asked.get(), "the last byte asked is offset + length - 1");
            for (String refused : List.of("/whole", "/other", "/short")) {
                assertThrows(
                        IOException.class,
                        () -> HttpRanges.of(server.uri(refused)).get(2, 4),
                        refused);
            }
        }
    }
}
