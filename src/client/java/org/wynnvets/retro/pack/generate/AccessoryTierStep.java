package org.wynnvets.retro.pack.generate;

import java.util.Map;
import net.minecraft.resources.Identifier;
import org.wynnvets.rendering.colors.Recolours;
import org.wynnvets.rendering.colors.Recolours.Outline;
import org.wynnvets.rendering.image.PixelDrawing;
import org.wynnvets.rendering.image.Pixels;
import org.wynnvets.rendering.image.PngCodec;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.manifest.StackSections;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * {@code accessory_tiers}: Wynncraft's art for each type in each tier's ramp, as a
 * {@link GeneratedItemWriter generated item} at {@link RetroAssetIds#accessoryPath}, and likewise
 * the art's edge and inner lines for the type's empty slot. Ingredient rows parent these items, so it runs before
 * the stubs.
 */
final class AccessoryTierStep implements GenerationStep {

    @Override
    public void run(GenerationContext gen) {
        PackOutput out = gen.out;
        StackSections.AccessoryTiers rows = gen.manifest.stacks().accessoryTiers();
        for (Map.Entry<String, String> art : rows.art().entrySet()) {
            Identifier from = out.textureFile(art.getValue());
            if (from == null) continue;
            out.attempt(
                    "could not tint " + art.getValue(),
                    () -> {
                        byte[] png = gen.liveFile(from);
                        if (png == null) {
                            out.skip("Wynncraft has no " + art.getValue() + " for its accessories");
                            return;
                        }
                        GeneratedItemWriter.write(
                                out,
                                RetroAssetIds.accessoryPath(
                                        art.getKey(), RetroAssetIds.ACCESSORY_EMPTY),
                                PixelDrawing.edge(
                                        PngCodec.decode(png),
                                        0xFF000000 | rows.emptyOutline(),
                                        rows.emptyLinesBelowLuma()),
                                Count.ACCESSORY_TIERS);
                        for (StackSections.AccessoryTier tier : rows.tiers()) {
                            Pixels image = PngCodec.decode(png);
                            image.apply(
                                    Recolours.withOutline(
                                            Recolours.ramp(tier.ramp()),
                                            Outline.greyBelow(rows.outlineBelowLuma())));
                            GeneratedItemWriter.write(
                                    out,
                                    RetroAssetIds.accessoryPath(art.getKey(), tier.name()),
                                    image,
                                    Count.ACCESSORY_TIERS);
                        }
                    });
        }
    }
}
