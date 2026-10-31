package org.wynnvets.debug.retro;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.wynnvets.rendering.colors.ColorMath;
import org.wynnvets.rendering.image.Pixels;
import org.wynnvets.rendering.image.PngCodec;
import org.wynnvets.retro.RetroReport;
import org.wynnvets.retro.pack.PackFiles;
import org.wynnvets.retro.rules.TextureDecision;

/**
 * The retro report's test of whether Wynncraft's texture is still a picture of vanilla's: a repaint
 * keeps the shapes, so it scores high whatever its colours.
 */
final class TextureDivergence {

    /** Below this brightness correlation, Wynncraft's texture no longer pictures vanilla's. */
    static final double DIVERGENT = 0.9;

    /** The side of the square {@link #brightnessCorrelation} scales both images to. */
    private static final int COMPARED_SIDE = 16;

    /** Below this sum of squared deviations, an image's brightness counts as one value. */
    private static final double FLAT = 1e-6;

    private TextureDivergence() {}

    /**
     * The divergent block textures.
     *
     * @param lines one line per texture, lowest correlation first
     * @param compared how many were compared
     * @param unreadable how many could not be read
     */
    record Found(List<String> lines, int compared, int unreadable) {}

    /**
     * The block textures {@code same_name} replaced whose Wynncraft art no longer pictures
     * vanilla's: their {@link #brightnessCorrelation} below {@link #DIVERGENT}. A texture vanilla
     * lacks is measured against the copy the retro pack drew.
     *
     * @param seen the generation
     * @param wynncraft Wynncraft's pack
     * @param vanilla Minecraft's vanilla pack
     * @param retro the retro pack drawn now, or {@code null}
     * @return what was found
     */
    static Found divergent(
            RetroReport.Seen seen,
            PackResources wynncraft,
            PackResources vanilla,
            PackResources retro) {
        Map<String, Double> correlations = new HashMap<>();
        Map<String, String> against = new HashMap<>();
        int unreadable = 0;
        for (Map.Entry<String, TextureDecision> texture : seen.textures().entrySet()) {
            String id = texture.getKey();
            if (!(texture.getValue() instanceof TextureDecision.Copy copy)
                    || !copy.from().equals(id)
                    || !id.startsWith("minecraft:block/")
                    || !seen.wynncraftTextures().contains(id)) {
                continue;
            }
            Identifier file = PackFiles.textureFile(id);
            boolean inVanilla = file != null && vanilla.getResource(PackFiles.TYPE, file) != null;
            Pixels wynncraftArt = decoded(wynncraft, file);
            Pixels reference = decoded(inVanilla ? vanilla : retro, file);
            if (wynncraftArt == null || reference == null) {
                unreadable++;
                continue;
            }
            double correlation = brightnessCorrelation(wynncraftArt, reference);
            correlations.put(id, correlation);
            if (correlation < DIVERGENT) {
                against.put(id, inVanilla ? "vanilla" : copy.source().yamlName());
            }
        }
        List<String> lines = new ArrayList<>();
        against.keySet().stream()
                .sorted((a, b) -> Double.compare(correlations.get(a), correlations.get(b)))
                .forEach(
                        id ->
                                lines.add(
                                        String.format(
                                                Locale.ROOT,
                                                "%s  %.2f against %s",
                                                id,
                                                correlations.get(id),
                                                against.get(id))));
        return new Found(lines, correlations.size(), unreadable);
    }

    /** {@code file} in {@code pack}, decoded; {@code null} when it is not there or unreadable. */
    private static Pixels decoded(PackResources pack, Identifier file) {
        IoSupplier<InputStream> contents =
                pack == null || file == null ? null : pack.getResource(PackFiles.TYPE, file);
        if (contents == null) return null;
        try {
            return PngCodec.decode(PackFiles.read(contents));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * How closely two images' light and dark follow each other: the correlation of their
     * brightness, cell by cell, once each is scaled to {@value #COMPARED_SIDE} pixels square.
     *
     * <p>A pixel's brightness is its {@link ColorMath#luma luma} times its opacity, so a
     * transparent pixel counts as black. A cell is the mean of the pixels that fall in it, or,
     * along a side shorter than the square, the nearest pixel. A strip of animation frames, a
     * height that is a whole number of times its width, counts by its first frame.
     *
     * @param first an image
     * @param second another image, of any size
     * @return from -1 to 1: 1 when one image's brightness is the other's scaled and shifted (the
     *     same picture in other colours), near 0 for unrelated pictures, -1 for a negative; when an
     *     image is a single brightness throughout, 1 if the other is too and 0 if not
     */
    static double brightnessCorrelation(Pixels first, Pixels second) {
        double[] a = brightness(first);
        double[] b = brightness(second);
        double meanA = Arrays.stream(a).average().orElse(0);
        double meanB = Arrays.stream(b).average().orElse(0);
        double covariance = 0;
        double varianceA = 0;
        double varianceB = 0;
        for (int i = 0; i < a.length; i++) {
            covariance += (a[i] - meanA) * (b[i] - meanB);
            varianceA += (a[i] - meanA) * (a[i] - meanA);
            varianceB += (b[i] - meanB) * (b[i] - meanB);
        }
        boolean flatA = varianceA < FLAT;
        boolean flatB = varianceB < FLAT;
        if (flatA || flatB) return flatA == flatB ? 1 : 0;
        return covariance / Math.sqrt(varianceA * varianceB);
    }

    /**
     * The brightness of each cell of {@code image}'s first frame, scaled to {@link #COMPARED_SIDE}
     * square, row by row.
     */
    private static double[] brightness(Pixels image) {
        int width = image.width();
        boolean strip = image.height() > width && image.height() % width == 0;
        int height = strip ? width : image.height();
        double[] cells = new double[COMPARED_SIDE * COMPARED_SIDE];
        for (int cellY = 0; cellY < COMPARED_SIDE; cellY++) {
            int top = cellY * height / COMPARED_SIDE;
            int bottom = Math.max(top + 1, (cellY + 1) * height / COMPARED_SIDE);
            for (int cellX = 0; cellX < COMPARED_SIDE; cellX++) {
                int left = cellX * width / COMPARED_SIDE;
                int right = Math.max(left + 1, (cellX + 1) * width / COMPARED_SIDE);
                double sum = 0;
                for (int y = top; y < bottom; y++) {
                    for (int x = left; x < right; x++) {
                        int pixel = image.get(x, y);
                        sum += ColorMath.luma(pixel) * ColorMath.alpha(pixel) / 255.0;
                    }
                }
                cells[cellY * COMPARED_SIDE + cellX] = sum / ((bottom - top) * (right - left));
            }
        }
        return cells;
    }
}
