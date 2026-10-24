package org.wynnvets.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wynnvets.mwe.anni.network.AnniQueryClient;
import org.wynnvets.mwe.anni.network.AnniRsvpClient;
import org.wynnvets.mwe.anni.network.AnniScrollspotClient;
import org.wynnvets.mwe.anni.network.AnniWsSender;
import org.wynnvets.util.Json;

/**
 * Pins the JSON of the frame each send method writes on the v1 inbound socket, byte for byte as the
 * socket would serialise it, and what each send that checks the socket does when it is down. The
 * on-connect auth re-send is not reached: only {@code connect()} installs it. The anni senders
 * reach {@link V1ApiManager#sendInboundIfConnected(JsonObject)}, so its down case is pinned
 * through them. Each send is also pinned not to throw when a disconnect lands between its check
 * and its send, and a staff-action callback caught by one to be answered once. {@code sendAuth}
 * is also pinned to mark an auth ack as awaited when it sends, and not when the
 * socket is down.
 *
 * <p>A recording socket stands in for the inbound connection: it never connects, records each
 * frame as {@link WsClient#sendAsync(JsonObject)} would write it ({@link WsClient#toWire}), and
 * reports a settable connection state. The expected strings are literals. The frames that carry
 * a random {@code uuid} and a clock {@code timestamp} (inbound chat and {@code rank_change}) have
 * both values checked and then masked in place, so key order is still pinned, and a staff-action
 * frame's random {@code req} is checked and masked the same way. Every test ends with no inbound
 * socket installed, no auth ack awaited, the pending staff-action callbacks drained and the cached
 * registration cleared. No test registers a listener or routes an ack.</p>
 */
class V1ApiManagerFramesTest {

    /** A socket that never connects: it records frames and reports {@link #connected}. */
    private static final class RecordingWsClient extends WsClient {
        final List<String> wire = new ArrayList<>();
        volatile boolean connected = true;

        /** What each send reports: true as if handed to the socket, false as if it failed. */
        volatile boolean sends = true;

        /** The socket number {@link #generation()} reports. */
        volatile long generation = 1;

        @Override
        public long generation() {
            return generation;
        }

        RecordingWsClient() {
            super(URI.create("wss://wynnvets.org/v1/inbound"), "test", json -> {});
        }

        @Override
        public CompletableFuture<Boolean> sendAsync(JsonObject json) {
            wire.add(WsClient.toWire(json));
            return CompletableFuture.completedFuture(sends);
        }

        @Override
        public boolean isConnected() {
            return connected;
        }
    }

    private final RecordingWsClient socket = new RecordingWsClient();

    @AfterEach
    void reset() {
        V1ApiManager.disconnect();
        V1ApiManager.clearRegistration();
        SessionAuthState.expectingAuthAck = false;
    }

    private void connect() {
        V1ApiManager.inboundClient = socket;
    }

    // ----- connected: one test per send that checks the socket -----

    @Test
    void registration() {
        connect();
        V1ApiManager.sendRegistration("bb171c68-7080-4d02-be7b-cc2d5dcbcd82", "Metrafish", "guild");
        assertEquals(
                List.of(
                        "{\"type\":\"register\",\"uuid\":\"bb171c68-7080-4d02-be7b-cc2d5dcbcd82\","
                                + "\"username\":\"Metrafish\",\"tier\":\"guild\","
                                + "\"exact_text\":true}"),
                socket.wire);
    }

    @Test
    void auth() {
        connect();
        V1ApiManager.sendAuth("k3y-Value_123");
        assertEquals(List.of("{\"type\":\"auth\",\"key\":\"k3y-Value_123\"}"), socket.wire);
        // Sending marks the auth frame as awaiting its ack.
        assertTrue(SessionAuthState.expectingAuthAck);
    }

    @Test
    void queueStatus() {
        connect();
        V1ApiManager.sendQueueStatus(true, "NA30");
        V1ApiManager.sendQueueStatus(false, null);
        assertEquals(
                List.of(
                        "{\"type\":\"queue_status\",\"queued\":true,\"world\":\"NA30\"}",
                        "{\"type\":\"queue_status\",\"queued\":false,\"world\":\"\"}"),
                socket.wire);
    }

    @Test
    void inbound() {
        connect();
        long t0 = System.currentTimeMillis();
        V1ApiManager.sendInbound("guild", "Chief", "Metrafish", "hello there");
        V1ApiManager.sendInbound("waitlist", null, "Holidaze", "hi");
        long t1 = System.currentTimeMillis();
        assertEquals(
                List.of(
                        "{\"uuid\":\"U\",\"type\":\"guild\",\"timestamp\":0,\"rank\":\"Chief\","
                                + "\"username\":\"Metrafish\",\"message\":\"hello there\"}",
                        "{\"uuid\":\"U\",\"type\":\"waitlist\",\"timestamp\":0,\"rank\":\"\","
                                + "\"username\":\"Holidaze\",\"message\":\"hi\"}"),
                masked(t0, t1));
    }

    @Test
    void rankChange() {
        connect();
        long t0 = System.currentTimeMillis();
        V1ApiManager.sendRankChange("Metrafish", "Holidaze", "Captain", "Recruit", "mote");
        long t1 = System.currentTimeMillis();
        assertEquals(
                List.of(
                        "{\"type\":\"rank_change\",\"uuid\":\"U\",\"timestamp\":0,"
                                + "\"actor\":\"Metrafish\",\"target\":\"Holidaze\","
                                + "\"from_rank\":\"Captain\",\"to_rank\":\"Recruit\","
                                + "\"classification\":\"mote\"}"),
                masked(t0, t1));
    }

    @Test
    void tabList() {
        connect();
        V1ApiManager.sendTabList(
                List.of(
                        new V1ApiManager.TabListEntry("WC1", "Metrafish"),
                        new V1ApiManager.TabListEntry("EU4", "Holidaze")));
        assertEquals(
                List.of(
                        "{\"type\":\"tablist\",\"entries\":["
                                + "{\"server\":\"WC1\",\"username\":\"Metrafish\"},"
                                + "{\"server\":\"EU4\",\"username\":\"Holidaze\"}]}"),
                socket.wire);
    }

    @Test
    void staffActionFrame_isSentAndItsCallbackQueued() {
        connect();
        JsonObject fields = new JsonObject();
        fields.addProperty("target_username", "Holidaze");
        fields.addProperty("confirm", true);
        List<String> acks = new ArrayList<>();
        V1ApiManager.sendStaffActionFrame(
                "caution_check", fields, ack -> acks.add(Json.GSON.toJson(ack)));
        assertEquals(
                List.of(
                        "{\"type\":\"caution_check\","
                                + "\"target_username\":\"Holidaze\",\"confirm\":true,"
                                + "\"req\":\"R\"}"),
                reqMasked());
        assertTrue(acks.isEmpty());
        // The callback waits under the frame's req: disconnecting drains it with the synthetic ack.
        V1ApiManager.disconnect();
        assertEquals(
                List.of("{\"status\":\"error\",\"detail\":\"Connection closed before reply.\"}"),
                acks);
    }

    @Test
    void aStaffActionCallbackIsRegisteredUnderTheReqItsFrameCarries() {
        connect();
        V1ApiManager.sendStaffActionFrame("caution_check", new JsonObject(), ack -> {});
        String req =
                Json.GSON.fromJson(socket.wire.get(0), JsonObject.class).get("req").getAsString();
        assertEquals(1, V1ApiManager.pendingAcks.size());
        assertNotNull(V1ApiManager.pendingAcks.complete(req));
    }

    @Test
    void aStaffActionFrameWithoutACallbackStillCarriesAReqAndRegistersNothing() {
        // So its ack is dropped as unknown instead of answering some other caller.
        connect();
        V1ApiManager.sendStaffActionFrame("caution_check", new JsonObject(), null);
        assertEquals(List.of("{\"type\":\"caution_check\",\"req\":\"R\"}"), reqMasked());
        assertTrue(V1ApiManager.pendingAcks.isEmpty());
    }

    @Test
    void anniQuery() {
        connect();
        assertTrue(AnniWsSender.sendAnniQuery());
        assertEquals(List.of("{\"type\":\"anni_query\"}"), socket.wire);
    }

    @Test
    void anniScrollspotSet_setAndClear() {
        connect();
        assertTrue(AnniWsSender.sendAnniScrollspotSet(12, 64, -300));
        assertTrue(AnniWsSender.sendAnniScrollspotSet(null, null, null));
        // The clear frame's scroll_spot is a JsonNull, and WsClient's frame serialiser keeps null
        // members, so the key reaches the wire as null, as temporary-server's protocol §1.12
        // asks. (Json.GSON would drop it.)
        assertEquals(
                List.of(
                        "{\"type\":\"anni_scrollspot_set\","
                                + "\"scroll_spot\":{\"x\":12,\"y\":64,\"z\":-300}}",
                        "{\"type\":\"anni_scrollspot_set\",\"scroll_spot\":null}"),
                socket.wire);
    }

    @Test
    void anniRsvp() {
        connect();
        assertTrue(AnniWsSender.sendAnniRsvp("hard"));
        assertEquals(List.of("{\"type\":\"anni_rsvp\",\"notice\":\"hard\"}"), socket.wire);
    }

    @Test
    void anniPartyObservation() {
        connect();
        assertTrue(
                AnniWsSender.sendAnniPartyObservation(
                        Arrays.asList("Metrafish", null, "", "Holidaze"), "Metrafish", "WC1"));
        assertTrue(AnniWsSender.sendAnniPartyObservation(null, null, null));
        assertEquals(
                List.of(
                        "{\"type\":\"anni_party_observation\","
                                + "\"party_member_usernames\":[\"Metrafish\",\"Holidaze\"],"
                                + "\"leader_username\":\"Metrafish\",\"world\":\"WC1\"}",
                        "{\"type\":\"anni_party_observation\",\"party_member_usernames\":[],"
                                + "\"leader_username\":\"\",\"world\":\"\"}"),
                socket.wire);
    }

    // ----- disconnected -----

    @Test
    void noSocket_sendsNothing() {
        V1ApiManager.inboundClient = null;
        assertNothingSentWhileDown();
    }

    @Test
    void socketNotConnected_sendsNothing() {
        socket.connected = false;
        connect();
        assertNothingSentWhileDown();
    }

    // ----- a disconnect between a send's check and its send -----

    /** A socket whose connection check runs {@link V1ApiManager#disconnect()} first, so the static
     *  field is null by the time a sender would read it again. */
    private static final class DisconnectingWsClient extends WsClient {
        final List<String> wire = new ArrayList<>();

        DisconnectingWsClient() {
            super(URI.create("wss://wynnvets.org/v1/inbound"), "test", json -> {});
        }

        @Override
        public boolean isConnected() {
            V1ApiManager.disconnect();
            return true;
        }

        @Override
        public CompletableFuture<Boolean> sendAsync(JsonObject json) {
            wire.add(WsClient.toWire(json));
            return CompletableFuture.completedFuture(true);
        }
    }

    @Test
    void aDisconnectBetweenCheckAndSendThrowsNothing() {
        List<Runnable> sends =
                List.of(
                        () ->
                                V1ApiManager.sendRegistration(
                                        "bb171c68-7080-4d02-be7b-cc2d5dcbcd82",
                                        "Metrafish",
                                        "guild"),
                        () -> V1ApiManager.sendAuth("k3y-Value_123"),
                        () -> V1ApiManager.sendQueueStatus(true, "NA30"),
                        () -> V1ApiManager.sendInbound("guild", "Chief", "Metrafish", "hi"),
                        () -> V1ApiManager.sendRankChange("A", "B", "Recruit", "Captain", "mote"),
                        () -> V1ApiManager.sendTabList(List.of()),
                        () -> assertTrue(AnniWsSender.sendAnniQuery()));
        for (Runnable send : sends) {
            DisconnectingWsClient racing = new DisconnectingWsClient();
            V1ApiManager.inboundClient = racing;
            assertDoesNotThrow(send::run);
            assertEquals(1, racing.wire.size(), "the send used the client it checked");
            assertNull(V1ApiManager.inboundClient);
        }
    }

    @Test
    void aStaffActionWhoseFrameIsNotSentIsAnsweredOnceWithTheDrainError() {
        connect();
        socket.sends = false;
        List<String> acks = new ArrayList<>();
        V1ApiManager.sendStaffActionFrame(
                "caution_check", new JsonObject(), ack -> acks.add(Json.GSON.toJson(ack)));
        assertEquals(
                List.of("{\"status\":\"error\",\"detail\":\"Connection closed before reply.\"}"),
                acks);
        assertTrue(V1ApiManager.pendingAcks.isEmpty());
    }

    @Test
    void aStaffActionCaughtByADisconnectIsAnsweredOnceWithTheDrainError() {
        V1ApiManager.inboundClient = new DisconnectingWsClient();
        List<String> acks = new ArrayList<>();
        V1ApiManager.sendStaffActionFrame(
                "caution_check", new JsonObject(), ack -> acks.add(Json.GSON.toJson(ack)));
        assertEquals(
                List.of("{\"status\":\"error\",\"detail\":\"Connection closed before reply.\"}"),
                acks);
        assertTrue(V1ApiManager.pendingAcks.isEmpty());
    }

    // ----- a lost socket and a deadline -----

    private static final String LOST_ACK =
            "{\"status\":\"error\",\"detail\":\"Connection lost before reply.\","
                    + "\"outcome_unknown\":\"connection lost\"}";
    private static final String TIMEOUT_ACK =
            "{\"status\":\"error\",\"detail\":\"No reply from the vets server.\","
                    + "\"outcome_unknown\":\"no reply\"}";

    /** Sends a staff-action frame whose callback records its acks into {@code acks}; returns the
     *  frame's req. */
    private String sendRecorded(List<String> acks) {
        V1ApiManager.sendStaffActionFrame(
                "caution_add", new JsonObject(), ack -> acks.add(Json.GSON.toJson(ack)));
        String last = socket.wire.get(socket.wire.size() - 1);
        return Json.GSON.fromJson(last, JsonObject.class).get("req").getAsString();
    }

    @Test
    void aCallbackWhoseSocketIsLostIsAnsweredOnceWithOutcomeUnknown() {
        // The bug: a written frame whose socket then died was answered by nothing until the next
        // disconnect(), so its caller hung.
        connect();
        List<String> acks = new ArrayList<>();
        sendRecorded(acks);
        assertTrue(acks.isEmpty());
        V1ApiManager.onInboundSocketLost(1);
        V1ApiManager.onInboundSocketLost(1);
        assertEquals(List.of(LOST_ACK), acks);
        assertTrue(V1ApiManager.pendingAcks.isEmpty());
    }

    @Test
    void aCallbackSentOnTheNextSocketSurvivesTheOldOnesLoss() {
        connect();
        socket.generation = 2;
        List<String> acks = new ArrayList<>();
        sendRecorded(acks);
        V1ApiManager.onInboundSocketLost(1);
        assertTrue(acks.isEmpty());
        assertEquals(1, V1ApiManager.pendingAcks.size());
    }

    @Test
    void aLostSocketClearsTheEchoedAckFlagAndTheAwaitedAuthButNotTheHelloFlag() {
        V1ApiManager.helloEchoesReq = true;
        V1ApiManager.ackEchoedReq = true;
        SessionAuthState.expectingAuthAck = true;
        V1ApiManager.onInboundSocketLost(1);
        assertTrue(V1ApiManager.helloEchoesReq, "the next outbound hello rewrites it");
        assertFalse(V1ApiManager.ackEchoedReq);
        assertFalse(SessionAuthState.expectingAuthAck);
    }

    @Test
    void aDeadlineIsEnforcedOnlyOnceTheServerEchoesIds() {
        connect();
        List<String> acks = new ArrayList<>();
        String req = sendRecorded(acks);
        V1ApiManager.expire(req);
        assertTrue(acks.isEmpty(), "without ids a late ack would answer the next caller instead");
        assertEquals(1, V1ApiManager.pendingAcks.size());
        V1ApiManager.helloEchoesReq = true;
        V1ApiManager.expire(req);
        V1ApiManager.expire(req);
        assertEquals(List.of(TIMEOUT_ACK), acks);
        assertTrue(V1ApiManager.pendingAcks.isEmpty());
    }

    @Test
    void aCallbackSentInEchoModeKeepsItsDeadlineAfterTheFlagsDrop() {
        // A server rollback: the stale hello flag routed the frame, then dropped.
        connect();
        V1ApiManager.helloEchoesReq = true;
        List<String> acks = new ArrayList<>();
        String req = sendRecorded(acks);
        V1ApiManager.helloEchoesReq = false;
        V1ApiManager.expire(req);
        assertEquals(List.of(TIMEOUT_ACK), acks);
    }

    // ----- a relay with an ack callback -----

    private static final String DRAIN_ACK =
            "{\"status\":\"error\",\"detail\":\"Connection closed before reply.\"}";

    @Test
    void aRelayCarriesAReqAndRegistersItsCallbackOnlyOnceTheServerEchoesIds() {
        connect();
        V1ApiManager.helloEchoesReq = true;
        long t0 = System.currentTimeMillis();
        V1ApiManager.sendInbound("waitlist", "Waitlist", "Holidaze", "hi", ack -> {});
        long t1 = System.currentTimeMillis();
        JsonObject frame = Json.GSON.fromJson(socket.wire.get(0), JsonObject.class);
        assertTrue(frame.get("req").getAsString().matches("[A-Za-z0-9]{8}"));
        PendingAcks.Entry entry = V1ApiManager.pendingAcks.complete(frame.get("req").getAsString());
        assertEquals(PendingAcks.Kind.RELAY, entry.kind());
        socket.wire.set(0, socket.wire.get(0).replace(frame.get("req").getAsString(), "R"));
        assertEquals(
                List.of(
                        "{\"uuid\":\"U\",\"type\":\"waitlist\",\"timestamp\":0,"
                                + "\"rank\":\"Waitlist\",\"username\":\"Holidaze\","
                                + "\"message\":\"hi\",\"req\":\"R\"}"),
                masked(t0, t1));
    }

    @Test
    void againstAServerThatDoesNotEchoARelayCarriesNoReqAndHearsOnlyAFailedWrite() {
        // Such a server would broadcast an unknown key to every outbound client.
        connect();
        List<String> acks = new ArrayList<>();
        V1ApiManager.sendInbound("queue", "Chief", "Holidaze", "hi", ack -> acks.add("ok"));
        assertFalse(socket.wire.get(0).contains("\"req\""));
        assertTrue(acks.isEmpty());
        assertTrue(V1ApiManager.pendingAcks.isEmpty());
        socket.sends = false;
        List<String> failed = new ArrayList<>();
        V1ApiManager.sendInbound(
                "queue", "Chief", "Holidaze", "hi", ack -> failed.add(Json.GSON.toJson(ack)));
        assertEquals(List.of(DRAIN_ACK), failed);
    }

    @Test
    void aForwardWithoutACallbackNeverCarriesAReq() {
        connect();
        V1ApiManager.helloEchoesReq = true;
        V1ApiManager.sendInbound("guild", "Chief", "Metrafish", "hello there");
        assertFalse(socket.wire.get(0).contains("\"req\""));
    }

    @Test
    void aRelayWithTheSocketDownIsAnsweredAtOnce() {
        V1ApiManager.inboundClient = null;
        List<String> acks = new ArrayList<>();
        V1ApiManager.sendInbound(
                "honourary", "Honourary", "Holidaze", "hi", ack -> acks.add(Json.GSON.toJson(ack)));
        assertEquals(
                List.of("{\"status\":\"error\",\"detail\":\"Inbound WebSocket not connected.\"}"),
                acks);
    }

    @Test
    void aRelayWhoseWriteFailsIsAnsweredOnceAndADisconnectLeavesARelayQuiet() {
        connect();
        V1ApiManager.helloEchoesReq = true;
        socket.sends = false;
        List<String> failed = new ArrayList<>();
        V1ApiManager.sendInbound(
                "waitlist", "Waitlist", "Holidaze", "hi", ack -> failed.add(Json.GSON.toJson(ack)));
        assertEquals(List.of(DRAIN_ACK), failed);
        assertTrue(V1ApiManager.pendingAcks.isEmpty());

        socket.sends = true;
        List<String> drained = new ArrayList<>();
        V1ApiManager.sendInbound(
                "waitlist",
                "Waitlist",
                "Holidaze",
                "hi",
                ack -> drained.add(Json.GSON.toJson(ack)));
        V1ApiManager.disconnect();
        assertEquals(List.of(LOST_ACK), drained, "an echoed line may have been delivered");
    }

    // ----- unregister -----

    private static final String UUID_1 = "bb171c68-7080-4d02-be7b-cc2d5dcbcd82";
    private static final String UNREGISTER = "{\"type\":\"unregister\"}";

    /** Registers on the connected socket, then forgets that frame, so a test sees only the
     *  frames after it. */
    private void registeredAndConnected(boolean serverKnowsUnregister) {
        connect();
        V1ApiManager.serverSupportsUnregister = serverKnowsUnregister;
        V1ApiManager.sendRegistration(UUID_1, "Metrafish", "waitlist");
        socket.wire.clear();
    }

    @Test
    void unregister_withTheCapabilityAndAConnectionWritesTheFrameAndClearsTheCache() {
        registeredAndConnected(true);
        assertEquals("waitlist", V1ApiManager.registeredTier());
        V1ApiManager.sendUnregistration("left");
        assertEquals(List.of(UNREGISTER), socket.wire);
        assertNull(V1ApiManager.registeredTier());
    }

    @Test
    void unregister_withoutTheCapabilityWritesNothingButClearsTheCache() {
        registeredAndConnected(false);
        V1ApiManager.sendUnregistration("left");
        assertEquals(List.of(), socket.wire, "an old server would answer an unknown type");
        assertNull(V1ApiManager.registeredTier(), "so a reconnect does not re-register");
    }

    @Test
    void unregister_withNoCachedRegistrationWritesNothing() {
        connect();
        V1ApiManager.serverSupportsUnregister = true;
        V1ApiManager.sendUnregistration("left");
        assertEquals(List.of(), socket.wire);
        assertNull(V1ApiManager.registeredTier());
    }

    @Test
    void unregister_withTheSocketDownClearsTheCache() {
        registeredAndConnected(true);
        socket.connected = false;
        V1ApiManager.sendUnregistration("auth");
        assertEquals(List.of(), socket.wire);
        assertNull(V1ApiManager.registeredTier());
    }

    @Test
    void unregister_aSecondCallAfterTheCacheIsClearedWritesNothing() {
        registeredAndConnected(true);
        V1ApiManager.sendUnregistration("left");
        socket.wire.clear();
        V1ApiManager.sendUnregistration("joined");
        assertEquals(List.of(), socket.wire);
    }

    @Test
    void serverInfoAdvertisesUnregisterOnlyWithTheKeyTrue() {
        assertTrue(
                V1ApiManager.offersUnregister(
                        Json.GSON.fromJson(
                                "{\"type\":\"server_info\",\"unregister\":true}",
                                JsonObject.class)));
        for (String hello :
                List.of(
                        "{\"type\":\"server_info\"}",
                        "{\"unregister\":false}",
                        "{\"unregister\":null}",
                        "{\"unregister\":\"true\"}")) {
            assertFalse(
                    V1ApiManager.offersUnregister(Json.GSON.fromJson(hello, JsonObject.class)),
                    hello);
        }
    }

    @Test
    void aServerInfoHelloRecordsTheUnregisterCapabilityAndDisconnectClearsIt() {
        V1ApiManager.handleOutboundFrame(
                Json.GSON.fromJson(
                        "{\"type\":\"server_info\",\"unauth_enabled\":true,\"unregister\":true}",
                        JsonObject.class));
        assertTrue(V1ApiManager.serverSupportsUnregister);
        V1ApiManager.handleOutboundFrame(
                Json.GSON.fromJson(
                        "{\"type\":\"server_info\",\"unauth_enabled\":true}", JsonObject.class));
        assertFalse(V1ApiManager.serverSupportsUnregister, "an older server withdraws it");
        V1ApiManager.serverSupportsUnregister = true;
        V1ApiManager.disconnect();
        assertFalse(V1ApiManager.serverSupportsUnregister);
    }

    private void assertNothingSentWhileDown() {
        V1ApiManager.sendRegistration("bb171c68-7080-4d02-be7b-cc2d5dcbcd82", "Metrafish", "guild");
        SessionAuthState.expectingAuthAck = false;
        V1ApiManager.sendAuth("k3y-Value_123");
        // Nothing was sent, so no auth ack is awaited.
        assertFalse(SessionAuthState.expectingAuthAck);
        V1ApiManager.sendQueueStatus(true, "NA30");
        V1ApiManager.sendInbound("guild", "Chief", "Metrafish", "hello there");
        V1ApiManager.sendRankChange("Metrafish", "Holidaze", "Captain", "Recruit", "mote");
        V1ApiManager.sendTabList(List.of(new V1ApiManager.TabListEntry("WC1", "Metrafish")));
        assertFalse(AnniWsSender.sendAnniQuery());
        assertFalse(AnniWsSender.sendAnniScrollspotSet(12, 64, -300));
        assertFalse(AnniWsSender.sendAnniScrollspotSet(null, null, null));
        assertFalse(AnniWsSender.sendAnniRsvp("hard"));
        assertFalse(
                AnniWsSender.sendAnniPartyObservation(List.of("Metrafish"), "Metrafish", "WC1"));

        // A staff-action frame is answered at once, on the caller's thread, and nothing is queued.
        List<String> acks = new ArrayList<>();
        V1ApiManager.sendStaffActionFrame(
                "caution_check", new JsonObject(), ack -> acks.add(Json.GSON.toJson(ack)));
        assertEquals(
                List.of("{\"status\":\"error\",\"detail\":\"Inbound WebSocket not connected.\"}"),
                acks);
        assertDoesNotThrow(() -> V1ApiManager.sendStaffActionFrame("caution_check", null, null));

        // The anni clients complete their not-connected outcomes rather than waiting.
        CompletableFuture<?> query = AnniQueryClient.query();
        assertTrue(query.isDone());
        assertNull(query.join());
        assertEquals(
                new AnniRsvpClient.Ack(false, "not connected"),
                AnniRsvpClient.send("revoke").join());
        assertEquals(
                new AnniScrollspotClient.Ack(false, "not connected"),
                AnniScrollspotClient.set(12, 64, -300).join());
        assertEquals(
                new AnniScrollspotClient.Ack(false, "not connected"),
                AnniScrollspotClient.clear().join());

        // Nothing was queued: disconnecting drains no callback.
        V1ApiManager.disconnect();
        assertEquals(1, acks.size());
        assertTrue(socket.wire.isEmpty());
    }

    /**
     * The recorded frames with {@code req} replaced, in place, by {@code "R"}, after checking that
     * each is a request id: eight characters from {@code [A-Za-z0-9]}.
     */
    private List<String> reqMasked() {
        List<String> out = new ArrayList<>();
        for (String frame : socket.wire) {
            JsonObject json = Json.GSON.fromJson(frame, JsonObject.class);
            assertTrue(json.get("req").getAsString().matches("[A-Za-z0-9]{8}"), frame);
            json.addProperty("req", "R");
            out.add(Json.GSON.toJson(json));
        }
        return out;
    }

    /**
     * The recorded frames with {@code uuid} and {@code timestamp} replaced, in place, by
     * {@code "U"} and {@code 0}, after checking that each uuid is a canonical UUID and each
     * timestamp a number of seconds between {@code t0} and {@code t1} (milliseconds).
     */
    private List<String> masked(long t0, long t1) {
        List<String> out = new ArrayList<>();
        for (String frame : socket.wire) {
            JsonObject json = Json.GSON.fromJson(frame, JsonObject.class);
            String uuid = json.get("uuid").getAsString();
            assertEquals(uuid, UUID.fromString(uuid).toString());
            assertTrue(json.get("timestamp").getAsJsonPrimitive().isNumber());
            double seconds = json.get("timestamp").getAsDouble();
            assertTrue(seconds >= t0 / 1000.0 && seconds <= t1 / 1000.0, frame);
            // A double on the wire, not a whole number of seconds.
            assertEquals(Double.toString(seconds), json.get("timestamp").getAsString(), frame);
            json.addProperty("uuid", "U");
            json.addProperty("timestamp", 0);
            out.add(Json.GSON.toJson(json));
        }
        return out;
    }
}
