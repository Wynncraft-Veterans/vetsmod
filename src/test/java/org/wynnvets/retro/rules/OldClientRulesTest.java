package org.wynnvets.retro.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.manifest.TextureSections.OldClientClear;
import org.wynnvets.retro.manifest.TextureSections.OldClientRow;

/** Tests for {@link OldClientRules}: which texture an old client holds, and what is cleared. */
class OldClientRulesTest {

    private static OldClientRow row(String textures, String file) {
        return new OldClientRow(Pattern.compile(textures), file);
    }

    @Test
    void eachTextureTakesItsFirstMatchingRowInIdOrder() {
        List<OldClientRow> rows =
                List.of(
                        row(
                                "minecraft:block/light_gray_wool",
                                "minecraft:blocks/wool_colored_silver"),
                        row("minecraft:block/(\\w+)_wool", "minecraft:blocks/wool_colored_$1"),
                        row("minecraft:entity/villager/type/\\w+", null));

        assertEquals(
                List.of(
                        new OldClientRules.Target(
                                "minecraft:block/light_gray_wool",
                                "minecraft:blocks/wool_colored_silver",
                                0),
                        new OldClientRules.Target(
                                "minecraft:block/white_wool",
                                "minecraft:blocks/wool_colored_white",
                                1),
                        new OldClientRules.Target(
                                "minecraft:entity/villager/type/plains", null, 2)),
                OldClientRules.targets(
                        rows,
                        Set.of(
                                "minecraft:entity/villager/type/plains",
                                "minecraft:block/white_wool",
                                "minecraft:block/stone",
                                "minecraft:block/light_gray_wool")));
    }

    @Test
    void everyMatchingClearRowAddsItsRectangles() {
        List<OldClientClear> clears =
                List.of(
                        new OldClientClear(
                                Pattern.compile("minecraft:entity/villager/\\w+"),
                                List.of(new Rect(32, 0, 32, 18))),
                        new OldClientClear(
                                Pattern.compile("minecraft:entity/villager/(villager|farmer)"),
                                List.of(new Rect(0, 62, 28, 2))));

        assertEquals(
                List.of(new Rect(32, 0, 32, 18), new Rect(0, 62, 28, 2)),
                OldClientRules.clears(clears, "minecraft:entity/villager/villager"));
        assertEquals(
                List.of(new Rect(32, 0, 32, 18)),
                OldClientRules.clears(clears, "minecraft:entity/villager/smith"));
        assertEquals(List.of(), OldClientRules.clears(clears, "minecraft:block/stone"));
    }
}
