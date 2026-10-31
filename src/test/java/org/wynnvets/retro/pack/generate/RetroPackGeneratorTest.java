package org.wynnvets.retro.pack.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.AssetSections;
import org.wynnvets.retro.manifest.RetroManifest;

/**
 * Tests for {@link RetroPackGenerator}'s static files: the language files it answers from the
 * rule files alone. {@link GenerationStepsTest} covers the generation.
 */
class RetroPackGeneratorTest {

    private static JsonObject json(byte[] file) {
        return JsonParser.parseString(new String(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void eachLocaleGetsOneLanguageFileHoldingEveryString() {
        Map<String, String> strings = new LinkedHashMap<>();
        strings.put("container.inventory", "Inventory");
        strings.put("menu.game", "Game Menu");

        Map<Identifier, byte[]> files =
                RetroPackGenerator.langFiles(
                        new AssetSections.Lang(List.of("en_us", "en_gb"), strings));

        assertEquals(
                List.of(
                        Identifier.parse("minecraft:lang/en_us.json"),
                        Identifier.parse("minecraft:lang/en_gb.json")),
                List.copyOf(files.keySet()),
                "in the section's order");
        for (byte[] file : files.values()) {
            JsonObject lang = json(file);
            assertEquals(2, lang.size());
            assertEquals("Inventory", lang.get("container.inventory").getAsString());
            assertEquals("Game Menu", lang.get("menu.game").getAsString());
        }
    }

    @Test
    void noStringsMeansNoFiles() {
        // An empty file would still be a language file, so none is written.
        assertTrue(
                RetroPackGenerator.langFiles(new AssetSections.Lang(List.of("en_us"), Map.of()))
                        .isEmpty());
        assertTrue(
                RetroPackGenerator.langFiles(
                                new AssetSections.Lang(List.of(), Map.of("menu.game", "Game Menu")))
                        .isEmpty());
    }

    @Test
    void theStaticFilesAreTheRealLangSections() throws IOException {
        AssetSections.Lang lang = RetroManifest.load().assets().lang();

        Map<Identifier, byte[]> files = RetroPackGenerator.staticFiles();

        assertEquals(lang.locales().size(), files.size());
        for (String locale : lang.locales()) {
            byte[] file = files.get(Identifier.parse("minecraft:lang/" + locale + ".json"));
            assertEquals(lang.strings().size(), json(file).size(), locale);
        }
    }

    @Test
    void theStaticFilesFollowTheScreensPart() {
        AssetSections.Lang lang =
                new AssetSections.Lang(List.of("en_us"), Map.of("menu.game", "Game Menu"));

        assertEquals(1, RetroPackGenerator.staticFiles(lang, true).size());
        assertTrue(RetroPackGenerator.staticFiles(lang, false).isEmpty());
    }
}
