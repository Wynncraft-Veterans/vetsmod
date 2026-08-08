package org.wynnvets.debug.dump;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.items.ItemDefinitions;
import org.wynnvets.items.LegacyItemHandler;
import org.wynnvets.logging.VetsLogger;

/**
 * Dumps the full component tree of an {@link ItemStack} to a JSON file
 * under {@code vetsmod/dumps/items/}. Each invocation creates a new file
 * so multiple states of the same item can be compared side-by-side.
 * Sibling of {@code vetsmod/dumps/anni/} — every dump artifact lives
 * under {@code vetsmod/dumps/} organised per-type.
 *
 * <p>This is a <b>debug-only</b> utility — not part of normal mod
 * functionality.  Activated via {@code /wv debug set itemDump true}
 * then pressing numpad {@code +} while hovering an item.</p>
 */
public final class ItemDumpHandler {

    /**
     * Dump format version. Bump whenever the JSON schema changes in a way
     * that would break diff tooling or analysis scripts.
     *
     * <ul>
     *   <li><b>1</b> — initial version (unversioned in file; inferred when absent)</li>
     *   <li><b>2</b> — adds {@code dumpFormatVersion}, {@code loadedMods},
     *       {@code freshTooltip}, {@code capturedTooltip}, {@code newFormatProbes}</li>
     *   <li><b>3</b> — adds {@code hoveredSlot} (menu/container indices, grid
     *       row/column, screen title) for guild-bank macro debugging</li>
     * </ul>
     */
    public static final int DUMP_FORMAT_VERSION = 3;

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final Path DUMP_DIR =
            FabricLoader.getInstance().getGameDir().resolve("vetsmod/dumps/items");

    private ItemDumpHandler() {}

    /**
     * Dumps the given item stack to a uniquely-named JSON file and notifies
     * the player in chat.
     */
    public static void dump(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("No item to dump.").withStyle(ChatFormatting.RED));
            return;
        }

        try {
            Files.createDirectories(DUMP_DIR);
        } catch (IOException e) {
            VetsLogger.error("Failed to create dump directory: {}", e.getMessage());
            return;
        }

        JsonObject root = new JsonObject();

        LocalDateTime now = LocalDateTime.now();
        root.addProperty("dumpTime", now.toString());
        root.addProperty("dumpFormatVersion", DUMP_FORMAT_VERSION);

        // Version info
        root.addProperty("vetsmodVersion", getModVersion("vetsmod"));
        root.addProperty("mcVersion", getModVersion("minecraft"));
        root.addProperty("wynntilsVersion", getModVersion("wynntils"));
        root.addProperty("fabricLoaderVersion", getModVersion("fabricloader"));
        root.addProperty("wynnmodVersion", getModVersion("wynnmod"));

        // All loaded mods (id -> version). Useful for correlating tooltip
        // anomalies to specific third-party mods.
        JsonObject mods = new JsonObject();
        for (ModContainer mc : FabricLoader.getInstance().getAllMods()) {
            mods.addProperty(
                    mc.getMetadata().getId(), mc.getMetadata().getVersion().getFriendlyString());
        }
        root.add("loadedMods", mods);

        // Item basics
        String registryId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        root.addProperty("itemType", registryId);
        root.addProperty("itemRegistryId", registryId);
        root.addProperty("count", stack.getCount());
        root.addProperty("damageValue", stack.getDamageValue());
        root.addProperty("maxDamage", stack.getMaxDamage());
        root.addProperty("hasFoil", stack.hasFoil());

        // Hover name
        Component hoverName = stack.getHoverName();
        root.addProperty("hoverName_raw", hoverName.getString());
        root.addProperty("hoverName_formatted", formatComponentWithCodes(hoverName));
        root.add("hoverName_tree", componentToJson(hoverName));

        // Custom name (may differ from hoverName — the CUSTOM_NAME data component)
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        if (customName != null) {
            root.addProperty("customName_raw", customName.getString());
            root.addProperty("customName_formatted", formatComponentWithCodes(customName));
            root.add("customName_tree", componentToJson(customName));
        } else {
            root.add("customName_raw", JsonNull.INSTANCE);
        }

        // Item name (non-custom display name, e.g. "Potion", "Leather Cap")
        Component itemName = stack.get(DataComponents.ITEM_NAME);
        if (itemName != null) {
            root.addProperty("itemName_raw", itemName.getString());
            root.addProperty("itemName_formatted", formatComponentWithCodes(itemName));
            root.add("itemName_tree", componentToJson(itemName));
        }

        // Lore
        ItemLore lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        JsonArray loreArr = new JsonArray();
        for (Component line : lore.lines()) {
            JsonObject loreLine = new JsonObject();
            loreLine.addProperty("raw", line.getString());
            loreLine.addProperty("formatted", formatComponentWithCodes(line));
            loreLine.add("tree", componentToJson(line));
            loreArr.add(loreLine);
        }
        root.add("lore", loreArr);

        // Tooltip style
        Identifier tooltipStyle = stack.get(DataComponents.TOOLTIP_STYLE);
        root.addProperty("tooltip_style", tooltipStyle != null ? tooltipStyle.toString() : null);

        // Custom model data
        CustomModelData cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        if (cmd != null) {
            JsonObject cmdObj = new JsonObject();
            JsonArray strings = new JsonArray();
            for (int i = 0; ; i++) {
                try {
                    String s = cmd.strings().get(i);
                    strings.add(s);
                } catch (IndexOutOfBoundsException e) {
                    break;
                }
            }
            cmdObj.add("strings", strings);
            JsonArray floats = new JsonArray();
            for (int i = 0; ; i++) {
                try {
                    float f = cmd.floats().get(i);
                    floats.add(f);
                } catch (IndexOutOfBoundsException e) {
                    break;
                }
            }
            cmdObj.add("floats", floats);
            JsonArray flags = new JsonArray();
            for (int i = 0; ; i++) {
                try {
                    boolean b = cmd.flags().get(i);
                    flags.add(b);
                } catch (IndexOutOfBoundsException e) {
                    break;
                }
            }
            cmdObj.add("flags", flags);
            JsonArray colors = new JsonArray();
            for (int i = 0; ; i++) {
                try {
                    int c = cmd.colors().get(i);
                    colors.add(String.format("#%06X", c & 0xFFFFFF));
                } catch (IndexOutOfBoundsException e) {
                    break;
                }
            }
            cmdObj.add("colors", colors);
            root.add("custom_model_data", cmdObj);
        }

        // All data components as toString
        JsonObject comps = new JsonObject();
        DataComponentMap dcMap = stack.getComponents();
        for (var entry : dcMap) {
            DataComponentType<?> type = entry.type();
            Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
            String key = id != null ? id.toString() : type.toString();
            Object value = entry.value();
            comps.addProperty(key, value != null ? value.toString() : "null");
        }
        root.add("dataComponents", comps);

        // Vetsmod detection analysis
        JsonObject analysis = new JsonObject();
        String plainName =
                LegacyItemHandler.normalizeName(
                        ChatFormatting.stripFormatting(hoverName.getString()));
        analysis.addProperty("normalizedName", plainName);
        analysis.addProperty("isLegacy", plainName != null && ItemDefinitions.isLegacy(plainName));
        analysis.addProperty(
                "isNoLoreLegacy", plainName != null && ItemDefinitions.isNoLoreLegacy(plainName));
        analysis.addProperty(
                "isMiscLegacy", plainName != null && ItemDefinitions.isMiscLegacy(plainName));
        analysis.addProperty(
                "isUnenchanted", plainName != null && ItemDefinitions.isUnenchanted(plainName));
        analysis.addProperty(
                "isNotJunk", plainName != null && ItemDefinitions.isNotJunk(plainName));
        analysis.addProperty("isEnchantExcludedItem", ItemDefinitions.isEnchantExcludedItem(stack));
        // The specific enchantment, which Wynncraft hides from the tooltip via
        // tooltip_display but still ships on the stack. Null on modern items —
        // they carry the component with an empty map.
        analysis.addProperty("enchantment", LegacyItemHandler.getEnchantmentLabel(stack));
        analysis.addProperty("hasMiscRarity", LegacyItemHandler.hasMiscRarity(lore.lines()));
        analysis.addProperty("hasJunkRarity", LegacyItemHandler.hasJunkRarity(lore.lines()));
        analysis.addProperty(
                "hasCraftingRarity", LegacyItemHandler.hasCraftingRarity(lore.lines()));
        analysis.addProperty(
                "hasBetaLegacyMarker", LegacyItemHandler.hasBetaLegacyMarker(lore.lines()));
        analysis.addProperty("hasRarityLine", LegacyItemHandler.hasRarityLine(lore.lines()));
        analysis.addProperty("tooltipStyleRarity", LegacyItemHandler.getTooltipStyleRarity(stack));
        analysis.addProperty("isLegacyItem_result", LegacyItemHandler.isLegacyItem(stack));
        root.add("vetsmodAnalysis", analysis);

        // Codepoints for name and lore (helps debug PUA issues)
        root.addProperty("hoverName_codepoints", toCodepointString(hoverName.getString()));
        JsonArray loreCp = new JsonArray();
        for (Component line : lore.lines()) {
            loreCp.add(toCodepointString(line.getString()));
        }
        root.add("lore_codepoints", loreCp);

        // ── Hovered-slot info ─────────────────────────────────────────────
        //
        // The slot the user was hovering when they pressed the dump key.
        // Useful for debugging guild-bank macros that target slots by index:
        // the menuSlotIndex matches what ServerboundContainerClickPacket
        // expects (i.e. the same number Wynntils' GuildBankHotkeyFeature
        // hard-codes as GUILD_BANK_SLOT = 15).
        root.add("hoveredSlot", buildHoveredSlotDump(stack));

        // ── Tooltip captures ──────────────────────────────────────────────
        //
        // Two views of the rendered tooltip line list:
        //
        //  - freshTooltip    : built right now via Screen.getTooltipFromItem.
        //                      Fires Wynntils tooltip events (post-Wynntils),
        //                      but does NOT trigger render-time wraps such as
        //                      wynnmod's LowAbstractContainerScreenMixin
        //                      because we are not inside renderTooltip's call
        //                      chain to setTooltipForNextFrame.
        //                      → "pre-wynnmod" view.
        //
        //  - capturedTooltip : recorded by LegacyItemTooltipMixin the last time
        //                      it ran. The mixin fires from inside the inner
        //                      setTooltipForNextFrame, after wynnmod's wrap has
        //                      replaced the list via event.setText(...).
        //                      → "post-wynnmod" view.
        //
        // Diffing the two cleanly fingerprints any third-party tooltip
        // rewrite (wynnmod, future processors, etc.).
        root.add("freshTooltip", buildFreshTooltipDump(stack));
        root.add("capturedTooltip", buildCapturedTooltipDump(stack));

        // NewFormatRenderer probes against the fresh tooltip — surface the
        // exact predicates the legacy renderer uses for branch selection.
        JsonObject probes = new JsonObject();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.screen != null) {
                List<Component> fresh = Screen.getTooltipFromItem(mc, stack);
                probes.addProperty("freshLineCount", fresh.size());
                probes.addProperty("isNewFormatItem", containsNewFormatFont(fresh));
                if (!fresh.isEmpty()) {
                    probes.addProperty(
                            "freshLine0_equalsCustomName",
                            customName != null
                                    && fresh.get(0).getString().equals(customName.getString()));
                    probes.addProperty(
                            "freshLine0_equalsHoverName",
                            fresh.get(0).getString().equals(hoverName.getString()));
                }
            } else {
                probes.addProperty("unavailable", "no screen open");
            }
        } catch (Throwable t) {
            probes.addProperty("error", t.toString());
        }
        root.add("newFormatProbes", probes);

        // Build filename: Name_YYYYMMDD_HHmmss_SSS_nanos.json
        String safeName =
                plainName != null ? plainName.replaceAll("[^a-zA-Z0-9_]", "_") : "Unknown";
        if (safeName.length() > 40) safeName = safeName.substring(0, 40);
        String fileName = safeName + "_" + now.format(FILE_TS) + "_" + System.nanoTime() + ".json";
        Path outFile = DUMP_DIR.resolve(fileName);

        try {
            Files.writeString(outFile, GSON.toJson(root));
            MutableComponent msg =
                    Component.literal("Item dumped to ")
                            .withStyle(ChatFormatting.GREEN)
                            .append(Component.literal(fileName).withStyle(ChatFormatting.YELLOW));
            ChatUtils.sendLocalMessage(msg);
            VetsLogger.info("Item dumped to {}", outFile);
        } catch (IOException e) {
            VetsLogger.error("Failed to write item dump: {}", e.getMessage());
            ChatUtils.sendLocalMessage(
                    Component.literal("Failed to dump item: " + e.getMessage())
                            .withStyle(ChatFormatting.RED));
        }
    }

    // ── Component tree serialization ─────────────────────────────────

    private static JsonObject componentToJson(Component comp) {
        JsonObject obj = new JsonObject();
        obj.addProperty("text", comp.getString());
        obj.addProperty("literalContent", comp.getContents().toString());

        Style style = comp.getStyle();
        if (!style.isEmpty()) {
            JsonObject styleObj = new JsonObject();
            TextColor color = style.getColor();
            if (color != null) {
                String name = color.serialize();
                styleObj.addProperty("color", name);
                styleObj.addProperty("color_value", color.getValue());
            }
            if (style.isBold()) styleObj.addProperty("bold", true);
            if (style.isItalic()) styleObj.addProperty("italic", true);
            if (style.isUnderlined()) styleObj.addProperty("underlined", true);
            if (style.isStrikethrough()) styleObj.addProperty("strikethrough", true);
            if (style.isObfuscated()) styleObj.addProperty("obfuscated", true);
            // Wynncraft disables text shadow on its boxed tooltip headers by
            // setting an explicit shadowColor. Serialized as both the raw ARGB
            // and hex because whether it is set at all — and with which alpha —
            // is otherwise invisible when diffing our output against theirs.
            Integer shadowColor = style.getShadowColor();
            if (shadowColor != null) {
                styleObj.addProperty("shadowColor", shadowColor);
                styleObj.addProperty("shadowColor_hex", String.format("#%08X", shadowColor));
            }
            if (style.getFont() != null) {
                styleObj.addProperty("font", style.getFont().toString());
            }
            obj.add("style", styleObj);
        }

        List<Component> siblings = comp.getSiblings();
        if (!siblings.isEmpty()) {
            JsonArray sibs = new JsonArray();
            for (Component sib : siblings) {
                sibs.add(componentToJson(sib));
            }
            obj.add("siblings", sibs);
        }

        return obj;
    }

    private static String formatComponentWithCodes(Component comp) {
        StringBuilder sb = new StringBuilder();
        appendFormatted(sb, comp);
        return sb.toString();
    }

    private static void appendFormatted(StringBuilder sb, Component comp) {
        Style style = comp.getStyle();
        TextColor color = style.getColor();
        if (color != null) {
            sb.append("\u00A7[").append(color.serialize()).append("]");
        }
        String contents = comp.getContents().toString();
        if (contents.startsWith("literal{")) {
            sb.append(contents.substring(8, contents.length() - 1));
        }
        for (Component sib : comp.getSiblings()) {
            appendFormatted(sb, sib);
        }
    }

    private static String toCodepointString(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("U+%04X", cp));
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static String getModVersion(String modId) {
        return FabricLoader.getInstance()
                .getModContainer(modId)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("not found");
    }

    // ── Hovered-slot dumping ─────────────────────────────────────────

    /**
     * Records which slot was being hovered when the dump was triggered.
     * Validates the stashed slot reference against the current screen and
     * its menu so that stale state (e.g. dump key pressed after the
     * container closed) is reported as unavailable rather than silently
     * emitting bogus indices.
     */
    private static JsonObject buildHoveredSlotDump(ItemStack stack) {
        JsonObject obj = new JsonObject();
        Slot slot = LegacyItemHandler.currentHoveredSlot;
        if (slot == null) {
            obj.addProperty("status", "no slot recorded (hover an item in a container first)");
            return obj;
        }

        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc != null ? mc.screen : null;
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            obj.addProperty(
                    "status", "no container screen open (stashed slot reference may be stale)");
            return obj;
        }
        // Reject stale references — Slot belongs to the previous menu.
        if (slot.container != null && containerScreen.getMenu().slots.indexOf(slot) < 0) {
            obj.addProperty("status", "stashed slot does not belong to current menu (stale)");
            return obj;
        }

        obj.addProperty("status", "ok");
        // The number macros / ServerboundContainerClickPacket want.
        obj.addProperty("menuSlotIndex", slot.index);
        obj.addProperty("containerSlotIndex", slot.getContainerSlot());
        int containerSize = slot.container != null ? slot.container.getContainerSize() : -1;
        obj.addProperty("containerSize", containerSize);
        // Wynncraft chest GUIs are always 9 columns wide; derive row/col so
        // macro authors can cross-check against the in-game grid layout.
        int containerSlot = slot.getContainerSlot();
        obj.addProperty("row", containerSlot / 9);
        obj.addProperty("column", containerSlot % 9);
        obj.addProperty("x", slot.x);
        obj.addProperty("y", slot.y);
        obj.addProperty("containerId", containerScreen.getMenu().containerId);
        obj.addProperty("screenTitle", screen.getTitle().getString());
        obj.addProperty(
                "stackMatchesSlotItem", ItemStack.isSameItemSameComponents(slot.getItem(), stack));
        return obj;
    }

    // ── Tooltip-line dumping ─────────────────────────────────────────

    /**
     * Builds a fresh tooltip via {@link Screen#getTooltipFromItem} and
     * serializes each line. Returns a JsonObject with status info and an
     * array of line dumps. The fresh tooltip is post-Wynntils-events but
     * pre-render-time wraps (so it bypasses wynnmod's decoration).
     */
    private static JsonObject buildFreshTooltipDump(ItemStack stack) {
        JsonObject obj = new JsonObject();
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            obj.addProperty("status", "no Minecraft instance");
            return obj;
        }
        if (mc.screen == null) {
            obj.addProperty(
                    "status",
                    "no screen open (Screen.getTooltipFromItem requires a screen for context)");
            return obj;
        }
        try {
            List<Component> fresh = Screen.getTooltipFromItem(mc, stack);
            obj.addProperty("status", "ok");
            obj.addProperty("lineCount", fresh.size());
            obj.add("lines", dumpComponentLines(fresh));
        } catch (Throwable t) {
            obj.addProperty("status", "error");
            obj.addProperty("error", t.toString());
        }
        return obj;
    }

    /**
     * Serializes the most recent capture from {@link TooltipCapture}, with
     * staleness flags and the metadata recorded at capture time.
     */
    private static JsonObject buildCapturedTooltipDump(ItemStack stack) {
        JsonObject obj = new JsonObject();
        if (!TooltipCapture.hasCapture()) {
            obj.addProperty("status", "no capture recorded yet (hover a tooltip first)");
            return obj;
        }
        long ageNanos = System.nanoTime() - TooltipCapture.capturedAtNanos();
        obj.addProperty("status", "ok");
        obj.addProperty("ageMillis", ageNanos / 1_000_000L);
        obj.addProperty(
                "capturedStackMatchesDumpedStack",
                ItemStack.isSameItemSameComponents(TooltipCapture.capturedStack(), stack));
        obj.addProperty("processed", TooltipCapture.processed());
        obj.addProperty("reentryGuardActive", TooltipCapture.reentryGuardActive());
        Identifier border = TooltipCapture.borderIdentifier();
        obj.addProperty("borderIdentifier", border != null ? border.toString() : null);
        obj.addProperty(
                "lastProcessedWasLegacyAfter", TooltipCapture.lastProcessedWasLegacyAfter());

        List<Component> input = TooltipCapture.inputSnapshot();
        List<Component> output = TooltipCapture.outputSnapshot();
        obj.addProperty("inputLineCount", input.size());
        obj.addProperty("outputLineCount", output.size());
        obj.addProperty("outputIsSameInstance", TooltipCapture.inputOutputSameInstance());
        obj.add("inputLines", dumpComponentLines(input));
        // Avoid duplicating the giant tree when no rewrite happened.
        if (input != output) {
            obj.add("outputLines", dumpComponentLines(output));
        }
        return obj;
    }

    /**
     * Dumps a list of components as an array of per-line objects, each with
     * raw text, formatted text, codepoint string, font usage summary, and
     * the full component tree.
     */
    private static JsonArray dumpComponentLines(List<Component> lines) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            JsonObject lineObj = new JsonObject();
            lineObj.addProperty("index", i);
            lineObj.addProperty("raw", line.getString());
            lineObj.addProperty("formatted", formatComponentWithCodes(line));
            lineObj.addProperty("codepoints", toCodepointString(line.getString()));
            lineObj.add("fonts", collectFontsAsArray(line));
            lineObj.add("tree", componentToJson(line));
            arr.add(lineObj);
        }
        return arr;
    }

    /**
     * Walks a Component tree and collects every distinct font identifier
     * encountered (including the implicit minecraft:default for unstyled
     * nodes). Order is insertion order to make diffs stable.
     */
    private static JsonArray collectFontsAsArray(Component root) {
        Set<String> fonts = new LinkedHashSet<>();
        root.visit(
                (Style style, String text) -> {
                    FontDescription fd = style.getFont();
                    fonts.add(fd != null ? fd.toString() : "<inherited>");
                    return Optional.empty();
                },
                Style.EMPTY);
        JsonArray arr = new JsonArray();
        for (String f : fonts) arr.add(f);
        return arr;
    }

    /**
     * Mirrors {@link org.wynnvets.items.NewFormatRenderer#isNewFormatItem
     * NewFormatRenderer#isNewFormatItem} without coupling the dump command to that package-private
     * class. Returns true if any line uses the {@code tooltip/emblem/frame} or {@code banner/box}
     * fonts (the markers Wynncraft's new tooltip format relies on).
     */
    private static boolean containsNewFormatFont(List<Component> lines) {
        for (Component line : lines) {
            boolean[] hit = {false};
            line.visit(
                    (Style style, String text) -> {
                        FontDescription fd = style.getFont();
                        if (fd == null) return Optional.empty();
                        String s = fd.toString();
                        if (s.contains("tooltip/emblem/frame") || s.contains("banner/box")) {
                            hit[0] = true;
                            return Optional.of(Boolean.TRUE);
                        }
                        return Optional.empty();
                    },
                    Style.EMPTY);
            if (hit[0]) return true;
        }
        return false;
    }
}
