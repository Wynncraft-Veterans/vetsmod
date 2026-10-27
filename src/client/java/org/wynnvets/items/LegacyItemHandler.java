package org.wynnvets.items;

import com.wynntils.core.components.Models;
import com.wynntils.utils.mc.McUtils;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.logging.VetsLogger;

/**
 * Legacy-item detection, and the state the legacy-item hooks share.
 *
 * <p>{@link #isLegacyItem} decides the slot highlight ({@link LegacyHighlightPainter}) and
 * {@link #processTooltip} rewrites the tooltip ({@link LegacyTooltipRenderer}). The name lists
 * both consult come from {@code definitions.yml} through {@link ItemDefinitions}; the lore,
 * foil and item-id checks are the static predicates here.</p>
 *
 * <p>The hover hooks
 * ({@link org.wynnvets.mixin.client.legacy.LegacyHighlightMixin LegacyHighlightMixin},
 * {@link org.wynnvets.listeners.LegacyTooltipEventListener LegacyTooltipEventListener}) set
 * the hovered item before its tooltip is drawn; the tooltip rewrite reads it and sets
 * {@link #lastProcessedWasLegacy} for
 * {@link org.wynnvets.mixin.client.legacy.LegacyItemTooltipMixin LegacyItemTooltipMixin}.</p>
 */
public final class LegacyItemHandler {

    /** Whether {@link #currentItemStack} has foil that counts (see {@link #setHoveredItem}). */
    public static boolean currentItemHasFoil = false;

    /** The hovered item whose tooltip is next drawn, or {@link ItemStack#EMPTY}. */
    public static ItemStack currentItemStack = ItemStack.EMPTY;

    /** The hovered container slot, for the item-dump tool; set by the highlight mixin. */
    public static Slot currentHoveredSlot = null;

    /**
     * Whether the last {@link #processTooltip} call rewrote the tooltip. The tooltip mixin
     * reads it to pick the gold border and to decide on the legacy screenshot.
     */
    public static boolean lastProcessedWasLegacy = false;

    // BEGIN PATCH(old-server-compat): Remove field once all servers use new tooltip borders.
    /**
     * Sticky server-session flag: set to {@code true} when any item with a
     * {@code tooltip_style} data component is drawn in a container slot, or when
     * {@link NewFormatRenderer#isNewFormatItem} finds a new-format font
     * ({@code tooltip/emblem/frame} or {@code banner/box}) in a tooltip.  When
     * {@code false} (i.e. on old servers that lack the new resource pack), the
     * gold tooltip border override is suppressed to avoid garish fallback
     * colours.
     * <p>Reset on disconnect by {@link org.wynnvets.listeners.ServerConnectionListener}.</p>
     */
    public static boolean newTooltipStylesAvailable = false;

    // END PATCH(old-server-compat)

    /**
     * Gold tooltip border identifier — Wynncraft's resource-pack
     * {@code minecraft:unique} tooltip style (vanilla ships only the default
     * tooltip sprites).
     */
    public static final Identifier LEGACY_BORDER = Identifier.parse("unique");

    /** The font Wynncraft draws an item's lore ribbon (such as VINTAGE) in. */
    private static final FontDescription BANNER_RIBBON_FONT =
            new FontDescription.Resource(Identifier.parse("banner/ribbon"));

    private LegacyItemHandler() {}

    /**
     * Records {@code stack} as the hovered item: sets {@link #currentItemStack}, and
     * {@link #currentItemHasFoil} to whether it has foil and is not an
     * {@code enchant_excluded_items} item.
     */
    public static void setHoveredItem(ItemStack stack) {
        currentItemStack = stack;
        currentItemHasFoil = stack.hasFoil() && !ItemDefinitions.isEnchantExcludedItem(stack);
    }

    /**
     * Returns {@code true} if the current screen title matches a known menu that
     * should never be treated as containing legacy items, or if the player is in
     * housing edit mode (where items are furniture, not real inventory items).
     * The blocked-screen patterns themselves live in
     * {@code definitions.yml > blocked_screen_titles}.
     */
    public static boolean isBlockedScreen() {
        if (Models.Housing.isInEditMode()) return true;
        Screen screen = McUtils.screen();
        if (screen == null) return false;
        String title = ChatFormatting.stripFormatting(screen.getTitle().getString());
        return ItemDefinitions.isBlockedScreenTitle(title);
    }

    /** How long a scan of the player's inventory for a suppressor item is trusted. */
    private static final long SUPPRESSOR_SCAN_INTERVAL_MS = 250;

    private static long lastSuppressorScanMs = 0;
    private static boolean inventoryHoldsSuppressor = false;

    /**
     * Returns {@code true} if legacy detection and tooltip rewriting must bail out for
     * every item: a blocked screen ({@link #isBlockedScreen}), or an item from
     * {@code definitions.yml > inventory_suppressors} (the Block Bank) in the player's
     * inventory.
     *
     * <p>The Block Bank is carried while in housing edit mode, where nothing can be
     * legacy. It is the more reliable signal than {@code Models.Housing.isInEditMode()},
     * which Wynntils sets from a chat line and so misses an edit mode entered without
     * one. The inventory is rescanned at most every {@value #SUPPRESSOR_SCAN_INTERVAL_MS}
     * ms because this is asked for every slot of every frame; the scan needs live
     * {@code ItemStack}s and cannot run under test, the name match it ends in
     * ({@link ItemDefinitions#isInventorySuppressor}) is tested.</p>
     */
    public static boolean isDetectionSuppressed() {
        if (isBlockedScreen()) return true;
        long now = System.currentTimeMillis();
        if (now - lastSuppressorScanMs >= SUPPRESSOR_SCAN_INTERVAL_MS
                || now < lastSuppressorScanMs) {
            lastSuppressorScanMs = now;
            inventoryHoldsSuppressor = scanInventoryForSuppressor();
        }
        return inventoryHoldsSuppressor;
    }

    private static boolean scanInventoryForSuppressor() {
        LocalPlayer player = McUtils.player();
        if (player == null) return false;
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack held = inventory.getItem(i);
            if (held.isEmpty()) continue;
            String name =
                    normalizeName(ChatFormatting.stripFormatting(held.getHoverName().getString()));
            if (name != null && ItemDefinitions.isInventorySuppressor(name)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the item uses Wynncraft's new display format,
     * identified by any supplementary Unicode code point (U+10000+) in the
     * custom name component.  Used with {@link ItemDefinitions#isNewFormatOverride}
     * to suppress the {@code definitions} and {@code misc_definitions} name matches
     * for specific items that exist in both old and new formats; the
     * {@code no_lore_legacy} match still applies.
     */
    public static boolean isNewFormatItem(ItemStack stack) {
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        if (customName == null) return false;
        String raw = customName.getString();
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            if (cp >= 0x10000) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code stack} is a new-format player-crafted item, one with the
     * {@code minecraft:crafted} {@code tooltip_style}. Such an item is never legacy: its crafter
     * chooses its name, so a name list can match it by accident. An old-format crafted item
     * would be legacy, so none is excluded here (none is known to exist).
     */
    public static boolean isCraftedItem(ItemStack stack) {
        return "Crafted".equals(getTooltipStyleRarity(stack));
    }

    /**
     * Returns {@code true} if {@code stack} is a legacy item: never for an empty stack or a
     * player-crafted one ({@link #isCraftedItem}), while {@code legacyItemHighlighting} is off
     * or while {@link #isDetectionSuppressed}, and otherwise when any of the nine numbered
     * checks in the body matches.
     *
     * <p>A new-format stack whose name is in {@code new_format_override} skips checks 1 and 2,
     * since that name also belongs to a modern item.</p>
     */
    public static boolean isLegacyItem(ItemStack stack) {
        if (!VetsConfig.get(VetsConfigKeys.LEGACY_ITEM_HIGHLIGHTING)) return false;
        if (stack.isEmpty()) return false;
        if (isDetectionSuppressed()) return false;
        if (isCraftedItem(stack)) return false;

        String name =
                normalizeName(ChatFormatting.stripFormatting(stack.getHoverName().getString()));
        boolean newFormatOverridden =
                name != null && isNewFormatItem(stack) && ItemDefinitions.isNewFormatOverride(name);

        // 1. definitions
        if (!newFormatOverridden && name != null && ItemDefinitions.isLegacy(name)) return true;

        List<Component> lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines();

        // 2. misc_definitions, only on a Misc. Item (modern items share these names)
        if (!newFormatOverridden
                && name != null
                && ItemDefinitions.isMiscLegacy(name)
                && hasMiscRarity(lore)) return true;

        // 3. no_lore_legacy, only with no lore
        if (lore.isEmpty()
                && name != null
                && !name.isBlank()
                && ItemDefinitions.isNoLoreLegacy(name)) return true;

        // 4. A pedestal-wiped shell
        if (isPedestalWipedItem(stack)) return true;

        // 5. A gold "Lv. min" line (alpha and beta items)
        if (hasBetaLegacyMarker(lore)) return true;

        // 6. Foil, unless the item id is in enchant_excluded_items or the name in unenchanted
        if (stack.hasFoil()
                && !ItemDefinitions.isEnchantExcludedItem(stack)
                && (name == null || !ItemDefinitions.isUnenchanted(name))) return true;

        // 7. A Junk Item, unless the name is in notjunk
        if (hasJunkRarity(lore) && (name == null || !ItemDefinitions.isNotJunk(name))) return true;

        // 8. A Crafting Item, a rarity modern Wynncraft no longer uses
        if (hasCraftingRarity(lore)) return true;

        // 9. Wynncraft's VINTAGE ribbon, for an item no name list covers
        if (hasVintageBanner(lore)) return true;

        return false;
    }

    /**
     * Normalizes item names for pattern matching by stripping:
     * <ul>
     *   <li>Every supplementary code point (U+10000 and above) and BMP Private Use
     *       Area glyph (U+E000–U+F8FF), which Wynncraft's new item format uses for
     *       invisible spacing, font switching and inline sprites; if any is
     *       removed, the result is also trimmed (see {@link #stripSupplementaryPua})</li>
     *   <li>Trailing À (U+00C0) appended in trade market listings</li>
     * </ul>
     */
    public static String normalizeName(String name) {
        if (name == null) return null;
        String result = stripSupplementaryPua(name);
        if (result.endsWith("\u00C0")) {
            result = result.substring(0, result.length() - 1).stripTrailing();
        }
        return result;
    }

    /**
     * Strips all supplementary Unicode code points (U+10000 and above) and
     * BMP Private Use Area characters (U+E000-U+F8FF) from text.
     * Wynncraft's new item format wraps item names with invisible spacing and
     * font-switching glyphs from various supplementary planes (observed at
     * U+CF000 and in Supplementary PUA-A/B), and uses BMP PUA glyphs for
     * inline sprites (e.g. U+E008 lock icon on unidentified items).
     * Since item display names only contain standard BMP characters, all
     * PUA code points are removed before name pattern matching.
     */
    private static String stripSupplementaryPua(String text) {
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            if (cp >= 0x10000 || (cp >= 0xE000 && cp <= 0xF8FF)) {
                if (sb == null) {
                    sb = new StringBuilder(text.length());
                    sb.append(text, 0, i);
                }
            } else if (sb != null) {
                sb.appendCodePoint(cp);
            }
            i += charCount;
        }
        return sb != null ? sb.toString().strip() : text;
    }

    /**
     * Derives a rarity name from the section-sign colour code at the start of
     * the item's custom name (e.g. {@code §b} → "Legendary").
     * Used by the no-lore-legacy tooltip branch to infer a rarity tier for
     * items that lack both a lore rarity line and a {@code tooltip_style}
     * data component.
     * Returns {@code null} when the custom name is absent or starts with an
     * unmapped colour code.
     */
    public static String getRarityFromNameColor(ItemStack stack) {
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        if (customName == null) return null;
        String raw = customName.getString();
        if (raw.length() >= 2 && raw.charAt(0) == '\u00A7') {
            return switch (Character.toLowerCase(raw.charAt(1))) {
                case 'f' -> "Normal";
                case 'e' -> "Unique";
                case 'd' -> "Rare";
                case 'b' -> "Legendary";
                case 'c' -> "Fabled";
                case '5' -> "Mythic";
                case 'a' -> "Set";
                case '3' -> "Crafted";
                default -> null;
            };
        }
        return null;
    }

    /** A standard rarity line ("Legendary Item"): group 1 the tier, group 2 a "[N]" count. */
    static final Pattern RARITY_PATTERN =
            Pattern.compile(
                    "^(Mythic|Fabled|Set|Legendary|Rare|Unique|Normal|Junk|Misc\\.|Crafting) [Ii]tem(?: (\\[\\d+\\]))?$");

    /**
     * Returns the tooltip rewritten for a legacy item, or {@code tooltipLines} itself when it
     * is not one; see {@link LegacyTooltipRenderer#processTooltip}.
     */
    public static List<Component> processTooltip(List<Component> tooltipLines) {
        return LegacyTooltipRenderer.processTooltip(tooltipLines);
    }

    /**
     * Copies a screenshot of {@code tooltip}, as rewritten, to the clipboard over Wynntils'
     * screenshot of the original, when the last processed tooltip was legacy and a press of
     * Wynntils' screenshot keybind is latched ({@link #notifyScreenshotKeyPressed}). The latch
     * is consumed either way; see {@link LegacyScreenshotHandler}.
     */
    public static void screenshotIfRequested(Font font, List<Component> tooltip) {
        boolean requested = LegacyScreenshotHandler.isScreenshotRequested();
        if (requested) {
            VetsLogger.info(
                    "Screenshot key detected — lastProcessedWasLegacy={}, tooltipSize={}",
                    lastProcessedWasLegacy,
                    tooltip.size());
        }
        if (lastProcessedWasLegacy && requested) {
            LegacyScreenshotHandler.takeScreenshot(font, tooltip);
        }
    }

    /**
     * Called by {@link org.wynnvets.listeners.LegacyTooltipEventListener}
     * when the Wynntils screenshot keybind is pressed over a legacy item in an inventory.
     */
    public static void notifyScreenshotKeyPressed() {
        LegacyScreenshotHandler.notifyKeyPressed();
    }

    /**
     * Returns {@code true} for vanilla armour/weapon shells that have been wiped
     * clean at the item pedestal: lore is empty, the custom name carries a
     * §a/§b/§d/§e colour code prefix, and the underlying item type is a vanilla
     * armour piece ({@code *_helmet}/{@code *_chestplate}/{@code *_leggings}/
     * {@code *_boots}) or one of the Wynncraft weapon-class proxies
     * ({@code iron_shovel}, {@code shears}, {@code bow}, {@code stick},
     * {@code cocoa_beans}, {@code paper}).
     *
     * <p>The pedestal wipe strips an item's lore but keeps its coloured name and vanilla item
     * id. The four accepted colour codes are the ones {@link #getRarityFromNameColor} reads as
     * set, legendary, rare and unique; a name in {@code not_pedestal} is never a shell.</p>
     */
    public static boolean isPedestalWipedItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (!stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().isEmpty())
            return false;

        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        if (customName == null) return false;
        String raw = customName.getString();
        if (raw.length() < 2 || raw.charAt(0) != '§') return false;
        char code = Character.toLowerCase(raw.charAt(1));
        if (code != 'a' && code != 'b' && code != 'd' && code != 'e') return false;

        String normalized = normalizeName(ChatFormatting.stripFormatting(raw));
        if (normalized != null && ItemDefinitions.isNotPedestal(normalized)) return false;

        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!"minecraft".equals(key.getNamespace())) return false;
        String path = key.getPath();
        return path.endsWith("_helmet")
                || path.endsWith("_chestplate")
                || path.endsWith("_leggings")
                || path.endsWith("_boots")
                || path.equals("iron_shovel")
                || path.equals("shears")
                || path.equals("bow")
                || path.equals("stick")
                || path.equals("cocoa_beans")
                || path.equals("paper");
    }

    /** Returns {@code true} if any tooltip line starts with "Misc. Item". */
    public static boolean hasMiscRarity(List<Component> lines) {
        for (Component line : lines) {
            String plain = ChatFormatting.stripFormatting(line.getString());
            if (plain != null && plain.startsWith("Misc. Item")) return true;
        }
        return false;
    }

    /** Returns {@code true} if any tooltip line reads "Junk Item". */
    public static boolean hasJunkRarity(List<Component> lines) {
        for (Component line : lines) {
            String plain = ChatFormatting.stripFormatting(line.getString());
            if ("Junk Item".equals(plain)) return true;
        }
        return false;
    }

    /** Returns {@code true} if any tooltip line reads "Crafting Item", ignoring case. */
    public static boolean hasCraftingRarity(List<Component> lines) {
        for (Component line : lines) {
            String plain = ChatFormatting.stripFormatting(line.getString());
            if ("Crafting Item".equalsIgnoreCase(plain)) return true;
        }
        return false;
    }

    /** Returns {@code true} if any tooltip line matches the standard rarity pattern. */
    public static boolean hasRarityLine(List<Component> lines) {
        for (Component line : lines) {
            String plain = ChatFormatting.stripFormatting(line.getString());
            if (plain != null && RARITY_PATTERN.matcher(plain).matches()) return true;
        }
        return false;
    }

    /** Returns {@code true} if a line is an all-gold "Lv. min" line (alpha and beta items). */
    public static boolean hasBetaLegacyMarker(List<Component> lines) {
        for (Component line : lines) {
            String plain = ChatFormatting.stripFormatting(line.getString());
            if (LegacyLoreText.isBetaLevelLine(plain) && isExclusivelyGold(line)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if a line carries Wynncraft's VINTAGE ribbon, the banner it puts at
     * the top of a legacy item's lore. Only text drawn in the {@code banner/ribbon} font is
     * read, so the word in ordinary lore text, or the same glyphs in another font, never count.
     */
    public static boolean hasVintageBanner(List<Component> lines) {
        for (Component line : lines) {
            StringBuilder ribbon = new StringBuilder();
            line.visit(
                    (Style style, String text) -> {
                        if (BANNER_RIBBON_FONT.equals(style.getFont())) ribbon.append(text);
                        return Optional.empty();
                    },
                    Style.EMPTY);
            if (LegacyLoreText.isVintageRibbon(ribbon.toString())) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if every colour code in the text is gold, or, when the text has
     * none, the component's own style colour is gold.
     */
    private static boolean isExclusivelyGold(Component component) {
        String raw = component.getString();
        boolean foundColorCode = false;
        for (int i = 0; i < raw.length() - 1; i++) {
            if (raw.charAt(i) == '\u00A7') {
                char code = Character.toLowerCase(raw.charAt(i + 1));
                if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
                    foundColorCode = true;
                    if (code != '6') return false;
                }
            }
        }
        if (foundColorCode) return true;

        // No section-sign color codes in text — check component style
        TextColor gold = TextColor.fromLegacyFormat(ChatFormatting.GOLD);
        if (gold == null) return false;
        TextColor styleColor = component.getStyle().getColor();
        return gold.equals(styleColor);
    }

    /** Maps {@code tooltip_style} data component paths to human-readable rarity names. */
    private static final Map<String, String> TOOLTIP_STYLE_TO_RARITY =
            Map.of(
                    "common", "Normal",
                    "unique", "Unique",
                    "rare", "Rare",
                    "set", "Set",
                    "legendary", "Legendary",
                    "fabled", "Fabled",
                    "mythic", "Mythic",
                    "crafted", "Crafted");

    /**
     * Returns a plain "Sharpness I"-style label for the item's enchantment, or {@code null}
     * when it carries none, for the item-dump tool.
     *
     * <p>It reads the {@code minecraft:enchantments} component, which stays on the stack even
     * when Wynncraft hides it from the tooltip with {@code tooltip_display}. Modern items carry
     * it with an empty map, so a non-empty map marks a legacy item, unlike
     * {@link ItemStack#hasFoil()}, which {@code enchantment_glint_override} also sets.</p>
     */
    public static String getEnchantmentLabel(ItemStack stack) {
        return LegacyEnchantmentRenderer.enchantmentLabel(stack);
    }

    /**
     * Extracts the item rarity from the {@code tooltip_style} data component.
     * New-format Wynncraft items encode their rarity tier as a {@code tooltip_style}
     * identifier (e.g. {@code minecraft:rare}, {@code minecraft:unique}) rather than
     * including a plain-text rarity line in the lore.
     *
     * @return the human-readable rarity name, or {@code null} if unavailable
     */
    public static String getTooltipStyleRarity(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Identifier tooltipStyle = stack.get(DataComponents.TOOLTIP_STYLE);
        if (tooltipStyle == null) return null;
        return TOOLTIP_STYLE_TO_RARITY.get(tooltipStyle.getPath());
    }
}
