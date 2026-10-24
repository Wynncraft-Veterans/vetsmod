package org.wynnvets.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wynnvets.util.Json;

/**
 * Tests for {@link InboundAckRouter#route}'s non-auth routing: which acks answer a pending
 * staff-action callback in {@link V1ApiManager#pendingAcks}, which callback, and which leave the
 * pending callbacks alone. An ack that echoes a request id goes to that id's callback only; the
 * eldest-first pairing for a server that does not echo ids (legacy mode) is pinned by the tests
 * that leave both echo flags false.
 *
 * <p>The auth-ok and auth-failure branches are not driven: they reach {@code VetsConfig}, whose
 * class initialisation fails outside a game launch. So the orphan rule's {@code !wasAuthAck} and
 * non-empty-queue terms are not pinned here: their discriminating inputs lead into the
 * auth-failure branch, or, for a plain error with nothing pending, change only what is logged. A
 * throwing callback is not driven either, since every callback here only records. Every test
 * starts and ends with no auth ack awaited, not confirmed staff, no pending callback and both echo
 * flags false (legacy mode).</p>
 */
class InboundAckRouterTest {

    private static final String TARGET = "0a1b2c3d-1111-2222-3333-444455556666";

    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void reset() {
        SessionAuthState.expectingAuthAck = false;
        SessionAuthState.confirmedStaff = false;
        SessionAuthState.confirmedStaffRank = "";
        V1ApiManager.pendingAcks.drainAll();
        V1ApiManager.helloEchoesReq = false;
        V1ApiManager.ackEchoedReq = false;
    }

    /** A callback that records which callback got which ack. It never throws. */
    private Consumer<JsonObject> callback(String name) {
        return ack -> calls.add(name + " " + Json.GSON.toJson(ack));
    }

    /** Registers a recording callback named {@code name}; returns its request id. */
    private String pending(String name) {
        return V1ApiManager.pendingAcks.register(callback(name), 1, PendingAcks.Kind.READ);
    }

    private static JsonObject ack(String json) {
        return Json.GSON.fromJson(json, JsonObject.class);
    }

    // ----- legacy mode: staff-action-shaped acks answer the eldest callback -----

    @Test
    void eachStaffActionShapeRoutesToTheHeadCallback() {
        String[] shapes = {
            "{\"status\":\"ok\",\"kind\":\"caution\"}",
            "{\"status\":\"ok\",\"triggered\":false}",
            "{\"status\":\"ok\",\"total_points\":3}",
            "{\"status\":\"ok\",\"target_uuid\":\"" + TARGET + "\"}",
            "{\"status\":\"would_trigger\"}"
        };
        for (String shape : shapes) {
            calls.clear();
            pending("head");
            InboundAckRouter.route(ack(shape));
            assertEquals(List.of("head " + shape), calls, shape);
            assertTrue(V1ApiManager.pendingAcks.isEmpty(), shape);
        }
    }

    @Test
    void twoPendingCallbacksResolveInSendOrder() {
        pending("first");
        pending("second");
        InboundAckRouter.route(ack("{\"status\":\"ok\",\"total_points\":1}"));
        InboundAckRouter.route(ack("{\"status\":\"ok\",\"total_points\":2}"));
        assertEquals(
                List.of(
                        "first {\"status\":\"ok\",\"total_points\":1}",
                        "second {\"status\":\"ok\",\"total_points\":2}"),
                calls);
    }

    // ----- legacy mode: error acks -----

    @Test
    void anOrphanErrorPopsThePendingCallback() {
        pending("head");
        InboundAckRouter.route(ack("{\"status\":\"error\",\"detail\":\"no such player\"}"));
        assertEquals(List.of("head {\"status\":\"error\",\"detail\":\"no such player\"}"), calls);
        assertTrue(V1ApiManager.pendingAcks.isEmpty());
    }

    @Test
    void anAuthRejectedErrorGoesToAPendingCallbackWhenNoAuthAckIsAwaited() {
        // The orphan rule is tested before the auth-failure prefixes, so a pending staff-action
        // callback takes even an "auth rejected" error when no auth ack is outstanding, and the
        // confirmed staff state is left as it was.
        SessionAuthState.confirmedStaff = true;
        SessionAuthState.confirmedStaffRank = "chief";
        pending("head");
        InboundAckRouter.route(ack("{\"status\":\"error\",\"detail\":\"auth rejected: x\"}"));
        assertEquals(List.of("head {\"status\":\"error\",\"detail\":\"auth rejected: x\"}"), calls);
        assertTrue(SessionAuthState.confirmedStaff);
        assertEquals("chief", SessionAuthState.confirmedStaffRank);
    }

    @Test
    void aStaffShapedErrorWithAnEmptyQueueIsSwallowed() {
        // Losing the swallow's return would carry this auth-prefixed error on into the auth-failure
        // branch, which cannot load here, so not throwing is what pins it.
        JsonObject error =
                ack(
                        "{\"status\":\"error\",\"detail\":\"auth rejected: x\",\"target_uuid\":\""
                                + TARGET
                                + "\"}");
        assertDoesNotThrow(() -> InboundAckRouter.route(error));
    }

    @Test
    void aPlainErrorWithNothingPendingIsNotAnAuthFailure() {
        // The auth-failure branch cannot load here, so not throwing pins that this error stays out
        // of it; whether it is then logged or swallowed is not visible.
        SessionAuthState.confirmedStaff = true;
        SessionAuthState.confirmedStaffRank = "chief";
        assertDoesNotThrow(
                () ->
                        InboundAckRouter.route(
                                ack("{\"status\":\"error\",\"detail\":\"rate limited\"}")));
        assertTrue(SessionAuthState.confirmedStaff);
        assertEquals("chief", SessionAuthState.confirmedStaffRank);
    }

    // ----- legacy mode: acks that leave a pending callback queued -----

    @Test
    void aTierKeyKeepsAWouldTriggerAckOutOfTheQueue() {
        // The tier key marks an auth ack. Its status is neither ok (an auth success) nor error
        // (the orphan rule would claim it), so it ends in the plain-error warn, not the queue.
        pending("head");
        InboundAckRouter.route(ack("{\"status\":\"would_trigger\",\"tier\":\"member\"}"));
        assertTrue(calls.isEmpty());
        assertEquals(1, V1ApiManager.pendingAcks.size());
    }

    @Test
    void aFrameWithoutStatusIsIgnored() {
        pending("head");
        InboundAckRouter.route(ack("{\"kind\":\"caution\"}"));
        assertTrue(calls.isEmpty());
        assertEquals(1, V1ApiManager.pendingAcks.size());
    }

    @Test
    void aPlainOkAckLeavesAPendingCallbackQueued() {
        pending("head");
        InboundAckRouter.route(ack("{\"status\":\"ok\"}"));
        assertTrue(calls.isEmpty());
        assertEquals(1, V1ApiManager.pendingAcks.size());
    }

    // ----- echoed request ids -----

    @Test
    void anErrorCarryingAReqAnswersOnlyThatCallback() {
        // The bug: without ids an error ack to some other frame went to the eldest callback.
        V1ApiManager.helloEchoesReq = true;
        pending("first");
        String second = pending("second");
        String error =
                "{\"status\":\"error\",\"detail\":\"no such player\",\"req\":\"" + second + "\"}";
        InboundAckRouter.route(ack(error));
        assertEquals(List.of("second " + error), calls);
        assertEquals(1, V1ApiManager.pendingAcks.size(), "first is still waiting");
    }

    @Test
    void anAckCarryingAReqIsMatchedByIdEvenWithoutTheHelloFlag() {
        String head = pending("head");
        String reply = "{\"status\":\"ok\",\"total_points\":3,\"req\":\"" + head + "\"}";
        InboundAckRouter.route(ack(reply));
        assertEquals(List.of("head " + reply), calls);
        assertTrue(V1ApiManager.ackEchoedReq, "an echoed id proves the server echoes");
        assertTrue(V1ApiManager.serverEchoesReq());
    }

    @Test
    void anAckForAnUnknownReqIsDroppedWithoutTouchingThePendingCallback() {
        pending("head");
        InboundAckRouter.route(ack("{\"status\":\"ok\",\"total_points\":3,\"req\":\"zzzzzzzz\"}"));
        InboundAckRouter.route(ack("{\"status\":\"error\",\"detail\":\"x\",\"req\":\"zzzzzzzz\"}"));
        assertTrue(calls.isEmpty());
        assertEquals(1, V1ApiManager.pendingAcks.size());
    }

    @Test
    void aReqlessErrorPopsNothingOnceTheServerEchoesIds() {
        // A guild forward refused while inbound processing is disabled: its error carries no req,
        // so it must not reach the staff command waiting for its own ack.
        V1ApiManager.helloEchoesReq = true;
        pending("head");
        JsonObject refused =
                ack("{\"status\":\"error\",\"detail\":\"Inbound processing is disabled\"}");
        assertDoesNotThrow(() -> InboundAckRouter.route(refused));
        assertTrue(calls.isEmpty());
        assertEquals(1, V1ApiManager.pendingAcks.size());
    }

    @Test
    void aReqlessStaffShapedAckIsDroppedOnceTheServerEchoesIds() {
        V1ApiManager.helloEchoesReq = true;
        pending("head");
        InboundAckRouter.route(ack("{\"status\":\"ok\",\"total_points\":3}"));
        assertTrue(calls.isEmpty());
        assertEquals(1, V1ApiManager.pendingAcks.size());
    }

    @Test
    void anEchoedAckIsAnsweredOnce() {
        String head = pending("head");
        String reply = "{\"status\":\"ok\",\"total_points\":3,\"req\":\"" + head + "\"}";
        InboundAckRouter.route(ack(reply));
        InboundAckRouter.route(ack(reply));
        assertEquals(List.of("head " + reply), calls, "the second copy finds no callback");
    }
}
