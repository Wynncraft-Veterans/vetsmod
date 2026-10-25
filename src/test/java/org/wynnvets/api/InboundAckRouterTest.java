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
 * Tests for {@link InboundAckRouter#route}'s non-auth routing: which acks pop the head of
 * {@link V1ApiManager}'s staff-action callback queue, in what order, and which leave it alone.
 *
 * <p>The auth-ok and auth-failure branches are not driven: they reach {@code VetsConfig}, whose
 * class initialisation fails outside a game launch. So the orphan rule's {@code !wasAuthAck} and
 * non-empty-queue terms are not pinned here: their discriminating inputs lead into the
 * auth-failure branch, or, for a plain error with an empty queue, change only what is logged. A
 * throwing callback is not driven either, since every callback here only records. Every test
 * starts and ends with no auth ack awaited, not confirmed staff, and an empty callback queue.</p>
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
        V1ApiManager.staffActionCallbacks.clear();
    }

    /** A callback that records which callback got which ack. It never throws. */
    private Consumer<JsonObject> callback(String name) {
        return ack -> calls.add(name + " " + Json.GSON.toJson(ack));
    }

    private static JsonObject ack(String json) {
        return Json.GSON.fromJson(json, JsonObject.class);
    }

    // ----- staff-action-shaped acks pop the head callback -----

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
            V1ApiManager.staffActionCallbacks.addLast(callback("head"));
            InboundAckRouter.route(ack(shape));
            assertEquals(List.of("head " + shape), calls, shape);
            assertTrue(V1ApiManager.staffActionCallbacks.isEmpty(), shape);
        }
    }

    @Test
    void twoPendingCallbacksResolveInSendOrder() {
        V1ApiManager.staffActionCallbacks.addLast(callback("first"));
        V1ApiManager.staffActionCallbacks.addLast(callback("second"));
        InboundAckRouter.route(ack("{\"status\":\"ok\",\"total_points\":1}"));
        InboundAckRouter.route(ack("{\"status\":\"ok\",\"total_points\":2}"));
        assertEquals(
                List.of(
                        "first {\"status\":\"ok\",\"total_points\":1}",
                        "second {\"status\":\"ok\",\"total_points\":2}"),
                calls);
    }

    // ----- error acks -----

    @Test
    void anOrphanErrorPopsThePendingCallback() {
        V1ApiManager.staffActionCallbacks.addLast(callback("head"));
        InboundAckRouter.route(ack("{\"status\":\"error\",\"detail\":\"no such player\"}"));
        assertEquals(List.of("head {\"status\":\"error\",\"detail\":\"no such player\"}"), calls);
        assertTrue(V1ApiManager.staffActionCallbacks.isEmpty());
    }

    @Test
    void anAuthRejectedErrorGoesToAPendingCallbackWhenNoAuthAckIsAwaited() {
        // The orphan rule is tested before the auth-failure prefixes, so a pending staff-action
        // callback takes even an "auth rejected" error when no auth ack is outstanding, and the
        // confirmed staff state is left as it was.
        SessionAuthState.confirmedStaff = true;
        SessionAuthState.confirmedStaffRank = "chief";
        V1ApiManager.staffActionCallbacks.addLast(callback("head"));
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

    // ----- acks that leave a pending callback queued -----

    @Test
    void aTierKeyKeepsAWouldTriggerAckOutOfTheQueue() {
        // The tier key marks an auth ack. Its status is neither ok (an auth success) nor error
        // (the orphan rule would claim it), so it ends in the plain-error warn, not the queue.
        V1ApiManager.staffActionCallbacks.addLast(callback("head"));
        InboundAckRouter.route(ack("{\"status\":\"would_trigger\",\"tier\":\"member\"}"));
        assertTrue(calls.isEmpty());
        assertEquals(1, V1ApiManager.staffActionCallbacks.size());
    }

    @Test
    void aFrameWithoutStatusIsIgnored() {
        V1ApiManager.staffActionCallbacks.addLast(callback("head"));
        InboundAckRouter.route(ack("{\"kind\":\"caution\"}"));
        assertTrue(calls.isEmpty());
        assertEquals(1, V1ApiManager.staffActionCallbacks.size());
    }

    @Test
    void aPlainOkAckLeavesAPendingCallbackQueued() {
        V1ApiManager.staffActionCallbacks.addLast(callback("head"));
        InboundAckRouter.route(ack("{\"status\":\"ok\"}"));
        assertTrue(calls.isEmpty());
        assertEquals(1, V1ApiManager.staffActionCallbacks.size());
    }
}
