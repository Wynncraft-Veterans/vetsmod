package org.wynnvets.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Tests for {@link ItemDefinitions}' ten testable query methods — nine that
 * take an item name plus {@code isBlockedScreenTitle}, which takes a screen
 * title — driven against the real {@code definitions.yml} plus small parsed-in
 * fixtures.
 *
 * <p>The file is the contract, not a stub: {@code src/client/resources} is on
 * the test runtime classpath, so {@code load()} reads exactly what production
 * reads. The suite pins per-pattern case-sensitivity via inline {@code (?i)},
 * {@code isUnenchanted}'s {@code isLegacy} veto, which YAML quoting keeps a regex
 * backslash, and the parser's handling of empty entries, null and repeated
 * sections. The file is standard YAML, parsed by SnakeYAML, so comments,
 * quoting and {@code ---} document breaks follow YAML's rules.</p>
 *
 * <p>{@code isEnchantExcludedItem(ItemStack)} is not covered — it resolves
 * {@code BuiltInRegistries.ITEM}, which needs {@code Bootstrap.bootStrap()}.
 * It is the eleventh public query and the only untestable one.</p>
 *
 * <p>NOTE: {@link ItemDefinitions} imports {@code BuiltInRegistries} and
 * {@code ItemStack}, but its static state is empty collections and the section
 * table, so nothing loads during class-init. If a future contributor adds a static initializer
 * that touches the registries, this test starts failing at class-init time.</p>
 */
class ItemDefinitionsTest {

    // The mod calls load() during init; the harness does not. Load once here so
    // the predicates have the real file behind them.
    static {
        ItemDefinitions.load();
    }

    // Test-only: load() clears all eleven collections before refilling, so it
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
        // nothing in the Java distinguishes them. "(?i)^Santa Hat.*$" folds case;
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
    void predicatesRequireAFullMatchNotASubstring() throws IOException {
        // matchesAny uses matcher.matches(). Against the real file that choice
        // is almost invisible — every pattern is anchored with ^ and the few
        // without a trailing $ end in a greedy .* — so an anchored fixture would
        // behave identically under find() and pin nothing. The discriminating
        // input is an UNANCHORED pattern, which the file happens not to contain
        // but nothing stops a future entry from adding.
        parseFixture(
                """
                definitions:
                  - "Anchorless"
                """);

        assertTrue(ItemDefinitions.isLegacy("Anchorless"), "the exact name still matches");
        assertFalse(
                ItemDefinitions.isLegacy("My Anchorless Probe"),
                "find() would accept this substring; matches() requires the whole name");
    }

    @Test
    void anchoredPatternsFromTheRealFileStillRejectSubstrings() {
        // The same rule seen through the file as it actually is.
        assertTrue(ItemDefinitions.isUnenchanted("Ability Shard"));
        assertFalse(ItemDefinitions.isUnenchanted("My Ability Shard"));
        assertFalse(ItemDefinitions.isUnenchanted("Ability Shards"));
    }

    // ----- The one cross-category precedence -----

    @Test
    void isUnenchanted_isVetoedByIsLegacy() throws IOException {
        // The only place one section consults another: a name in both lists is
        // legacy, not unenchanted.
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

    // ----- Empty entries -----

    @Test
    void everySectionDropsAnEmptyEntry() throws IOException {
        // Under matches() an empty pattern matches only the empty name, so an empty
        // entry could only make a nameless item count as listed. Every section drops it.
        parseFixture(
                """
                definitions:
                  - ""
                misc_definitions:
                  - ""
                no_lore_legacy:
                  - ""
                new_format_override:
                  - ""
                unenchanted:
                  - ""
                notjunk:
                  - ""
                not_pedestal:
                  - ""
                blocked_screen_titles:
                  - ""
                inventory_suppressors:
                  - ""
                  - "^SuppressorProbe$"
                """);

        assertFalse(ItemDefinitions.isLegacy(""));
        assertFalse(ItemDefinitions.isMiscLegacy(""));
        assertFalse(ItemDefinitions.isNoLoreLegacy(""));
        assertFalse(ItemDefinitions.isNewFormatOverride(""));
        assertFalse(ItemDefinitions.isUnenchanted(""));
        assertFalse(ItemDefinitions.isNotJunk(""));
        assertFalse(ItemDefinitions.isNotPedestal(""));
        assertFalse(ItemDefinitions.isBlockedScreenTitle(""));
        assertFalse(ItemDefinitions.isInventorySuppressor(""));
        assertTrue(
                ItemDefinitions.isInventorySuppressor("SuppressorProbe"),
                "the section's other entries still load");
    }

    // ----- Comments and quoting follow YAML -----

    @Test
    void trailingCommentsAreStrippedFromQuotedAndPlainEntriesAlike() throws IOException {
        // YAML drops a trailing comment after a quoted or a bare entry.
        parseFixture(
                """
                definitions:
                  - "^QuotedProbe$" # this comment is discarded
                  - ^BareProbe$ # and so is this one
                """);

        assertTrue(ItemDefinitions.isLegacy("QuotedProbe"));
        assertTrue(ItemDefinitions.isLegacy("BareProbe"));
    }

    @Test
    void aRegexBackslashSurvivesInSingleQuotes() throws IOException {
        // Single quotes keep a backslash as it is, so the regex sees an escaped '?'.
        // The real file's '^\?\?\?$' depends on this.
        parseFixture(
                """
                definitions:
                  - '^Q\\?$'
                """);

        assertTrue(ItemDefinitions.isLegacy("Q?"));
        assertFalse(ItemDefinitions.isLegacy("Q"));
        assertTrue(ItemDefinitions.isUnenchanted("???"), "the real file's entry");
    }

    @Test
    void theRealFileParsesWithoutError() throws IOException {
        // load() logs a parse error rather than failing client init, so a broken
        // definitions.yml would load only part of it, or none. Parsing it here, with
        // nothing catching, is what turns a bad edit into a failed build.
        try (var in = ItemDefinitionsTest.class.getResourceAsStream("/definitions.yml")) {
            ItemDefinitions.parse(in);
        }
    }

    @Test
    void aDoubleQuotedBackslashThatIsNotAYamlEscapeFailsTheParse() {
        // Inside double quotes only YAML's own escapes are legal, so a regex escape
        // such as \? there is a syntax error, not a silent change to the pattern.
        assertThrows(
                YAMLException.class,
                () ->
                        parseFixture(
                                """
                                definitions:
                                  - "^Q\\?$"
                                """));
    }

    @Test
    void aRepeatedSectionFailsTheParse() {
        // In one mapping a repeated key would otherwise silently replace the earlier
        // section's list.
        assertThrows(
                YAMLException.class,
                () ->
                        parseFixture(
                                """
                                definitions:
                                  - "^FirstProbe$"
                                definitions:
                                  - "^SecondProbe$"
                                """));
        assertFalse(ItemDefinitions.isLegacy("FirstProbe"), "nothing from the document loads");
        assertFalse(ItemDefinitions.isLegacy("SecondProbe"));
    }

    @Test
    void aYamlEscapeInDoubleQuotesIsDecodedBeforeTheRegexSeesIt() throws IOException {
        // YAML's \_ is a no-break space, while the regex would read \_ as a plain '_'. So
        // this entry matches one name or the other depending on which of the two decoded it.
        parseFixture(
                """
                definitions:
                  - "^A\\_B$"
                """);
        assertTrue(ItemDefinitions.isLegacy("A\u00A0B"), "YAML decoded the escape");
        assertFalse(ItemDefinitions.isLegacy("A_B"), "the regex never saw it");

        // The real file's "(?i)^Fiert\u00e9$": YAML turns the escape into the letter,
        // which matches the same names the regex escape did.
        assertTrue(ItemDefinitions.isNoLoreLegacy("Fiert\u00e9"));
        assertTrue(
                ItemDefinitions.isNoLoreLegacy("fiert\u00e9"),
                "(?i) still folds the ASCII letters");
    }

    @Test
    void wholeLineCommentsAreSkippedByTheParserItself() throws IOException {
        // A leading '#' is handled, including on a commented-out list entry.
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
        // "---" starts a new YAML document. One that is a bare list rather than a
        // mapping names no category, so its entries are dropped rather than
        // appended to the previous section.
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

    // ----- Null -----

    @Test
    void noPredicateMatchesNull() {
        // A null name or title is on no list; no predicate throws for it.
        assertFalse(ItemDefinitions.isLegacy(null));
        assertFalse(ItemDefinitions.isMiscLegacy(null));
        assertFalse(ItemDefinitions.isNoLoreLegacy(null));
        assertFalse(ItemDefinitions.isNewFormatOverride(null));
        assertFalse(ItemDefinitions.isUnenchanted(null));
        assertFalse(ItemDefinitions.isNotJunk(null));
        assertFalse(ItemDefinitions.isNotPedestal(null));
        assertFalse(ItemDefinitions.isBlockedScreenTitle(null));
        assertFalse(ItemDefinitions.isInventorySuppressor(null));
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
        assertTrue(ItemDefinitions.isInventorySuppressor("Block Bank"));
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

    // ----- Misc Bucket items -----

    @Test
    void miscBucketNamesAreMiscLegacyAndOnlyMiscLegacy() {
        // Each needs the Misc. Item rarity line (the misc_definitions gate), so none
        // may also sit in the unconditional list.
        for (String name :
                new String[] {
                    "Stitched Flesh",
                    "Aqua Dust",
                    "Flamingo Feather",
                    "Uncraftable Potion",
                    "Cralozide Crystal",
                    "Sunflower Seeds",
                    "Pigman Meat",
                    "Broken Amulet",
                    "Shattered Crystal",
                    "Cobblestone"
                }) {
            assertTrue(ItemDefinitions.isMiscLegacy(name), name + " is misc legacy");
            assertFalse(ItemDefinitions.isLegacy(name), name + " is not matched by name alone");
        }
    }

    @Test
    void miscBucketNamesAreExactAndCaseFolded() {
        assertTrue(ItemDefinitions.isMiscLegacy("aqua dust"), "(?i)");
        assertFalse(ItemDefinitions.isMiscLegacy("Aqua Dust Shard"), "full match only");
        assertFalse(ItemDefinitions.isMiscLegacy("Cobblestone Slab"));
        assertFalse(ItemDefinitions.isMiscLegacy("Mossy Cobblestone"));
        assertFalse(ItemDefinitions.isMiscLegacy("Flamingo"), "a prefix is not the item");
        assertFalse(ItemDefinitions.isMiscLegacy("Lava"));
    }

    @Test
    void wolfsFangIsNoLoreLegacyInBothApostropheForms() {
        assertTrue(ItemDefinitions.isNoLoreLegacy("Wolf's Fang"));
        assertTrue(ItemDefinitions.isNoLoreLegacy("Wolf\u2019s Fang"), "typographic apostrophe");
        assertFalse(
                ItemDefinitions.isLegacy("Wolf's Fang"),
                "only legacy when loreless, never by name alone");
        assertFalse(
                ItemDefinitions.isNoLoreLegacy("Wolf Fang"),
                "the existing definitions entry is a different name and stays name-only");
        assertFalse(ItemDefinitions.isNoLoreLegacy("Wolf's Fangs"));
    }

    // ----- Removed modern items -----

    @Test
    void vibrantAugmentIsLegacyByNameAlone() {
        assertTrue(ItemDefinitions.isLegacy("Vibrant Augment"));
        assertTrue(ItemDefinitions.isLegacy("vibrant augment"), "(?i)");
        assertFalse(ItemDefinitions.isLegacy("Vibrant Augments"), "full match only");
        assertFalse(ItemDefinitions.isLegacy("Vibrant"), "a prefix is not the item");
        assertFalse(ItemDefinitions.isLegacy("Augment"), "the stem alone is not the removed item");
    }

    @Test
    void warpedMountIsLegacyButWorkingMountNamesAreNot() {
        assertTrue(ItemDefinitions.isLegacy("Warped Mount"));
        assertTrue(ItemDefinitions.isLegacy("warped mount"), "(?i)");
        assertFalse(ItemDefinitions.isLegacy("Warped Mount of Wynn"), "full match only");
        // Working mounts have names of their own.
        assertFalse(ItemDefinitions.isLegacy("Horse Saddle"));
        assertFalse(ItemDefinitions.isLegacy("Meteor's Saddle"));
        assertFalse(ItemDefinitions.isMiscLegacy("Horse Saddle"));
        assertFalse(ItemDefinitions.isNoLoreLegacy("Meteor's Saddle"));
    }

    // ----- Confirmed not legacy -----

    @Test
    void itemsConfirmedNotLegacyAreInNoNameList() {
        for (String name :
                new String[] {
                    "The Juggernaut",
                    "Sacred",
                    "Quicksand Crossers",
                    "Quicksand Crossers [30.0%]",
                    "Replica Hallowynn Mask"
                }) {
            assertFalse(ItemDefinitions.isLegacy(name), name + " is not legacy");
            assertFalse(ItemDefinitions.isMiscLegacy(name), name);
            assertFalse(ItemDefinitions.isNoLoreLegacy(name), name);
        }
    }

    @Test
    void theHallowynnMaskItselfStaysLegacy() {
        assertTrue(ItemDefinitions.isLegacy("Hallowynn Mask"));
        assertTrue(ItemDefinitions.isLegacy("hallowynn mask"), "(?i)");
        assertFalse(
                ItemDefinitions.isLegacy("Replica Hallowynn Mask"),
                "the replica is a current item and must not ride on the original's entry");
    }

    // ----- Boat passes versus the Seaskipper menu's ticket UI element -----

    @Test
    void galleonsGraveyardPassIsLegacyButTheSeaskipperTicketElementIsNot() {
        assertTrue(ItemDefinitions.isLegacy("Galleon's Graveyard Pass"), "a real pass");
        assertTrue(ItemDefinitions.isLegacy("galleon's graveyard pass"), "(?i)");
        // The menu element's normalised name: the cost is a number plus a coin glyph
        // (U+00B2).
        assertFalse(ItemDefinitions.isLegacy("Galleon's Graveyard Pass for 16\u00B2"));
        assertFalse(ItemDefinitions.isLegacy("Galleon's Graveyard Pass for 16"));
        assertFalse(ItemDefinitions.isLegacy("Galleon's Graveyard Pass for 4"));
    }

    @Test
    void otherBoatPassesStayLegacy() {
        assertTrue(ItemDefinitions.isLegacy("Selchar Boat Pass"), "a real pass");
        assertTrue(ItemDefinitions.isLegacy("Some Island Boat Pass"));
        assertFalse(
                ItemDefinitions.isLegacy("Selchar Boat Pass for 16\u00B2"),
                "a cost suffix ends the name past 'Boat Pass', so the suffix-anchored entry "
                        + "never matched a ticket element either");
    }

    // ----- Inventory suppressors (Block Bank) -----

    @Test
    void blockBankIsAnInventorySuppressor() {
        assertTrue(ItemDefinitions.isInventorySuppressor("Block Bank"));
        assertFalse(ItemDefinitions.isInventorySuppressor("block bank"), "case-sensitive entry");
        assertFalse(ItemDefinitions.isInventorySuppressor("Block Banker"), "full match only");
        assertFalse(ItemDefinitions.isInventorySuppressor("Block of Quartz"));
        assertFalse(ItemDefinitions.isInventorySuppressor("Bank"));
        assertFalse(ItemDefinitions.isInventorySuppressor("Account Bank"));
    }

    @Test
    void inventorySuppressorsAreAnIndependentCategory() {
        // The housing item is not itself legacy in any list, and the suppressor list does
        // not leak into the legacy lists.
        assertFalse(ItemDefinitions.isLegacy("Block Bank"));
        assertFalse(ItemDefinitions.isMiscLegacy("Block Bank"));
        assertFalse(ItemDefinitions.isNoLoreLegacy("Block Bank"));
        assertFalse(ItemDefinitions.isInventorySuppressor("Skeleton Key"));
    }

    // ----- min_rerolls -----

    @Test
    void minRerolls_givesTheThresholdOfAMatchingFilter() throws IOException {
        parseFixture(
                """
                min_rerolls:
                  - filter: "^RerollProbe$"
                    threshold: 3
                """);

        assertEquals(3, ItemDefinitions.minRerolls("RerollProbe"));
        assertEquals(0, ItemDefinitions.minRerolls("Unlisted Probe"), "unlisted names have none");
        assertEquals(0, ItemDefinitions.minRerolls(null));
    }

    @Test
    void minRerolls_takesTheHighestThresholdOfOverlappingFilters() throws IOException {
        parseFixture(
                """
                min_rerolls:
                  - filter: "^Overlap.*$"
                    threshold: 2
                  - filter: "^Overlap Probe$"
                    threshold: 5
                  - filter: "(?i)^overlap probe$"
                    threshold: 4
                """);

        assertEquals(5, ItemDefinitions.minRerolls("Overlap Probe"));
        assertEquals(2, ItemDefinitions.minRerolls("Overlap Other"));
    }

    @Test
    void minRerolls_skipsMalformedEntriesAndKeepsTheRest() throws IOException {
        parseFixture(
                """
                min_rerolls:
                  - "^BareStringProbe$"
                  - filter: "^NoThresholdProbe$"
                  - threshold: 2
                  - filter: "^TextThresholdProbe$"
                    threshold: "2"
                  - filter: "^ZeroThresholdProbe$"
                    threshold: 0
                  - filter: "^NegativeThresholdProbe$"
                    threshold: -1
                  - filter: ""
                    threshold: 2
                  - filter: "^ValidProbe$"
                    threshold: 2
                    note: an extra key is ignored
                """);

        assertEquals(0, ItemDefinitions.minRerolls("BareStringProbe"));
        assertEquals(0, ItemDefinitions.minRerolls("NoThresholdProbe"));
        assertEquals(0, ItemDefinitions.minRerolls("TextThresholdProbe"));
        assertEquals(0, ItemDefinitions.minRerolls("ZeroThresholdProbe"));
        assertEquals(0, ItemDefinitions.minRerolls("NegativeThresholdProbe"));
        assertEquals(0, ItemDefinitions.minRerolls(""), "an empty filter is dropped");
        assertEquals(2, ItemDefinitions.minRerolls("ValidProbe"), "the valid entry still loads");
    }

    @Test
    void minRerolls_filterMustMatchTheWholeName() throws IOException {
        parseFixture(
                """
                min_rerolls:
                  - filter: "Partial Probe"
                    threshold: 2
                """);

        assertEquals(2, ItemDefinitions.minRerolls("Partial Probe"));
        assertEquals(0, ItemDefinitions.minRerolls("A Partial Probe Chestplate"));
    }

    @Test
    void theRealFileListsTheSolidifiedChestplate() {
        // No current Solidified Chestplate has fewer than 2 rerolls (user, 2026-10-27).
        assertEquals(2, ItemDefinitions.minRerolls("Solidified Chestplate"));
        assertEquals(0, ItemDefinitions.minRerolls("Solidified Chestplates"));
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
