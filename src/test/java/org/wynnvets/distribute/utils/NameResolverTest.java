package org.wynnvets.distribute.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NameResolver}'s four payload extractors plus
 * {@code normalizeUuid}.
 *
 * <p>These decide which Members-GUI tile a distribution lands on, so a wrong
 * answer misroutes resources rather than merely logging oddly. The HTTP halves
 * are not covered — they reach {@code GuildStateManager} and Wynntils
 * {@code Models.Guild} — but every extractor takes a response body as a plain
 * string, and the whole class's static state is one {@code HttpClient} and one
 * {@code Gson}.</p>
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
     * A wapi v3 guild payload. Casey renamed (tile shows "Dana"); Dana is a
     * different member who never renamed but whose current name collides with
     * Casey's legacy name; Erin never renamed; "Frank" is a well-formed member
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
                  "Casey": {"uuid": "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", "legacyName": "Dana"}
                },
                "chief": {
                  "Dana": {"uuid": "11111111-2222-3333-4444-555555555555", "legacyName": null},
                  "Erin": {"uuid": "99999999999999999999999999999999"},
                  "Frank": {"legacyName": "OldFrank"},
                  "Broken": 42
                }
              }
            }
            """;

    // ----- normalizeUuid -----

    @Test
    void normalizeUuid_stripsDashesAndLowercases() {
        assertEquals(
                "aaaaaaaabbbbccccddddeeeeeeeeeeee",
                NameResolver.normalizeUuid("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"));
        assertEquals(
                "aaaaaaaabbbbccccddddeeeeeeeeeeee",
                NameResolver.normalizeUuid("aaaaaaaabbbbccccddddeeeeeeeeeeee"),
                "an already-normalised uuid is idempotent");
    }

    @Test
    void normalizeUuid_nullBecomesEmptyStringNotNull() {
        // Callers use the result as a map key, so a null would poison the index
        // rather than simply missing.
        assertEquals("", NameResolver.normalizeUuid(null));
    }

    // ----- extractAllLegacyNames -----

    @Test
    void extractAllLegacyNames_prefersLegacyAndFallsBackToCurrent() {
        List<String> names = NameResolver.extractAllLegacyNames(ROSTER);

        assertEquals(
                List.of("Dana", "Dana", "Erin", "OldFrank", "Broken"),
                names,
                "Casey and Frank renamed; Dana, Erin and Broken fall back to their key");
    }

    @Test
    void extractAllLegacyNames_keepsMalformedEntriesUnderTheirCurrentName() {
        // The asymmetry: a member whose JSON value is not an object still
        // contributes a name here, because legacyNameOf tolerates null and the
        // handler has no null guard of its own.
        assertTrue(
                NameResolver.extractAllLegacyNames(ROSTER).contains("Broken"),
                "a malformed entry is not skipped by this extractor");
    }

    @Test
    void extractAllLegacyNames_canProduceDuplicates() {
        // Two distinct members both display as "Dana". The list is not
        // deduplicated, so a random pick weights that tile twice.
        List<String> names = NameResolver.extractAllLegacyNames(ROSTER);
        assertEquals(2, names.stream().filter("Dana"::equals).count());
    }

    // ----- extractUuidToLegacyName -----

    @Test
    void extractUuidToLegacyName_keysOnTheNormalisedUuid() {
        Map<String, String> map = NameResolver.extractUuidToLegacyName(ROSTER);

        assertEquals("Dana", map.get("aaaaaaaabbbbccccddddeeeeeeeeeeee"), "Casey's renamed tile");
        assertEquals("Dana", map.get("11111111222233334444555555555555"), "Dana never renamed");
        assertEquals("Erin", map.get("99999999999999999999999999999999"));
    }

    @Test
    void extractUuidToLegacyName_skipsMalformedAndUuidlessEntries() {
        // The asymmetry with extractAllLegacyNames: no uuid means no key, so
        // the member simply cannot be opted out. Two distinct guards produce
        // that outcome and the fixture exercises both — "Broken" trips the
        // `member == null` check, "Frank" is a perfectly good member object
        // that trips the later `uuid == null` one.
        Map<String, String> map = NameResolver.extractUuidToLegacyName(ROSTER);

        assertEquals(3, map.size(), "Casey, Dana and Erin only");
        assertTrue(!map.containsValue("Broken"), "no member object");
        assertTrue(!map.containsValue("OldFrank"), "member object, but no uuid to key it on");
    }

    // ----- extractNameIndex -----

    @Test
    void extractNameIndex_registersBothTheCurrentAndTheLegacyForm() {
        Map<String, String> index = NameResolver.extractNameIndex(ROSTER);

        assertEquals("Dana", index.get("casey"), "the current name resolves to the tile name");
        assertEquals("Dana", index.get("dana"), "so does the legacy name");
        assertEquals("Erin", index.get("erin"));
    }

    @Test
    void extractNameIndex_keysAreLowercased() {
        Map<String, String> index = NameResolver.extractNameIndex(ROSTER);

        assertTrue(index.containsKey("casey"));
        assertTrue(!index.containsKey("Casey"), "the raw-case key is not registered");
    }

    @Test
    void extractNameIndex_foldsWithLocaleRootSoATurkishClientIndexesTheSameKeys() {
        // The argument to toLowerCase is load-bearing and several neighbours in
        // this repo omit it. "Erin" contains a capital I, which a default-locale
        // fold would turn into the dotless U+0131 — so the key would be "erın"
        // and every lookup for "erin" would miss.
        Locale.setDefault(TURKISH);

        Map<String, String> index = NameResolver.extractNameIndex(ROSTER);

        assertEquals("Erin", index.get("erin"), "ASCII i, not the dotless one");
        assertTrue(!index.containsKey("erın"), "the default-locale key is never produced");
    }

    @Test
    void extractNameIndex_malformedEntriesStillGetAnEntry() {
        assertEquals("Broken", NameResolver.extractNameIndex(ROSTER).get("broken"));
    }

    // ----- findLegacyName -----

    @Test
    void findLegacyName_resolvesACurrentNameToItsLegacyName() {
        assertEquals("Dana", NameResolver.findLegacyName(ROSTER, "Casey"));
    }

    @Test
    void findLegacyName_isCaseInsensitiveOnBothSides() {
        assertEquals("Dana", NameResolver.findLegacyName(ROSTER, "casey"));
        assertEquals("Dana", NameResolver.findLegacyName(ROSTER, "CASEY"));
    }

    @Test
    void findLegacyName_acceptsALegacyNameVerbatim() {
        assertEquals("Erin", NameResolver.findLegacyName(ROSTER, "erin"));
    }

    @Test
    void findLegacyName_payloadOrderDecidesACollisionBetweenALegacyAndACurrentName() {
        // "Dana" is Casey's legacy name and also another member's current name.
        // The walk returns on the first hit and Gson preserves document order,
        // so the owner bucket's legacy match wins over the chief bucket's
        // current-name match. Both happen to answer "Dana" here, which is why
        // the collision is invisible in production — and why it needs pinning
        // before anything reorders the walk.
        assertEquals("Dana", NameResolver.findLegacyName(ROSTER, "dana"));
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
            assertEquals("Casey", NameResolver.findLegacyName(body, "Casey"), body);
        }
    }

    @Test
    void nullPayloadIsAlsoFailOpen() {
        assertEquals(List.of(), NameResolver.extractAllLegacyNames(null));
        assertEquals("Casey", NameResolver.findLegacyName(null, "Casey"));
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
                        "{\"members\": {\"total\": {\"Alice\": {\"legacyName\": \"A\"}}}}"),
                "a bucket named total is skipped whatever it contains");
    }
}
