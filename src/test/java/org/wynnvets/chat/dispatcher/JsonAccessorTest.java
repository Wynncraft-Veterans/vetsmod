package org.wynnvets.chat.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CommandDispatcher}'s {@code stringOrNull} — one of six
 * hand-rolled JSON string accessors scattered across five packages, and one of
 * seven accessors in all ({@code CautionCommands} declares two).
 *
 * <p>CORRECTED, and both errors are pre-existing rather than this phase's.
 * (1) This Javadoc used to open "There is no {@code org.wynnvets.util} package
 * and no shared {@code Json} helper" — stale since 5b, which created both for
 * the shared {@code Gson}. {@link org.wynnvets.util.Json Json} now also holds
 * the four accessors these six sites are being folded onto. (2) It used to say
 * only one pair below is a genuine duplicate; there are two — see the closing
 * paragraph. What has not changed is the reason the table matters: the six
 * sites do not agree on any of the three questions a shared helper has to
 * answer. This is the table those five sibling test classes exist to make
 * mechanical:</p>
 *
 * <table border="1">
 *   <caption>Policy per site</caption>
 *   <tr><th>Site</th><th>Missing or null</th><th>Wrong type</th><th>Null receiver</th></tr>
 *   <tr><td>{@code CommandDispatcher.stringOrNull}</td><td>null</td>
 *       <td>swallowed to null</td><td>tolerated</td></tr>
 *   <tr><td>{@code StaffFetcher.stringOrNull}</td><td>null</td>
 *       <td>swallowed to null</td><td>tolerated</td></tr>
 *   <tr><td>{@code WarningRewriter.optString}</td><td>fallback</td>
 *       <td>throws</td><td>tolerated</td></tr>
 *   <tr><td>{@code CautionCommands.optString}</td><td>fallback</td>
 *       <td>throws</td><td>tolerated</td></tr>
 *   <tr><td>{@code OnlineMemberService.stringOrEmpty}</td><td>empty string</td>
 *       <td>throws</td><td>tolerated</td></tr>
 *   <tr><td>{@code OutboundDisplayHandler.getStringOrEmpty}</td><td>empty string</td>
 *       <td>throws</td><td>tolerated</td></tr>
 * </table>
 *
 * <p>The third column read NPE at five of these six rows until 5g's C2. It does
 * not any more: all six tolerate a null receiver, which settles axis 3 and
 * leaves two genuinely open.</p>
 *
 * <p>Two independent axes, then, where there were three — so no single
 * signature absorbs all six without changing at least three of them. A fourth
 * axis is the return contract itself: three of the six take no
 * {@code fallback} parameter, so folding them
 * into a fallback-taking helper grows an argument at every call site.</p>
 *
 * <p>The last row was missing from the Phase 4 brief's census and was found by
 * the verify pass. <b>Two</b> pairs in the table are genuine duplicates:
 * {@code OnlineMemberService.stringOrEmpty} and
 * {@code OutboundDisplayHandler.getStringOrEmpty} share a body exactly, and so
 * do {@code CommandDispatcher.stringOrNull} and {@code StaffFetcher.stringOrNull}
 * — down to the blank line and the seam comment above the declaration.</p>
 *
 * <p>NOTE: {@link CommandDispatcher}'s static initializer reaches an
 * {@code HttpClient}, a request and a single-threaded executor. The client is
 * no longer built here — the field now reads
 * {@link org.wynnvets.util.HttpClients HttpClients#standard()}, so touching
 * this class triggers that class's initializer instead — but the thread is
 * spawned either way. Both spawn threads: the executor's only on first submit,
 * the client's selector thread immediately. Class-init is safe in the harness
 * only because both are daemons and so cannot hold the test JVM open.</p>
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
        // This site's answer to axis 2, and it is the minority answer: four of
        // the six let the exception out.
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
    void stringOrNull_toleratesANullObject() {
        // This site's answer to axis 3, changed: it used to NPE, and so did
        // four of the other five. NameResolver.forEachGuildMember genuinely
        // hands a null member to its handler for a malformed entry, so a
        // shared helper that NPE'd would permanently exclude the one site that
        // needs the tolerance. At this call site the receiver is non-null by
        // construction — element.getAsJsonObject() behind an isJsonObject()
        // check — so nothing here changes.
        assertNull(CommandDispatcher.stringOrNull(null, "k"));
    }
}
