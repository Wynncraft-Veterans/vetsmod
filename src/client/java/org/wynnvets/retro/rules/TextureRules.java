package org.wynnvets.retro.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.manifest.TextureSections;
import org.wynnvets.retro.manifest.TintFactor;

/**
 * The retro pack's decisions about textures: whether a texture is replaced, how and from which
 * source. Every answer comes from the {@link TextureSections} plus what the caller says the live
 * packs hold ({@link PackFacts}), so the class is pure: it reads no Minecraft or Wynntils state and
 * keeps nothing beyond the sections. Ids are written in full, {@code minecraft:block/stone}, as in
 * the rule files.
 *
 * <p>A texture is decided by the first of these that applies. Each rule applies whether or not
 * Wynncraft ships the texture, so 2013 art also replaces vanilla art Wynncraft leaves alone.</p>
 * <ol>
 *   <li>{@code keep_wynncraft}: Wynncraft's texture stays.</li>
 *   <li>{@code tints}: the row's source texture is tinted in from the first of its sources that
 *       has it. When none has it, or none of a {@code match} texture's sources has that, or, for
 *       a {@code like}, the row it names could not tint the texture it names, Wynncraft's texture
 *       stays and the tint is a gap ({@link KeepReason#NO_SOURCE}); the rules below are not
 *       tried.</li>
 *   <li>{@code aliases}: the target texture is copied in from the first of its sources that has
 *       it. When none has it, Wynncraft's texture stays and the alias is a gap
 *       ({@link KeepReason#NO_SOURCE}); {@code same_name} is not tried.</li>
 *   <li>{@code same_name}: the texture of the same id is copied in from the first of its sources
 *       that has it. When none has it the rule does not apply, which is how most of Wynncraft's
 *       own textures fall through.</li>
 *   <li>Wynncraft ships the texture fully transparent: it stays, as a deliberate blank. This
 *       ranks below the rules above because Wynncraft blanks the very sprites retro
 *       restores.</li>
 *   <li>Otherwise Wynncraft's texture stays, or the lower packs' when Wynncraft ships none.</li>
 * </ol>
 *
 * <p>A texture's sources are the first {@code sources} row matching the texture that is read:
 * for an alias, its target; for a tint, its source and its {@code match} texture, and for a
 * {@code like}, also those of the row it names. {@link #decideAll} then applies
 * {@code layer_groups}, which only ever turn copies and tints back into keeps.</p>
 */
public final class TextureRules {
    private static final TextureDecision.Keep KEEP_WYNNCRAFT =
            new TextureDecision.Keep(KeepReason.KEEP_WYNNCRAFT);
    private static final TextureDecision.Keep NO_SOURCE =
            new TextureDecision.Keep(KeepReason.NO_SOURCE);
    private static final TextureDecision.Keep LAYER_GROUP =
            new TextureDecision.Keep(KeepReason.LAYER_GROUP);
    private static final TextureDecision.Keep TRANSPARENT =
            new TextureDecision.Keep(KeepReason.TRANSPARENT);
    private static final TextureDecision.Keep NO_RULE =
            new TextureDecision.Keep(KeepReason.NO_RULE);

    private final TextureSections sections;

    /**
     * Decides with these sections' rules.
     *
     * @param sections the parsed sections
     */
    public TextureRules(TextureSections sections) {
        this.sections = sections;
    }

    /**
     * The packs {@code textureId} is read from, most preferred first: the first {@code sources}
     * row that matches it.
     *
     * @param textureId the texture read, which may be {@code null}
     * @return the packs; empty when no row matches, or for {@code null}
     */
    public List<RetroSource> sourceOrder(String textureId) {
        if (textureId == null) return List.of();
        for (TextureSections.SourceRow row : sections.sources()) {
            if (row.textures().matcher(textureId).matches()) return row.order();
        }
        return List.of();
    }

    /**
     * The pack {@code textureId} is read from: the first of its sources that has it.
     *
     * @param textureId the texture
     * @param facts what the live packs hold
     * @return the source, or {@code null} when none of its sources has it
     */
    public RetroSource firstSourceWith(String textureId, PackFacts facts) {
        for (RetroSource source : sourceOrder(textureId)) {
            if (facts.has(source, textureId)) return source;
        }
        return null;
    }

    /**
     * Decides one texture by the rules in the class comment, without its layer group; see
     * {@link #decideAll}.
     *
     * @param textureId the texture, which may be {@code null}
     * @param facts what the live packs hold
     * @return a copy, a tint, or a keep with its reason; {@link KeepReason#NO_RULE} for
     *     {@code null}
     */
    public TextureDecision decide(String textureId, PackFacts facts) {
        if (textureId == null) return NO_RULE;
        if (RowMatches.matchesAny(sections.keepWynncraft(), textureId)) return KEEP_WYNNCRAFT;
        for (TextureSections.Tint tint : sections.tints()) {
            Matcher matcher = tint.textures().matcher(textureId);
            if (!matcher.matches()) continue;
            if (!(tint.factor() instanceof TintFactor.Like like)) {
                return tinted(tint, matcher, textureId, facts);
            }
            String from =
                    tint.source() == null
                            ? textureId
                            : RowMatches.substitute(matcher, tint.source());
            RetroSource source = firstSourceWith(from, facts);
            TextureDecision named = namedTint(like.texture(), facts);
            return source != null && named instanceof TextureDecision.Tint reference
                    ? new TextureDecision.Tint(
                            from,
                            source,
                            like,
                            reference.strength(),
                            reference.contrast(),
                            reference)
                    : NO_SOURCE;
        }
        for (TextureSections.Alias alias : sections.aliases()) {
            Matcher matcher = alias.textures().matcher(textureId);
            if (!matcher.matches()) continue;
            String target = RowMatches.substitute(matcher, alias.target());
            RetroSource source = firstSourceWith(target, facts);
            return source != null ? new TextureDecision.Copy(target, source) : NO_SOURCE;
        }
        if (RowMatches.matchesAny(sections.sameName(), textureId)) {
            RetroSource source = firstSourceWith(textureId, facts);
            if (source != null) return new TextureDecision.Copy(textureId, source);
        }
        if (facts.wynncraftShips(textureId) && facts.wynncraftTransparent(textureId)) {
            return TRANSPARENT;
        }
        return NO_RULE;
    }

    /**
     * The decision {@code tint}, a row that is no like, makes for {@code textureId}, which
     * {@code matcher} has just matched in full: a {@link TextureDecision.Tint}, or a
     * {@link KeepReason#NO_SOURCE} keep when no source has its source or its {@code match}
     * texture.
     */
    private TextureDecision tinted(
            TextureSections.Tint tint, Matcher matcher, String textureId, PackFacts facts) {
        String from =
                tint.source() == null ? textureId : RowMatches.substitute(matcher, tint.source());
        RetroSource source = firstSourceWith(from, facts);
        TintFactor factor = filledIn(matcher, tint.factor());
        boolean matched =
                !(factor instanceof TintFactor.Match match)
                        || firstSourceWith(match.texture(), facts) != null;
        return source != null && matched
                ? new TextureDecision.Tint(from, source, factor, tint.strength(), tint.contrast())
                : NO_SOURCE;
    }

    /**
     * The decision the {@code tints} row matching {@code textureId}, which a like names, makes
     * for it, whatever {@code keep_wynncraft} says of it. A {@link KeepReason#NO_SOURCE} keep
     * when its sources lack what it reads, and when no row matches or that row is a like, which
     * the manifest refuses.
     */
    private TextureDecision namedTint(String textureId, PackFacts facts) {
        for (TextureSections.Tint tint : sections.tints()) {
            Matcher matcher = tint.textures().matcher(textureId);
            if (!matcher.matches()) continue;
            return tint.factor() instanceof TintFactor.Like
                    ? NO_SOURCE
                    : tinted(tint, matcher, textureId, facts);
        }
        return NO_SOURCE;
    }

    /**
     * Decides every texture, then applies {@code layer_groups}. A group is replaced whole or not
     * at all: unless every member Wynncraft ships is copied or tinted, and every such member is
     * read from one source, each copy and tint in the group becomes a
     * {@link KeepReason#LAYER_GROUP} keep. The members are the given textures the group's pattern
     * matches, so {@code textureIds} should hold every texture Wynncraft ships and every texture
     * the sources have.
     *
     * @param textureIds the textures to decide; a {@code null} among them is skipped
     * @param facts what the live packs hold
     * @return each texture's decision, in {@code textureIds}' order; unmodifiable
     */
    public Map<String, TextureDecision> decideAll(Collection<String> textureIds, PackFacts facts) {
        Map<String, TextureDecision> decisions = new LinkedHashMap<>();
        for (String textureId : textureIds) {
            if (textureId != null) decisions.put(textureId, decide(textureId, facts));
        }
        for (Pattern group : sections.layerGroups()) keepUnlessWhole(group, decisions, facts);
        return Collections.unmodifiableMap(decisions);
    }

    /** Turns a decision into a keep while its layer group cannot be replaced whole. */
    private static void keepUnlessWhole(
            Pattern group, Map<String, TextureDecision> decisions, PackFacts facts) {
        List<String> members = new ArrayList<>();
        RetroSource shared = null;
        boolean whole = true;
        for (Map.Entry<String, TextureDecision> entry : decisions.entrySet()) {
            String textureId = entry.getKey();
            if (!group.matcher(textureId).matches()) continue;
            members.add(textureId);
            RetroSource source = sourceOf(entry.getValue());
            if (source != null) {
                if (shared == null) shared = source;
                whole &= source == shared;
            } else if (facts.wynncraftShips(textureId)) {
                // Wynncraft's layer would stay under or over the copied ones.
                whole = false;
            }
        }
        if (whole) return;
        for (String member : members) {
            if (sourceOf(decisions.get(member)) != null) decisions.put(member, LAYER_GROUP);
        }
    }

    /** The pack a decision reads from, or {@code null} for a keep, which reads nothing. */
    private static RetroSource sourceOf(TextureDecision decision) {
        return switch (decision) {
            case TextureDecision.Copy copy -> copy.source();
            case TextureDecision.Tint tint -> tint.source();
            case TextureDecision.Keep keep -> null;
        };
    }

    /** {@code factor} with the groups of {@code matched} filled into its texture id, if any. */
    private static TintFactor filledIn(Matcher matched, TintFactor factor) {
        return switch (factor) {
            case TintFactor.Match match ->
                    new TintFactor.Match(RowMatches.substitute(matched, match.texture()));
            case TintFactor.RelativeTo relative ->
                    new TintFactor.RelativeTo(RowMatches.substitute(matched, relative.texture()));
            case TintFactor.Colour colour -> colour;
            case TintFactor.Auto auto -> auto;
            case TintFactor.Unchanged unchanged -> unchanged;
            // A literal id: the manifest refuses a $n in it.
            case TintFactor.Like like -> like;
        };
    }
}
