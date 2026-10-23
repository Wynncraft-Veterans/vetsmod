package org.wynnvets.datamodels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.wynnvets.api.VetsApi;
import org.wynnvets.util.Json;

/**
 * Tests for {@link User#isInVets()}: which guild objects in a {@code /v3/player} payload count as
 * the Returners guild; and for the join-time getters, which read {@code firstJoin} and {@code
 * lastJoin} as instants or as the date part of their text. The payloads are parsed with {@code
 * Json.GSON}, as the fetchers parse them, and written by hand after wapi's shape.
 */
class UserTest {

    private static User parse(String json) {
        return Json.GSON.fromJson(json, User.class);
    }

    @Test
    void isInVets_returnersGuild() {
        String json = "{\"guild\": {\"uuid\": \"%s\"}}".formatted(VetsApi.GUILD_UUID);
        assertTrue(parse(json).isInVets());
    }

    @Test
    void isInVets_otherGuild() {
        String json = "{\"guild\": {\"uuid\": \"bbbbbbbb-1111-2222-3333-444444444444\"}}";
        assertFalse(parse(json).isInVets());
    }

    @Test
    void isInVets_noGuild() {
        assertFalse(parse("{}").isInVets());
        assertFalse(parse("{\"guild\": null}").isInVets());
    }

    @Test
    void isInVets_guildWithoutUuid() {
        // A partial payload: the guild object is there and its uuid is not. This threw before.
        assertFalse(parse("{\"guild\": {\"name\": \"Returners\"}}").isInVets());
        assertFalse(parse("{\"guild\": {\"uuid\": null}}").isInVets());
    }

    @Test
    void joinInstants_parseTheApiTimestamps() {
        User user =
                parse(
                        "{\"firstJoin\": \"2015-10-14T01:00:00.000Z\","
                                + " \"lastJoin\": \"2026-10-22T01:07:00.000Z\"}");
        assertEquals(Instant.parse("2015-10-14T01:00:00Z"), user.getFirstJoinInstant());
        assertEquals(Instant.parse("2026-10-22T01:07:00Z"), user.getLastJoinInstant());
        // The string getters keep cutting at the T, as InviteGate needs.
        assertEquals("2015-10-14", user.getFirstJoinDate());
        assertEquals("2026-10-22", user.getLastJoinDate());
    }

    @Test
    void joinInstants_keepAnOffsetAsTheSameMoment() {
        User user = parse("{\"lastJoin\": \"2026-10-21T22:07:00-03:00\"}");
        assertEquals(Instant.parse("2026-10-22T01:07:00Z"), user.getLastJoinInstant());
    }

    @Test
    void joinInstants_nullWhenAbsentOrUnparseable() {
        User none = parse("{}");
        assertNull(none.getFirstJoinInstant());
        assertNull(none.getLastJoinInstant());
        User bad = parse("{\"firstJoin\": \"not a time\", \"lastJoin\": \"2026-10-22\"}");
        assertNull(bad.getFirstJoinInstant());
        assertNull(bad.getLastJoinInstant());
        assertEquals("2026-10-22", bad.getLastJoinDate());
    }
}
