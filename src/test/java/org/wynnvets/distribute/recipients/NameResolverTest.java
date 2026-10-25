package org.wynnvets.distribute.recipients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NameResolver}'s four payload extractors.
 *
 * <p>These decide which Members-GUI tile a distribution lands on, so a wrong
 * answer misroutes resources rather than merely logging oddly. The HTTP halves
 * are not covered — they reach {@code GuildStateManager} and Wynntils
 * {@code Models.Guild} — but every extractor takes a response body as a plain
 * string, and the whole class's static state is one {@code HttpClient}, taken
 * from {@link org.wynnvets.util.HttpClients HttpClients}. (It held a
 * {@code Gson} of its own too, until that was replaced by the shared
 * {@link org.wynnvets.util.Json Json#GSON}.)</p>
 *
 * <p>The three deliberate asymmetries pinned here are the ones a "these all
 * walk the same payload, share one helper" pass would flatten:</p>
 *
 * <ul>
 *   <li>{@code findLegacyName} <b>skips malformed entries</b>, so an input
 *       matching a malformed entry's current name returns the not-found
 *       fallback rather than that name;</li>
 *   <li>{@code extractUuidToLegacyName} skips null members and members with no
 *       uuid, while {@code extractAllLegacyNames} keeps null members and emits
 *       their current name;</li>
 *   <li>every parse failure is <b>fail-open</b> — an empty accumulator or the
 *       caller's own input, never an exception.</li>
 * </ul>
 */
class NameResolverTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr");

    /** Captured at class-init, before any test can have changed it. */
    private static final Locale ORIGINAL_DEFAULT = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(ORIGINAL_DEFAULT);
    }

    /**
     * A wapi v3 guild payload. Wenweia renamed (tile shows "Holidaze"); Holidaze is a
     * different member who never renamed but whose current name collides with
     * Wenweia's legacy name; Metrafish never renamed; "Nazzae" is a well-formed member
     * object carrying no uuid at all; "Broken" is a malformed entry whose value
     * is not an object.
     */
    private static final String ROSTER =
            """
            {
              "name": "Returners",
              "members": {
                "total": 4,
                "owner": {
                  "Wenweia": {"uuid": "085D0E58-29D4-44AA-8379-92A4568A59D6", "legacyName": "Holidaze"}
                },
                "chief": {
                  "Holidaze": {"uuid": "fa8aa700-4538-485f-bf91-325263606995", "legacyName": null},
                  "Metrafish": {"uuid": "bb171c6870804d02be7bcc2d5dcbcd82"},
                  "Nazzae": {"legacyName": "OldNazzae"},
                  "Broken": 42
                }
              }
            }
            """;

    // ----- extractAllLegacyNames -----

    @Test
    void extractAllLegacyNames_prefersLegacyAndFallsBackToCurrent() {
        List<String> names = NameResolver.extractAllLegacyNames(ROSTER);

        assertEquals(
                List.of("Holidaze", "Holidaze", "Metrafish", "OldNazzae", "Broken"),
                names,
                "Wenweia and Nazzae renamed; Holidaze, Metrafish and Broken fall back to their key");
    }

    @Test
    void extractAllLegacyNames_keepsMalformedEntriesUnderTheirCurrentName() {
        // The asymmetry: a member whose JSON value is not an object still
        // contributes a name here, because Json.stringOrNull tolerates a null
        // receiver (answering null, with a warn) and the handler has no null
        // guard of its own.
        assertTrue(
                NameResolver.extractAllLegacyNames(ROSTER).contains("Broken"),
                "a malformed entry is not skipped by this extractor");
    }

    @Test
    void extractAllLegacyNames_canProduceDuplicates() {
        // Two distinct members both display as "Holidaze". The list is not
        // deduplicated here; RandomDistributor dedupes it before picking.
        List<String> names = NameResolver.extractAllLegacyNames(ROSTER);
        assertEquals(2, names.stream().filter("Holidaze"::equals).count());
    }

    // ----- extractUuidToLegacyName -----

    @Test
    void extractUuidToLegacyName_keysOnTheNormalisedUuid() {
        Map<String, String> map = NameResolver.extractUuidToLegacyName(ROSTER);

        assertEquals(
                "Holidaze", map.get("085d0e5829d444aa837992a4568a59d6"), "Wenweia's renamed tile");
        assertEquals(
                "Holidaze", map.get("fa8aa7004538485fbf91325263606995"), "Holidaze never renamed");
        assertEquals("Metrafish", map.get("bb171c6870804d02be7bcc2d5dcbcd82"));
    }

    @Test
    void extractUuidToLegacyName_skipsMalformedAndUuidlessEntries() {
        // The asymmetry with extractAllLegacyNames: no uuid means no key, so
        // the member is not filtered even while on the opt-out list. Two
        // distinct guards produce
        // that outcome and the fixture exercises both — "Broken" trips the
        // `member == null` check, "Nazzae" is a perfectly good member object
        // that trips the later `uuid == null` one.
        Map<String, String> map = NameResolver.extractUuidToLegacyName(ROSTER);

        assertEquals(3, map.size(), "Wenweia, Holidaze and Metrafish only");
        assertTrue(!map.containsValue("Broken"), "no member object");
        assertTrue(!map.containsValue("OldNazzae"), "member object, but no uuid to key it on");
    }

    // ----- extractNameIndex -----

    @Test
    void extractNameIndex_registersBothTheCurrentAndTheLegacyForm() {
        Map<String, String> index = NameResolver.extractNameIndex(ROSTER);

        assertEquals(
                "Holidaze", index.get("wenweia"), "the current name resolves to the tile name");
        assertEquals("Holidaze", index.get("holidaze"), "so does the legacy name");
        assertEquals("Metrafish", index.get("metrafish"));
    }

    @Test
    void extractNameIndex_keysAreLowercased() {
        Map<String, String> index = NameResolver.extractNameIndex(ROSTER);

        assertTrue(index.containsKey("wenweia"));
        assertTrue(!index.containsKey("Wenweia"), "the raw-case key is not registered");
    }

    @Test
    void extractNameIndex_foldsWithLocaleRootSoATurkishClientIndexesTheSameKeys() {
        // The argument to toLowerCase is load-bearing and several neighbours in
        // this repo omit it. "Metrafish" contains a capital I, which a default-locale
        // fold would turn into the dotless U+0131 — so the key would be "metrafısh"
        // and every lookup for "metrafish" would miss.
        Locale.setDefault(TURKISH);

        Map<String, String> index = NameResolver.extractNameIndex(ROSTER);

        assertEquals("Metrafish", index.get("metrafish"), "ASCII i, not the dotless one");
        assertTrue(!index.containsKey("metrafısh"), "the default-locale key is never produced");
    }

    @Test
    void extractNameIndex_malformedEntriesStillGetAnEntry() {
        assertEquals("Broken", NameResolver.extractNameIndex(ROSTER).get("broken"));
    }

    // ----- findLegacyName -----

    @Test
    void findLegacyName_resolvesACurrentNameToItsLegacyName() {
        assertEquals("Holidaze", NameResolver.findLegacyName(ROSTER, "Wenweia"));
    }

    @Test
    void findLegacyName_isCaseInsensitiveOnBothSides() {
        assertEquals("Holidaze", NameResolver.findLegacyName(ROSTER, "wenweia"));
        assertEquals("Holidaze", NameResolver.findLegacyName(ROSTER, "WENWEIA"));
    }

    @Test
    void findLegacyName_acceptsALegacyNameVerbatim() {
        assertEquals("Metrafish", NameResolver.findLegacyName(ROSTER, "metrafish"));
    }

    @Test
    void findLegacyName_payloadOrderDecidesACollisionBetweenALegacyAndACurrentName() {
        // "Holidaze" is Wenweia's legacy name and also another member's current name.
        // The walk returns on the first hit and Gson preserves document order,
        // so the owner bucket's legacy match wins over the chief bucket's
        // current-name match. Both happen to answer "Holidaze" here, which is why
        // the collision is invisible in production — and why it needs pinning
        // before anything reorders the walk.
        assertEquals("Holidaze", NameResolver.findLegacyName(ROSTER, "holidaze"));
    }

    @Test
    void findLegacyName_skipsMalformedEntriesSoTheirNameFallsBackToTheInput() {
        // Load-bearing, and the reason the source carries a comment about it.
        // "Broken" is a real key in the payload, but its entry is skipped, so
        // the method never matches it and returns the caller's input verbatim —
        // preserving the input's case rather than the payload's.
        assertEquals(
                "broken",
                NameResolver.findLegacyName(ROSTER, "broken"),
                "the input is returned unchanged, not normalised to the payload's spelling");
    }

    @Test
    void findLegacyName_unknownInputIsReturnedUnchanged() {
        assertEquals("Nobody", NameResolver.findLegacyName(ROSTER, "Nobody"));
    }

    // ----- Fail-open on every malformed payload -----

    @Test
    void malformedPayloadsYieldEmptyResultsRatherThanThrowing() {
        for (String body :
                List.of(
                        "",
                        "not json at all",
                        "[]",
                        "{}",
                        "{\"members\": 7}",
                        "{\"members\": {\"owner\": 3}}")) {
            assertEquals(List.of(), NameResolver.extractAllLegacyNames(body), body);
            assertEquals(Map.of(), NameResolver.extractUuidToLegacyName(body), body);
            assertEquals(Map.of(), NameResolver.extractNameIndex(body), body);
            assertEquals("Wenweia", NameResolver.findLegacyName(body, "Wenweia"), body);
        }
    }

    @Test
    void nullPayloadIsAlsoFailOpen() {
        assertEquals(List.of(), NameResolver.extractAllLegacyNames(null));
        assertEquals("Wenweia", NameResolver.findLegacyName(null, "Wenweia"));
    }

    @Test
    void theTotalBucketIsSkippedByName() {
        // The payload labels the member count "total" alongside the rank
        // buckets. It is skipped by key, not by shape — so a rank literally
        // named "total" would be dropped too.
        assertTrue(!NameResolver.extractAllLegacyNames(ROSTER).contains("total"));
        assertEquals(
                List.of(),
                NameResolver.extractAllLegacyNames(
                        "{\"members\": {\"total\": {\"Metrafish\": {\"legacyName\": \"A\"}}}}"),
                "a bucket named total is skipped whatever it contains");
    }
}
