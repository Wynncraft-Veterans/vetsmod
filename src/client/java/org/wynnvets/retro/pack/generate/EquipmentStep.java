package org.wynnvets.retro.pack.generate;

import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.pack.PackFiles;

/**
 * {@code equipment}: each asset Wynncraft ships that a row sends to a vanilla asset, replaced with
 * vanilla's own file for that asset, byte for byte, unless {@code armour} is off. Equipment assets
 * are read by Minecraft's equipment asset loader, whose preparation runs on the reload's workers.
 */
final class EquipmentStep implements GenerationStep {

    @Override
    public void run(GenerationContext gen) {
        PackOutput out = gen.out;
        List<Identifier> assets =
                PackFiles.jsonFiles(
                        gen.wynncraft, Identifier.DEFAULT_NAMESPACE, PackFiles.EQUIPMENT_FOLDER);
        assets.sort(null);
        for (Identifier file : assets) {
            Identifier asset = PackFiles.EQUIPMENT.fileToId(file);
            out.equipmentIds.add(asset.toString());
            if (!gen.parts.on(RetroPart.ARMOUR)) continue;
            out.attempt(
                    "could not replace the equipment asset " + asset,
                    () -> {
                        Optional<String> target = gen.modelRules.equipmentAsset(asset.toString());
                        if (target.isEmpty()) return;
                        Identifier targetFile = PackFiles.equipmentFile(target.get());
                        byte[] json =
                                targetFile == null
                                        ? null
                                        : gen.sources.read(RetroSource.VANILLA, targetFile);
                        if (json == null) {
                            out.skip(
                                    "vanilla has no equipment asset "
                                            + target.get()
                                            + " for "
                                            + asset);
                            return;
                        }
                        out.files.put(file, json);
                        out.count(Count.EQUIPMENT);
                    });
        }
    }
}
