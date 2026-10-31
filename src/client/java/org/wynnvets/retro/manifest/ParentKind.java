package org.wynnvets.retro.manifest;

/**
 * How an {@code item_models} stub is held and shown: as the vanilla model
 * {@code minecraft:item/<yaml name>}, whose display the stub's parent inherits.
 */
public enum ParentKind {
    /** As {@code minecraft:item/handheld}: held diagonally, like a tool or a stick. */
    HANDHELD("handheld"),
    /** As {@code minecraft:item/handheld_rod}: held out like a fishing rod. */
    HANDHELD_ROD("handheld_rod"),
    /** As {@code minecraft:item/bow}: held upright like a bow. */
    BOW("bow"),
    /** As {@code minecraft:item/generated}: held flat, like most items. */
    GENERATED("generated");

    private final String yamlName;

    ParentKind(String yamlName) {
        this.yamlName = yamlName;
    }

    /**
     * The kind's name in {@code retro_textures.yml}.
     *
     * @return the name, lowercase
     */
    public String yamlName() {
        return yamlName;
    }

    /**
     * Looks a kind up by its name in {@code retro_textures.yml}. The match is exact.
     *
     * @param yamlName the name from the file, which may be {@code null}
     * @return the kind, or {@code null} for {@code null} or a name this client does not know
     */
    public static ParentKind fromYaml(String yamlName) {
        for (ParentKind kind : values()) {
            if (kind.yamlName.equals(yamlName)) {
                return kind;
            }
        }
        return null;
    }
}
