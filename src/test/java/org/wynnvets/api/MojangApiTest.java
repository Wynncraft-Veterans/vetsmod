package org.wynnvets.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MojangApi#getUserUUID}: the name is URL-encoded into the path, so a name the
 * player mistyped with a space or a URL delimiter neither throws nor asks about a shorter name.
 */
class MojangApiTest {

    private static final String BASE = "https://api.mojang.com/users/profiles/minecraft/";

    @Test
    void plainName_isUnchanged() {
        assertEquals(BASE + "Wenweia", MojangApi.getUserUUID("Wenweia").toString());
    }

    @Test
    void spaceAndPipe_areEncodedInsteadOfThrowing() {
        assertEquals(BASE + "Foo+Bar", MojangApi.getUserUUID("Foo Bar").toString());
        assertEquals(BASE + "Foo%7C", MojangApi.getUserUUID("Foo|").toString());
    }

    @Test
    void hashAndQuestionMark_stayPartOfTheName() {
        assertEquals(BASE + "Foo%23Bar", MojangApi.getUserUUID("Foo#Bar").toString());
        assertEquals(BASE + "Foo%3FBar", MojangApi.getUserUUID("Foo?Bar").toString());
    }
}
