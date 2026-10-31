package org.wynnvets.retro.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.ManifestFixtures;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.manifest.TintFactor;

/**
 * Tests for {@link TextureRules}, on small parsed fixtures and a {@link PackFacts} built from sets.
 *
 * <p>The suite pins the texture precedence ({@code keep_wynncraft}, then tints, then aliases,
 * then {@code same_name}, then the automatic keep of a transparent texture), a tint's source,
 * {@code $n} substitution and gap, an alias's {@code $n} substitution and its missing-target gap,
 * the source order, the transparency question staying lazy, and layer groups replaced whole or not
 * at all. What the shipped files decide is
 * {@link org.wynnvets.retro.manifest.ShippedTexturesTest ShippedTexturesTest}'s.</p>
 */
class TextureRulesTest {

    private static final String STONE = "minecraft:block/stone";
    private static final String HEART = "minecraft:gui/sprites/hud/heart/full";

    /** Chests read vanilla alone; everything else reads Programmer Art, then vanilla. */
    private static final String SOURCES =
            """
            sources:
              'minecraft:entity/chest/.*': [vanilla]
              '.*': [programmer_art, vanilla]
            """;

    /** Pack contents from sets. It records each transparency question it is asked. */
    private static final class Packs implements PackFacts {
        private final Set<String> wynncraft = new HashSet<>();
        private final Set<String> transparent = new HashSet<>();
        private final Map<RetroSource, Set<String>> sources = new EnumMap<>(RetroSource.class);
        private final List<String> transparencyAsked = new ArrayList<>();

        /** Wynncraft ships {@code ids}, painted. */
        Packs ships(String... ids) {
            wynncraft.addAll(List.of(ids));
            return this;
        }

        /** Wynncraft ships {@code ids}, fully transparent. */
        Packs blank(String... ids) {
            ships(ids);
            transparent.addAll(List.of(ids));
            return this;
        }

        /** {@code source} has {@code ids}. */
        Packs in(RetroSource source, String... ids) {
            sources.computeIfAbsent(source, s -> new HashSet<>()).addAll(List.of(ids));
            return this;
        }

        @Override
        public boolean wynncraftShips(String textureId) {
            return wynncraft.contains(textureId);
        }

        @Override
        public boolean wynncraftTransparent(String textureId) {
            transparencyAsked.add(textureId);
            return transparent.contains(textureId);
        }

        @Override
        public boolean has(RetroSource source, String textureId) {
            return sources.getOrDefault(source, Set.of()).contains(textureId);
        }
    }

    private static TextureRules rules(String textures) {
        return new TextureRules(ManifestFixtures.parseTextures(textures).textures());
    }

    private static TextureDecision copy(String from, RetroSource source) {
        return new TextureDecision.Copy(from, source);
    }

    private static TextureDecision keep(KeepReason reason) {
        return new TextureDecision.Keep(reason);
    }

    // ----- Sources -----

    @Test
    void theFirstMatchingSourcesRowGivesTheOrder() {
        TextureRules rules =
                rules(
                        """
                        sources:
                          'minecraft:entity/.*': [programmer_art, vanilla]
                          'minecraft:entity/chest/.*': [vanilla]
                          'minecraft:block/.*': [vanilla, programmer_art]
                        """);

        assertEquals(
                List.of(RetroSource.PROGRAMMER_ART, RetroSource.VANILLA),
                rules.sourceOrder("minecraft:entity/chest/normal"),
                "the earlier, broader row wins");
        assertEquals(
                List.of(RetroSource.VANILLA, RetroSource.PROGRAMMER_ART), rules.sourceOrder(STONE));
        assertEquals(List.of(), rules.sourceOrder("minecraft:item/stick"), "no row matches");
    }

    @Test
    void sameNameCopiesFromTheFirstSourceThatHasTheTexture() {
        TextureRules rules = rules(SOURCES + "same_name: ['minecraft:block/.*']\n");

        assertEquals(
                copy(STONE, RetroSource.PROGRAMMER_ART),
                rules.decide(
                        STONE,
                        new Packs()
                                .ships(STONE)
                                .in(RetroSource.PROGRAMMER_ART, STONE)
                                .in(RetroSource.VANILLA, STONE)));
        assertEquals(
                copy(STONE, RetroSource.VANILLA),
                rules.decide(STONE, new Packs().ships(STONE).in(RetroSource.VANILLA, STONE)),
                "without it the next source answers");
    }

    @Test
    void aSourceTheRowDoesNotListIsNeverRead() {
        String chest = "minecraft:entity/chest/normal";
        TextureRules rules = rules(SOURCES + "same_name: ['minecraft:entity/.*']\n");

        assertEquals(
                keep(KeepReason.NO_RULE),
                rules.decide(chest, new Packs().ships(chest).in(RetroSource.PROGRAMMER_ART, chest)),
                "only Programmer Art has it, and chests skip Programmer Art");
    }

    @Test
    void withNoSourcesRowNothingIsCopied() {
        TextureRules rules = rules("same_name: ['minecraft:block/.*']\n");
        Packs packs = new Packs().ships(STONE);
        for (RetroSource source : RetroSource.values()) packs.in(source, STONE);

        assertEquals(keep(KeepReason.NO_RULE), rules.decide(STONE, packs));
    }

    @Test
    void aTextureWynncraftDoesNotShipIsReplacedAllTheSame() {
        // 2013 art replaces vanilla art Wynncraft leaves alone.
        String stick = "minecraft:item/stick";
        TextureRules rules = rules(SOURCES + "same_name: ['minecraft:item/.*']\n");

        assertEquals(
                copy(stick, RetroSource.PROGRAMMER_ART),
                rules.decide(
                        stick,
                        new Packs()
                                .in(RetroSource.PROGRAMMER_ART, stick)
                                .in(RetroSource.VANILLA, stick)));
    }

    // ----- Precedence -----

    @Test
    void aTransparentHudHeartWithASameNameRuleResolvesToTheSource() {
        // Wynncraft blanks the very sprites retro restores, so the automatic keep of a
        // transparent texture loses to an explicit rule.
        Packs packs =
                new Packs()
                        .blank(HEART)
                        .in(RetroSource.PROGRAMMER_ART, HEART)
                        .in(RetroSource.VANILLA, HEART);

        assertEquals(
                copy(HEART, RetroSource.PROGRAMMER_ART),
                rules(SOURCES + "same_name: ['minecraft:gui/sprites/hud/heart/.*']\n")
                        .decide(HEART, packs));
        assertEquals(
                keep(KeepReason.TRANSPARENT),
                rules(SOURCES + "same_name: ['minecraft:block/.*']\n").decide(HEART, packs),
                "without a rule it is a deliberate blank");
    }

    @Test
    void keepWynncraftBeatsAnAliasAndSameName() {
        String tripwire = "minecraft:block/tripwire";
        TextureRules rules =
                rules(
                        SOURCES
                                + """
                                keep_wynncraft: ['minecraft:block/tripwire']
                                aliases:
                                  'minecraft:block/tripwire': 'minecraft:block/stone'
                                same_name: ['minecraft:block/.*']
                                """);
        Packs packs = new Packs().blank(tripwire);
        for (RetroSource source : RetroSource.values()) packs.in(source, tripwire, STONE);

        assertEquals(keep(KeepReason.KEEP_WYNNCRAFT), rules.decide(tripwire, packs));
    }

    @Test
    void anAliasBeatsSameName() {
        String variant = "minecraft:block/variants/stone_0";
        TextureRules rules =
                rules(
                        SOURCES
                                + """
                                aliases:
                                  'minecraft:block/variants/stone_\\d+': 'minecraft:block/stone'
                                same_name: ['minecraft:block/.*']
                                """);

        assertEquals(
                copy(STONE, RetroSource.PROGRAMMER_ART),
                rules.decide(
                        variant,
                        new Packs().ships(variant).in(RetroSource.PROGRAMMER_ART, variant, STONE)));
    }

    @Test
    void anAliasWhoseTargetNoSourceHasIsAGapAndSameNameIsNotTried() {
        String chain = "minecraft:block/variants/chain";
        TextureRules rules =
                rules(
                        SOURCES
                                + """
                                aliases:
                                  'minecraft:block/variants/chain': 'minecraft:block/chain'
                                same_name: ['minecraft:block/.*']
                                """);
        Packs packs = new Packs().blank(chain).in(RetroSource.PROGRAMMER_ART, chain);

        assertEquals(keep(KeepReason.NO_SOURCE), rules.decide(chain, packs));
        assertEquals(List.of(), packs.transparencyAsked, "nor the automatic keep");
    }

    @Test
    void sameNameWithNoSourceFallsThroughToTheAutomaticKeep() {
        // Most of Wynncraft's own textures sit under same_name's folders with no source.
        String dark = "minecraft:block/variants/dark_stone";
        TextureRules rules = rules(SOURCES + "same_name: ['minecraft:block/.*']\n");

        assertEquals(keep(KeepReason.NO_RULE), rules.decide(dark, new Packs().ships(dark)));
        assertEquals(keep(KeepReason.TRANSPARENT), rules.decide(dark, new Packs().blank(dark)));
    }

    @Test
    void transparencyIsAskedOnlyOfAShippedTextureNoRuleReplaced() {
        // Scanning a texture's pixels is the expensive question, so it comes last.
        String kept = "minecraft:block/tripwire";
        String own = "minecraft:block/variants/dark_stone";
        String notShipped = "minecraft:block/dirt";
        TextureRules rules =
                rules(
                        SOURCES
                                + """
                                keep_wynncraft: ['minecraft:block/tripwire']
                                same_name: ['minecraft:block/.*']
                                """);
        Packs packs = new Packs().blank(kept, STONE, own).in(RetroSource.PROGRAMMER_ART, STONE);

        rules.decide(kept, packs);
        rules.decide(STONE, packs);
        rules.decide(notShipped, packs);
        assertEquals(List.of(), packs.transparencyAsked);

        rules.decide(own, packs);
        assertEquals(List.of(own), packs.transparencyAsked);
    }

    // ----- Tints -----

    @Test
    void aTintStartsFromItsSourceOrTheTextureItselfFromTheFirstSourceThatHasIt() {
        String noteBlock = "minecraft:block/note_block";
        String oakLog = "minecraft:block/oak_log";
        TextureRules rules =
                rules(
                        SOURCES
                                + """
                                tints:
                                  'minecraft:block/note_block':
                                    tint: {match: 'minecraft:block/oak_log'}
                                  'minecraft:block/purpur_\\w+':
                                    source: 'minecraft:block/oak_log'
                                    tint: '#808080'
                                    strength: 0.5
                                """);
        Packs packs =
                new Packs()
                        .in(RetroSource.PROGRAMMER_ART, noteBlock, oakLog)
                        .in(RetroSource.VANILLA, noteBlock, oakLog);

        assertEquals(
                new TextureDecision.Tint(
                        noteBlock, RetroSource.PROGRAMMER_ART, new TintFactor.Match(oakLog), 1, 1),
                rules.decide(noteBlock, packs));
        assertEquals(
                new TextureDecision.Tint(
                        oakLog,
                        RetroSource.PROGRAMMER_ART,
                        new TintFactor.Colour(0x808080),
                        0.5,
                        1),
                rules.decide("minecraft:block/purpur_block", packs),
                "a texture no source has, from another's");
    }

    @Test
    void aTintCarriesItsContrastAndARowWithoutATintKeepsTheSourcesColour() {
        String furnace = "minecraft:block/furnace_side";
        String front = "minecraft:block/furnace_front";
        String bricks = "minecraft:block/stone_bricks";
        TextureRules rules =
                rules(
                        SOURCES
                                + """
                                tints:
                                  'minecraft:block/furnace_side':
                                    tint: {match: 'minecraft:block/stone_bricks'}
                                    contrast: 0.45
                                  'minecraft:block/furnace_front': {contrast: 0.65}
                                same_name: ['minecraft:block/.*']
                                """);
        Packs packs = new Packs().in(RetroSource.PROGRAMMER_ART, furnace, front, bricks);

        assertEquals(
                new TextureDecision.Tint(
                        furnace, RetroSource.PROGRAMMER_ART, new TintFactor.Match(bricks), 1, 0.45),
                rules.decide(furnace, packs));
        assertEquals(
                new TextureDecision.Tint(
                        front, RetroSource.PROGRAMMER_ART, new TintFactor.Unchanged(), 1, 0.65),
                rules.decide(front, packs),
                "a contrast-only row beats same_name, its source the texture itself");
    }

    /**
     * A like row (first, as the file order does not matter to it) and the row it names, whose
     * group fills its source and match texture from the named texture.
     */
    private static final String LIKE =
            SOURCES
                    + """
                    tints:
                      'minecraft:block/dispenser_front':
                        tint: {like: 'minecraft:block/furnace_side'}
                      'minecraft:block/furnace_(side)':
                        source: 'minecraft:block/old_$1'
                        tint: {match: 'minecraft:block/$1_bricks'}
                        strength: 0.5
                        contrast: 0.45
                    keep_wynncraft: ['minecraft:block/furnace_side']
                    """;

    @Test
    void aLikeCarriesTheDecisionOfTheRowItNamesWhateverKeepWynncraftSaysOfIt() {
        String front = "minecraft:block/dispenser_front";
        String old = "minecraft:block/old_side";
        String bricks = "minecraft:block/side_bricks";
        Packs packs =
                new Packs()
                        .in(RetroSource.PROGRAMMER_ART, front)
                        .in(RetroSource.VANILLA, old, bricks);
        TextureDecision.Tint side =
                new TextureDecision.Tint(
                        old, RetroSource.VANILLA, new TintFactor.Match(bricks), 0.5, 0.45);

        assertEquals(
                new TextureDecision.Tint(
                        front,
                        RetroSource.PROGRAMMER_ART,
                        new TintFactor.Like("minecraft:block/furnace_side"),
                        0.5,
                        0.45,
                        side),
                rules(LIKE).decide(front, packs),
                "its own source, with the named row's strength, contrast and decision");
        assertEquals(
                keep(KeepReason.KEEP_WYNNCRAFT),
                rules(LIKE).decide("minecraft:block/furnace_side", packs),
                "the named texture itself is kept, and the like still takes its row's change");
    }

    @Test
    void aLikeIsAGapWhenNoSourceHasItsOwnSourceOrWhatTheNamedRowReads() {
        String front = "minecraft:block/dispenser_front";
        String old = "minecraft:block/old_side";
        String bricks = "minecraft:block/side_bricks";

        assertEquals(
                keep(KeepReason.NO_SOURCE),
                rules(LIKE).decide(front, new Packs().in(RetroSource.VANILLA, old, bricks)),
                "its own source");
        assertEquals(
                keep(KeepReason.NO_SOURCE),
                rules(LIKE).decide(front, new Packs().in(RetroSource.VANILLA, front, bricks)),
                "the named row's source");
        assertEquals(
                keep(KeepReason.NO_SOURCE),
                rules(LIKE).decide(front, new Packs().in(RetroSource.VANILLA, front, old)),
                "the named row's match texture");
    }

    @Test
    void aTintFillsItsGroupsIntoItsSourceAndTheTextureItMeasures() {
        TextureRules rules =
                rules(
                        SOURCES
                                + """
                                tints:
                                  'minecraft:block/variants/dark_(\\w+?)(_\\d+)?':
                                    source: 'minecraft:block/$1'
                                    tint: {relative_to: 'minecraft:block/$1'}
                                  'minecraft:block/(\\w+)_tinted':
                                    tint: {match: 'minecraft:block/$1'}
                                """);
        Packs packs = new Packs().in(RetroSource.VANILLA, STONE, "minecraft:block/sand_tinted");

        assertEquals(
                new TextureDecision.Tint(
                        STONE, RetroSource.VANILLA, new TintFactor.RelativeTo(STONE), 1, 1),
                rules.decide("minecraft:block/variants/dark_stone_1", packs));
        assertEquals(
                keep(KeepReason.NO_SOURCE),
                rules.decide("minecraft:block/sand_tinted", packs),
                "the match, minecraft:block/sand, is in no source");
    }

    @Test
    void aTintBeatsAnAliasAndSameNameButNotKeepWynncraft() {
        String purpur = "minecraft:block/purpur_block";
        String tints =
                """
                tints:
                  'minecraft:block/purpur_block': {source: 'minecraft:block/stone', tint: auto}
                aliases:
                  'minecraft:block/purpur_block': 'minecraft:block/stone'
                same_name: ['minecraft:block/.*']
                """;
        Packs packs = new Packs().ships(purpur);
        for (RetroSource source : RetroSource.values()) packs.in(source, purpur, STONE);

        assertEquals(
                new TextureDecision.Tint(
                        STONE, RetroSource.PROGRAMMER_ART, new TintFactor.Auto(), 1, 1),
                rules(SOURCES + tints).decide(purpur, packs));
        assertEquals(
                keep(KeepReason.KEEP_WYNNCRAFT),
                rules(SOURCES + tints + "keep_wynncraft: ['minecraft:block/purpur_\\w+']\n")
                        .decide(purpur, packs));
    }

    @Test
    void aTintWhoseSourceNoSourceHasIsAGapAndTheRulesBelowAreNotTried() {
        // Its own reads come from the sources rows: a chest skips Programmer Art here too.
        String chest = "minecraft:entity/chest/normal";
        TextureRules rules =
                rules(
                        SOURCES
                                + """
                                tints:
                                  'minecraft:block/x': {source: 'minecraft:entity/chest/normal', tint: auto}
                                aliases:
                                  'minecraft:block/x': 'minecraft:block/stone'
                                same_name: ['minecraft:block/.*']
                                """);
        Packs packs =
                new Packs()
                        .blank("minecraft:block/x")
                        .in(RetroSource.PROGRAMMER_ART, chest, STONE, "minecraft:block/x");

        assertEquals(keep(KeepReason.NO_SOURCE), rules.decide("minecraft:block/x", packs));
        assertEquals(List.of(), packs.transparencyAsked, "nor the automatic keep");
    }

    @Test
    void aTintedMemberOfALayerGroupCountsAsReadFromItsSource() {
        // A group is replaced whole only when every member is read from one source, tints too.
        String body = "minecraft:entity/wolf/wolf";
        String collar = "minecraft:entity/wolf/wolf_collar";
        String fixture =
                SOURCES
                        + """
                        tints:
                          'minecraft:entity/wolf/wolf_collar': {tint: '#FF0000'}
                        same_name: ['minecraft:entity/.*']
                        layer_groups: ['minecraft:entity/wolf/.*']
                        """;
        Packs oneSource =
                new Packs().ships(body, collar).in(RetroSource.PROGRAMMER_ART, body, collar);
        Packs twoSources =
                new Packs()
                        .ships(body, collar)
                        .in(RetroSource.PROGRAMMER_ART, body)
                        .in(RetroSource.VANILLA, collar);

        Map<String, TextureDecision> whole =
                rules(fixture).decideAll(List.of(body, collar), oneSource);
        assertEquals(copy(body, RetroSource.PROGRAMMER_ART), whole.get(body));
        assertEquals(
                new TextureDecision.Tint(
                        collar, RetroSource.PROGRAMMER_ART, new TintFactor.Colour(0xFF0000), 1, 1),
                whole.get(collar));

        Map<String, TextureDecision> mixed =
                rules(fixture).decideAll(List.of(body, collar), twoSources);
        assertEquals(keep(KeepReason.LAYER_GROUP), mixed.get(body));
        assertEquals(keep(KeepReason.LAYER_GROUP), mixed.get(collar));
    }

    // ----- Aliases -----

    @Test
    void anAliasFillsItsGroupsIn() {
        TextureRules rules =
                rules(
                        SOURCES
                                + """
                                aliases:
                                  'minecraft:block/variants/(\\w+)_(\\d+)': 'minecraft:block/$1'
                                  'minecraft:item/(\\w+)_to_(\\w+)': 'minecraft:item/$2_$1'
                                  'minecraft:block/x(_\\d+)?': 'minecraft:block/y$1'
                                """);
        Packs packs =
                new Packs()
                        .in(
                                RetroSource.VANILLA,
                                "minecraft:block/cobweb",
                                "minecraft:item/b_a",
                                "minecraft:block/y",
                                "minecraft:block/y_2");

        assertEquals(
                copy("minecraft:block/cobweb", RetroSource.VANILLA),
                rules.decide("minecraft:block/variants/cobweb_2", packs));
        assertEquals(
                copy("minecraft:item/b_a", RetroSource.VANILLA),
                rules.decide("minecraft:item/a_to_b", packs),
                "groups in any order");
        assertEquals(
                copy("minecraft:block/y", RetroSource.VANILLA),
                rules.decide("minecraft:block/x", packs),
                "a group that took no part inserts nothing");
        assertEquals(
                copy("minecraft:block/y_2", RetroSource.VANILLA),
                rules.decide("minecraft:block/x_2", packs));
    }

    @Test
    void theFirstMatchingAliasDecides() {
        String variant = "minecraft:block/variants/stone_0";
        TextureRules rules =
                rules(
                        SOURCES
                                + """
                                aliases:
                                  'minecraft:block/variants/stone_0': 'minecraft:block/andesite'
                                  'minecraft:block/variants/stone_\\d+': 'minecraft:block/stone'
                                """);
        Packs packs = new Packs().in(RetroSource.VANILLA, STONE, "minecraft:block/andesite");

        assertEquals(
                copy("minecraft:block/andesite", RetroSource.VANILLA),
                rules.decide(variant, packs));
        assertEquals(
                copy(STONE, RetroSource.VANILLA),
                rules.decide("minecraft:block/variants/stone_1", packs));
    }

    @Test
    void anAliasReadsItsTargetFromTheTargetsSources() {
        // The sources row is about the file read, so a chest target skips Programmer Art too.
        String chest = "minecraft:entity/chest/normal";
        TextureRules rules =
                rules(
                        SOURCES
                                + """
                                aliases:
                                  'minecraft:block/fake_chest': 'minecraft:entity/chest/normal'
                                """);
        Packs packs =
                new Packs().in(RetroSource.PROGRAMMER_ART, chest).in(RetroSource.VANILLA, chest);

        assertEquals(
                copy(chest, RetroSource.VANILLA),
                rules.decide("minecraft:block/fake_chest", packs));
    }

    // ----- Layer groups -----

    private static final String VILLAGER = "minecraft:entity/villager/villager";
    private static final String FARMER = "minecraft:entity/villager/profession/farmer";
    private static final String PLAINS = "minecraft:entity/villager/type/plains";
    private static final String COW = "minecraft:entity/cow/cow";

    private static final String VILLAGER_RULES =
            SOURCES
                    + """
                    same_name: ['minecraft:entity/.*']
                    layer_groups: ['minecraft:entity/villager/.*']
                    """;

    @Test
    void aGroupFromOneSourceIsCopiedWhole() {
        Packs packs =
                new Packs()
                        .ships(VILLAGER, FARMER)
                        .in(RetroSource.PROGRAMMER_ART, VILLAGER, FARMER, PLAINS);

        assertEquals(
                Map.of(
                        VILLAGER, copy(VILLAGER, RetroSource.PROGRAMMER_ART),
                        FARMER, copy(FARMER, RetroSource.PROGRAMMER_ART),
                        PLAINS, copy(PLAINS, RetroSource.PROGRAMMER_ART)),
                rules(VILLAGER_RULES).decideAll(List.of(VILLAGER, FARMER, PLAINS), packs));
    }

    @Test
    void aGroupWhoseCopiesComeFromDifferentSourcesIsNotCopiedAtAll() {
        // Programmer Art's villager body under vanilla's profession layer would not fit.
        Packs packs =
                new Packs()
                        .ships(VILLAGER, FARMER)
                        .in(RetroSource.PROGRAMMER_ART, VILLAGER, COW)
                        .in(RetroSource.VANILLA, VILLAGER, FARMER, COW);

        Map<String, TextureDecision> decisions =
                rules(VILLAGER_RULES).decideAll(List.of(VILLAGER, FARMER, COW), packs);

        assertEquals(keep(KeepReason.LAYER_GROUP), decisions.get(VILLAGER));
        assertEquals(keep(KeepReason.LAYER_GROUP), decisions.get(FARMER));
        assertEquals(
                copy(COW, RetroSource.PROGRAMMER_ART),
                decisions.get(COW),
                "a texture outside the group is untouched");
    }

    @Test
    void aMemberWynncraftShipsThatStaysKeepsTheWholeGroup() {
        // Wynncraft's layer would stay under or over the copied ones.
        Packs noSource = new Packs().ships(VILLAGER, FARMER).in(RetroSource.VANILLA, VILLAGER);
        Map<String, TextureDecision> decisions =
                rules(VILLAGER_RULES).decideAll(List.of(VILLAGER, FARMER), noSource);

        assertEquals(keep(KeepReason.LAYER_GROUP), decisions.get(VILLAGER));
        assertEquals(keep(KeepReason.NO_RULE), decisions.get(FARMER), "its own reason stays");

        Packs everywhere =
                new Packs().ships(VILLAGER, FARMER).in(RetroSource.VANILLA, VILLAGER, FARMER);
        Map<String, TextureDecision> kept =
                rules(VILLAGER_RULES + "keep_wynncraft: ['" + FARMER + "']\n")
                        .decideAll(List.of(VILLAGER, FARMER), everywhere);

        assertEquals(keep(KeepReason.LAYER_GROUP), kept.get(VILLAGER));
        assertEquals(keep(KeepReason.KEEP_WYNNCRAFT), kept.get(FARMER));
    }

    @Test
    void aMemberWynncraftDoesNotShipNeedNotBeCopied() {
        // Nothing of Wynncraft's stays in the group, and the copies agree on one source.
        Packs packs = new Packs().ships(VILLAGER).in(RetroSource.PROGRAMMER_ART, VILLAGER);
        Map<String, TextureDecision> decisions =
                rules(VILLAGER_RULES).decideAll(List.of(VILLAGER, PLAINS), packs);

        assertEquals(copy(VILLAGER, RetroSource.PROGRAMMER_ART), decisions.get(VILLAGER));
        assertEquals(keep(KeepReason.NO_RULE), decisions.get(PLAINS));
    }

    @Test
    void decideAloneLeavesLayerGroupsToDecideAll() {
        Packs packs =
                new Packs()
                        .ships(VILLAGER, FARMER)
                        .in(RetroSource.PROGRAMMER_ART, VILLAGER)
                        .in(RetroSource.VANILLA, FARMER);

        assertEquals(
                copy(VILLAGER, RetroSource.PROGRAMMER_ART),
                rules(VILLAGER_RULES).decide(VILLAGER, packs));
    }

    @Test
    void decideAllKeepsTheInputOrderAndCannotBeModified() {
        Packs packs = new Packs().in(RetroSource.PROGRAMMER_ART, COW, VILLAGER, FARMER);
        Map<String, TextureDecision> decisions =
                rules(VILLAGER_RULES).decideAll(List.of(FARMER, COW, VILLAGER), packs);

        assertEquals(List.of(FARMER, COW, VILLAGER), List.copyOf(decisions.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> decisions.remove(COW));
    }

    // ----- Null -----

    @Test
    void noLookupThrowsForNull() {
        TextureRules rules =
                rules(
                        SOURCES
                                + """
                                tints:
                                  'minecraft:block/x.*': {tint: auto}
                                same_name: ['minecraft:block/.*']
                                layer_groups: ['minecraft:block/.*']
                                """);
        Packs packs = new Packs().in(RetroSource.VANILLA, STONE);

        assertEquals(List.of(), rules.sourceOrder(null));
        assertEquals(keep(KeepReason.NO_RULE), rules.decide(null, packs));
        assertEquals(
                Map.of(STONE, copy(STONE, RetroSource.VANILLA)),
                rules.decideAll(Arrays.asList(STONE, null), packs),
                "a null id is skipped");
        assertNull(rules.firstSourceWith(null, packs));
    }
}
