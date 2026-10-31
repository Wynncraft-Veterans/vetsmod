package org.wynnvets.retro.pack.generate;

import java.util.TreeSet;
import net.minecraft.resources.Identifier;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.pack.PackFiles;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * {@code gear_box_icons}: vanilla's item definition of each item a row names, byte for byte, at
 * {@link RetroAssetIds#itemDefinitionCopy}'s id, so the GUI can draw the vanilla item whatever
 * Wynncraft's own definition of it draws. Item definitions are read by the model manager's item
 * definition loader, on the reload's workers.
 */
final class ItemDefinitionStep implements GenerationStep {

    @Override
    public void run(GenerationContext gen) {
        PackOutput out = gen.out;
        for (String item : new TreeSet<>(gen.manifest.stacks().gearBoxIcons().values())) {
            Identifier id = Identifier.tryParse(item);
            String copy = RetroAssetIds.itemDefinitionCopy(item);
            Identifier copyId = copy == null ? null : Identifier.tryParse(copy);
            if (id == null || copyId == null) {
                out.skip("not an item id: " + item);
                continue;
            }
            out.attempt(
                    "could not copy the item definition of " + item,
                    () -> {
                        byte[] json =
                                gen.sources.read(RetroSource.VANILLA, PackFiles.ITEMS.idToFile(id));
                        if (json == null) {
                            out.skip("vanilla has no item definition for " + item);
                            return;
                        }
                        out.files.put(PackFiles.ITEMS.idToFile(copyId), json);
                        out.copiedItems.add(item);
                        out.count(Count.ITEM_DEFINITIONS);
                    });
        }
    }
}
