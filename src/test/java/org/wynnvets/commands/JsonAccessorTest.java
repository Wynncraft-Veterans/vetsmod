package org.wynnvets.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CautionCommands}' {@code optString} and {@code optInt} — two
 * of the seven hand-rolled JSON accessors scattered across five packages.
 *
 * <p>The cross-site policy table lives on
 * {@code org.wynnvets.chat.dispatcher.JsonAccessorTest}. This file holds the
 * two most interesting rows:</p>
 *
 * <ul>
 *   <li>{@code optString} is the <b>only</b> one of the six string accessors
 *       that tolerates a null receiver. Its body is otherwise identical to
 *       {@code WarningRewriter.optString}, so a "these two are the same
 *       function" collapse onto that one introduces an NPE here.</li>
 *   <li>{@code optInt} is the only accessor whose answer on all three axes is
 *       "fallback". It is probably the shape the other six should converge to,
 *       and it is the one none of them currently match.</li>
 * </ul>
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

    private static JsonObject withNumber(Number value) {
        JsonObject obj = new JsonObject();
        obj.addProperty("k", value);
        return obj;
    }

    // ----- optString -----

    @Test
    void optString_readsAStringAndIgnoresTheFallback() {
        assertEquals("value", CautionCommands.optString(withString("value"), "k", "fb"));
    }

    @Test
    void optString_missingKeyAndJsonNullBothGiveTheFallback() {
        assertEquals("fb", CautionCommands.optString(new JsonObject(), "k", "fb"));
        assertEquals("fb", CautionCommands.optString(withValue(JsonNull.INSTANCE), "k", "fb"));
    }

    @Test
    void optString_toleratesANullObject() {
        // The one row in the table that guards. Every other string accessor
        // NPEs on this input.
        assertEquals("fb", CautionCommands.optString(null, "k", "fb"));
    }

    @Test
    void optString_stillLetsAWrongTypedValueThrow() {
        // The null guard is the only difference from WarningRewriter.optString;
        // the wrong-type policy is identical.
        assertThrows(
                UnsupportedOperationException.class,
                () -> CautionCommands.optString(withValue(new JsonObject()), "k", "fb"));
    }

    // ----- optInt -----

    @Test
    void optInt_readsANumber() {
        assertEquals(42, CautionCommands.optInt(withNumber(42), "k", -1));
    }

    @Test
    void optInt_truncatesADecimalRatherThanRejectingIt() {
        // getAsInt on a floating-point value truncates toward zero.
        assertEquals(3, CautionCommands.optInt(withNumber(3.9), "k", -1));
        assertEquals(-3, CautionCommands.optInt(withNumber(-3.9), "k", -1));
    }

    @Test
    void optInt_parsesANumericString() {
        assertEquals(42, CautionCommands.optInt(withString("42"), "k", -1));
    }

    @Test
    void optInt_fallsBackOnAllThreeAxes() {
        // Missing, null-valued, wrong-typed and null-receiver all give the
        // fallback. No other accessor here is uniform across the three. The
        // wrong-type row has one exception, pinned separately below: a
        // singleton array is unwrapped rather than rejected.
        assertEquals(-1, CautionCommands.optInt(new JsonObject(), "k", -1), "missing");
        assertEquals(
                -1, CautionCommands.optInt(withValue(JsonNull.INSTANCE), "k", -1), "json null");
        assertEquals(
                -1, CautionCommands.optInt(withString("not a number"), "k", -1), "unparseable");
        assertEquals(-1, CautionCommands.optInt(withValue(new JsonObject()), "k", -1), "object");
        assertEquals(-1, CautionCommands.optInt(withValue(new JsonArray()), "k", -1), "array");
        assertEquals(-1, CautionCommands.optInt(null, "k", -1), "null receiver");
    }

    @Test
    void optInt_aSingletonArrayIsUnwrappedSoTheWrongTypeColumnHasACaveat() {
        // Gson delegates getAsInt on a one-element array to that element. So
        // "wrong-typed gives the fallback" holds for objects and multi-element
        // arrays and fails for singletons — at this site and at all six others,
        // none of which reject the shape.
        JsonArray single = new JsonArray();
        single.add(7);
        assertEquals(7, CautionCommands.optInt(withValue(single), "k", -1));
    }

    @Test
    void optInt_anOverflowingLiteralWrapsRatherThanFallingBack() {
        // getAsInt narrows a long rather than throwing, so the catch never
        // fires and an out-of-range value arrives silently wrong. Pinned
        // because "unparseable gives the fallback" reads like it covers this.
        assertEquals(
                (int) 4_294_967_298L,
                CautionCommands.optInt(withNumber(4_294_967_298L), "k", -1),
                "narrowed, not rejected");
    }
}
