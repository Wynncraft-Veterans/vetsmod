package org.wynnvets.mwe.anni.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AnniRsvpClient#parseAck}, which reads an {@code anni_rsvp_response} frame into
 * an {@link AnniRsvpClient.Ack}: the status, the optional {@code detail} and the optional
 * {@code result} that tells a revoke that withdrew an RSVP from one that found none. The FIFO
 * queue, the deadline and the frame send are not covered: they run on the WebSocket and the
 * timer.
 */
class AnniRsvpClientTest {

    private static AnniRsvpClient.Ack parse(String json) {
        JsonObject frame = JsonParser.parseString(json).getAsJsonObject();
        return AnniRsvpClient.parseAck(frame);
    }

    @Test
    void parseAck_okWithResult() {
        AnniRsvpClient.Ack ack =
                parse(
                        "{\"status\": \"ok\", \"detail\": null, \"result\": \"nothing_to_withdraw\"}");
        assertTrue(ack.ok());
        assertNull(ack.detail());
        assertEquals("nothing_to_withdraw", ack.result());
    }

    @Test
    void parseAck_okWithoutResult_resultNull() {
        // An older server sends no result field.
        AnniRsvpClient.Ack ack = parse("{\"status\": \"ok\"}");
        assertTrue(ack.ok());
        assertNull(ack.result());
        assertEquals(new AnniRsvpClient.Ack(true, null), ack);
    }

    @Test
    void parseAck_errorWithDetail() {
        AnniRsvpClient.Ack ack =
                parse("{\"status\": \"error\", \"detail\": \"RSVP is closed (within 90 min)\"}");
        assertFalse(ack.ok());
        assertEquals("RSVP is closed (within 90 min)", ack.detail());
        assertNull(ack.result());
    }

    @Test
    void parseAck_nullStatus_isError() {
        assertFalse(parse("{\"status\": null}").ok());
        assertFalse(parse("{}").ok());
    }

    @Test
    void parseAck_nonStringResult_readsAsNull() {
        assertNull(parse("{\"status\": \"ok\", \"result\": 3}").result());
        assertNull(parse("{\"status\": \"ok\", \"result\": null}").result());
    }

    @Test
    void ack_twoArgConstructor_hasNoResult() {
        AnniRsvpClient.Ack ack = new AnniRsvpClient.Ack(false, "not connected");
        assertFalse(ack.ok());
        assertEquals("not connected", ack.detail());
        assertNull(ack.result());
    }
}
