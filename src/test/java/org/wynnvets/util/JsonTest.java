package org.wynnvets.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Json}'s four field accessors.
 *
 * <p>This is the sole survivor of five {@code JsonAccessorTest} classes — in {@code chat},
 * {@code chat.dispatcher}, {@code chat.rewriter}, {@code commands} and
 * {@code fetcher.ondemand} — that between them pinned seven hand-rolled accessors with 36 test
 * methods. Sixteen here is not a coverage cut: the five were deliberately worded line for line
 * alike so their disagreements would be legible side by side, and once there is one policy the
 * per-site restatements are restatements of each other. What the five pinned that survives is
 * every <i>axis</i> and every <i>literal</i>, and all of both are below.</p>
 *
 * <p>⚠️ <b>The warn is not asserted, and cannot be.</b> Every case here that falls back on a
 * wrong-typed value (an object or a two-element array), on a {@code null} receiver, or, for
 * {@code optInt}, on an unparseable string also emits a {@code VetsLogger.warn}, and that warn is
 * half of why the tolerant body was acceptable at all. No log-capture library is on the test
 * classpath and there is no
 * precedent for adding one ({@code VetsLoggerTest} only toggles a flag); a capture seam would
 * be a structural change made for a test, which {@code CLAUDE.md} forbids. So these methods are
 * specified here by return value only, and the warn is verified by reading. Said out loud so
 * the gap does not read as an oversight.</p>
 *
 * <p>{@link Json} loads no Minecraft and no Wynntils. It touches {@code VetsLogger} on the warn
 * paths, whose {@code <clinit>} is one SLF4J {@code LoggerFactory.getLogger} call.</p>
 */
class JsonTest {

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

    private static JsonArray array(Object... values) {
        JsonArray a = new JsonArray();
        for (Object v : values) {
            if (v instanceof Number n) {
                a.add(n);
            } else {
                a.add((String) v);
            }
        }
        return a;
    }

    // ----- optString: present values -----

    @Test
    void optString_readsAStringVerbatimAndIgnoresTheFallback() {
        assertEquals("value", Json.optString(withString("value"), "k", "fb"));
        assertEquals(
                "",
                Json.optString(withString(""), "k", "fb"),
                "an empty string is a present value, not a miss");
    }

    @Test
    void optString_coercesPrimitivesRatherThanRejectingThem() {
        // getAsString on a number or a boolean returns its text, so a payload
        // that types a field wrongly-but-primitively is accepted silently — no
        // fallback and no warn, because to Gson it is not wrongly typed.
        JsonObject number = new JsonObject();
        number.addProperty("k", 42);
        assertEquals("42", Json.optString(number, "k", "fb"));

        JsonObject bool = new JsonObject();
        bool.addProperty("k", true);
        assertEquals("true", Json.optString(bool, "k", "fb"));
    }

    // ----- optString: the three axes -----

    @Test
    void optString_missingKeyAndJsonNullBothGiveTheFallback() {
        // Axis 1, and the only one of the three that is silent: a missing
        // optional field is normal, not a defect.
        assertEquals("fb", Json.optString(new JsonObject(), "k", "fb"));
        assertEquals("fb", Json.optString(withValue(JsonNull.INSTANCE), "k", "fb"));
    }

    @Test
    void optString_aWrongTypedValueGivesTheFallback() {
        // Axis 2, and the behaviour change the tolerant rewrite actually made:
        // before it, four of the six string accessors let this escape as an
        // UnsupportedOperationException or an IllegalStateException. Both shapes
        // are probed — an object and a TWO-element array, because a singleton is
        // unwrapped (below).
        assertEquals("fb", Json.optString(withValue(new JsonObject()), "k", "fb"), "object");
        assertEquals("fb", Json.optString(withValue(array("a", "b")), "k", "fb"), "2-array");
    }

    @Test
    void optString_aNullReceiverGivesTheFallback() {
        // Axis 3. Five of the six string accessors NPE'd here. The one site
        // that needs the tolerance is NameResolver.forEachGuildMember, whose
        // MemberHandler.accept genuinely receives null for a malformed entry.
        assertEquals("fb", Json.optString(null, "k", "fb"));
    }

    @Test
    void optString_aSingletonArrayIsUnwrappedByGsonRatherThanRejected() {
        // Gson delegates getAsString on a one-element array to that element, so
        // the fallback never fires and a shape error passes through as a value.
        // True at all seven original sites too; none rejected the shape.
        assertEquals("inner", Json.optString(withValue(array("inner")), "k", "fb"));
    }

    @Test
    void optString_theFallbackIsReturnedVerbatimIncludingNull() {
        // Nothing constrains the fallback. This is what makes stringOrNull a
        // convenience rather than a fourth policy.
        assertNull(Json.optString(new JsonObject(), "k", null));
    }

    // ----- The two conveniences -----

    @Test
    void stringOrNull_isOptStringWithANullFallback() {
        assertEquals("value", Json.stringOrNull(withString("value"), "k"));
        assertNull(Json.stringOrNull(new JsonObject(), "k"), "missing");
        assertNull(Json.stringOrNull(withValue(JsonNull.INSTANCE), "k"), "json null");
        assertNull(Json.stringOrNull(withValue(new JsonObject()), "k"), "wrong type");
        assertNull(Json.stringOrNull(null, "k"), "null receiver");
    }

    @Test
    void stringOrEmpty_isOptStringWithAnEmptyStringFallback() {
        assertEquals("value", Json.stringOrEmpty(withString("value"), "k"));
        assertEquals("", Json.stringOrEmpty(new JsonObject(), "k"), "missing");
        assertEquals("", Json.stringOrEmpty(withValue(JsonNull.INSTANCE), "k"), "json null");
        assertEquals("", Json.stringOrEmpty(withValue(new JsonObject()), "k"), "wrong type");
        assertEquals("", Json.stringOrEmpty(null, "k"), "null receiver");
    }

    @Test
    void theThreeStringEntryPointsDifferOnlyOnWhatTheMissAnswers() {
        // The whole justification for having three names for one function: the
        // conveniences exist so that 28 of the 50 call sites do not grow an
        // argument. If they ever diverge on anything but the fallback, that
        // justification is gone — so pin it.
        JsonObject present = withString("value");
        assertEquals("value", Json.optString(present, "k", "fb"));
        assertEquals("value", Json.stringOrNull(present, "k"));
        assertEquals("value", Json.stringOrEmpty(present, "k"));

        // Present-but-empty is a value at all three, not a miss.
        JsonObject empty = withString("");
        assertEquals("", Json.optString(empty, "k", "fb"));
        assertEquals("", Json.stringOrNull(empty, "k"));
        assertEquals("", Json.stringOrEmpty(empty, "k"));

        // Every failure mode routes to that entry point's own fallback and
        // nothing else.
        for (JsonObject miss :
                new JsonObject[] {
                    new JsonObject(), withValue(JsonNull.INSTANCE), withValue(new JsonObject())
                }) {
            assertEquals("fb", Json.optString(miss, "k", "fb"));
            assertNull(Json.stringOrNull(miss, "k"));
            assertEquals("", Json.stringOrEmpty(miss, "k"));
        }
        assertEquals("fb", Json.optString(null, "k", "fb"));
        assertNull(Json.stringOrNull(null, "k"));
        assertEquals("", Json.stringOrEmpty(null, "k"));
    }

    // ----- optInt -----

    @Test
    void optInt_readsANumber() {
        assertEquals(42, Json.optInt(withNumber(42), "k", -1));
    }

    @Test
    void optInt_fallsBackOnAllThreeAxes() {
        assertEquals(-1, Json.optInt(new JsonObject(), "k", -1), "missing");
        assertEquals(-1, Json.optInt(withValue(JsonNull.INSTANCE), "k", -1), "json null");
        assertEquals(-1, Json.optInt(withValue(new JsonObject()), "k", -1), "object");
        assertEquals(-1, Json.optInt(withValue(array("a", "b")), "k", -1), "2-array");
        assertEquals(-1, Json.optInt(null, "k", -1), "null receiver");
    }

    @Test
    void optInt_parsesANumericStringAndFallsBackOnAnUnparseableOne() {
        assertEquals(42, Json.optInt(withString("42"), "k", -1));
        assertEquals(-1, Json.optInt(withString("not a number"), "k", -1));
    }

    @Test
    void optInt_truncatesADecimalTowardZeroRatherThanRejectingIt() {
        assertEquals(3, Json.optInt(withNumber(3.9), "k", -1));
        assertEquals(-3, Json.optInt(withNumber(-3.9), "k", -1));
    }

    @Test
    void optInt_anOverflowingLiteralNarrowsRatherThanFallingBack() {
        // getAsInt narrows a long rather than throwing, so the catch never
        // fires and an out-of-range value arrives silently wrong. Pinned
        // because "unparseable gives the fallback" reads like it covers this.
        assertEquals(
                (int) 4_294_967_298L,
                Json.optInt(withNumber(4_294_967_298L), "k", -1),
                "narrowed, not rejected");
    }

    @Test
    void optInt_aSingletonArrayIsUnwrappedToo() {
        assertEquals(7, Json.optInt(withValue(array(7)), "k", -1));
    }
}
