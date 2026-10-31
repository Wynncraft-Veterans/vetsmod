package org.wynnvets.retro.rules;

import org.wynnvets.retro.manifest.RetroSource;

/**
 * What the live packs hold, as far as {@link TextureRules} needs to know. The generator answers
 * from the Wynncraft pack and the sources it opened; a test answers from sets.
 */
public interface PackFacts {
    /** Whether the Wynncraft pack ships {@code textureId}. */
    boolean wynncraftShips(String textureId);

    /**
     * Whether Wynncraft's copy of {@code textureId} is fully transparent. Asked only for a texture
     * Wynncraft ships and no rule replaced, so an expensive scan can stay lazy. The answer changes
     * no file, since such a texture stays either way, so the pack generator answers {@code false}
     * rather than decode the image; only a report needs the split.
     */
    boolean wynncraftTransparent(String textureId);

    /** Whether {@code source} has {@code textureId}; never, for a source that is missing. */
    boolean has(RetroSource source, String textureId);
}
