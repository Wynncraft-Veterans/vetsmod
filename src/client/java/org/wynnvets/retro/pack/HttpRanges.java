package org.wynnvets.retro.pack;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
     * The ranges of the file at {@code uri}, over the mod's shared HTTP client, each within
     * {@link #TIMEOUT} ({@link HttpClients#bytesWithin}). Only a {@code 206 Partial Content}
     * answer for exactly the range asked has its body read: a server that ignores the range answers
     * with the whole file, which is not kept.
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
            HttpResponse<byte[]> response;
            try {
                response =
                        HttpClients.bytesWithin(
                                request,
                                answer ->
                                        answer.statusCode() == 206
                                                && contentRange(answer.headers())
                                                        .startsWith("bytes " + range + "/"),
                                TIMEOUT);
            } catch (IOException e) {
                // One jar is read in many ranges of the same file: say which one failed.
                throw new IOException("bytes " + range + " of " + uri, e);
            }
            byte[] bytes = response.body();
            if (bytes == null) {
                throw new IOException(
                        uri
                                + " answered "
                                + response.statusCode()
                                + " '"
                                + contentRange(response.headers())
                                + "' to bytes "
                                + range);
            }
            if (bytes.length != length) {
                throw new IOException(uri + " sent " + bytes.length + " of bytes " + range);
            }
            return bytes;
        };
    }

    private static String contentRange(HttpHeaders headers) {
        return headers.firstValue("Content-Range").orElse("");
    }
}
