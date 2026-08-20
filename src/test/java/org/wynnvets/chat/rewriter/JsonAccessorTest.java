package org.wynnvets.chat.rewriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link WarningRewriter}'s {@code optString} — one of five
 * hand-rolled JSON string accessors scattered across four packages.
 *
 * <p>The cross-site policy table lives on
 * {@code org.wynnvets.chat.dispatcher.JsonAccessorTest}. This site's row is
 * <b>fallback / throws / NPE</b>: it takes a caller-supplied default for the
 * missing case, but lets a wrong-typed value escape as an exception, and does
 * not guard a null receiver. It is the shortest of the five — three lines, no
 * try block — and that brevity is the whole difference from
 * {@code CommandDispatcher.stringOrNull}.</p>
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

    // ----- Present values -----

    @Test
    void optString_readsAStringAndIgnoresTheFallback() {
        assertEquals("value", WarningRewriter.optString(withString("value"), "k", "fb"));
        assertEquals(
                "",
                WarningRewriter.optString(withString(""), "k", "fb"),
                "an empty string is a present value, not a miss");
    }

    @Test
    void optString_coercesPrimitives() {
        JsonObject number = new JsonObject();
        number.addProperty("k", 42);
        assertEquals("42", WarningRewriter.optString(number, "k", "fb"));
    }

    // ----- Axis 1: missing or null -----

    @Test
    void optString_missingKeyAndJsonNullBothGiveTheFallback() {
        assertEquals("fb", WarningRewriter.optString(new JsonObject(), "k", "fb"));
        assertEquals("fb", WarningRewriter.optString(withValue(JsonNull.INSTANCE), "k", "fb"));
    }

    @Test
    void optString_theFallbackIsReturnedVerbatimIncludingNull() {
        // Nothing constrains the fallback, so this site can produce null too —
        // it just makes the caller ask for it.
        assertNull(WarningRewriter.optString(new JsonObject(), "k", null));
    }

    // ----- Axis 2: wrong type -----

    @Test
    void optString_letsAWrongTypedValueThrowRatherThanFallingBack() {
        // The divergence that matters. Where CommandDispatcher.stringOrNull
        // swallows this into null, here it escapes into the rewriter. Unifying
        // the five onto a swallowing body would turn a loud failure into a
        // silent fallback on this path.
        assertThrows(
                UnsupportedOperationException.class,
                () -> WarningRewriter.optString(withValue(new JsonObject()), "k", "fb"));
        assertThrows(
                IllegalStateException.class,
                () -> WarningRewriter.optString(withValue(twoElementArray()), "k", "fb"));
    }

    private static JsonArray twoElementArray() {
        JsonArray array = new JsonArray();
        array.add("a");
        array.add("b");
        return array;
    }

    // ----- Axis 3: null receiver -----

    @Test
    void optString_throwsOnANullObject() {
        assertThrows(NullPointerException.class, () -> WarningRewriter.optString(null, "k", "fb"));
    }
}
