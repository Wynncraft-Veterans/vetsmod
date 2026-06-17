package org.wynnvets.mwe.anni.mode;

import org.wynnvets.config.VetsConfig;

/**
 * The three audience-centric anni modes a vetsmod user can run in.
 *
 * <ul>
 *   <li>{@link #SILENT} — strict no-op. Boss-bar pipeline disengaged,
 *       outline/waypoint hooks inert. This is the only mode allowed
 *       while {@code /stream} is active (spec §3.1, "ONLY mode allowed
 *       when the vanilla /stream is enabled").</li>
 *   <li>{@link #PASSIVE} — synthetic boss bar driven by the snapshot;
 *       no chat alerts, no zone overlays. The "most users will use
 *       this" mode per spec §3.1.</li>
 *   <li>{@link #AGGRESSIVE} — everything in PASSIVE plus zone lines,
 *       scroll-spot waypoint, ghosts prompt, chat alerts on material
 *       changes (S5; the consumers gate themselves off this enum
 *       value).</li>
 * </ul>
 *
 * <p>Persisted as a lowercase string in
 * {@link VetsConfig#VETS_ANNI_MODE}; the canonical converter pair is
 * {@link #fromConfig()} / {@link #toConfigValue()}. The string side
 * is the source of truth (S2's command placeholders already wrote it),
 * so this enum is read-only sugar over the config — never store the
 * enum itself.</p>
 */
public enum AnniMode {
    SILENT,
    PASSIVE,
    AGGRESSIVE;

    /** Read the current mode from {@link VetsConfig#VETS_ANNI_MODE}.
     *  Falls back to {@link #SILENT} if the config slot is empty or
     *  holds an unknown value (defensive — the {@code /wv anni …}
     *  command path validates, but a hand-edited config.json could
     *  still land an unknown string). */
    public static AnniMode fromConfig() {
        return fromString(VetsConfig.getString(VetsConfig.VETS_ANNI_MODE));
    }

    /** Convert a config-shaped string back into the enum. {@code null}
     *  or unknown → {@link #SILENT}. Case-insensitive. */
    public static AnniMode fromString(String value) {
        if (value == null) return SILENT;
        switch (value.toLowerCase()) {
            case "passive":    return PASSIVE;
            case "aggressive": return AGGRESSIVE;
            case "silent":
            default:           return SILENT;
        }
    }

    /** The lowercase string this enum persists as in
     *  {@link VetsConfig#VETS_ANNI_MODE}. Stable wire shape — do not
     *  rename without coordinating with the config validator and the
     *  {@code /wv anni <mode>} command parser. */
    public String toConfigValue() {
        return name().toLowerCase();
    }
}
