package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ManifestFixtures.parseTextures;

import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

/** Tests for the parser's reading of {@link PartSections}, on small fixtures. */
class PartSectionsTest {

    @Test
    void aRowReadsItsAboutItsPatternsAndItsFeatures() {
        PartSections.Part row =
                parseTextures(
                                """
                                parts:
                                  weapons:
                                    about: 'Weapons'
                                    textures: ['minecraft:a/.*']
                                    models: ['minecraft:b/.*', 'minecraft:c']
                                    wynntils: ['Feature']
                                """)
                        .parts()
                        .parts()
                        .get(RetroPart.WEAPONS);

        assertEquals(RetroPart.WEAPONS, row.part());
        assertEquals("Weapons", row.about());
        assertEquals("minecraft:a/.*", row.textures().get(0).pattern());
        assertEquals(2, row.models().size());
        assertEquals(Set.of("Feature"), row.wynntils());
    }

    @Test
    void onlyAboutIsRequiredAndTheRowsKeepTheirOrder() {
        PartSections parts =
                parseTextures(
                                """
                                parts:
                                  hud: {about: 'b'}
                                  blocks: {about: 'a'}
                                """)
                        .parts();

        assertEquals(List.of(RetroPart.HUD, RetroPart.BLOCKS), List.copyOf(parts.parts().keySet()));
        assertTrue(parts.parts().get(RetroPart.HUD).textures().isEmpty());
        assertTrue(parts.parts().get(RetroPart.HUD).wynntils().isEmpty());
    }

    @Test
    void noSectionMeansNoRows() {
        assertTrue(parseTextures("").parts().parts().isEmpty());
    }

    @Test
    void aMalformedRowFailsTheParse() {
        for (String row :
                List.of(
                        "nope: {about: 'a'}",
                        "HUD: {about: 'a'}",
                        "hud: {textures: ['minecraft:a']}",
                        "hud: {about: 'a', other: 'b'}",
                        "hud: {about: 'a', wynntils: ['']}",
                        "hud: {about: 'a', models: 'minecraft:a'}")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> parseTextures("parts:\n  " + row + "\n"),
                    row);
        }
        assertThrows(
                PatternSyntaxException.class,
                () -> parseTextures("parts:\n  hud: {about: 'a', textures: ['(']}\n"));
    }
}
