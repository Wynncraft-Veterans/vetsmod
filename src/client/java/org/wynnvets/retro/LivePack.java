package org.wynnvets.retro;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * What the retro pack drawn right now holds, for the code that draws over the screen. Every answer
 * comes from {@link RetroModeManager#liveReport()}, so a question asked while no pack is live has
 * the same answer as one about something the generation did not write: nothing is there to draw
 * with.
 */
public final class LivePack {

    private LivePack() {}

    /**
     * Whether the retro pack's copy of Wynncraft's unique tooltip border is drawn right now: the
     * pack is live and its generation wrote both sprites of
     * {@link RetroAssetIds#LEGACY_TOOLTIP_STYLE}. While it is, that style is the gold border;
     * Wynncraft's own {@code minecraft:unique} is vanilla's frame.
     *
     * @return {@code true} while {@link RetroAssetIds#LEGACY_TOOLTIP_STYLE} draws Wynncraft's
     *     unique border
     */
    public static boolean isLegacyTooltipStyle() {
        RetroReport report = RetroModeManager.liveReport();
        return report != null
                && report.wynncraftCopies().containsAll(RetroAssetIds.LEGACY_TOOLTIP_SPRITES);
    }

    /**
     * The retro pack's copy of one of Wynncraft's textures, while the pack is drawn: the
     * {@code vetsmod} texture a {@code wynncraft_copies} row copies it to, when the live
     * generation wrote that copy. It holds the texture as the reload would show it without
     * retro, which a rule may since have replaced.
     *
     * @param textureId Wynncraft's texture, such as {@code minecraft:gui/container/generic_54}
     * @return the copy, such as {@code vetsmod:gui/container/wynncraft/generic_54}; empty while
     *     the pack is not live, or when no row copies the texture or its copy was not written
     */
    public static Optional<String> wynncraftCopy(String textureId) {
        RetroReport report = RetroModeManager.liveReport();
        if (report == null) return Optional.empty();
        Map<String, String> rows = RetroModeManager.manifest().assets().wynncraftCopies();
        for (Map.Entry<String, String> row : rows.entrySet()) {
            if (row.getValue().equals(textureId)
                    && report.wynncraftCopies().contains(row.getKey())) {
                return Optional.of(row.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * The retro pack's copy of vanilla's item definition of one item, while the pack is drawn: the
     * item model id a stack can name ({@code minecraft:item_model}) to be drawn as the vanilla
     * item whatever another pack's definition of it draws, when the live generation copied it
     * ({@code gear_box_icons}).
     *
     * @param itemId the item, such as {@code minecraft:bow}
     * @return its copy's id, such as {@code vetsmod:retro/minecraft/bow}; empty while the pack is
     *     not live, or when the live generation did not copy that item's definition
     */
    public static Optional<String> itemDefinition(String itemId) {
        RetroReport report = RetroModeManager.liveReport();
        if (report == null || !report.copiedItems().contains(itemId)) return Optional.empty();
        return Optional.ofNullable(RetroAssetIds.itemDefinitionCopy(itemId));
    }

    /**
     * Whether the pack drawn right now has an item definition {@code accessory_tiers} or
     * {@code outlined_icons} wrote.
     *
     * @param itemId its id, such as {@code vetsmod:retro/accessory/ring_gold}
     * @return whether a stack may be drawn with it; never while the pack is not live
     */
    public static boolean isGeneratedItem(String itemId) {
        RetroReport report = RetroModeManager.liveReport();
        return report != null && report.generatedItems().contains(itemId);
    }

    /**
     * The rectangles {@code image_edits} painted on one texture of the pack drawn right now: what
     * the code drawing over the screens may take as painted over. A fill its palette guard
     * refused is not among them, so the slots under it still answer.
     *
     * @param textureId the texture, such as {@code minecraft:gui/container/inventory}
     * @return its rectangles, in the rule files' order; none while the pack is not live, or when
     *     its generation painted none on that texture
     */
    public static List<Rect> imageEdits(String textureId) {
        RetroReport report = RetroModeManager.liveReport();
        return report == null ? List.of() : report.imageEdits().getOrDefault(textureId, List.of());
    }
}
