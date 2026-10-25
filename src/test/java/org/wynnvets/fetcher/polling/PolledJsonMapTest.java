package org.wynnvets.fetcher.polling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wynnvets.api.VetsApi;

/**
 * Normalizer symmetry and the cold-cache contract for {@link PolledJsonMap}.
 *
 * <p>The parse body itself is carried-across code and is not what this pins. What is new is
 * that one field now decides how keys are spelled on <em>both</em> sides of the map, and the
 * two instances disagree about what that field should be: {@link PolledJsonMap#WYNN_ALIASES}
 * folds case, {@link PolledJsonMap#GUILD_ROSTER} does not. Get that asymmetric and stale-named
 * players stop resolving in {@code /wv list} with no failed fetch and no log line to point at
 * it, so it is worth a test even though the code around it is unchanged.</p>
 *
 * <p>Only {@link PolledJsonMap#parse(String)} is exercised. The network half is the same
 * untestable shape the rest of the fetcher tree has — no {@code mockwebserver}-class
 * dependency exists in this build — and the two instances under test are the real production
 * constants, reset to empty after each case.</p>
 */
class PolledJsonMapTest {

    @AfterEach
    void resetBothMaps() {
        PolledJsonMap.GUILD_ROSTER.parse("{}");
        PolledJsonMap.WYNN_ALIASES.parse("{}");
    }

    @Test
    void aliasKeysAreFoldedOnBothSides() {
        PolledJsonMap.WYNN_ALIASES.parse("{\"OldNamE\": \"uuid-1\"}");

        // Ingest folded the key, so every spelling of it has to resolve.
        assertEquals("uuid-1", PolledJsonMap.WYNN_ALIASES.get("OldNamE"));
        assertEquals("uuid-1", PolledJsonMap.WYNN_ALIASES.get("oldname"));
        assertEquals("uuid-1", PolledJsonMap.WYNN_ALIASES.get("OLDNAME"));
    }

    @Test
    void aliasKeysAreStoredFolded() {
        PolledJsonMap.WYNN_ALIASES.parse("{\"OldNamE\": \"uuid-1\"}");

        // Lookup folding alone would leave the mixed-case key in the published map and the
        // whole map would then be a lookup miss. The snapshot is what proves ingest folded.
        assertEquals(Map.of("oldname", "uuid-1"), PolledJsonMap.WYNN_ALIASES.snapshot());
    }

    @Test
    void rosterKeysAreNotFolded() {
        PolledJsonMap.GUILD_ROSTER.parse("{\"A1B2-C3D4\": \"Steve\"}");

        // OnlineMemberService.merge() overlays these keys onto a map keyed by
        // UUID.toString(); folding them here would put a second, unmatched entry in it.
        assertEquals(Map.of("A1B2-C3D4", "Steve"), PolledJsonMap.GUILD_ROSTER.snapshot());
        assertEquals("Steve", PolledJsonMap.GUILD_ROSTER.get("A1B2-C3D4"));
        assertNull(PolledJsonMap.GUILD_ROSTER.get("a1b2-c3d4"));
    }

    @Test
    void aColdInstanceIsAnEmptyMapAndANullGet() {
        // merge() has no cold-start branch and depends on both halves of this. The two
        // production constants cannot be returned to their cold state once any case has run,
        // hence a fresh instance rather than an assertion on GUILD_ROSTER.
        PolledJsonMap cold =
                new PolledJsonMap(
                        VetsApi.ROSTER,
                        UnaryOperator.identity(),
                        "VetsMod-Test-ColdInstance",
                        "cold instance",
                        "items",
                        5,
                        5);

        assertTrue(cold.snapshot().isEmpty(), "a never-parsed map should be empty, not null");
        assertNull(cold.get("anything"));
    }

    @Test
    void anEmptyPayloadPublishesAnEmptyMap() {
        PolledJsonMap.GUILD_ROSTER.parse("{\"uuid-1\": \"Steve\"}");
        PolledJsonMap.GUILD_ROSTER.parse("{}");

        // An emptied roster must replace the old one rather than be treated as a failed
        // fetch; the server sends {} when it knows of no members.
        assertTrue(PolledJsonMap.GUILD_ROSTER.snapshot().isEmpty());
        assertNull(PolledJsonMap.GUILD_ROSTER.get("uuid-1"));
    }

    @Test
    void snapshotHandsBackOneStableReference() {
        PolledJsonMap.GUILD_ROSTER.parse("{\"uuid-1\": \"Steve\"}");
        Map<String, String> first = PolledJsonMap.GUILD_ROSTER.snapshot();
        assertSame(
                first,
                PolledJsonMap.GUILD_ROSTER.snapshot(),
                "two reads between polls must hand back the same map");

        // A caller holding a snapshot must not see the next poll land mid-iteration.
        PolledJsonMap.GUILD_ROSTER.parse("{\"uuid-2\": \"Alex\"}");
        assertEquals(
                Map.of("uuid-1", "Steve"), first, "a held snapshot was mutated by the next poll");
        assertEquals(Map.of("uuid-2", "Alex"), PolledJsonMap.GUILD_ROSTER.snapshot());
    }

    @Test
    void anUnparseableBodyLeavesThePreviousSnapshotInPlace() {
        PolledJsonMap.GUILD_ROSTER.parse("{\"uuid-1\": \"Steve\"}");
        PolledJsonMap.GUILD_ROSTER.parse("not json at all");
        assertEquals(Map.of("uuid-1", "Steve"), PolledJsonMap.GUILD_ROSTER.snapshot());
    }

    @Test
    void nullAndBlankKeysReturnNullRatherThanThrowing() {
        // get() runs the normalizer on its argument, so an unguarded null would NPE inside
        // the alias instance's lambda rather than at the map lookup.
        assertNull(PolledJsonMap.WYNN_ALIASES.get(null));
        assertNull(PolledJsonMap.WYNN_ALIASES.get(""));
        assertNull(PolledJsonMap.GUILD_ROSTER.get(null));
        assertNull(PolledJsonMap.GUILD_ROSTER.get(""));
    }

    @Test
    void nonPrimitiveAndEmptyValuesAreDropped() {
        PolledJsonMap.GUILD_ROSTER.parse(
                "{\"uuid-1\": \"Steve\", \"uuid-2\": \"\", \"uuid-3\": {\"nested\": 1},"
                        + " \"uuid-4\": [1, 2]}");
        assertEquals(Map.of("uuid-1", "Steve"), PolledJsonMap.GUILD_ROSTER.snapshot());
    }
}
