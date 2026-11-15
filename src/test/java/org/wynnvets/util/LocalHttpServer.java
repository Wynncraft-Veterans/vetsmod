package org.wynnvets.util;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

/**
 * An HTTP server on this machine, for the suites that test a download's checks against real
 * answers: each path answers as its handler says.
 */
public final class LocalHttpServer implements AutoCloseable {

    private final HttpServer server;

    /**
     * Binds a server to a free port; it answers nothing until {@link #start()}.
     *
     * @throws IOException when no port can be bound
     */
    public LocalHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    /**
     * Answers {@code path} with {@code handler}.
     *
     * @param path the path, such as {@code /ok.png}
     * @param handler the answer
     * @return this server
     */
    public LocalHttpServer answer(String path, HttpHandler handler) {
        server.createContext(path, handler);
        return this;
    }

    /**
     * Starts answering.
     *
     * @return this server
     */
    public LocalHttpServer start() {
        server.start();
        return this;
    }

    /**
     * A path's address on this server.
     *
     * @param path the path, such as {@code /ok.png}
     * @return its address
     */
    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    /**
     * Answers an exchange with {@code body}.
     *
     * @param exchange the exchange
     * @param status the status
     * @param body the body
     * @param length the length to declare: 0 for chunked, -1 for no body
     * @throws IOException when the answer cannot be sent
     */
    public static void send(HttpExchange exchange, int status, byte[] body, long length)
            throws IOException {
        exchange.sendResponseHeaders(status, length);
        try (OutputStream out = exchange.getResponseBody()) {
            if (length != -1) out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
