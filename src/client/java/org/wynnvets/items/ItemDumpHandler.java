package org.wynnvets.items;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.logging.VetsLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Dumps the full component tree of an {@link ItemStack} to a JSON file
 * under {@code vetsmod/dumps/}. Each invocation creates a new file so
 * multiple states of the same item can be compared side-by-side.
 */
public final class ItemDumpHandler {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final Path DUMP_DIR = FabricLoader.getInstance().getGameDir().resolve("vetsmod/dumps");

    private ItemDumpHandler() {}

    /**
     * Dumps the given item stack to a uniquely-named JSON file and notifies
     * the player in chat.
     */
    public static void dump(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            ChatUtils.sendLocalMessage(Component.literal("No item to dump.").withStyle(ChatFormatting.RED));
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

        // Version info
        root.addProperty("vetsmodVersion", getModVersion("vetsmod"));
        root.addProperty("mcVersion", getModVersion("minecraft"));
        root.addProperty("wynntilsVersion", getModVersion("wynntils"));
        root.addProperty("fabricLoaderVersion", getModVersion("fabricloader"));

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
            // CustomModelData stores strings, floats, flags, colors
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
        String plainName = LegacyItemHandler.normalizeName(ChatFormatting.stripFormatting(hoverName.getString()));
        analysis.addProperty("normalizedName", plainName);
        analysis.addProperty("isLegacy", plainName != null && ItemDefinitions.isLegacy(plainName));
        analysis.addProperty("isMiscLegacy", plainName != null && ItemDefinitions.isMiscLegacy(plainName));
        analysis.addProperty("isUnenchanted", plainName != null && ItemDefinitions.isUnenchanted(plainName));
        analysis.addProperty("isNotJunk", plainName != null && ItemDefinitions.isNotJunk(plainName));
        analysis.addProperty("isEnchantExcludedItem", ItemDefinitions.isEnchantExcludedItem(stack));
        analysis.addProperty("hasMiscRarity", LegacyItemHandler.hasMiscRarity(lore.lines()));
        analysis.addProperty("hasJunkRarity", LegacyItemHandler.hasJunkRarity(lore.lines()));
        analysis.addProperty("hasCraftingRarity", LegacyItemHandler.hasCraftingRarity(lore.lines()));
        analysis.addProperty("hasBetaLegacyMarker", LegacyItemHandler.hasBetaLegacyMarker(lore.lines()));
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

        // Build filename: Name_YYYYMMDD_HHmmss_SSS_nanos.json
        String safeName = plainName != null ? plainName.replaceAll("[^a-zA-Z0-9_]", "_") : "Unknown";
        if (safeName.length() > 40) safeName = safeName.substring(0, 40);
        String fileName = safeName + "_" + now.format(FILE_TS) + "_" + System.nanoTime() + ".json";
        Path outFile = DUMP_DIR.resolve(fileName);

        try {
            Files.writeString(outFile, GSON.toJson(root));
            MutableComponent msg = Component.literal("Item dumped to ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(fileName).withStyle(ChatFormatting.YELLOW));
            ChatUtils.sendLocalMessage(msg);
            VetsLogger.info("Item dumped to {}", outFile);
        } catch (IOException e) {
            VetsLogger.error("Failed to write item dump: {}", e.getMessage());
            ChatUtils.sendLocalMessage(Component.literal("Failed to dump item: " + e.getMessage()).withStyle(ChatFormatting.RED));
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

    /**
     * Formats a Component with color codes in a human-readable way,
     * using {@code §[color_name]} or {@code §[#RRGGBB]} notation.
     */
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
        // Get the literal content of just this component (not siblings)
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
}
