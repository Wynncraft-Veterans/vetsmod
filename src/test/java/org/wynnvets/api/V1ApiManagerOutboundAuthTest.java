package org.wynnvets.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wynnvets.fetcher.polling.StaffRanksPoller;
import org.wynnvets.util.Json;

/**
 * Pins when the outbound socket authenticates and where its ack goes: only after a
 * {@code server_info} offering the all-tiers map AND an inbound ack confirming a member tier,
 * never with {@code expectingAuthAck} set, and its untyped ack goes to {@link OutboundAckRouter}
 * without touching the outbound listeners or {@link SessionAuthState}.
 *
 * <p>A recording socket stands in for the outbound connection, as in
 * {@code V1ApiManagerFramesTest}. No test reads the stored key ({@code VetsConfig}): the sends
 * go through {@link V1ApiManager#sendOutboundAuth(String)}, and every frame routed through
 * {@link V1ApiManager#handleOutboundFrame(JsonObject)} leaves at least one of the two flags
 * unset, so the key is never read. No test routes a transient error ack, whose retry needs the
 * Wynntils tick scheduler.</p>
 */
class V1ApiManagerOutboundAuthTest {

    private static final String KEY = "k".repeat(43);

    /** A socket that never connects: it records frames and reports {@link #connected}. */
    private static final class RecordingWsClient extends WsClient {
        final List<String> wire = new ArrayList<>();
        volatile boolean connected = true;

        RecordingWsClient() {
            super(URI.create("wss://wynnvets.org/v1/outbound"), "test", json -> {});
        }

        @Override
        public CompletableFuture<Boolean> sendAsync(JsonObject json) {
            wire.add(Json.GSON.toJson(json));
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public boolean isConnected() {
            return connected;
        }
    }

    private final RecordingWsClient socket = new RecordingWsClient();
    private final AtomicInteger authenticated = new AtomicInteger();
    private final Runnable realOnAuthenticated = OutboundAckRouter.onAuthenticated;

    @BeforeEach
    void stubTheStaffRefresh() {
        // The real hook clears the staff overlay and starts an HTTP fetch.
        OutboundAckRouter.onAuthenticated = authenticated::incrementAndGet;
    }

    @AfterEach
    void reset() {
        V1ApiManager.disconnect();
        SessionAuthState.expectingAuthAck = false;
        SessionAuthState.confirmedStaff = false;
        OutboundAckRouter.onAuthenticated = realOnAuthenticated;
    }

    private void ready(boolean offered, boolean memberTier) {
        V1ApiManager.outboundClient = socket;
        V1ApiManager.outboundAuthOffered = offered;
        V1ApiManager.inboundMemberTier = memberTier;
    }

    private static JsonObject frame(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ----- when the outbound auth frame is sent -----

    @Test
    void sendsTheAuthFrameWhenOfferedAndAMemberTier() {
        ready(true, true);
        V1ApiManager.sendOutboundAuth(KEY);
        assertEquals(List.of("{\"type\":\"auth\",\"key\":\"" + KEY + "\"}"), socket.wire);
        assertFalse(SessionAuthState.expectingAuthAck, "the outbound ack is not the inbound one");
    }

    @Test
    void sendsNothingWithoutTheAllTiersOffer() {
        ready(false, true);
        V1ApiManager.sendOutboundAuth(KEY);
        assertEquals(List.of(), socket.wire);
    }

    @Test
    void sendsNothingForANonMemberTier() {
        ready(true, false);
        V1ApiManager.sendOutboundAuth(KEY);
        assertEquals(List.of(), socket.wire);
    }

    @Test
    void sendsNothingWithoutAKeyOrASocket() {
        ready(true, true);
        V1ApiManager.sendOutboundAuth("");
        V1ApiManager.sendOutboundAuth(null);
        socket.connected = false;
        V1ApiManager.sendOutboundAuth(KEY);
        assertEquals(List.of(), socket.wire);
    }

    @Test
    void memberTiersAreMemberWaitlistAndHonourary() {
        assertTrue(V1ApiManager.isMemberTier("member"));
        assertTrue(V1ApiManager.isMemberTier("waitlist"));
        assertTrue(V1ApiManager.isMemberTier("honourary"));
        assertFalse(V1ApiManager.isMemberTier("other"));
        assertFalse(V1ApiManager.isMemberTier("guild"), "a ws_tier, not a tier");
        assertFalse(V1ApiManager.isMemberTier(""));
        assertFalse(V1ApiManager.isMemberTier(null));
    }

    @Test
    void anInboundAuthOkRecordsTheTierAndAFailureClearsIt() {
        ready(false, false);
        V1ApiManager.onInboundAuthOk("waitlist");
        assertTrue(V1ApiManager.inboundMemberTier);
        V1ApiManager.onInboundAuthOk("other");
        assertFalse(V1ApiManager.inboundMemberTier);
        V1ApiManager.onInboundAuthOk("member");
        V1ApiManager.onInboundAuthFailed();
        assertFalse(V1ApiManager.inboundMemberTier);
        assertEquals(List.of(), socket.wire, "never offered, so nothing is sent");
    }

    @Test
    void connectStateIsClearedByDisconnect() {
        ready(true, true);
        V1ApiManager.disconnect();
        assertFalse(V1ApiManager.outboundAuthOffered);
        assertFalse(V1ApiManager.inboundMemberTier);
    }

    // ----- server_info -----

    @Test
    void serverInfoOffersTheMapOnlyForChatTiersAll() {
        assertTrue(
                V1ApiManager.offersAllChatTiers(
                        frame("{\"type\":\"server_info\",\"chat_tiers\":\"all\"}")));
        assertFalse(V1ApiManager.offersAllChatTiers(frame("{\"type\":\"server_info\"}")));
        assertFalse(V1ApiManager.offersAllChatTiers(frame("{\"chat_tiers\":\"tiered\"}")));
        assertFalse(V1ApiManager.offersAllChatTiers(frame("{\"chat_tiers\":null}")));
    }

    @Test
    void aServerInfoHelloRecordsTheOffer() {
        ready(false, false);
        V1ApiManager.handleOutboundFrame(
                frame("{\"type\":\"server_info\",\"unauth_enabled\":true,\"chat_tiers\":\"all\"}"));
        assertTrue(V1ApiManager.outboundAuthOffered);
        V1ApiManager.handleOutboundFrame(
                frame("{\"type\":\"server_info\",\"unauth_enabled\":true}"));
        assertFalse(V1ApiManager.outboundAuthOffered, "an older server withdraws the offer");
        assertEquals(List.of(), socket.wire, "no member tier yet, so nothing is sent");
    }

    // ----- the outbound ack -----

    @Test
    void anUntypedAckGoesToTheOutboundRouterNotTheListenersOrTheInboundState() {
        ready(true, false);
        AtomicInteger heard = new AtomicInteger();
        Consumer<JsonObject> listener = json -> heard.incrementAndGet();
        V1ApiManager.addOutboundListener(listener);
        try {
            V1ApiManager.handleOutboundFrame(
                    frame(
                            "{\"status\":\"ok\",\"tier\":\"member\",\"ws_tier\":\"guild\","
                                    + "\"is_staff\":true,\"staff_rank\":\"chief\"}"));
            V1ApiManager.handleOutboundFrame(
                    frame("{\"status\":\"error\",\"detail\":\"auth rejected: revoked\"}"));
        } finally {
            V1ApiManager.removeOutboundListener(listener);
        }
        assertEquals(0, heard.get());
        assertFalse(SessionAuthState.confirmedStaff, "only the inbound ack confirms staff");
        assertFalse(SessionAuthState.expectingAuthAck);
        assertFalse(OutboundAckRouter.retryUsed(), "a verdict on the key is not retried");
        assertEquals(1, authenticated.get(), "the ok ack, not the error, resets the overlay");
    }

    // ----- staff presence -----

    @Test
    void aNamelessStaffOfflineReachesThePollerByUuid() {
        String uuid = "bb171c68-7080-4d02-be7b-cc2d5dcbcd82";
        V1ApiManager.handleOutboundFrame(
                frame(
                        "{\"type\":\"staff_online\",\"uuid\":\""
                                + uuid
                                + "\",\"username\":\"Metrafish\",\"rank\":\"chief\"}"));
        assertEquals(
                java.util.Optional.of("chief"), StaffRanksPoller.confirmedRankFor("Metrafish"));
        V1ApiManager.handleOutboundFrame(
                frame("{\"type\":\"staff_offline\",\"uuid\":\"" + uuid + "\",\"username\":null}"));
        assertEquals(java.util.Optional.empty(), StaffRanksPoller.confirmedRankFor("Metrafish"));
    }

    @Test
    void disconnectClearsTheStaffOverlay() {
        StaffRanksPoller.applyLiveStaffEvent("Metrafish", null, "chief", true);
        V1ApiManager.disconnect();
        assertEquals(java.util.Optional.empty(), StaffRanksPoller.confirmedRankFor("Metrafish"));
    }

    @Test
    void aChatFrameStillReachesTheListeners() {
        AtomicInteger heard = new AtomicInteger();
        Consumer<JsonObject> listener = json -> heard.incrementAndGet();
        V1ApiManager.addOutboundListener(listener);
        try {
            V1ApiManager.handleOutboundFrame(
                    frame("{\"type\":\"waitlist\",\"username\":\"A\",\"message\":\"hi\"}"));
        } finally {
            V1ApiManager.removeOutboundListener(listener);
        }
        assertEquals(1, heard.get());
    }

    @Test
    void onlyAnIntrospectionFailureIsTransient() {
        assertTrue(
                OutboundAckRouter.isTransient(
                        "auth rejected: introspection unavailable: timed out"));
        assertFalse(OutboundAckRouter.isTransient("auth rejected: revoked"));
        assertFalse(OutboundAckRouter.isTransient(null));
    }

    @Test
    void theRetryIsDroppedAfterAReset() {
        AtomicInteger ran = new AtomicInteger();
        Runnable kept =
                OutboundAckRouter.fenced(OutboundAckRouter.generation(), ran::incrementAndGet);
        kept.run();
        Runnable dropped =
                OutboundAckRouter.fenced(OutboundAckRouter.generation(), ran::incrementAndGet);
        OutboundAckRouter.reset();
        dropped.run();
        assertEquals(1, ran.get());
    }
}
