package org.wynnvets.retro.manifest;

/**
 * A pack the retro pack copies textures from, with the name {@code retro_textures.yml} gives it.
 *
 * <p>The file's {@code sources} section lists these per texture in order of preference, and
 * {@link org.wynnvets.retro.rules.TextureRules TextureRules} takes the first one that has the
 * texture. A source that is missing at runtime simply has no textures, so the next one in the list
 * supplies them.
 */
public enum RetroSource {
    /** The Vanilla Tweaks art the jar bundles, credited in {@code credits.txt}. */
    VANILLA_TWEAKS("vanilla_tweaks"),
    /** The {@code programmer_art} pack built into the running client. */
    PROGRAMMER_ART("programmer_art"),
    /** The running client's own textures. */
    VANILLA("vanilla");

    private final String yamlName;

    RetroSource(String yamlName) {
        this.yamlName = yamlName;
    }

    /**
     * The source's name in {@code retro_textures.yml}.
     *
     * @return the name, lowercase
     */
    public String yamlName() {
        return yamlName;
    }

    /**
     * Looks a source up by its name in {@code retro_textures.yml}. The match is exact.
     *
     * @param yamlName the name from the file, which may be {@code null}
     * @return the source, or {@code null} for {@code null} or a name this client does not know
     */
    public static RetroSource fromYaml(String yamlName) {
        for (RetroSource source : values()) {
            if (source.yamlName.equals(yamlName)) {
                return source;
            }
        }
        return null;
    }
}
