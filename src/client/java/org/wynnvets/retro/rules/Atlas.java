package org.wynnvets.retro.rules;

import java.util.Optional;

/**
 * The texture atlas an item-model stub draws from. An item model cannot mix atlases (one that does
 * fails to bake), so a stub's art and its parent's blank tint layer share one.
 */
public enum Atlas {
    /** The blocks atlas: every namespace's {@code block/} textures. */
    BLOCKS("block"),
    /** The items atlas: every namespace's {@code item/} textures. */
    ITEMS("item");

    private final String folder;

    Atlas(String folder) {
        this.folder = folder;
    }

    /**
     * The texture folder the atlas takes, which also names a stub's parent.
     *
     * @return {@code block} or {@code item}
     */
    public String folder() {
        return folder;
    }

    /**
     * The atlas a texture belongs to, from its folder.
     *
     * @param textureId a full texture id such as {@code minecraft:item/stick}, which may be
     *     {@code null}
     * @return {@link #BLOCKS} for {@code <namespace>:block/<name>}, {@link #ITEMS} for
     *     {@code <namespace>:item/<name>}; empty for any other folder, a missing namespace or
     *     name, or {@code null}
     */
    public static Optional<Atlas> of(String textureId) {
        if (textureId == null) return Optional.empty();
        int colon = textureId.indexOf(':');
        int slash = textureId.indexOf('/');
        if (colon <= 0 || slash < colon || slash == textureId.length() - 1) {
            return Optional.empty();
        }
        String folder = textureId.substring(colon + 1, slash);
        for (Atlas atlas : values()) {
            if (atlas.folder.equals(folder)) return Optional.of(atlas);
        }
        return Optional.empty();
    }
}
