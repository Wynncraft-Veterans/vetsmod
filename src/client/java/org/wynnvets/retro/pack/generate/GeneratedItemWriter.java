package org.wynnvets.retro.pack.generate;

import net.minecraft.resources.Identifier;
import org.wynnvets.rendering.image.Pixels;
import org.wynnvets.rendering.image.PngCodec;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.pack.PackFiles;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * Writes an item the pack makes up: a texture, a flat model of it and an item definition of that,
 * all at one path. A stack is drawn with such an item only when the report names it, so art that
 * has gone leaves the stack as it was and draws no missing model.
 */
final class GeneratedItemWriter {

    private GeneratedItemWriter() {}

    /**
     * Writes one item and counts it.
     *
     * @param out the generation's output
     * @param path the item's path ({@link RetroAssetIds#generatedItem})
     * @param image its art
     * @param count what it counts as
     */
    static void write(PackOutput out, String path, Pixels image, Count count) {
        String asset = RetroAssetIds.generatedAsset(path);
        String item = RetroAssetIds.generatedItem(path);
        out.files.put(PackFiles.textureFile(asset), PngCodec.png(image));
        out.files.put(PackFiles.modelFile(asset), ModelJson.generatedItem(asset));
        out.files.put(
                PackFiles.ITEMS.idToFile(Identifier.parse(item)), ModelJson.itemDefinition(asset));
        out.generatedItems.add(item);
        out.count(count);
    }
}
