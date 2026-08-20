package org.wynnvets.fetcher.ondemand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Tests for the two JSON string accessors in this package —
 * {@link StaffFetcher}'s {@code stringOrNull} and {@link OnlineMemberService}'s
 * {@code stringOrEmpty}.
 *
 * <p>The cross-site policy table lives on
 * {@code org.wynnvets.chat.dispatcher.JsonAccessorTest}. These two share a
 * package and a payload shape and still disagree on both of the axes they can
 * disagree on: one returns null on a miss and swallows a wrong type, the other
 * returns an empty string and lets the exception out. Whichever body a shared
 * helper adopts, one of these two call sites changes behaviour.</p>
 *
 * <p>{@code stringOrEmpty} was already package-private and needed no seam.</p>
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

    // ----- StaffFetcher.stringOrNull -----

    @Test
    void stringOrNull_readsAStringVerbatim() {
        assertEquals("value", StaffFetcher.stringOrNull(withString("value"), "k"));
    }

    @Test
    void stringOrNull_missingKeyAndJsonNullBothGiveNull() {
        assertNull(StaffFetcher.stringOrNull(new JsonObject(), "k"));
        assertNull(StaffFetcher.stringOrNull(withValue(JsonNull.INSTANCE), "k"));
    }

    @Test
    void stringOrNull_swallowsAWrongTypedValueIntoNull() {
        assertNull(StaffFetcher.stringOrNull(withValue(new JsonObject()), "k"));
        assertNull(StaffFetcher.stringOrNull(withValue(new JsonArray()), "k"));
    }

    @Test
    void stringOrNull_throwsOnANullObject() {
        assertThrows(NullPointerException.class, () -> StaffFetcher.stringOrNull(null, "k"));
    }

    // ----- OnlineMemberService.stringOrEmpty -----

    @Test
    void stringOrEmpty_readsAStringVerbatim() {
        assertEquals("value", OnlineMemberService.stringOrEmpty(withString("value"), "k"));
    }

    @Test
    void stringOrEmpty_missingKeyAndJsonNullBothGiveTheEmptyString() {
        // A third answer to axis 1 — not null, not a caller-supplied fallback.
        // It also makes "absent" and "present but empty" indistinguishable to
        // the caller, which the other four keep apart.
        assertEquals("", OnlineMemberService.stringOrEmpty(new JsonObject(), "k"));
        assertEquals("", OnlineMemberService.stringOrEmpty(withValue(JsonNull.INSTANCE), "k"));
        assertEquals(
                "",
                OnlineMemberService.stringOrEmpty(withString(""), "k"),
                "the present-but-empty case collapses onto the same answer");
    }

    @Test
    void stringOrEmpty_letsAWrongTypedValueThrow() {
        // The disagreement with its package-mate directly above.
        assertThrows(
                UnsupportedOperationException.class,
                () -> OnlineMemberService.stringOrEmpty(withValue(new JsonObject()), "k"));
    }

    @Test
    void stringOrEmpty_throwsOnANullObject() {
        assertThrows(
                NullPointerException.class, () -> OnlineMemberService.stringOrEmpty(null, "k"));
    }

    // ----- The two disagree on real input -----

    @Test
    void thePackageMatesDisagreeOnBothAxesTheyCanDisagreeOn() {
        JsonObject missing = new JsonObject();
        // An object-valued field, not an array: Gson unwraps a one-element
        // array and throws IllegalStateException for any other size, so an
        // object is the unambiguous wrong-type probe.
        JsonObject wrongType = withValue(new JsonObject());

        assertNull(StaffFetcher.stringOrNull(missing, "k"));
        assertEquals("", OnlineMemberService.stringOrEmpty(missing, "k"));

        assertNull(StaffFetcher.stringOrNull(wrongType, "k"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> OnlineMemberService.stringOrEmpty(wrongType, "k"));
    }
}
