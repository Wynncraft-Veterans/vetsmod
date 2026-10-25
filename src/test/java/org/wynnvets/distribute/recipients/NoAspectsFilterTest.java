package org.wynnvets.distribute.recipients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NoAspectsFilter}'s two pure halves — the opt-out payload
 * parse and the UUID-to-legacy-name translation — and for the ungated arm of
 * {@code fetchExcludedLegacyNames}, which makes no request.
 *
 * <p>A wrong answer here defeats the opt-out list: either an aspect is
 * dispatched to someone who already owns every one available to them, or a
 * member is wrongly withheld from — skipped by every selector, refused on the
 * literal path — on an aspects run, or on a tomes or emeralds run the list was
 * never meant to gate. The class has <b>zero</b>
 * Wynntils imports and one {@code HttpClient} as its only static state, taken
 * from {@link org.wynnvets.util.HttpClients HttpClients}; it parses through the
 * shared {@link org.wynnvets.util.Json Json#GSON} rather than a {@code Gson} of
 * its own.</p>
 *
 * <p>Both halves are fail-open by design — an empty exclude set means nobody is
 * filtered, which is the pre-opt-out behaviour, so a flaky fetch degrades to
 * "distribute to everyone" rather than blocking the command. That is pinned in
 * both directions, including the sharp edge: a single unusable {@code uuid}
 * value discards the <em>whole</em> list rather than just that entry, because
 * the per-element guards do not cover what {@code getAsString} can throw.</p>
 */
class NoAspectsFilterTest {

    private static final String DASHED = "BB171C68-7080-4D02-BE7B-CC2D5DCBCD82";
    private static final String NORMALISED = "bb171c6870804d02be7bcc2d5dcbcd82";

    // ----- fetchExcludedLegacyNames -----

    @Test
    void fetchExcludedLegacyNames_ungated_isAnEmptySetAlreadyComplete() {
        // A tomes or emeralds run. Only the false arm is called: the true arm
        // makes two network requests.
        CompletableFuture<Set<String>> excluded = NoAspectsFilter.fetchExcludedLegacyNames(false);
        assertTrue(excluded.isDone());
        assertEquals(Set.of(), excluded.join());
    }

    // ----- parseUuids -----

    @Test
    void parseUuids_normalisesEveryEntry() {
        // The endpoint emits dashed UUIDs; wapi may emit either form. Both
        // sides normalise so the join in buildExcludedNames can be a plain
        // map lookup.
        Set<String> uuids =
                NoAspectsFilter.parseUuids(
                        "[{\"uuid\": \"" + DASHED + "\", \"username\": \"Metrafish\"}]");

        assertEquals(Set.of(NORMALISED), uuids);
    }

    @Test
    void parseUuids_dedupesAcrossSpellings() {
        // Same account listed dashed and undashed collapses to one entry,
        // because the set is keyed on the normalised form.
        Set<String> uuids =
                NoAspectsFilter.parseUuids(
                        "[{\"uuid\": \"" + DASHED + "\"}, {\"uuid\": \"" + NORMALISED + "\"}]");

        assertEquals(1, uuids.size());
    }

    @Test
    void parseUuids_skipsEntriesWithNoUsableUuid() {
        // Per-element continue: a bad neighbour does not cost the good entries.
        Set<String> uuids =
                NoAspectsFilter.parseUuids(
                        """
                        [
                          {"username": "NoUuid"},
                          {"uuid": null},
                          {"uuid": ""},
                          "not an object",
                          123,
                          {"uuid": "fa8aa700-4538-485f-bf91-325263606995"}
                        ]
                        """);

        assertEquals(Set.of("fa8aa7004538485fbf91325263606995"), uuids);
    }

    @Test
    void parseUuids_coercesANumericUuidRatherThanRejectingIt() {
        // getAsString on a JSON number returns its text, so a mistyped payload
        // contributes a garbage key rather than being skipped. Harmless — it
        // simply matches no guild member — but it is not a rejection.
        assertEquals(Set.of("123"), NoAspectsFilter.parseUuids("[{\"uuid\": 123}]"));
    }

    @Test
    void parseUuids_oneUnusableUuidValueDiscardsTheEntireList() {
        // The sharp edge. getAsString throws on an object-valued uuid, or on
        // an array unless Gson can unwrap it to a single string-readable
        // element, and the catch is around the whole loop rather than the
        // element,
        // so every already-parsed entry is thrown away with it. A later
        // refactor that moves the try inside the loop would change which
        // members get filtered.
        Set<String> uuids =
                NoAspectsFilter.parseUuids(
                        "[{\"uuid\": \"" + DASHED + "\"}, {\"uuid\": {\"nested\": 1}}]");

        assertEquals(Set.of(), uuids, "the good first entry is lost along with the bad second");
    }

    @Test
    void parseUuids_failsOpenOnAnythingThatIsNotAJsonArray() {
        for (String body :
                List.of("", "not json", "{}", "{\"uuids\": []}", "null", "\"a string\"", "7")) {
            assertEquals(Set.of(), NoAspectsFilter.parseUuids(body), body);
        }
        assertEquals(Set.of(), NoAspectsFilter.parseUuids(null));
    }

    @Test
    void parseUuids_emptyArrayIsAnEmptySetNotAFailure() {
        assertEquals(Set.of(), NoAspectsFilter.parseUuids("[]"));
    }

    // ----- buildExcludedNames -----

    @Test
    void buildExcludedNames_translatesUuidsToTileNames() {
        Set<String> names =
                NoAspectsFilter.buildExcludedNames(
                        Set.of(NORMALISED, "fa8aa7004538485fbf91325263606995"),
                        Map.of(
                                NORMALISED,
                                "OldMetrafish",
                                "fa8aa7004538485fbf91325263606995",
                                "Holidaze",
                                "085d0e5829d444aa837992a4568a59d6",
                                "Wenweia"));

        assertEquals(
                Set.of("OldMetrafish", "Holidaze"),
                names,
                "only the opted-out members are excluded");
    }

    @Test
    void buildExcludedNames_dropsUuidsThatAreNotInTheCurrentGuild() {
        // The opt-out list legitimately retains members who have since left, so
        // this is a silent no-op rather than a warning.
        Set<String> names =
                NoAspectsFilter.buildExcludedNames(
                        Set.of(NORMALISED, "deadbeefdeadbeefdeadbeefdeadbeef"),
                        Map.of(NORMALISED, "OldMetrafish"));

        assertEquals(Set.of("OldMetrafish"), names);
    }

    @Test
    void buildExcludedNames_collapsesTwoUuidsSharingATileName() {
        // Distinct accounts can display the same legacy name; the result is a
        // set of names, so both exclusions land on the one tile.
        Set<String> names =
                NoAspectsFilter.buildExcludedNames(
                        Set.of(NORMALISED, "fa8aa7004538485fbf91325263606995"),
                        Map.of(NORMALISED, "Nazzae", "fa8aa7004538485fbf91325263606995", "Nazzae"));

        assertEquals(Set.of("Nazzae"), names);
    }

    @Test
    void buildExcludedNames_failsOpenWhenEitherSideIsEmpty() {
        // Both directions matter: an empty opt-out list means nobody opted out,
        // and an empty roster map means the wapi fetch failed. Neither may
        // block the distribution.
        assertTrue(NoAspectsFilter.buildExcludedNames(Set.of(), Map.of(NORMALISED, "A")).isEmpty());
        assertTrue(NoAspectsFilter.buildExcludedNames(Set.of(NORMALISED), Map.of()).isEmpty());
        assertTrue(NoAspectsFilter.buildExcludedNames(Set.of(), Map.of()).isEmpty());
    }

    @Test
    void buildExcludedNames_lookupIsExactSoBothSidesMustBeNormalised() {
        // The join is a plain map get with no folding of its own. If either end
        // ever stopped normalising, every lookup would miss and the opt-out
        // list would silently stop working.
        assertEquals(
                Set.of(),
                NoAspectsFilter.buildExcludedNames(
                        Set.of(DASHED), Map.of(NORMALISED, "OldMetrafish")),
                "a dashed key does not find a normalised entry");
        assertEquals(
                Set.of(),
                NoAspectsFilter.buildExcludedNames(
                        Set.of(NORMALISED),
                        Map.of(NORMALISED.toUpperCase(Locale.ROOT), "OldMetrafish")),
                "nor does a case-mismatched one");
    }

    // ----- The two halves compose -----

    @Test
    void parseUuidsFeedsBuildExcludedNamesWithoutFurtherNormalisation() {
        Set<String> uuids = NoAspectsFilter.parseUuids("[{\"uuid\": \"" + DASHED + "\"}]");

        assertEquals(
                Set.of("OldMetrafish"),
                NoAspectsFilter.buildExcludedNames(uuids, Map.of(NORMALISED, "OldMetrafish")));
    }

    @Test
    void anEmptyResultIsTheSharedImmutableInstanceOnEveryFailurePath() {
        // Set.of() rather than a fresh HashSet, so callers must not mutate the
        // result of a failed fetch. Both exit paths are covered: "garbage" and
        // "{}" return from the not-an-array guard, the object-valued uuid from
        // the catch block at the bottom.
        assertSame(NoAspectsFilter.parseUuids("garbage"), NoAspectsFilter.parseUuids("{}"));
        assertSame(
                NoAspectsFilter.parseUuids("{}"),
                NoAspectsFilter.parseUuids("[{\"uuid\": {\"nested\": 1}}]"),
                "the catch path returns the same shared instance as the guard");
    }
}
