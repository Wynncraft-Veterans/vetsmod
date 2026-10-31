package org.wynnvets.retro.rules;

import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.manifest.TintFactor;

/** What the retro pack does with one texture; {@link TextureRules} decides it. */
public sealed interface TextureDecision {
    /**
     * Copy {@code from}, out of {@code source}, to the texture's own id, together with the
     * {@code .mcmeta} beside it, if any.
     *
     * @param from the texture read: the texture itself, or an alias's target
     * @param source the pack it is read from
     */
    record Copy(String from, RetroSource source) implements TextureDecision {}

    /**
     * Write {@code from}, out of {@code source}, to the texture's own id with each channel
     * multiplied by the factor {@code factor} and {@code strength} give, then its contrast changed
     * by {@code contrast}, together with the {@code .mcmeta} beside {@code from}, if any. For a
     * {@link TintFactor.Like}, the factors and the mean the contrast turns around are those
     * {@code like} gives its own texture, the one the factor names.
     *
     * @param from the texture read: the {@code tints} row's source
     * @param source the pack it is read from
     * @param factor how the factors are found, its texture ids filled in
     * @param strength how much of them is applied, from 0 to 1; for a like, {@code like}'s
     * @param contrast the contrast, at least 0, 1 changing nothing: the row's, or for a like,
     *     {@code like}'s
     * @param like for a like, the decision the row it names makes for the texture it names, which
     *     is no like; {@code null} otherwise
     */
    record Tint(
            String from,
            RetroSource source,
            TintFactor factor,
            double strength,
            double contrast,
            Tint like)
            implements TextureDecision {

        /**
         * A tint that is no like.
         *
         * @param from the texture read: the {@code tints} row's source
         * @param source the pack it is read from
         * @param factor how the factors are found, its texture ids filled in
         * @param strength how much of them is applied, from 0 to 1
         * @param contrast the row's contrast, at least 0; 1 changes nothing
         */
        public Tint(
                String from,
                RetroSource source,
                TintFactor factor,
                double strength,
                double contrast) {
            this(from, source, factor, strength, contrast, null);
        }
    }

    /**
     * Emit nothing, so Wynncraft's texture shows, or the lower packs' when Wynncraft ships none.
     *
     * @param reason why
     */
    record Keep(KeepReason reason) implements TextureDecision {}
}
