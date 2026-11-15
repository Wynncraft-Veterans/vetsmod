package org.wynnvets.retro.pack;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.wynnvets.util.HttpClients;

/**
 * Byte ranges of one remote file: what {@link ZipDirectory} reads an old client's jar through, so
 * only the files retro needs are downloaded.
 */
@FunctionalInterface
interface HttpRanges {

    /** How long one range may take, from the request to the last byte. */
    Duration TIMEOUT = Duration.ofSeconds(20);

    /**
     * Reads bytes {@code offset} to {@code offset + length - 1}.
     *
     * @param offset the first byte
     * @param length how many bytes, at least 1
     * @return exactly {@code length} bytes
     * @throws IOException when the range cannot be read whole
     */
    byte[] get(long offset, int length) throws IOException;

    /**
     * The ranges of the file at {@code uri}, over the mod's shared HTTP client. Only a
     * {@code 206 Partial Content} answer for exactly the range asked counts: the body of any
     * other answer (a server that ignores the range sends the whole file) is not kept. The request's
     * own timeout ends when the headers arrive, so the whole exchange waits at most
     * {@link #TIMEOUT}, after which it is cancelled.
     *
     * @param uri the file
     * @return its ranges
     */
    static HttpRanges of(URI uri) {
        return (offset, length) -> {
            String range = offset + "-" + (offset + length - 1);
            HttpRequest request =
                    HttpRequest.newBuilder(uri)
                            .timeout(TIMEOUT)
                            .header("Range", "bytes=" + range)
                            .GET()
                            .build();
            CompletableFuture<HttpResponse<byte[]>> exchange =
                    HttpClients.standard()
                            .sendAsync(
                                    request,
                                    answer ->
                                            answer.statusCode() == 206
                                                    ? HttpResponse.BodySubscribers.ofByteArray()
                                                    : HttpResponse.BodySubscribers.replacing(null));
            HttpResponse<byte[]> response;
            try {
                response = exchange.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                exchange.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("interrupted reading " + uri, e);
            } catch (TimeoutException e) {
                exchange.cancel(true);
                throw new IOException(uri + " took over " + TIMEOUT + " for bytes " + range, e);
            } catch (ExecutionException e) {
                throw new IOException(uri + " failed for bytes " + range, e.getCause());
            }
            String answered = response.headers().firstValue("Content-Range").orElse("");
            if (response.statusCode() != 206 || !answered.startsWith("bytes " + range + "/")) {
                throw new IOException(
                        uri
                                + " answered "
                                + response.statusCode()
                                + " '"
                                + answered
                                + "' to bytes "
                                + range);
            }
            byte[] bytes = response.body();
            if (bytes == null || bytes.length != length) {
                throw new IOException(
                        uri + " sent " + (bytes == null ? 0 : bytes.length) + " of bytes " + range);
            }
            return bytes;
        };
    }
}
