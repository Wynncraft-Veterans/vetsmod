package org.wynnvets.retro.pack.generate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.resources.Identifier;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.pack.PackFiles;
import org.wynnvets.retro.rules.KeepReason;
import org.wynnvets.retro.rules.TextureDecision;

/**
 * Textures, as {@link org.wynnvets.retro.rules.TextureRules#decideAll TextureRules.decideAll}
 * decides them over every texture the packs up to Wynncraft's hold and every texture a source has:
 * {@link TextureWriter} writes the copies and {@link TintWriter} the tints, but for those a part
 * being off leaves out ({@link DrawnArt}).
 */
final class TextureStep implements GenerationStep {

    private static final TextureDecision.Keep PART_OFF =
            new TextureDecision.Keep(KeepReason.PART_OFF);

    @Override
    public void run(GenerationContext gen) {
        PackOutput out = gen.out;
        Set<String> textureIds = new TreeSet<>(gen.sources.textureIds());
        for (Identifier file : PackFiles.TEXTURES.listMatchingResources(gen.lower).keySet()) {
            textureIds.add(PackFiles.TEXTURES.fileToId(file).toString());
        }
        Map<String, TextureDecision> decisions =
                withPartsOff(gen, gen.textureRules.decideAll(textureIds, gen.facts));
        out.decisions.putAll(decisions);
        TextureWriter writer = new TextureWriter(gen);
        TintWriter tints = new TintWriter(gen, writer);
        for (Map.Entry<String, TextureDecision> decided : decisions.entrySet()) {
            String textureId = decided.getKey();
            switch (decided.getValue()) {
                case TextureDecision.Copy copy -> writer.copy(textureId, copy);
                case TextureDecision.Tint tint -> tints.tint(textureId, tint);
                case TextureDecision.Keep keep -> kept(gen, textureId, keep.reason());
            }
        }
        for (String edited : gen.manifest.assets().imageEdits().keySet()) {
            TextureDecision decision = decisions.get(edited);
            if (decision instanceof TextureDecision.Keep keep) {
                if (keep.reason() != KeepReason.PART_OFF) {
                    out.skip("image_edits for " + edited + ": it is kept (" + keep.reason() + ")");
                }
            } else if (decision == null) {
                out.skip("image_edits for " + edited + ": no pack has it");
            }
        }
    }

    /**
     * {@code decisions} with each copy and tint a part being off leaves out turned into a keep.
     * With every part on it is {@code decisions} itself, and no model is read.
     */
    private static Map<String, TextureDecision> withPartsOff(
            GenerationContext gen, Map<String, TextureDecision> decisions) {
        if (gen.parts.allOn()) return decisions;
        DrawnArt drawn = DrawnArt.of(gen);
        Map<String, TextureDecision> filtered = new LinkedHashMap<>();
        decisions.forEach(
                (textureId, decision) ->
                        filtered.put(
                                textureId,
                                decision instanceof TextureDecision.Keep
                                                || gen.parts.textureOn(
                                                        textureId, drawn.on(), drawn.off())
                                        ? decision
                                        : PART_OFF));
        return filtered;
    }

    /** Counts a texture that stays Wynncraft's under its reason; a missing source is reported. */
    private static void kept(GenerationContext gen, String textureId, KeepReason reason) {
        PackOutput out = gen.out;
        switch (reason) {
            case KEEP_WYNNCRAFT -> out.count(Count.KEEP_WYNNCRAFT);
            case NO_SOURCE ->
                    out.skip(
                            "no source has a texture the alias or tint for "
                                    + textureId
                                    + " reads");
            case LAYER_GROUP -> out.count(Count.LAYER_GROUPS);
            case PART_OFF -> out.count(Count.PARTS_OFF);
            case TRANSPARENT, NO_RULE -> {
                // TRANSPARENT never comes back here: telling it apart needs a decoded image.
                // Only Wynncraft's textures count: any other one is vanilla's, which stays.
                if (gen.shipped.contains(textureId)) out.count(Count.NO_RULE);
            }
        }
    }
}
