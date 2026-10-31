package org.wynnvets.retro.rules;

/** Why a texture is not replaced. */
public enum KeepReason {
    /** A {@code keep_wynncraft} row covers it. */
    KEEP_WYNNCRAFT,
    /**
     * An alias or a tint covers it, but none of the sources of a texture it reads has that
     * texture: a gap.
     */
    NO_SOURCE,
    /** Its layer group could not be replaced whole. */
    LAYER_GROUP,
    /** A rule would replace it, but the part it belongs to is off. */
    PART_OFF,
    /** No rule replaced it, and Wynncraft ships it fully transparent: a deliberate blank. */
    TRANSPARENT,
    /** No rule replaced it. */
    NO_RULE
}
