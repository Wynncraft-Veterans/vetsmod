package org.wynnvets.items;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;

/**
 * Rewrites a legacy item's tooltip: the tooltip-side counterpart of
 * {@link LegacyItemHandler#isLegacyItem}, which decides the slot highlight.
 *
 * <h2>The cascade</h2>
 * <p>{@link #processTooltip} tries these branches in order and the first match rewrites the
 * tooltip. The numbers are the {@code Branch N} comments in the method body.</p>
 * <ul>
 *   <li><b>0</b> — highlighting off, an empty tooltip, detection suppressed
 *       ({@link LegacyItemHandler#isDetectionSuppressed}) or a player-crafted item: no
 *       rewrite.</li>
 *   <li><b>1</b> — line 0 is blank (Wynntils' {@code ItemStatInfoFeature} layout) and a stack
 *       is hovered: the name comes from that stack, the item is legacy if
 *       {@link LegacyItemHandler#isLegacyItem} says so, and only the new-format rewrite is
 *       tried. The cascade ends here either way.</li>
 *   <li><b>2</b> — the name is in {@code definitions}.</li>
 *   <li><b>3</b> — the name is in {@code misc_definitions} and the item is a "Misc. Item".</li>
 *   <li><b>4</b> — the name is in {@code no_lore_legacy} and the item has no lore.</li>
 *   <li><b>4b</b> — a pedestal-wiped shell ({@link LegacyItemHandler#isPedestalWipedItem}).</li>
 *   <li><b>5</b> — a gold "Lv. min" lore line: "Alpha Legacy Item" without a rarity line,
 *       "Beta Legacy Item" with one.</li>
 *   <li><b>6</b> — foil ({@link #isEnchanted}): the name gains an "Enchanted" prefix.</li>
 *   <li><b>7</b> — a "Junk Item" whose name is not in {@code notjunk}.</li>
 *   <li><b>8</b> — a "Crafting Item".</li>
 *   <li><b>9</b> — Wynncraft's VINTAGE lore ribbon.</li>
 * </ul>
 *
 * <h2>Old format and new format</h2>
 * <ul>
 *   <li><b>Old format:</b> a plain-text name and a rarity lore line ("Legendary Item"). The
 *       rewrite puts a gold name on line 0 and swaps the rarity line for a gold
 *       "Legacy Item (Legendary)" label ({@link #rewriteOldFormat}).</li>
 *   <li><b>New format:</b> resource-pack fonts and PUA glyphs, with the rarity in the
 *       {@code tooltip_style} component. {@link NewFormatRenderer} recolours the name line gold
 *       and inserts the LEGACY box before the rarity box ({@link #rewriteNewFormat}).</li>
 * </ul>
 * <p>Branches 2, 3, 6 and 9 try the new format first and fall back to the old
 * ({@link #rewriteLegacy}); branch 1 tries only the new format; the others rewrite in the old
 * format only. An enchanted item rewritten by branches 1, 2, 3, 5 or 6 also gets its
 * enchantment named while {@code legacyItemShowEnchantments} is on
 * ({@link LegacyEnchantmentRenderer}).</p>
 */
final class LegacyTooltipRenderer {

    /** Matches the F3+H debug line showing the item's registry id (e.g. "minecraft:paper"). */
    private static final Pattern DEBUG_ID_PATTERN = Pattern.compile("^\\w+:\\w[\\w/.-]*$");

    /** Matches Minecraft's F3+H debug line showing component count (e.g. "12 component(s)"). */
    private static final Pattern DEBUG_COMPONENTS_PATTERN =
            Pattern.compile("^\\d+ component\\(s\\)$");

    /** Matches a Wynntils percentage suffix like " [61.7%]" appended to item names. */
    private static final Pattern PERCENT_SUFFIX_PATTERN =
            Pattern.compile("\\s*(\\[\\d+\\.?\\d*%\\])$");

    /** Matches a player-crafted item's line ("Crafted Helmet [30/30 Durability]"): never legacy. */
    private static final Pattern CRAFTED_PATTERN =
            Pattern.compile(
                    "^Crafted (?:Helmet|Chestplate|Pants|Boots|Ring|Potion|Scroll|Food|Wand|Spear|Relik|Bow|Dagger|by .+) \\[\\d+/\\d+ Durability\\]$");

    private LegacyTooltipRenderer() {}

    /**
     * Returns the tooltip rewritten for a legacy item, or {@code tooltipLines} itself (the same
     * instance) when no branch matches. Sets {@link LegacyItemHandler#lastProcessedWasLegacy}
     * to whether it rewrote. The hovered item is read from
     * {@link LegacyItemHandler#currentItemStack} and {@link LegacyItemHandler#currentItemHasFoil},
     * which the hover hooks set before the tooltip is drawn.
     */
    static List<Component> processTooltip(List<Component> tooltipLines) {
        LegacyItemHandler.lastProcessedWasLegacy = false;

        // Branch 0: no rewrite.
        if (!VetsConfig.get(VetsConfigKeys.LEGACY_ITEM_HIGHLIGHTING)) return tooltipLines;
        if (tooltipLines.isEmpty()) return tooltipLines;
        if (LegacyItemHandler.isDetectionSuppressed()) return tooltipLines;
        if (isCraftedItem(tooltipLines)) return tooltipLines;

        ItemStack stack = LegacyItemHandler.currentItemStack;
        Component firstLine = tooltipLines.get(0);
        String plainText =
                LegacyItemHandler.normalizeName(
                        ChatFormatting.stripFormatting(firstLine.getString()));
        // Wynntils' percentage suffix ("[61.7%]") is kept for the rewritten name but not matched.
        Component raritySuffix = extractColoredSuffix(firstLine, plainText);
        if (plainText != null) plainText = stripPercentSuffix(plainText);

        // A new_format_override name also belongs to a modern item, so on a new-format stack
        // its definitions / misc_definitions match (branches 2 and 3) does not count.
        boolean newFormatOverridden =
                !stack.isEmpty()
                        && LegacyItemHandler.isNewFormatItem(stack)
                        && plainText != null
                        && ItemDefinitions.isNewFormatOverride(plainText);

        // Branch 1: Wynntils' ItemStatInfoFeature rebuilds identified gear with a blank spacer
        // as line 0, so the name is taken from the hovered stack instead.
        if ((plainText == null || plainText.isBlank()) && !stack.isEmpty()) {
            String hoverName =
                    LegacyItemHandler.normalizeName(
                            ChatFormatting.stripFormatting(stack.getHoverName().getString()));
            if (hoverName != null) hoverName = stripPercentSuffix(hoverName);
            if (hoverName != null && !hoverName.isBlank()) plainText = hoverName;
            List<Component> modified = new ArrayList<>(tooltipLines);
            boolean newFormat = NewFormatRenderer.isNewFormatItem(modified);
            boolean legacy = LegacyItemHandler.isLegacyItem(stack);
            if (newFormat && legacy && rewriteNewFormat(modified, plainText)) {
                return markLegacy(modified);
            }
            return tooltipLines;
        }

        // Branch 2: definitions.
        if (!newFormatOverridden && plainText != null && ItemDefinitions.isLegacy(plainText)) {
            return rewriteLegacy(tooltipLines, plainText, raritySuffix);
        }

        // Branch 3: misc_definitions, only on a Misc. Item (modern items share these names).
        if (!newFormatOverridden
                && plainText != null
                && ItemDefinitions.isMiscLegacy(plainText)
                && LegacyItemHandler.hasMiscRarity(tooltipLines)) {
            return rewriteLegacy(tooltipLines, plainText, raritySuffix);
        }

        // Branch 4: no_lore_legacy, only with no lore. The rarity comes from tooltip_style,
        // else from the custom name's colour code.
        if (plainText != null
                && !plainText.isBlank()
                && ItemDefinitions.isNoLoreLegacy(plainText)
                && stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().isEmpty()) {
            String rarity = LegacyItemHandler.getTooltipStyleRarity(stack);
            if (rarity == null) rarity = LegacyItemHandler.getRarityFromNameColor(stack);
            String label = rarity != null ? "Legacy Item (" + rarity + ")" : "Legacy Item";
            return rewriteWithLabel(tooltipLines, plainText, raritySuffix, label);
        }

        // Branch 4b: a pedestal-wiped shell.
        if (LegacyItemHandler.isPedestalWipedItem(stack)) {
            return rewriteWithLabel(
                    tooltipLines, plainText, raritySuffix, "Legacy Item (Pedestal-Wiped)");
        }

        // Branch 5: a gold "Lv. min" line. Alpha items predate rarity lines; beta items have one.
        if (LegacyItemHandler.hasBetaLegacyMarker(tooltipLines)) {
            boolean alpha = !LegacyItemHandler.hasRarityLine(tooltipLines);
            return rewriteOldFormat(
                    new ArrayList<>(tooltipLines),
                    plainText,
                    raritySuffix,
                    alpha ? "Alpha" : "Beta",
                    isEnchanted(plainText));
        }

        // Branch 6: foil.
        if (isEnchanted(plainText)) {
            return rewriteLegacy(tooltipLines, plainText, raritySuffix);
        }

        // Branch 7: a Junk Item, unless the name is in notjunk.
        if (LegacyItemHandler.hasJunkRarity(tooltipLines)
                && plainText != null
                && !ItemDefinitions.isNotJunk(plainText)) {
            return rewriteOldFormat(
                    new ArrayList<>(tooltipLines), plainText, raritySuffix, null, false);
        }

        // Branch 8: a Crafting Item.
        if (LegacyItemHandler.hasCraftingRarity(tooltipLines) && plainText != null) {
            return rewriteOldFormat(
                    new ArrayList<>(tooltipLines), plainText, raritySuffix, null, false);
        }

        // Branch 9: the VINTAGE ribbon, for an item no name list covers.
        if (LegacyItemHandler.hasVintageBanner(tooltipLines) && plainText != null) {
            return rewriteLegacy(tooltipLines, plainText, raritySuffix);
        }

        return tooltipLines;
    }

    /** Sets {@link LegacyItemHandler#lastProcessedWasLegacy} and returns {@code modified}. */
    private static List<Component> markLegacy(List<Component> modified) {
        LegacyItemHandler.lastProcessedWasLegacy = true;
        return modified;
    }

    /**
     * Returns {@code true} if the hovered item counts as enchanted: it has foil, its item id is
     * not in {@code enchant_excluded_items} (both folded into
     * {@link LegacyItemHandler#currentItemHasFoil}), and its name is not unenchanted
     * ({@link ItemDefinitions#isUnenchanted}; a {@code definitions} name never is). Modern items
     * that also carry foil are what the two lists are for.
     */
    private static boolean isEnchanted(String plainText) {
        return LegacyItemHandler.currentItemHasFoil
                && plainText != null
                && !ItemDefinitions.isUnenchanted(plainText);
    }

    /**
     * The rewrite of branches 2, 3, 6 and 9: the new-format rewrite on a new-format tooltip,
     * else (or if the LEGACY box could not be placed) the old-format one.
     */
    private static List<Component> rewriteLegacy(
            List<Component> tooltipLines, String plainText, Component raritySuffix) {
        List<Component> modified = new ArrayList<>(tooltipLines);
        if (NewFormatRenderer.isNewFormatItem(modified) && rewriteNewFormat(modified, plainText)) {
            return markLegacy(modified);
        }
        return rewriteOldFormat(modified, plainText, raritySuffix, null, isEnchanted(plainText));
    }

    /**
     * Rewrites a new-format tooltip in place: the name line goes gold (with an "Enchanted"
     * prefix for an enchanted item), the LEGACY box goes before the rarity box, and an enchanted
     * item gets its enchantment named.
     *
     * <p>The target lines are found by font, not by index: Wynntils' layout keeps a custom-name
     * placeholder at line 0 and the name line at line 1, while other tooltip processors (such
     * as wynnmod's {@code DecorateTooltipFeature}) put the name line at line 0.</p>
     *
     * @return {@code true} if the LEGACY box was inserted
     */
    private static boolean rewriteNewFormat(List<Component> modified, String plainText) {
        boolean enchanted = isEnchanted(plainText);
        if (plainText != null) {
            if (enchanted) {
                NewFormatRenderer.applyEnchantedToNewFormatNameLine(modified, plainText);
            } else {
                NewFormatRenderer.recolorNewFormatNameLine(modified, plainText);
            }
        }
        if (!NewFormatRenderer.insertLegacyBoxLine(modified)) return false;
        if (enchanted) appendEnchantmentBlock(modified);
        return true;
    }

    /**
     * Rewrites an old-format tooltip in place and returns it marked legacy: a gold name on
     * line 0, the rarity line swapped for a gold label ({@link #replaceRarityLines}), and, for
     * an enchanted item, the enchantment named.
     *
     * @param prefix the label's era prefix ("Alpha" or "Beta"), or {@code null}
     */
    private static List<Component> rewriteOldFormat(
            List<Component> modified,
            String plainText,
            Component raritySuffix,
            String prefix,
            boolean enchanted) {
        if (plainText != null) modified.set(0, buildGoldName(plainText, enchanted, raritySuffix));
        replaceRarityLines(modified, prefix);
        if (enchanted) appendEnchantmentBlock(modified);
        return markLegacy(modified);
    }

    /**
     * Copies the tooltip, puts a gold name on line 0 and adds a gold {@code label} line at the
     * end of its content, and returns the copy marked legacy. For items with no rarity line.
     */
    private static List<Component> rewriteWithLabel(
            List<Component> tooltipLines, String plainText, Component raritySuffix, String label) {
        List<Component> modified = new ArrayList<>(tooltipLines);
        modified.set(0, buildGoldName(plainText, false, raritySuffix));
        modified.add(
                debugLinesStart(modified), Component.literal(label).withStyle(ChatFormatting.GOLD));
        return markLegacy(modified);
    }

    /**
     * Names the item's enchantment, unless {@code legacyItemShowEnchantments} is off; see
     * {@link LegacyEnchantmentRenderer#showEnchantment}. Called only once a branch has decided
     * the item is legacy and {@link #isEnchanted}, so the {@code unenchanted} and
     * {@code enchant_excluded_items} lists govern this display too.
     */
    private static void appendEnchantmentBlock(List<Component> modified) {
        if (!VetsConfig.get(VetsConfigKeys.LEGACY_ITEM_SHOW_ENCHANTMENTS)) return;
        LegacyEnchantmentRenderer.showEnchantment(modified, LegacyItemHandler.currentItemStack);
    }

    /**
     * Builds the gold name for an old-format rewrite, prefixed with a white ⬡ and a gold
     * "Enchanted " when {@code enchanted}.
     */
    private static MutableComponent buildGoldName(
            String plainText, boolean enchanted, Component raritySuffix) {
        MutableComponent name;
        if (enchanted) {
            name =
                    Component.literal("\u2B21 ")
                            .withStyle(ChatFormatting.WHITE)
                            .append(
                                    Component.literal("Enchanted " + plainText)
                                            .withStyle(ChatFormatting.GOLD));
        } else {
            name = Component.literal(plainText).withStyle(ChatFormatting.GOLD);
        }
        if (raritySuffix != null) name.append(raritySuffix);
        return name;
    }

    /**
     * Returns {@code true} if the tooltip belongs to a player-crafted item.
     * Crafted items are never legacy and are skipped before any detection logic.
     */
    private static boolean isCraftedItem(List<Component> lines) {
        for (Component line : lines) {
            String plain = ChatFormatting.stripFormatting(line.getString());
            if (plain != null && CRAFTED_PATTERN.matcher(plain).matches()) return true;
        }
        return false;
    }

    /**
     * Finds and replaces the standard rarity line (e.g. "Legendary Item") with
     * a gold "Legacy Item (Legendary)" label.  For old-format items this scans
     * lore bottom-up; for new-format items it falls back to the tooltip_style
     * data component.  If neither source yields a rarity, a plain "Legacy Item"
     * label is used.
     *
     * @param lines  the mutable tooltip lines (rarity line is removed in-place)
     * @param prefix optional prefix like "Alpha" or "Beta" prepended to the label
     */
    private static void replaceRarityLines(List<Component> lines, String prefix) {
        if (lines.isEmpty()) return;

        Component legacyLabel = null;

        // Scan bottom-up to find and remove the rarity line
        for (int i = lines.size() - 1; i >= 1; i--) {
            String plain = ChatFormatting.stripFormatting(lines.get(i).getString());
            if (plain == null) continue;

            Matcher m = LegacyItemHandler.RARITY_PATTERN.matcher(plain);
            if (m.matches()) {
                legacyLabel = buildLegacyLabel(m.group(1), m.group(2), prefix);
                lines.remove(i);
                break;
            }
        }

        // New-format items encode rarity in the tooltip_style data component instead
        // of a plain-text lore line. Fall back to that when no lore rarity was found.
        if (legacyLabel == null && !LegacyItemHandler.currentItemStack.isEmpty()) {
            String rarity =
                    LegacyItemHandler.getTooltipStyleRarity(LegacyItemHandler.currentItemStack);
            if (rarity != null) {
                legacyLabel = buildLegacyLabel(rarity, null, prefix);
            }
        }

        if (legacyLabel == null) {
            String label = prefix != null ? prefix + " Legacy Item" : "Legacy Item";
            legacyLabel = Component.literal(label).withStyle(ChatFormatting.GOLD);
        }

        // Insert before any F3+H debug lines (resource id / component count)
        lines.add(debugLinesStart(lines), legacyLabel);
    }

    /**
     * Returns the index at which to append to a tooltip's content — the end of
     * the list, but before any F3+H advanced tooltip debug lines (resource ID and
     * component count).  If no debug lines are present, returns the end of the
     * list.
     */
    static int debugLinesStart(List<Component> lines) {
        for (int i = lines.size() - 1; i >= 1; i--) {
            String plain = ChatFormatting.stripFormatting(lines.get(i).getString());
            if (plain == null) continue;
            if (!DEBUG_ID_PATTERN.matcher(plain).matches()
                    && !DEBUG_COMPONENTS_PATTERN.matcher(plain).matches()) {
                return i + 1;
            }
        }
        return 1;
    }

    private static String stripPercentSuffix(String plainText) {
        return PERCENT_SUFFIX_PATTERN.matcher(plainText).replaceFirst("");
    }

    /**
     * Extracts a trailing Wynntils percentage suffix (e.g. "[61.7%]") from the original
     * component, preserving its color. Wynntils appends the percentage as a sibling
     * Component with its own Style/{@code TextColor}, so we check siblings first.
     * Falls back to scanning for §-codes in legacy-formatted text.
     * Returns null if no colored suffix is present.
     */
    private static Component extractColoredSuffix(Component original, String plainText) {
        if (plainText == null) return null;
        Matcher m = PERCENT_SUFFIX_PATTERN.matcher(plainText);
        if (!m.find()) return null;

        String suffix = m.group(1);

        // Wynntils adds the percentage as a sibling Component with its own Style.
        // Walk siblings last-to-first to find it.
        List<Component> siblings = original.getSiblings();
        for (int i = siblings.size() - 1; i >= 0; i--) {
            Component sibling = siblings.get(i);
            if (sibling.getString().contains(suffix)) {
                return sibling.copy();
            }
        }

        // Fallback: scan raw text for §-codes (legacy formatting)
        String raw = original.getString();
        int suffixStart = raw.lastIndexOf(suffix);
        if (suffixStart < 0) return null;

        for (int i = suffixStart - 1; i >= 1; i--) {
            if (raw.charAt(i - 1) == '\u00A7') {
                ChatFormatting fmt = ChatFormatting.getByCode(raw.charAt(i));
                if (fmt != null && fmt.isColor()) {
                    return Component.literal(" " + suffix).withStyle(fmt);
                }
            }
        }

        return null;
    }

    /**
     * Builds the gold "Legacy Item (Rarity)" or "Beta Legacy Item (Rarity [3])" label.
     *
     * @param rarity the rarity name (e.g. "Legendary", "Normal")
     * @param count  optional Wynntils count suffix like "[3]", or null
     * @param prefix optional era prefix like "Alpha" or "Beta", or null
     */
    private static Component buildLegacyLabel(String rarity, String count, String prefix) {
        String base = prefix != null ? prefix + " Legacy Item" : "Legacy Item";
        String label =
                count != null
                        ? base + " (" + rarity + " " + count + ")"
                        : base + " (" + rarity + ")";
        return Component.literal(label).withStyle(ChatFormatting.GOLD);
    }
}
