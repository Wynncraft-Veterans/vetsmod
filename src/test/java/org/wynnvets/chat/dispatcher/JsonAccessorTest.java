package org.wynnvets.chat.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CommandDispatcher}'s {@code stringOrNull} — one of five
 * hand-rolled JSON string accessors scattered across four packages.
 *
 * <p>There is no {@code org.wynnvets.util} package and no shared {@code Json}
 * helper. A later phase proposes creating one, and the five sites do not agree
 * on any of the three questions such a helper would have to answer. This is the
 * table those four sibling test classes exist to make mechanical:</p>
 *
 * <table border="1">
 *   <caption>Policy per site</caption>
 *   <tr><th>Site</th><th>Missing or null</th><th>Wrong type</th><th>Null receiver</th></tr>
 *   <tr><td>{@code CommandDispatcher.stringOrNull}</td><td>null</td>
 *       <td>swallowed to null</td><td>NPE</td></tr>
 *   <tr><td>{@code StaffFetcher.stringOrNull}</td><td>null</td>
 *       <td>swallowed to null</td><td>NPE</td></tr>
 *   <tr><td>{@code WarningRewriter.optString}</td><td>fallback</td>
 *       <td>throws</td><td>NPE</td></tr>
 *   <tr><td>{@code CautionCommands.optString}</td><td>fallback</td>
 *       <td>throws</td><td>tolerated</td></tr>
 *   <tr><td>{@code OnlineMemberService.stringOrEmpty}</td><td>empty string</td>
 *       <td>throws</td><td>NPE</td></tr>
 * </table>
 *
 * <p>Three independent axes, not one — so no single signature absorbs all five
 * without changing at least three of them.</p>
 *
 * <p>NOTE: {@link CommandDispatcher}'s static initializer builds an
 * {@code HttpClient}, a request and a single-threaded executor. The executor's
 * threads are daemons and are not started until a task is submitted, so
 * class-init is safe in the harness.</p>
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
    void stringOrNull_readsAStringVerbatim() {
        assertEquals("value", CommandDispatcher.stringOrNull(withString("value"), "k"));
        assertEquals("", CommandDispatcher.stringOrNull(withString(""), "k"), "empty is a value");
    }

    @Test
    void stringOrNull_coercesPrimitivesRatherThanRejectingThem() {
        // getAsString on a number or a boolean returns its text. A payload that
        // types a field wrongly-but-primitively is silently accepted.
        JsonObject number = new JsonObject();
        number.addProperty("k", 42);
        assertEquals("42", CommandDispatcher.stringOrNull(number, "k"));

        JsonObject bool = new JsonObject();
        bool.addProperty("k", true);
        assertEquals("true", CommandDispatcher.stringOrNull(bool, "k"));
    }

    // ----- Axis 1: missing or null -----

    @Test
    void stringOrNull_missingKeyAndJsonNullBothGiveNull() {
        // This site's answer to axis 1. It has no fallback parameter at all, so
        // absorbing it into a fallback-taking helper means every call site
        // grows an argument.
        assertNull(CommandDispatcher.stringOrNull(new JsonObject(), "k"));
        assertNull(CommandDispatcher.stringOrNull(withValue(JsonNull.INSTANCE), "k"));
    }

    // ----- Axis 2: wrong type -----

    @Test
    void stringOrNull_swallowsAnObjectOrArrayValueIntoNull() {
        // This site's answer to axis 2, and it is the minority answer: three of
        // the five let the exception out.
        assertNull(CommandDispatcher.stringOrNull(withValue(new JsonObject()), "k"));
        assertNull(CommandDispatcher.stringOrNull(withValue(new JsonArray()), "k"));
    }

    @Test
    void stringOrNull_aSingletonArrayIsUnwrappedByGsonRatherThanRejected() {
        // Gson delegates getAsString on a one-element array to that element, so
        // the swallow never fires and a shape error passes through as a value.
        JsonArray single = new JsonArray();
        single.add("inner");
        assertEquals("inner", CommandDispatcher.stringOrNull(withValue(single), "k"));
    }

    // ----- Axis 3: null receiver -----

    @Test
    void stringOrNull_throwsOnANullObject() {
        // This site's answer to axis 3. Four of the five agree; only
        // CautionCommands.optString guards.
        assertThrows(NullPointerException.class, () -> CommandDispatcher.stringOrNull(null, "k"));
    }
}
