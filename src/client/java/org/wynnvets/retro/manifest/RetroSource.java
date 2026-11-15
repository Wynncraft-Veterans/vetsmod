package org.wynnvets.retro.manifest;

/**
 * A pack the retro pack copies textures from, with the name {@code retro.yml} gives it.
 *
 * <p>The file's {@code sources} section lists these per texture in order of preference, and
 * {@link org.wynnvets.retro.rules.TextureRules TextureRules} takes the first one that has the
 * texture. A source that is missing at runtime simply has no textures, so the next one in the list
 * supplies them.
 */
public enum RetroSource {
    /** Minecraft 1.7.10's art, as the {@code old_clients} rows take it from that client. */
    MINECRAFT_1_7_10("1.7.10", true),
    /** Minecraft 1.8.9's art, as the {@code old_clients} rows take it from that client. */
    MINECRAFT_1_8_9("1.8.9", true),
    /** Minecraft 1.9.4's art, as the {@code old_clients} rows take it from that client. */
    MINECRAFT_1_9_4("1.9.4", true),
    /** The Vanilla Tweaks art the jar bundles, credited in {@code credits.txt}. */
    VANILLA_TWEAKS("vanilla_tweaks", false),
    /** The {@code programmer_art} pack built into the running client. */
    PROGRAMMER_ART("programmer_art", false),
    /** The running client's own textures. */
    VANILLA("vanilla", false);

    private final String yamlName;
    private final boolean oldClient;

    RetroSource(String yamlName, boolean oldClient) {
        this.yamlName = yamlName;
        this.oldClient = oldClient;
    }

    /**
     * The source's name in {@code retro.yml}.
     *
     * @return the name: a version such as {@code 1.7.10} for an old client, else lowercase
     */
    public String yamlName() {
        return yamlName;
    }

    /**
     * Whether this is an old Minecraft client, whose files each player downloads from Mojang.
     *
     * @return {@code true} for the {@code old_clients} versions
     */
    public boolean oldClient() {
        return oldClient;
    }

    /**
     * Looks a source up by its name in {@code retro.yml}. The match is exact.
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
