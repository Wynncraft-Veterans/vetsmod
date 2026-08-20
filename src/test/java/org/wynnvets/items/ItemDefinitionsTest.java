package org.wynnvets.items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ItemDefinitions}' eight name predicates, driven against the
 * real {@code definitions.yml} plus small parsed-in fixtures.
 *
 * <p>The file is the contract, not a stub: {@code src/client/resources} is on
 * the test runtime classpath, so {@code load()} reads exactly what production
 * reads. Four behaviours here are invisible from the Java side and would be
 * silently normalised by a table-driven rewrite, which is why they are pinned:
 * per-pattern case-sensitivity via inline {@code (?i)}, the empty-guard
 * asymmetry between the five guarded sections and the four unguarded ones,
 * {@code isUnenchanted}'s {@code isLegacy} veto, and the fact that inline
 * {@code #} comments are stripped as a side effect of quote handling rather
 * than by any comment logic.</p>
 *
 * <p>{@code isEnchantExcludedItem(ItemStack)} is not covered — it resolves
 * {@code BuiltInRegistries.ITEM}, which needs {@code Bootstrap.bootStrap()}.
 * It is the ninth public query and the only untestable one.</p>
 *
 * <p>NOTE: {@link ItemDefinitions} imports {@code BuiltInRegistries} and
 * {@code ItemStack}, but its static state is nine empty collections, so nothing
 * loads during class-init. If a future contributor adds a static initializer
 * that touches the registries, this test starts failing at class-init time.</p>
 */
class ItemDefinitionsTest {

    // The mod calls load() during init; the harness does not. Load once here so
    // the predicates have the real 203-line file behind them.
    static {
        ItemDefinitions.load();
    }

    // Test-only: load() clears all nine collections before refilling, so it
    // doubles as the reset that undoes any fixture a test parsed in.
    @AfterEach
    void reloadRealDefinitions() {
        ItemDefinitions.load();
    }

    /** Appends a fixture to the already-loaded real definitions — {@code parse}
     *  does not clear, only {@code load()} does. */
    private static void parseFixture(String yaml) throws IOException {
        ItemDefinitions.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    // ----- Per-pattern case sensitivity -----

    @Test
    void isLegacy_caseSensitivityIsPerPatternNotPerCategory() {
        // Most entries carry an inline (?i); a handful deliberately do not, and
        // nothing in the Java distinguishes them. "(?i)^Santa.*$" folds case;
        // "^Wolf Fang$" and "^Corrupted Treasure$" do not.
        assertTrue(ItemDefinitions.isLegacy("SANTA HAT"), "(?i) entry folds case");
        assertTrue(ItemDefinitions.isLegacy("santa hat"));

        assertTrue(ItemDefinitions.isLegacy("Wolf Fang"));
        assertFalse(ItemDefinitions.isLegacy("wolf fang"), "no (?i) on this entry");
        assertFalse(ItemDefinitions.isLegacy("WOLF FANG"));

        assertTrue(ItemDefinitions.isLegacy("Corrupted Treasure"));
        assertFalse(ItemDefinitions.isLegacy("corrupted treasure"));
    }

    @Test
    void isNotJunk_sameSplitInADifferentCategory() {
        assertTrue(ItemDefinitions.isNotJunk("leather"), "(?i)^Leather$");
        assertTrue(ItemDefinitions.isNotJunk("Leather"));
        assertTrue(ItemDefinitions.isNotJunk("Tanned Sunfish"));
        assertFalse(ItemDefinitions.isNotJunk("tanned sunfish"), "^Tanned Sunfish$ has no (?i)");
    }

    // ----- matches(), not find() -----

    @Test
    void predicatesRequireAFullMatchNotASubstring() {
        // Every loop uses matcher.matches(). An unanchored pattern would behave
        // very differently under find(): "^Ability Shard$" would then match any
        // name containing it.
        assertTrue(ItemDefinitions.isUnenchanted("Ability Shard"));
        assertFalse(
                ItemDefinitions.isUnenchanted("My Ability Shard"),
                "find() would accept this; matches() does not");
        assertFalse(ItemDefinitions.isUnenchanted("Ability Shards"));
    }

    // ----- The one cross-category precedence -----

    @Test
    void isUnenchanted_isVetoedByIsLegacy() throws IOException {
        // The only place one category consults another. A name in both lists is
        // legacy, not unenchanted — collapsing the eight predicates into one
        // table-driven lookup drops this edge unless it is carried explicitly.
        parseFixture(
                """
                definitions:
                  - "^VetoProbe$"
                unenchanted:
                  - "^VetoProbe$"
                  - "^VetoOnly$"
                """);

        assertTrue(ItemDefinitions.isLegacy("VetoProbe"));
        assertFalse(
                ItemDefinitions.isUnenchanted("VetoProbe"),
                "isLegacy short-circuits before the unenchanted list is consulted");
        assertTrue(
                ItemDefinitions.isUnenchanted("VetoOnly"),
                "the same list still answers for a name that is not legacy");
    }

    // ----- The empty-guard asymmetry -----

    @Test
    void unguardedSectionsAcceptAnEmptyPatternAndGuardedOnesDropIt() throws IOException {
        // definitions, no_lore_legacy, misc_definitions and unenchanted have no
        // isEmpty() check; not_pedestal, notjunk, new_format_override,
        // enchant_excluded_items and blocked_screen_titles do. Almost certainly
        // unintentional — pinned so a later pass has to choose a direction
        // rather than normalise it by accident.
        assertFalse(ItemDefinitions.isLegacy(""), "precondition: no empty pattern is loaded yet");
        assertFalse(ItemDefinitions.isNotPedestal(""));

        parseFixture(
                """
                definitions:
                  - ""
                not_pedestal:
                  - ""
                """);

        assertTrue(
                ItemDefinitions.isLegacy(""),
                "the unguarded section compiled the empty pattern, which matches the empty"
                        + " name");
        assertFalse(
                ItemDefinitions.isNotPedestal(""),
                "the guarded section dropped it before Pattern.compile");
    }

    @Test
    void anEmptyPatternMatchesOnlyTheEmptyName() throws IOException {
        // Worth stating because "an empty regex" reads like "matches
        // everything". Under matches() it is the opposite: it matches nothing
        // but the empty string.
        parseFixture(
                """
                definitions:
                  - ""
                """);

        assertTrue(ItemDefinitions.isLegacy(""));
        assertFalse(ItemDefinitions.isLegacy("x"));
    }

    // ----- Inline comment stripping is accidental -----

    @Test
    void trailingCommentsAreStrippedOnlyBecauseTheValueIsQuoted() throws IOException {
        // extractQuotedString takes the substring between the first two quotes.
        // Nothing in the parser knows what a '#' is, so an unquoted entry keeps
        // its comment and compiles it into the pattern.
        parseFixture(
                """
                definitions:
                  - "^QuotedProbe$" # this comment is discarded
                  - ^BareProbe$ # this comment becomes part of the regex
                """);

        assertTrue(ItemDefinitions.isLegacy("QuotedProbe"));
        assertFalse(
                ItemDefinitions.isLegacy("BareProbe"),
                "the unquoted entry compiled as '^BareProbe$ # ...' and matches nothing");
    }

    @Test
    void wholeLineCommentsAreSkippedByTheParserItself() throws IOException {
        // A leading '#' is handled, including the commented-out list entries the
        // real file uses to park disabled patterns.
        parseFixture(
                """
                definitions:
                  #- "^DisabledProbe$"
                  - "^EnabledProbe$"
                """);

        assertFalse(ItemDefinitions.isLegacy("DisabledProbe"));
        assertTrue(ItemDefinitions.isLegacy("EnabledProbe"));
    }

    // ----- Parser shape -----

    @Test
    void sectionsEndAtTheDocumentSeparator() throws IOException {
        // "---" resets currentSection to null, so entries after it with no new
        // header are dropped rather than appended to the previous section.
        parseFixture(
                """
                definitions:
                  - "^BeforeSeparator$"
                ---
                  - "^AfterSeparator$"
                """);

        assertTrue(ItemDefinitions.isLegacy("BeforeSeparator"));
        assertFalse(ItemDefinitions.isLegacy("AfterSeparator"));
    }

    @Test
    void anUnknownSectionNameIsSilentlyIgnored() throws IOException {
        // No error, no log — a typo in a section header simply loses every
        // pattern under it.
        parseFixture(
                """
                defnitions:
                  - "^TypoProbe$"
                """);

        assertFalse(ItemDefinitions.isLegacy("TypoProbe"));
    }

    // ----- Null handling is inconsistent -----

    @Test
    void onlyIsBlockedScreenTitleGuardsNull() {
        // The screen-title predicate takes a nullable title from the screen
        // handler; the seven name predicates do not guard, and Matcher rejects
        // a null CharSequence.
        assertFalse(ItemDefinitions.isBlockedScreenTitle(null));

        assertThrows(NullPointerException.class, () -> ItemDefinitions.isLegacy(null));
        assertThrows(NullPointerException.class, () -> ItemDefinitions.isNoLoreLegacy(null));
        assertThrows(NullPointerException.class, () -> ItemDefinitions.isMiscLegacy(null));
        assertThrows(NullPointerException.class, () -> ItemDefinitions.isUnenchanted(null));
        assertThrows(NullPointerException.class, () -> ItemDefinitions.isNotPedestal(null));
        assertThrows(NullPointerException.class, () -> ItemDefinitions.isNotJunk(null));
        assertThrows(NullPointerException.class, () -> ItemDefinitions.isNewFormatOverride(null));
    }

    // ----- The real file answers for each remaining category -----

    @Test
    void eachCategoryIsPopulatedAndAnswersFromTheRealFile() {
        assertTrue(ItemDefinitions.isNoLoreLegacy("Rotten Flesh"));
        assertTrue(ItemDefinitions.isMiscLegacy("Gunpowder"));
        assertTrue(ItemDefinitions.isUnenchanted("Cycle"));
        assertTrue(ItemDefinitions.isNotPedestal("Armour Merchant"));
        assertTrue(ItemDefinitions.isNotJunk("Rubble"));
        assertTrue(ItemDefinitions.isNewFormatOverride("Skeleton Key"));
        assertTrue(ItemDefinitions.isBlockedScreenTitle("Permissions for Bob"));
    }

    @Test
    void categoriesAreIndependentOfEachOther() {
        // A name in "definitions" is not thereby in any other list. Only
        // isUnenchanted crosses categories.
        assertTrue(ItemDefinitions.isLegacy("Corrupted Treasure"));
        assertFalse(ItemDefinitions.isNoLoreLegacy("Corrupted Treasure"));
        assertFalse(ItemDefinitions.isMiscLegacy("Corrupted Treasure"));
        assertFalse(ItemDefinitions.isNotJunk("Corrupted Treasure"));
    }

    // ----- load() is the reset -----

    @Test
    void load_isIdempotentAndDiscardsParsedInFixtures() throws IOException {
        parseFixture(
                """
                definitions:
                  - "^ReloadProbe$"
                """);
        assertTrue(ItemDefinitions.isLegacy("ReloadProbe"));

        ItemDefinitions.load();

        assertFalse(ItemDefinitions.isLegacy("ReloadProbe"), "load() clears before it refills");
        assertTrue(ItemDefinitions.isLegacy("Wolf Fang"), "and the real file is back");

        ItemDefinitions.load();
        assertTrue(ItemDefinitions.isLegacy("Wolf Fang"), "and a repeat load() is stable");
    }
}
