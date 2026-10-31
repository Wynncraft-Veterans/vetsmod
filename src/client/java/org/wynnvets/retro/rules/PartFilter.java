package org.wynnvets.retro.rules;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.wynnvets.retro.manifest.PartSections;
import org.wynnvets.retro.manifest.RetroPart;

/**
 * The retro pack's decisions about its {@linkplain RetroPart parts}: whether a model, a Wynntils
 * setting or a texture is left as if retro were not installed, given the parts that are on. The
 * first {@code parts} row in file order whose pattern matches an id owns it, and an id no row owns
 * cannot be turned off. Every answer comes from the {@link PartSections} and the set it was made
 * with, so the class is pure. {@link TextureRules} and {@link ModelRules} do not know of it, so the
 * report and the item dump still read what the rule files say.
 */
public final class PartFilter {

    private final PartSections sections;
    private final Set<RetroPart> on;
    private final boolean allOn;

    /**
     * Decides with these rows and these parts on.
     *
     * @param sections the parsed section
     * @param on the parts that are on
     */
    public PartFilter(PartSections sections, Set<RetroPart> on) {
        this.sections = sections;
        EnumSet<RetroPart> parts = EnumSet.noneOf(RetroPart.class);
        parts.addAll(on);
        this.on = Collections.unmodifiableSet(parts);
        this.allOn = parts.size() == RetroPart.all().size();
    }

    /**
     * Whether a part is on.
     *
     * @param part the part
     * @return whether it is
     */
    public boolean on(RetroPart part) {
        return on.contains(part);
    }

    /**
     * Whether every part is on, when nothing here changes what the rule files decide.
     *
     * @return whether it is
     */
    public boolean allOn() {
        return allOn;
    }

    /**
     * Whether the stub of a model, or a block model's, may be written.
     *
     * @param modelId the model
     * @return whether the part that owns it is on, or no part owns it
     */
    public boolean modelOn(String modelId) {
        if (allOn) return true;
        RetroPart owner = owner(PartSections.Part::models, modelId);
        return owner == null || on(owner);
    }

    /**
     * Whether retro switches the {@code wynntils_config} settings of a feature.
     *
     * @param feature the feature's short name
     * @return whether the part that owns it is on, or no part owns it
     */
    public boolean settingOn(String feature) {
        if (allOn) return true;
        for (PartSections.Part row : sections.parts().values()) {
            if (row.wynntils().contains(feature)) return on(row.part());
        }
        return true;
    }

    /**
     * Whether a texture no model shares has its replacement written.
     *
     * @param textureId the texture
     * @return whether the part that owns it is on, or no part owns it
     */
    public boolean textureOn(String textureId) {
        return textureOn(textureId, Set.of(), Set.of());
    }

    /**
     * Whether a texture's replacement is written. A texture owned by {@link RetroPart#ITEMS} is
     * shared art: Wynncraft's own models draw vanilla's items, so replacing one because
     * {@code items} is on would redraw what an off part's models show. A texture owned by
     * {@link RetroPart#BLOCKS} is the world, which no other part changes: Wynncraft's item models
     * draw vanilla's stone and dirt too, and those icons show the world's art.
     *
     * @param textureId the texture
     * @param drawnOn the textures that what the on parts draw names, which are replaced wherever
     *     else they are drawn
     * @param drawnOff the textures that what the off parts leave as Wynncraft's names
     * @return whether {@code blocks} is on, for a texture it owns; otherwise {@code true} when
     *     {@code drawnOn} has it, and failing that whether the part that owns it is on, or no part
     *     owns it, unless that part is {@code items} and {@code drawnOff} has it
     */
    public boolean textureOn(String textureId, Set<String> drawnOn, Set<String> drawnOff) {
        if (allOn) return true;
        RetroPart owner = owner(PartSections.Part::textures, textureId);
        if (owner == RetroPart.BLOCKS) return on(owner);
        if (owner == null || drawnOn.contains(textureId)) return true;
        return on(owner) && !(owner == RetroPart.ITEMS && drawnOff.contains(textureId));
    }

    private RetroPart owner(Function<PartSections.Part, List<Pattern>> patterns, String id) {
        for (PartSections.Part row : sections.parts().values()) {
            if (RowMatches.matchesAny(patterns.apply(row), id)) return row.part();
        }
        return null;
    }
}
