package org.wynnvets.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link WsClient}'s loss hook: which socket events report a lost socket, and with which
 * {@link WsClient#generation()}. Stub sockets are installed through
 * {@link WsClient#installSocket}, the success branch of a real handshake, so nothing connects.
 * The client points at a closed local port and is closed after each test, which cancels any
 * reconnect a test schedules before it can run.
 */
class WsClientLossTest {

    /** A socket that does nothing and records whether it was aborted; its input reports closed
     *  only when a test says so. */
    private static final class StubSocket implements WebSocket {
        boolean aborted;
        boolean inputClosed;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {}

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return inputClosed;
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }

    private WsClient client;
    private final List<Long> lost = new ArrayList<>();
    private int connects;

    @BeforeEach
    void makeClient() {
        client = new WsClient(URI.create("ws://127.0.0.1:1/"), "test", json -> {});
        client.setOnLostCallback(lost::add);
        client.setOnConnectCallback(() -> connects++);
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void eachInstalledSocketGetsTheNextGeneration() {
        assertEquals(0, client.generation());
        client.installSocket(new StubSocket());
        assertEquals(1, client.generation());
        assertTrue(client.isConnected());
    }

    @Test
    void theCurrentSocketClosingIsReportedOnceWithItsGeneration() {
        StubSocket socket = new StubSocket();
        client.installSocket(socket);
        client.onClose(socket, WebSocket.NORMAL_CLOSURE, "bye");
        client.onClose(socket, WebSocket.NORMAL_CLOSURE, "bye again");
        assertEquals(List.of(1L), lost);
        assertFalse(client.isConnected());
        assertEquals(1, client.reconnectsScheduled(), "one reconnect for the one loss");
    }

    @Test
    void theCurrentSocketFailingIsReportedWithItsGeneration() {
        StubSocket socket = new StubSocket();
        client.installSocket(socket);
        client.onError(socket, new RuntimeException("boom"));
        assertEquals(List.of(1L), lost);
        assertTrue(socket.aborted);
    }

    @Test
    void aReplacedSocketIsAbortedAndReportedAndItsLateCloseIsNot() {
        // Before, the late close of a replaced socket also scheduled a reconnect, whose install
        // aborted the healthy socket.
        StubSocket first = new StubSocket();
        StubSocket second = new StubSocket();
        client.installSocket(first);
        client.installSocket(second);
        assertTrue(first.aborted);
        assertEquals(List.of(1L), lost);
        client.onClose(first, WebSocket.NORMAL_CLOSURE, "late");
        client.onError(first, new RuntimeException("late"));
        assertEquals(List.of(1L), lost, "a stale socket reports nothing");
        assertTrue(client.isConnected(), "the healthy socket is still installed");
        assertEquals(2, client.generation());
        assertEquals(0, client.reconnectsScheduled(), "no second connection");
    }

    @Test
    void aSocketThatClosesBeforeItIsInstalledIsNotInstalled() {
        // The JDK can deliver onClose before the handshake's future completes. The close then
        // finds no current socket, so it schedules no reconnect; the install must not then
        // install the dead socket for the rest of the session.
        StubSocket socket = new StubSocket();
        client.onClose(socket, 1012, "service restart");
        assertEquals(0, client.reconnectsScheduled(), "the early close cannot reconnect");
        client.installSocket(socket);
        assertFalse(client.isConnected());
        assertTrue(socket.aborted);
        assertEquals(0, client.generation());
        assertEquals(List.of(0L), lost, "what its frames set is cleared; it held no acks");
        assertEquals(1, client.reconnectsScheduled(), "the install schedules the reconnect");
        assertEquals(0, connects, "nor runs the on-connect callback");
    }

    @Test
    void aDeadSocketBesideALiveOneLeavesTheLiveOneAlone() {
        StubSocket live = new StubSocket();
        client.installSocket(live);
        StubSocket dead = new StubSocket();
        dead.inputClosed = true;
        client.installSocket(dead);
        assertTrue(client.isConnected(), "the live socket stays installed");
        assertEquals(List.of(), lost, "and is not reported lost");
        assertEquals(1, client.generation());
    }

    @Test
    void aSocketWhoseInputAlreadyClosedIsNotInstalled() {
        StubSocket socket = new StubSocket();
        socket.inputClosed = true;
        client.installSocket(socket);
        assertFalse(client.isConnected());
        assertTrue(socket.aborted);
        assertEquals(1, client.reconnectsScheduled());
        assertEquals(0, connects);
    }

    @Test
    void closingTheClientReportsNothing() {
        StubSocket socket = new StubSocket();
        client.installSocket(socket);
        client.close();
        client.onClose(socket, WebSocket.NORMAL_CLOSURE, "after close");
        assertEquals(List.of(), lost);
    }
}
