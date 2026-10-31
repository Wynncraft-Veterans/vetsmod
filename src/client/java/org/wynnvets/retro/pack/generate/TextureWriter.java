package org.wynnvets.retro.pack.generate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.wynnvets.rendering.image.PixelDrawing;
import org.wynnvets.rendering.image.Pixels;
import org.wynnvets.rendering.image.PngCodec;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.manifest.AssetSections.Fill;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.pack.PackFiles;
import org.wynnvets.retro.rules.TextureDecision;

/**
 * Writes the files of {@link TextureStep}'s decisions. A copy takes the source's PNG and the
 * {@code .mcmeta} beside it, if any, because Minecraft reads a texture's {@code .mcmeta} only from
 * the pack that provides the PNG or one above it. A copy or tint with {@code image_edits} rows has
 * their fills painted on, each one only where its palette guard allows. A {@code same_name} copy
 * from vanilla of a texture Wynncraft does not ship and no edit touches is left out: it would
 * change nothing, or override a resource pack the player chose.
 */
final class TextureWriter {

    private final GenerationContext gen;
    private final PackOutput out;

    TextureWriter(GenerationContext gen) {
        this.gen = gen;
        this.out = gen.out;
    }

    /** Writes one copy, or counts it as unchanged. */
    void copy(String textureId, TextureDecision.Copy copy) {
        boolean sameName = copy.from().equals(textureId);
        if (sameName
                && copy.source() == RetroSource.VANILLA
                && !gen.shipped.contains(textureId)
                && !gen.manifest.assets().imageEdits().containsKey(textureId)) {
            out.count(Count.UNCHANGED);
            return;
        }
        PackOutput.CopyFiles files = out.copyFiles(copy.from(), textureId);
        if (files == null) return;
        out.attempt(
                "could not read " + copy.from() + " from " + copy.source().yamlName(),
                () -> {
                    byte[] png = gen.sources.read(copy.source(), files.from());
                    if (png == null) {
                        out.skip(copy.source().yamlName() + " has no " + copy.from());
                        return;
                    }
                    write(
                            textureId,
                            png,
                            copy.source(),
                            files,
                            sameName ? Count.SAME_NAME : Count.ALIASES);
                });
    }

    /**
     * Writes {@code png} as {@code textureId}'s file, with its {@code image_edits} painted on, and
     * the {@code .mcmeta} beside the file it was read from in {@code source} beside it, and counts
     * it.
     */
    void write(
            String textureId,
            byte[] png,
            RetroSource source,
            PackOutput.CopyFiles files,
            Count count)
            throws IOException {
        List<Fill> edits = gen.manifest.assets().imageEdits().get(textureId);
        List<Rect> painted = new ArrayList<>();
        byte[] written = edits == null ? png : edited(textureId, png, edits, painted);
        byte[] mcmeta = gen.sources.read(source, PackFiles.mcmeta(files.from()));
        out.writeTexture(files.to(), written, mcmeta, count);
        if (!painted.isEmpty()) {
            out.imageEdits.put(textureId, painted);
            out.count(Count.IMAGE_EDITS);
        }
    }

    /**
     * {@code png} with {@code edits} painted on: each fill takes the colour of its sample pixel,
     * and a fill its palette guard refuses is skipped and reported. The PNG comes back as it was
     * when no fill applied or it cannot be decoded. Once the edited PNG is written, the rectangles
     * painted are added to {@code painted}.
     */
    private byte[] edited(String textureId, byte[] png, List<Fill> edits, List<Rect> painted) {
        return out.attempt(
                "image_edits for " + textureId + ": could not edit it",
                png,
                () -> {
                    Pixels image = PngCodec.decode(png);
                    List<Rect> filled = new ArrayList<>();
                    for (Fill fill : edits) {
                        String where =
                                "image_edits for " + textureId + ": fill " + fill.rect().toYaml();
                        if (!image.contains(new Rect(fill.sampleX(), fill.sampleY(), 1, 1))) {
                            out.skip(where + " samples a pixel outside the texture");
                        } else if (PixelDrawing.fill(
                                image,
                                fill.rect(),
                                image.get(fill.sampleX(), fill.sampleY()),
                                fill.palette())) {
                            filled.add(fill.rect());
                        } else {
                            out.skip(
                                    where
                                            + " leaves the texture or holds a colour outside its"
                                            + " palette, so the texture's layout has changed");
                        }
                    }
                    if (filled.isEmpty()) return png;
                    byte[] edited = PngCodec.png(image);
                    painted.addAll(filled);
                    return edited;
                });
    }
}
