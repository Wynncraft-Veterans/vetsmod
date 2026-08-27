package org.wynnvets.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 *   <li>{@code optInt} was the only accessor whose answer on all three axes was
 *       "fallback" — the shape the other six had to converge to, and the one
 *       none of them matched. It is the model {@link org.wynnvets.util.Json Json}'s
 *       four bodies were written from.</li>
 *   <li>{@code optString} was the only string accessor that tolerated a null
 *       receiver; since 5g's C2 all six do. C4 then gave it {@code optInt}'s
 *       wrong-type answer as well, so the two methods in this file now differ
 *       only in the type they read.</li>
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
        // This was the one row in the table that guarded. Since 5g's C2 every
        // string accessor does.
        assertEquals("fb", CautionCommands.optString(null, "k", "fb"));
    }

    @Test
    void optString_fallsBackOnAWrongTypedValue() {
        // Changed by 5g's C4. This used to let UnsupportedOperationException
        // out of a body reached from Minecraft.execute, so it landed in
        // BlockableEventLoop.doRunTask — a FATAL-marker ERROR, then swallowed,
        // with renderCautionHistory's header and some rows already on screen.
        // Now the readout completes with one field substituted.
        assertEquals("fb", CautionCommands.optString(withValue(new JsonObject()), "k", "fb"));
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
