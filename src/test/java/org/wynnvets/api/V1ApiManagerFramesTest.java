package org.wynnvets.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * on-connect auth re-send is not reached: only {@code connect()} installs it.
 * {@link V1ApiManager#sendInboundUnchecked(JsonObject)} checks nothing, and the tests reach it only
 * through sends that check first, so what it does while the socket is down is not pinned.
 *
 * <p>A recording socket stands in for the inbound connection: it never connects, records each
 * frame as {@link WsClient#send(JsonObject)} would write it, and reports a settable connection
 * state. The expected strings are literals. The frames that carry a random {@code uuid} and a clock
 * {@code timestamp} (inbound chat and {@code rank_change}) have both values checked and then
 * masked in place, so key order is still pinned. Every test ends with no inbound socket installed,
 * the callback queue drained and the cached registration cleared. No test registers a listener or
 * routes an ack.</p>
 */
class V1ApiManagerFramesTest {

    /** A socket that never connects: it records frames and reports {@link #connected}. */
    private static final class RecordingWsClient extends WsClient {
        final List<String> wire = new ArrayList<>();
        volatile boolean connected = true;

        RecordingWsClient() {
            super(URI.create("wss://example.invalid/v1/inbound"), "test", json -> {});
        }

        @Override
        public void send(JsonObject json) {
            wire.add(Json.GSON.toJson(json));
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
        V1ApiManager.sendRegistration("0a1b2c3d-1111-2222-3333-444455556666", "Alice", "guild");
        assertEquals(
                List.of(
                        "{\"type\":\"register\",\"uuid\":\"0a1b2c3d-1111-2222-3333-444455556666\","
                                + "\"username\":\"Alice\",\"tier\":\"guild\"}"),
                socket.wire);
    }

    @Test
    void auth() {
        connect();
        V1ApiManager.sendAuth("k3y-Value_123");
        assertEquals(List.of("{\"type\":\"auth\",\"key\":\"k3y-Value_123\"}"), socket.wire);
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
        V1ApiManager.sendInbound("guild", "Chief", "Alice", "hello there");
        V1ApiManager.sendInbound("waitlist", null, "Bob", "hi");
        long t1 = System.currentTimeMillis();
        assertEquals(
                List.of(
                        "{\"uuid\":\"U\",\"type\":\"guild\",\"timestamp\":0,\"rank\":\"Chief\","
                                + "\"username\":\"Alice\",\"message\":\"hello there\"}",
                        "{\"uuid\":\"U\",\"type\":\"waitlist\",\"timestamp\":0,\"rank\":\"\","
                                + "\"username\":\"Bob\",\"message\":\"hi\"}"),
                masked(t0, t1));
    }

    @Test
    void rankChange() {
        connect();
        long t0 = System.currentTimeMillis();
        V1ApiManager.sendRankChange("Alice", "Bob", "Captain", "Recruit", "mote");
        long t1 = System.currentTimeMillis();
        assertEquals(
                List.of(
                        "{\"type\":\"rank_change\",\"uuid\":\"U\",\"timestamp\":0,"
                                + "\"actor\":\"Alice\",\"target\":\"Bob\","
                                + "\"from_rank\":\"Captain\",\"to_rank\":\"Recruit\","
                                + "\"classification\":\"mote\"}"),
                masked(t0, t1));
    }

    @Test
    void tabList() {
        connect();
        V1ApiManager.sendTabList(
                List.of(
                        new V1ApiManager.TabListEntry("WC1", "Alice"),
                        new V1ApiManager.TabListEntry("EU4", "Bob")));
        assertEquals(
                List.of(
                        "{\"type\":\"tablist\",\"entries\":["
                                + "{\"server\":\"WC1\",\"username\":\"Alice\"},"
                                + "{\"server\":\"EU4\",\"username\":\"Bob\"}]}"),
                socket.wire);
    }

    @Test
    void staffActionFrame_isSentAndItsCallbackQueued() {
        connect();
        JsonObject fields = new JsonObject();
        fields.addProperty("target_username", "Bob");
        fields.addProperty("confirm", true);
        List<String> acks = new ArrayList<>();
        V1ApiManager.sendStaffActionFrame(
                "caution_check", fields, ack -> acks.add(Json.GSON.toJson(ack)));
        assertEquals(
                List.of(
                        "{\"type\":\"caution_check\","
                                + "\"target_username\":\"Bob\",\"confirm\":true}"),
                socket.wire);
        assertTrue(acks.isEmpty());
        // The callback waits in the queue: disconnecting drains it with the synthetic ack.
        V1ApiManager.disconnect();
        assertEquals(
                List.of("{\"status\":\"error\",\"detail\":\"Connection closed before reply.\"}"),
                acks);
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
        // The clear frame's scroll_spot is a JsonNull, and Json.GSON drops null members when it
        // serialises, so the key never reaches the wire. temporary-server (at 4d47cba) reads a
        // missing scroll_spot as null, which clears the spot, though its v1_protocol.md §1.12
        // says a missing key is rejected (anni-scrollspot-clear-frame-omits-scroll-spot-key).
        assertEquals(
                List.of(
                        "{\"type\":\"anni_scrollspot_set\","
                                + "\"scroll_spot\":{\"x\":12,\"y\":64,\"z\":-300}}",
                        "{\"type\":\"anni_scrollspot_set\"}"),
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
                        Arrays.asList("Alice", null, "", "Bob"), "Alice", "WC1"));
        assertTrue(AnniWsSender.sendAnniPartyObservation(null, null, null));
        assertEquals(
                List.of(
                        "{\"type\":\"anni_party_observation\","
                                + "\"party_member_usernames\":[\"Alice\",\"Bob\"],"
                                + "\"leader_username\":\"Alice\",\"world\":\"WC1\"}",
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

    private void assertNothingSentWhileDown() {
        V1ApiManager.sendRegistration("0a1b2c3d-1111-2222-3333-444455556666", "Alice", "guild");
        V1ApiManager.sendAuth("k3y-Value_123");
        V1ApiManager.sendQueueStatus(true, "NA30");
        V1ApiManager.sendInbound("guild", "Chief", "Alice", "hello there");
        V1ApiManager.sendRankChange("Alice", "Bob", "Captain", "Recruit", "mote");
        V1ApiManager.sendTabList(List.of(new V1ApiManager.TabListEntry("WC1", "Alice")));
        assertFalse(AnniWsSender.sendAnniQuery());
        assertFalse(AnniWsSender.sendAnniScrollspotSet(12, 64, -300));
        assertFalse(AnniWsSender.sendAnniScrollspotSet(null, null, null));
        assertFalse(AnniWsSender.sendAnniRsvp("hard"));
        assertFalse(AnniWsSender.sendAnniPartyObservation(List.of("Alice"), "Alice", "WC1"));

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
