package org.wynnvets.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.wynnvets.logging.VetsLogger;

/**
 * The mod's shared {@link Gson} and its shared field accessors.
 *
 * <p>Twenty-one classes each declared their own {@code private static final Gson GSON = new
 * Gson()} &mdash; character-identical, unconfigured, and per-class in nothing but where the
 * copy happened to land. They all read this field instead. <b>Twenty read it today</b>, two of
 * the original twenty-one having since been merged into a single {@code PolledJsonMap}.</p>
 *
 * <p>Sharing one instance is safe by Gson's own contract: {@code Gson} is documented as
 * thread-safe and intended to be reused across an application. What sharing it actually
 * shares is its internal type-adapter caching, and that is a win rather than a risk &mdash;
 * the classes deserialising the same wapi and vets-API payload shapes now warm one cache
 * between them instead of one apiece.</p>
 *
 * <p><b>The three configured instances are deliberately not residents.</b> Each builds through
 * a {@code GsonBuilder} and each depends on what it configured:
 * {@link org.wynnvets.config.VetsConfig VetsConfig} ({@code setPrettyPrinting}),
 * {@link org.wynnvets.debug.dump.ItemDumpHandler ItemDumpHandler}
 * ({@code + disableHtmlEscaping}) and
 * {@link org.wynnvets.mwe.anni.debug.AnniDebugCommands AnniDebugCommands}
 * ({@code + serializeNulls}, load-bearing for the anni debug round-trip and documented at its
 * own declaration). Folding any of them in here would change what it writes. <b>The accessors
 * below do not weaken that paragraph</b> &mdash; they read a {@link JsonObject} that is already
 * parsed and never touch a {@code Gson} at all.</p>
 *
 * <p>This class holds JSON policy and nothing else. In particular it constructs no
 * {@code HttpClient}, so a JSON-only code path never drags a transport construction into its
 * {@code <clinit>}.</p>
 *
 * <h2>The field accessors</h2>
 *
 * <p>Six classes in five packages each hand-rolled the same read &mdash; "give me this key's
 * value, or something else if it is not there" &mdash; seven methods over forty-four call
 * sites, and they answered the three questions such a read has to answer <b>four different
 * ways</b>. This was the table before:</p>
 *
 * <table border="1">
 *   <caption>Policy per site, before</caption>
 *   <tr><th>Site</th><th>Missing or JSON-null</th><th>Wrong type</th><th>Null receiver</th></tr>
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
 *   <tr><td>{@code OutboundDisplayHandler.getStringOrEmpty}</td><td>empty string</td>
 *       <td>throws</td><td>NPE</td></tr>
 * </table>
 *
 * <p>That is six rows for six string accessors. <b>The seventh method was the model.</b>
 * {@code CautionCommands.optInt}, in the same file as the fourth row, already answered
 * <i>fallback</i> on all three axes &mdash; the only one of the seven that was uniform, and the
 * one none of the others matched. It is what the bodies below are, with the type swapped and
 * the warn added.</p>
 *
 * <p>And this is the table after &mdash; one row, because there is one policy:</p>
 *
 * <table border="1">
 *   <caption>Policy per site, after</caption>
 *   <tr><th>Site</th><th>Missing or JSON-null</th><th>Wrong type</th><th>Null receiver</th></tr>
 *   <tr><td>all four entry points here</td><td>fallback, silently</td>
 *       <td>fallback, with a warn</td><td>fallback, with a warn</td></tr>
 * </table>
 *
 * <p><b>Why tolerant, and why loud.</b> The case against a tolerant body was that it "would
 * turn a loud failure into a silent fallback". Traced to where each exception actually landed,
 * that did not hold: not one of the four throwing sites let the exception reach a user as a
 * failure. Two were caught into a {@code VetsLogger.debug} that dropped an <i>entire</i>
 * payload &mdash; the whole connected-user list, the whole staff-name set, or a member walk
 * abandoned mid-iteration with a half-filled accumulator. One was caught by
 * {@code V1ApiManager}'s outbound fan-out into a generic WARN, and the chat line never
 * rendered. One reached {@code BlockableEventLoop.doRunTask}, which logs a FATAL-marker ERROR
 * and then swallows it, aborting a {@code /caution} readout with its header already on screen.
 * So the change is not "loud to silent"; it is <b>"most of a payload destroyed, half of it
 * invisibly" to "one field defaulted, always named"</b>. The warn is what makes the second half
 * of that true, and it is why these methods are not pure functions &mdash; {@code JsonTest}
 * specifies the return values only.</p>
 *
 * <p><b>The {@code isJsonNull()} check earns its place through the warn, not the return
 * value.</b> Deleting it changes nothing a test can see: {@code JsonNull.getAsString()} throws
 * {@code UnsupportedOperationException}, the {@code catch} takes it, and the fallback comes
 * back either way. What changes is that a normal absent-optional-field read would start
 * logging a warning. Verified by mutation, and worth stating because the line otherwise looks
 * redundant to anyone reading the {@code catch} below it.</p>
 *
 * <p><b>The warn is unconditional; there is no suppression cache.</b> A dedup map would put
 * mutable state in a class whose whole charter is holding policy, to throttle a condition that
 * previously destroyed the entire payload at four of the five clusters. Warning once per
 * occurrence is strictly less disruptive than that. The ceiling worth knowing:
 * {@code OnlineMemberService}'s roster loop reads three fields per member, so a wholly
 * malformed roster costs three warns per member per poll. Throttling is a separate change if
 * it ever bites.</p>
 *
 * <p><b>Gson caveats that survive unchanged</b>, and that the "wrong type gives the fallback"
 * row does not cover:</p>
 *
 * <ul>
 *   <li><b>A singleton array is unwrapped, not rejected.</b> Gson delegates {@code getAsString}
 *       / {@code getAsInt} on a one-element array to that element, so {@code ["x"]} arrives as
 *       {@code "x"} and never reaches the {@code catch}. No accessor in the repo ever rejected
 *       this shape. A two-element array does throw, and does fall back.</li>
 *   <li><b>Primitives coerce.</b> {@code 42} read as a string gives {@code "42"};
 *       {@code true} gives {@code "true"}. A wrongly-but-primitively typed field is accepted
 *       silently, because to Gson it is not wrongly typed.</li>
 *   <li><b>{@code optInt} is lenient in three more ways.</b> It parses a numeric string
 *       ({@code "42"} &rarr; {@code 42}); it truncates a decimal toward zero ({@code 3.9}
 *       &rarr; {@code 3}, {@code -3.9} &rarr; {@code -3}); and it <i>narrows</i> an
 *       out-of-range literal rather than rejecting it, so a JSON {@code 4294967298} arrives as
 *       {@code 2}. None of the three fires the fallback or the warn.</li>
 * </ul>
 *
 * <p><b>Scope.</b> These four are what those seven declarations and three named inline
 * reads collapse onto &mdash; {@code NameResolver}'s {@code legacyName} and {@code uuid}, and
 * {@code WarningRewriter}'s {@code points_after}. The other thirty-one inlined
 * {@code isJsonNull()} reads across the client are deliberately out of scope, and that is a
 * boundary rather than a gap: most of them throw on a wrong type today, so each conversion is
 * its own behaviour change and belongs in its own commit.</p>
 */
public final class Json {

    /**
     * The shared parser/serialiser. {@code new Gson()} with no builder, so HTML-escaping and
     * null-suppression are both at their defaults, exactly as all twenty-one copies had them.
     */
    public static final Gson GSON = new Gson();

    private Json() {}

    /**
     * Reads {@code key} as a string, answering {@code fallback} on every failure.
     *
     * <p>Missing keys and JSON nulls are normal and fall back silently. A wrong-typed value or
     * a {@code null} receiver falls back too, and warns. See the class Javadoc for what "wrong
     * type" does and does not include &mdash; notably a singleton array is unwrapped by Gson
     * and never gets here.</p>
     *
     * @param obj      the object to read; a {@code null} is tolerated and warns
     * @param key      the field name
     * @param fallback the value to return on a miss, a wrong type or a null receiver; may
     *                 itself be {@code null}
     * @return the field's string value, or {@code fallback}
     */
    public static String optString(JsonObject obj, String key, String fallback) {
        if (obj == null) {
            VetsLogger.warn("Json.optString: null object reading '{}'; using the fallback", key);
            return fallback;
        }
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsString();
        } catch (RuntimeException e) {
            VetsLogger.warn(
                    "Json.optString: '{}' is not a string ({}); using the fallback",
                    key,
                    e.getClass().getSimpleName());
            return fallback;
        }
    }

    /**
     * {@link #optString} with {@code null} as the fallback.
     *
     * <p>Exists so the call sites that never supplied a fallback did not grow an argument
     * &mdash; twelve when this was written, <b>seventeen</b> once {@code NameResolver}'s five
     * member-field reads joined them. {@code null} <i>is</i> a fallback; this is not a fourth policy.</p>
     *
     * @param obj the object to read; a {@code null} is tolerated and warns
     * @param key the field name
     * @return the field's string value, or {@code null}
     */
    public static String stringOrNull(JsonObject obj, String key) {
        return optString(obj, key, null);
    }

    /**
     * {@link #optString} with the empty string as the fallback.
     *
     * <p>Note what this collapses: "absent", "present but JSON-null", "wrongly typed" and
     * "present and empty" all read as {@code ""}, and the caller cannot tell them apart. Most
     * of its eleven call sites gate on {@code isEmpty()} on the next line and do not care; the
     * ones that do not ({@code type}, {@code tier}) feed an {@code equals} comparison or a
     * display field where {@code ""} is already the neutral value.</p>
     *
     * @param obj the object to read; a {@code null} is tolerated and warns
     * @param key the field name
     * @return the field's string value, or {@code ""}
     */
    public static String stringOrEmpty(JsonObject obj, String key) {
        return optString(obj, key, "");
    }

    /**
     * Reads {@code key} as an {@code int}, answering {@code fallback} on every failure.
     *
     * <p>Same three-axis policy as {@link #optString}, and the same Gson leniency plus three
     * more: numeric strings parse, decimals truncate toward zero, and an out-of-range literal
     * narrows rather than failing. All three are documented on the class.</p>
     *
     * @param obj      the object to read; a {@code null} is tolerated and warns
     * @param key      the field name
     * @param fallback the value to return on a miss, a wrong type or a null receiver
     * @return the field's int value, or {@code fallback}
     */
    public static int optInt(JsonObject obj, String key, int fallback) {
        if (obj == null) {
            VetsLogger.warn("Json.optInt: null object reading '{}'; using the fallback", key);
            return fallback;
        }
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException e) {
            VetsLogger.warn(
                    "Json.optInt: '{}' is not an int ({}); using the fallback",
                    key,
                    e.getClass().getSimpleName());
            return fallback;
        }
    }
}
