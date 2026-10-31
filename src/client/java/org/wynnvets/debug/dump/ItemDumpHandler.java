package org.wynnvets.debug.dump;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.wynntils.utils.mc.McUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.items.ItemDefinitions;
import org.wynnvets.items.LegacyItemHandler;
import org.wynnvets.logging.VetsLogger;

/**
 * Dumps the full component tree of an {@link ItemStack} to a JSON file
 * under {@code vetsmod/dumps/items/} ({@link DumpFiles}). Each invocation creates a new file
 * so multiple states of the same item can be compared side-by-side.
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
     *   <li><b>4</b> — adds {@code vetsmodAnalysis.retro} (the item model, the first
     *       custom-model-data float, the Wynntils item type, and the models the live item
     *       definition draws with each one's pack and retro rule;
     *       {@link HoverDumps#buildRetroDump})</li>
     * </ul>
     */
    public static final int DUMP_FORMAT_VERSION = 4;

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private ItemDumpHandler() {}

    /**
     * Dumps the given item stack to a uniquely-named JSON file and notifies
     * the player in chat. Everything is read now, on the render thread; the file is
     * written there a moment later, once {@link HoverDumps#buildRetroDump}'s retro block
     * is in (it parses the stack's item definition on a background worker).
     */
    public static void dump(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("No item to dump.").withStyle(ChatFormatting.RED));
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
        root.addProperty("hoverName_formatted", ComponentDumps.formatComponentWithCodes(hoverName));
        root.add("hoverName_tree", ComponentDumps.componentToJson(hoverName));

        // Custom name (may differ from hoverName — the CUSTOM_NAME data component)
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        if (customName != null) {
            root.addProperty("customName_raw", customName.getString());
            root.addProperty(
                    "customName_formatted", ComponentDumps.formatComponentWithCodes(customName));
            root.add("customName_tree", ComponentDumps.componentToJson(customName));
        } else {
            root.add("customName_raw", JsonNull.INSTANCE);
        }

        // Item name (non-custom display name, e.g. "Potion", "Leather Cap")
        Component itemName = stack.get(DataComponents.ITEM_NAME);
        if (itemName != null) {
            root.addProperty("itemName_raw", itemName.getString());
            root.addProperty(
                    "itemName_formatted", ComponentDumps.formatComponentWithCodes(itemName));
            root.add("itemName_tree", ComponentDumps.componentToJson(itemName));
        }

        // Lore
        ItemLore lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        JsonArray loreArr = new JsonArray();
        for (Component line : lore.lines()) {
            JsonObject loreLine = new JsonObject();
            loreLine.addProperty("raw", line.getString());
            loreLine.addProperty("formatted", ComponentDumps.formatComponentWithCodes(line));
            loreLine.add("tree", ComponentDumps.componentToJson(line));
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
        // The specific enchantment: on new-format items Wynncraft hides it from the
        // tooltip via tooltip_display but it still ships on the stack (V2-era items
        // leave it visible in the tooltip already). Null on modern items — they carry
        // the component with an empty map.
        analysis.addProperty("enchantment", LegacyItemHandler.getEnchantmentLabel(stack));
        analysis.addProperty("hasMiscRarity", LegacyItemHandler.hasMiscRarity(lore.lines()));
        analysis.addProperty("hasJunkRarity", LegacyItemHandler.hasJunkRarity(lore.lines()));
        analysis.addProperty(
                "hasCraftingRarity", LegacyItemHandler.hasCraftingRarity(lore.lines()));
        analysis.addProperty(
                "hasBetaLegacyMarker", LegacyItemHandler.hasBetaLegacyMarker(lore.lines()));
        analysis.addProperty("hasVintageBanner", LegacyItemHandler.hasVintageBanner(lore.lines()));
        analysis.addProperty("underroll", LegacyItemHandler.getUnderrollSummary(stack));
        analysis.addProperty("hasRarityLine", LegacyItemHandler.hasRarityLine(lore.lines()));
        analysis.addProperty("tooltipStyleRarity", LegacyItemHandler.getTooltipStyleRarity(stack));
        analysis.addProperty("isCraftedItem", LegacyItemHandler.isCraftedItem(stack));
        analysis.addProperty("isLegacyItem_result", LegacyItemHandler.isLegacyItem(stack));
        // The analysis' last field, "retro", is added when the file is written (below).
        CompletableFuture<JsonObject> retro = HoverDumps.buildRetroDump(stack);
        root.add("vetsmodAnalysis", analysis);

        // Codepoints for name and lore (helps debug PUA issues)
        root.addProperty(
                "hoverName_codepoints", ComponentDumps.toCodepointString(hoverName.getString()));
        JsonArray loreCp = new JsonArray();
        for (Component line : lore.lines()) {
            loreCp.add(ComponentDumps.toCodepointString(line.getString()));
        }
        root.add("lore_codepoints", loreCp);

        // ── Hovered-slot info ─────────────────────────────────────────────
        //
        // The slot the user was hovering when they pressed the dump key.
        // Useful for debugging guild-bank macros that target slots by index:
        // the menuSlotIndex matches what ServerboundContainerClickPacket
        // expects (i.e. the same number Wynntils' GuildBankHotkeyFeature
        // hard-codes as GUILD_BANK_SLOT = 15).
        root.add("hoveredSlot", HoverDumps.buildHoveredSlotDump(stack));

        // ── Tooltip captures ──────────────────────────────────────────────
        //
        // Two views of the rendered tooltip line list:
        //
        //  - freshTooltip    : built right now via Screen.getTooltipFromItem.
        //                      Fires Wynntils' ItemTooltipFlagsEvent (hooked
        //                      on getTooltipLines), but does NOT trigger
        //                      render-time wraps. We never call
        //                      setTooltipForNextFrame, so Wynntils'
        //                      ItemTooltipRenderEvent.Pre (posted from its
        //                      wraps of that call, where features such as
        //                      ItemStatInfoFeature rewrite the list) does
        //                      not fire; nor does wynnmod's
        //                      LowAbstractContainerScreenMixin, because we
        //                      are not inside renderTooltip's call
        //                      chain to setTooltipForNextFrame.
        //                      → "pre-rewrite" view: no render-time
        //                      wrap, Wynntils' or wynnmod's, has run.
        //
        //  - capturedTooltip : recorded by LegacyItemTooltipMixin the last time
        //                      it ran. The mixin fires from inside the inner
        //                      setTooltipForNextFrame, after wynnmod's wrap has
        //                      replaced the list via event.setText(...).
        //                      → "post-rewrite" view: downstream of
        //                      both Wynntils' wraps and wynnmod's.
        //
        // Diffing the two fingerprints any render-time tooltip rewrite
        // (Wynntils' own, wynnmod's, future processors, etc.).
        root.add("freshTooltip", HoverDumps.buildFreshTooltipDump(stack));
        root.add("capturedTooltip", HoverDumps.buildCapturedTooltipDump(stack));

        // Probes against the fresh tooltip. isNewFormatItem approximates
        // NewFormatRenderer.isNewFormatItem (substring font match, see
        // containsNewFormatFont), which the legacy renderer runs on its incoming,
        // possibly rewritten lines; the line-0 name comparisons are extra
        // diagnostics, not predicates the legacy renderer tests.
        JsonObject probes = new JsonObject();
        try {
            Minecraft mc = McUtils.mc();
            if (mc != null && mc.screen != null) {
                List<Component> fresh = Screen.getTooltipFromItem(mc, stack);
                probes.addProperty("freshLineCount", fresh.size());
                probes.addProperty("isNewFormatItem", ComponentDumps.containsNewFormatFont(fresh));
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

        // The retro block completes on the render thread, never exceptionally.
        retro.thenAccept(
                block -> {
                    analysis.add("retro", block);
                    write(root, fileName);
                });
    }

    /** Writes the dump and says in chat where it went. Render thread. */
    private static void write(JsonObject root, String fileName) {
        try {
            Path outFile = DumpFiles.write("items", fileName, GSON.toJson(root));
            MutableComponent msg =
                    Component.literal("Item dumped to ")
                            .withStyle(ChatFormatting.GREEN)
                            .append(Component.literal(fileName).withStyle(ChatFormatting.YELLOW));
            ChatUtils.sendLocalMessage(msg);
            VetsLogger.info("Item dumped to {}", outFile);
        } catch (IOException | RuntimeException e) {
            VetsLogger.error("Failed to write item dump: {}", e.getMessage());
            ChatUtils.sendLocalMessage(
                    Component.literal("Failed to dump item: " + e.getMessage())
                            .withStyle(ChatFormatting.RED));
        }
    }

    private static String getModVersion(String modId) {
        return FabricLoader.getInstance()
                .getModContainer(modId)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("not found");
    }
}
