package org.wynnvets.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OutboundDisplayHandler}'s {@code getStringOrEmpty} — the
 * sixth hand-rolled JSON string accessor, and the one the Phase 4 brief's
 * census missed.
 *
 * <p>The cross-site policy table lives on
 * {@code org.wynnvets.chat.dispatcher.JsonAccessorTest}. This one matters more
 * than "one more duplicate" suggests: it is byte-for-byte the same body as
 * {@code OnlineMemberService.stringOrEmpty} in a different package, it has
 * eight call sites, and it sits <b>directly upstream of a tested sibling</b> —
 * {@code onOutboundMessage} reads a bridge frame with it and hands the result
 * to {@code WarningRewriter.render}, whose own {@code optString} answers the
 * same three questions differently. A shared helper has to reconcile the two
 * ends of that one code path, not merely five scattered copies.</p>
 */
class JsonAccessorTest {

    private static JsonObject withValue(JsonElement value) {
        JsonObject obj = new JsonObject();
        obj.add("k", value);
        return obj;
    }

    private static JsonObject withString(String value) {
        JsonObject obj = new JsonObject();
        obj.addProperty("k", value);
        return obj;
    }

    @Test
    void getStringOrEmpty_readsAStringVerbatim() {
        assertEquals("value", OutboundDisplayHandler.getStringOrEmpty(withString("value"), "k"));
    }

    @Test
    void getStringOrEmpty_missingKeyAndJsonNullBothGiveTheEmptyString() {
        assertEquals("", OutboundDisplayHandler.getStringOrEmpty(new JsonObject(), "k"));
        assertEquals(
                "", OutboundDisplayHandler.getStringOrEmpty(withValue(JsonNull.INSTANCE), "k"));
    }

    @Test
    void getStringOrEmpty_letsAWrongTypedValueThrow() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> OutboundDisplayHandler.getStringOrEmpty(withValue(new JsonObject()), "k"));
    }

    @Test
    void getStringOrEmpty_throwsOnANullObject() {
        assertThrows(
                NullPointerException.class,
                () -> OutboundDisplayHandler.getStringOrEmpty(null, "k"));
    }

    @Test
    void getStringOrEmpty_unwrapsASingletonArrayLikeEveryOtherAccessor() {
        // Gson delegates getAsString on a one-element array to that element. No
        // accessor in the repo rejects this shape, so a singleton-wrapped field
        // arrives as a value at all six sites and throws at none.
        JsonArray single = new JsonArray();
        single.add("inner");
        assertEquals("inner", OutboundDisplayHandler.getStringOrEmpty(withValue(single), "k"));
    }

    // The twin, OnlineMemberService.stringOrEmpty, cannot be called from here:
    // it is package-private in fetcher.ondemand and widening it to public for a
    // test is out of bounds. Each package asserts its own copy against the same
    // fixtures instead, which is why the four cases above are worded to match
    // fetcher.ondemand's JsonAccessorTest line for line.
}
