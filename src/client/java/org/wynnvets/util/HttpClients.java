package org.wynnvets.util;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The mod's shared HTTP transport.
 *
 * <p>Eighteen classes each built a character-identical chain &mdash; {@code HTTP_1_1}, a 5 s
 * connect timeout, no other customisation of any kind &mdash; and each held the result in its
 * own {@code private static final HttpClient HTTP_CLIENT}. They all call {@link #standard()}
 * instead. The field stays where it is at all eighteen; only its initialiser changes, so no
 * call site and no import moves.</p>
 *
 * <p><b>Never call {@code close()}, {@code shutdown()} or {@code shutdownNow()} on the client
 * this hands back, and never use it in a try-with-resources.</b> Java 21 made
 * {@link java.net.http.HttpClient HttpClient} an {@link AutoCloseable}: {@code close()} blocks
 * until every in-flight operation finishes and then permanently disables the client. One
 * try-with-resources anywhere would therefore take down the HTTP of all eighteen subsystems
 * that share it, for the rest of the session, and nothing in the type system objects. No
 * reviewer will be looking for this.</p>
 *
 * <p><b>One failure domain.</b> All eighteen callers depend on this class initialising. If the
 * default {@code SSLContext} fails to resolve, the first class to touch this one gets an
 * {@link ExceptionInInitializerError} and the other seventeen get
 * {@code NoClassDefFoundError: Could not initialize class org.wynnvets.util.HttpClients},
 * which carries no trace of the original cause. Vanishingly unlikely &mdash; a default
 * {@code SSLContext} is part of every JRE this mod can run on &mdash; and accepted.</p>
 *
 * <p><b>What can honestly be asserted about sharing, and what cannot.</b>
 * {@link org.wynnvets.fetcher.lookup.PlayerLookup PlayerLookup} already shares one client
 * across five of its six providers (the sixth, {@code VetsSnapshotProvider}, goes over the
 * WebSocket), and its {@code runCascadeStep} uses {@code thenCompose} to launch provider N+1's
 * request from a completion thread of provider N's response, on that same client. That runs in production, so the default executor is demonstrably not
 * single-threaded and does tolerate a nested request. It does <b>not</b> prove the pool is
 * unbounded: {@code HttpClient.Builder.executor}'s javadoc guarantees only that a default
 * executor exists per client and says nothing about its sizing, so no argument of the form
 * "it is a cached pool, so blocking on it is fine" is admissible here.</p>
 *
 * <p><b>Two deliberate non-residents.</b>
 * {@link org.wynnvets.mwe.anni.zone.AnniZone AnniZone} and
 * {@link org.wynnvets.api.WsClient WsClient} keep clients of their own. Both use a 10 s
 * connect timeout and neither pins an HTTP version, so neither could adopt this chain without
 * a behaviour change. What they have in common with each other &mdash; those same two
 * properties &mdash; is coincidence rather than a shared requirement.
 * {@code WsClient}'s client is a WebSocket factory that
 * re-declares its 10 s on the WebSocket builder, where it governs the whole handshake;
 * {@code AnniZone}'s is a synchronous GET client whose 10 s comes from its own
 * {@code HTTP_TIMEOUT_SECONDS} and also drives its request timeout. Merging the two would put
 * guild-chat frame delivery behind a 60 s world-events poller on one executor, to save two
 * objects.</p>
 *
 * @see Json
 */
public final class HttpClients {

    private static final HttpClient STANDARD =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

    /**
     * The one client, configured exactly as the eighteen hand-built chains were:
     * {@code HTTP_1_1}, a 5 s connect timeout, and no redirect, proxy, executor,
     * authenticator, cookie-handler or SSL customisation whatsoever.
     *
     * <p>Every call returns the same instance, so the eighteen fields hold one selector
     * thread, one default executor and one connection pool between them.</p>
     */
    public static HttpClient standard() {
        return STANDARD;
    }

    private HttpClients() {}
}
