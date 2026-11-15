package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.wynnvets.retro.manifest.ManifestFixtures.parse;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.image.Rect;

/**
 * Tests for the parser's reading of {@link TextureSections}: the sources, the tints and their
 * likes, the aliases and the mapping rows' order, on small fixtures.
 */
class TextureSectionsTest {

    @Test
    void mappingRowsKeepTheirFileOrder() {
        // "The first matching row decides" depends on it.
        RetroManifest manifest =
                parse(
                        """
                        aliases:
                          'minecraft:block/z': 'minecraft:block/a'
                          'minecraft:block/a': 'minecraft:block/z'
                          'minecraft:block/m': 'minecraft:block/n'
                        """);

        assertEquals(
                List.of("minecraft:block/z", "minecraft:block/a", "minecraft:block/m"),
                manifest.textures().aliases().stream()
                        .map(alias -> alias.textures().pattern())
                        .toList());
    }

    @Test
    void tintRowsReadTheirSourceTintStrengthAndContrastInFileOrder() {
        RetroManifest manifest =
                parse(
                        """
                        tints:
                          'minecraft:block/a': {tint: '#80c0ff'}
                          'minecraft:block/variants/dark_(\\w+)':
                            source: 'minecraft:block/$1'
                            tint: {relative_to: 'minecraft:block/$1'}
                          'minecraft:block/b':
                            tint: {match: 'minecraft:block/c'}
                            strength: 0.7
                          'minecraft:block/d': {tint: auto, strength: 0}
                          'minecraft:block/e': {tint: auto, strength: 1}
                          'minecraft:block/f': {tint: auto, contrast: 0.45}
                          'minecraft:block/g': {contrast: 2}
                        """);

        List<TextureSections.Tint> tints = manifest.textures().tints();
        assertEquals(
                List.of(
                        "minecraft:block/a",
                        "minecraft:block/variants/dark_(\\w+)",
                        "minecraft:block/b",
                        "minecraft:block/d",
                        "minecraft:block/e",
                        "minecraft:block/f",
                        "minecraft:block/g"),
                tints.stream().map(tint -> tint.textures().pattern()).toList());
        assertEquals(1.0, tints.get(0).contrast(), "the contrast kept by default");
        assertEquals(0.45, tints.get(5).contrast());
        assertEquals(new TintFactor.Unchanged(), tints.get(6).factor(), "no tint");
        assertEquals(2.0, tints.get(6).contrast(), "a whole number, and above 1");
        assertEquals(new TintFactor.Colour(0x80C0FF), tints.get(0).factor());
        assertNull(tints.get(0).source(), "the texture's own id");
        assertEquals(1.0, tints.get(0).strength(), "the whole factor by default");
        assertEquals("minecraft:block/$1", tints.get(1).source());
        assertEquals(new TintFactor.RelativeTo("minecraft:block/$1"), tints.get(1).factor());
        assertEquals(new TintFactor.Match("minecraft:block/c"), tints.get(2).factor());
        assertEquals(0.7, tints.get(2).strength());
        assertEquals(new TintFactor.Auto(), tints.get(3).factor());
        assertEquals(0.0, tints.get(3).strength());
        assertEquals(1.0, tints.get(4).strength(), "a whole number is read too");
    }

    @Test
    void aMalformedTintFailsTheParse() {
        for (String fields :
                List.of(
                        "{source: 'minecraft:block/y'}",
                        "{tint: automatic}",
                        "{tint: {matches: 'minecraft:block/y'}}",
                        "{tint: {match: 'minecraft:block/y', relative_to: 'minecraft:block/z'}}",
                        "{tint: {match: 'block/y'}}",
                        "{tint: {relative_to: 'minecraft:block/$2'}}",
                        "{tint: auto, source: 'minecraft:block/$2'}",
                        "{tint: auto, source: 'block/y'}",
                        "{tint: auto, strength: 1.5}",
                        "{tint: auto, strength: -0.1}",
                        "{tint: auto, strength: half}",
                        "{tint: auto, strength: '0.5'}",
                        "{strength: 0.5}",
                        "{contrast: -0.1}",
                        "{contrast: high}",
                        "{contrast: '0.5'}",
                        "{tint: auto, contrast: .inf}",
                        "{contrast: 0.5, tint: {match: 'block/y'}}")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> parse("tints:\n  'minecraft:block/(x)': " + fields + "\n"),
                    fields);
        }
    }

    @Test
    void aLikeRowNamesALiteralTextureAndHasNoStrengthOrContrastOfItsOwn() {
        // The like row comes first in the file and its source has a group: neither matters.
        RetroManifest manifest =
                parse(
                        """
                        tints:
                          'minecraft:block/(front)':
                            source: 'minecraft:block/$1_art'
                            tint: {like: 'minecraft:block/side'}
                          'minecraft:block/side':
                            tint: {match: 'minecraft:block/bricks'}
                            strength: 0.5
                            contrast: 0.45
                        """);

        TextureSections.Tint like = manifest.textures().tints().get(0);
        assertEquals(new TintFactor.Like("minecraft:block/side"), like.factor());
        assertEquals("minecraft:block/$1_art", like.source());
        assertEquals(1.0, like.strength(), "none of its own");
        assertEquals(1.0, like.contrast(), "none of its own");
    }

    @Test
    void aMalformedLikeFailsTheParse() {
        // Each fixture's other rows are well formed, so the like alone fails it.
        String a = "  'minecraft:block/a': ";
        String likeB = "{tint: {like: 'minecraft:block/b'}";
        String named = "  'minecraft:block/b': {tint: auto, contrast: 0.5}\n";
        assertEquals(
                2,
                parse("tints:\n" + a + likeB + "}\n" + named).textures().tints().size(),
                "the like and its row alone parse");
        for (String rows :
                List.of(
                        // Its own strength or contrast.
                        a + likeB + ", contrast: 0.5}\n" + named,
                        a + likeB + ", strength: 1}\n" + named,
                        // No row matches the texture it names.
                        a + "{tint: {like: 'minecraft:block/c'}}\n" + named,
                        // Two rows match it.
                        a + likeB + "}\n" + named + "  'minecraft:block/(b|c)': {tint: auto}\n",
                        // The row matching it is a like too, or the like's own row.
                        a
                                + likeB
                                + "}\n"
                                + "  'minecraft:block/b': {tint: {like: 'minecraft:block/c'}}\n"
                                + "  'minecraft:block/c': {tint: auto}\n",
                        "  'minecraft:block/(a|b)': " + likeB + "}\n",
                        // Not a literal texture id.
                        "  'minecraft:block/(a)': {tint: {like: 'minecraft:block/$1'}}\n" + named,
                        a + "{tint: {like: 'minecraft:block/(b)'}}\n" + named,
                        a + "{tint: {like: 'block/b'}}\n" + named,
                        a + "{tint: {like: ''}}\n" + named,
                        a + "{tint: {like: 5}}\n" + named,
                        // Beside another way to find the factors.
                        a
                                + "{tint: {like: 'minecraft:block/b',"
                                + " match: 'minecraft:block/c'}}\n"
                                + named)) {
            assertThrows(IllegalArgumentException.class, () -> parse("tints:\n" + rows), rows);
        }
    }

    @Test
    void anUnknownSourceFailsTheParse() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parse(
                                """
                                sources:
                                  '.*': [programmer_art, vanila]
                                """));
    }

    @Test
    void oldClientRowsReadTheirFileOrBlankPerClientInFileOrder() {
        RetroManifest manifest =
                parse(
                        """
                        old_clients:
                          '1.7.10':
                            'minecraft:block/light_gray_wool': 'minecraft:blocks/wool_colored_silver'
                            'minecraft:block/(\\w+)_wool': 'minecraft:blocks/wool_colored_$1'
                            'minecraft:entity/villager/type/\\w+': blank
                          '1.9.4':
                            'minecraft:entity/shield/(\\w+)': 'minecraft:entity/shield/$1'
                        old_client_clears:
                          'minecraft:entity/villager/\\w+': [[32, 0, 32, 18], [0, 62, 28, 2]]
                        """);

        TextureSections textures = manifest.textures();
        List<TextureSections.OldClientRow> rows =
                textures.oldClients().get(RetroSource.MINECRAFT_1_7_10);
        assertEquals(
                List.of(
                        "minecraft:block/light_gray_wool",
                        "minecraft:block/(\\w+)_wool",
                        "minecraft:entity/villager/type/\\w+"),
                rows.stream().map(row -> row.textures().pattern()).toList());
        assertEquals("minecraft:blocks/wool_colored_$1", rows.get(1).file());
        assertNull(rows.get(2).file(), "blank");
        assertEquals(1, textures.oldClients().get(RetroSource.MINECRAFT_1_9_4).size());
        assertNull(textures.oldClients().get(RetroSource.MINECRAFT_1_8_9), "no rows, no client");
        assertEquals(
                List.of(new Rect(32, 0, 32, 18), new Rect(0, 62, 28, 2)),
                textures.oldClientClears().get(0).rects());
    }

    @Test
    void anOldClientsVersionMustBeAnOldClient() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parse(
                                """
                                old_clients:
                                  vanilla:
                                    'minecraft:block/stone': 'minecraft:blocks/stone'
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parse(
                                """
                                old_clients:
                                  '1.12.2':
                                    'minecraft:block/stone': 'minecraft:blocks/stone'
                                """));
    }

    @Test
    void anOldClientsFileIsAFullIdWhoseGroupsThePatternHas() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parse(
                                """
                                old_clients:
                                  '1.7.10':
                                    'minecraft:block/stone': 'blocks/stone'
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parse(
                                """
                                old_clients:
                                  '1.7.10':
                                    'minecraft:block/stone': 'minecraft:blocks/$1'
                                """));
    }

    @Test
    void anOldClientClearNeedsARectangle() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parse(
                                """
                                old_client_clears:
                                  'minecraft:entity/villager/villager': []
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parse(
                                """
                                old_client_clears:
                                  'minecraft:entity/villager/villager': [[0, 0, 0, 4]]
                                """));
    }
}
