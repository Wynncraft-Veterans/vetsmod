package org.wynnvets.retro.pack.generate;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.wynnvets.rendering.image.Pixels;
import org.wynnvets.rendering.image.PngCodec;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.manifest.TintFactor;
import org.wynnvets.retro.pack.PackFiles;
import org.wynnvets.retro.rules.TextureDecision;

/**
 * Writes {@link TextureStep}'s tints: the source's PNG with each channel multiplied by its factor
 * ({@link TintFactors#measure}), then its contrast changed around the result's mean
 * ({@link TintFactors#tint}), with the same {@code .mcmeta}. A {@code like} tint takes the factors,
 * the contrast and the mean of the texture it names, as that texture's own row works them out from
 * its own images.
 */
final class TintWriter {

    /**
     * The change a {@code tints} row makes to one texture, as a like applies it to another.
     *
     * @param factors {@code {red, green, blue}}, the row's factors for that texture
     * @param contrast the row's contrast, at least 0
     * @param pivot {@code {red, green, blue}}, the texture's mean after the factors, which the
     *     contrast turns around
     */
    private record Change(double[] factors, double contrast, double[] pivot) {}

    private final GenerationContext gen;
    private final PackOutput out;
    private final TextureWriter writer;

    /**
     * The change its own row makes to each texture a like names, by that texture's id, once worked
     * out: several likes name one texture.
     */
    private final Map<String, Change> likeChanges = new HashMap<>();

    TintWriter(GenerationContext gen, TextureWriter writer) {
        this.gen = gen;
        this.out = gen.out;
        this.writer = writer;
    }

    /** Writes {@code tint.from()} with the tint's change made to it as {@code textureId}. */
    void tint(String textureId, TextureDecision.Tint tint) {
        PackOutput.CopyFiles files = out.copyFiles(tint.from(), textureId);
        if (files == null) return;
        String where = "the tint for " + textureId;
        out.attempt(
                "could not make " + where + " from " + tint.from(),
                () -> {
                    Pixels image = tintSource(tint, files.from(), where);
                    if (image == null) return;
                    double[] factors;
                    if (tint.factor() instanceof TintFactor.Like like) {
                        Change change = likeChange(like.texture(), tint.like(), where);
                        if (change == null) return;
                        factors = change.factors();
                        TintFactors.tint(image, factors, change.contrast(), change.pivot());
                    } else {
                        factors = factors(textureId, tint, image, where);
                        if (factors == null) return;
                        TintFactors.tint(image, factors, tint.contrast());
                    }
                    writer.write(textureId, PngCodec.png(image), tint.source(), files, Count.TINTS);
                    out.tintFactors.put(textureId, List.of(factors[0], factors[1], factors[2]));
                });
    }

    /**
     * {@code tint.from()}, the file {@code from}, decoded from {@code tint.source()};
     * {@code null}, reported, when that source lacks it.
     */
    private Pixels tintSource(TextureDecision.Tint tint, Identifier from, String where)
            throws IOException {
        byte[] png = gen.sources.read(tint.source(), from);
        if (png == null) {
            out.skip(tint.source().yamlName() + " has no " + tint.from() + " for " + where);
            return null;
        }
        return PngCodec.decode(png);
    }

    /**
     * The factors {@code tint}, which is no like, multiplies {@code textureId}'s source,
     * {@code image}, by. The images they are measured on are read only as the kind of tint needs
     * them: a {@code match} texture from its own sources, as a copy would be, and Wynncraft's live
     * textures from the Wynncraft pack and those below it.
     *
     * @return the factors; {@code null}, reported, when they cannot be measured
     */
    private double[] factors(
            String textureId, TextureDecision.Tint tint, Pixels image, String where)
            throws IOException {
        Pixels reference =
                switch (tint.factor()) {
                    case TintFactor.Match match -> gen.sourceImage(match.texture());
                    case TintFactor.RelativeTo relative -> gen.liveImage(relative.texture());
                    case TintFactor.Colour colour -> null;
                    case TintFactor.Auto auto -> null;
                    case TintFactor.Unchanged unchanged -> null;
                    // Never asked: tint() takes a like's factors from the row it names.
                    case TintFactor.Like like -> null;
                };
        boolean measuresLive =
                tint.factor() instanceof TintFactor.RelativeTo
                        || tint.factor() instanceof TintFactor.Auto;
        Pixels live = measuresLive ? gen.liveImage(textureId) : null;
        double[] factors =
                TintFactors.measure(tint.factor(), tint.strength(), image, reference, live);
        if (factors == null) {
            out.skip(
                    where
                            + " ("
                            + tint.factor()
                            + ") has an image missing or fully transparent, or one"
                            + " whose mean it divides by has a channel of 0");
        }
        return factors;
    }

    /**
     * The change {@code named}'s own row makes to it, which a like applies: that row's factors,
     * measured on that texture's own images as its own tint measures them, its contrast, and its
     * mean after those factors, which that contrast turns around. Worked out once per texture.
     *
     * @param named the texture the like names
     * @param decision the decision its row makes for it, which is no like
     * @param where the like's tint, for a skipped line
     * @return the change; {@code null}, reported, when it cannot be worked out
     */
    private Change likeChange(String named, TextureDecision.Tint decision, String where)
            throws IOException {
        Change known = likeChanges.get(named);
        if (known != null) return known;
        String namedWhere = where + ", like " + named;
        Identifier file = PackFiles.textureFile(decision.from());
        if (file == null) {
            out.skip("not a texture id: " + decision.from() + " for " + namedWhere);
            return null;
        }
        Pixels art = tintSource(decision, file, namedWhere);
        if (art == null) return null;
        double[] factors = factors(named, decision, art, namedWhere);
        if (factors == null) return null;
        double[] pivot = TintFactors.tintedMean(art, factors);
        if (pivot == null) {
            out.skip(namedWhere + ": " + decision.from() + " is fully transparent");
            return null;
        }
        Change change = new Change(factors, decision.contrast(), pivot);
        likeChanges.put(named, change);
        return change;
    }
}
