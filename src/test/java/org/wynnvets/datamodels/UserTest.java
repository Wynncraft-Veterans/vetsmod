package org.wynnvets.datamodels;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.wynnvets.api.VetsApi;
import org.wynnvets.util.Json;

/**
 * Tests for {@link User#isInVets()}: which guild objects in a {@code /v3/player} payload count as
 * the Returners guild. The payloads are parsed with {@code Json.GSON}, as the fetchers parse them,
 * and written by hand after wapi's shape.
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
}
