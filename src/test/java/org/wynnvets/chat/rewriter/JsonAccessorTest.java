package org.wynnvets.chat.rewriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link WarningRewriter}'s {@code optString} — one of six
 * hand-rolled JSON string accessors scattered across five packages.
 *
 * <p>The cross-site policy table lives on
 * {@code org.wynnvets.chat.dispatcher.JsonAccessorTest}. This site's row is
 * <b>fallback / fallback / tolerated</b>, and it took 5g's C2 and C3 to get
 * there. It used to NPE on a null receiver and used to let a wrong-typed value
 * escape as an exception; it now answers the caller's fallback on all three
 * axes and warns on the latter two. The body is character-identical to
 * {@link org.wynnvets.util.Json Json#optString}, which C7 points it at.</p>
 *
 * <p>The objection this class used to raise — that swallowing a wrong type
 * "would turn a loud failure into a silent fallback" — is answered on
 * {@code Json}'s Javadoc, site by site, by tracing where each of the four
 * throws actually landed. Not one reached a user as a failure; all four were
 * already caught, and every one destroyed more of the payload than the
 * fallback does. Here specifically the throw escaped into
 * {@code V1ApiManager}'s outbound fan-out, whose {@code catch (Exception)}
 * logged a generic WARN and dropped the whole warning banner.</p>
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
    void optString_fallsBackOnAWrongTypedValueRatherThanThrowing() {
        // Changed by 5g's C3. This used to let UnsupportedOperationException
        // (object) and IllegalStateException (multi-element array) escape into
        // the rewriter, from where V1ApiManager's fan-out caught them and the
        // whole warning banner failed to render. Now one field defaults and
        // the banner draws, with a warn naming the field.
        assertEquals("fb", WarningRewriter.optString(withValue(new JsonObject()), "k", "fb"));
        assertEquals("fb", WarningRewriter.optString(withValue(twoElementArray()), "k", "fb"));
    }

    private static JsonArray twoElementArray() {
        JsonArray array = new JsonArray();
        array.add("a");
        array.add("b");
        return array;
    }

    // ----- Axis 3: null receiver -----

    @Test
    void optString_toleratesANullObject() {
        assertEquals("fb", WarningRewriter.optString(null, "k", "fb"));
    }
}
