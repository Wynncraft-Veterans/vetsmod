package org.wynnvets.debug.retro;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.wynntils.core.components.Models;
import com.wynntils.utils.mc.McUtils;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.commands.CommandUsage;
import org.wynnvets.debug.dump.DumpFiles;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.RetroReport;
import org.wynnvets.retro.WynntilsConfigOverrides;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.pack.RetroPackInjector;

/**
 * {@code /wv debug tree retro …}: the retro report, for whoever edits the retro rule files, and
 * {@code /wv debug trigger retroDump}: retro's state.
 *
 * <p>{@code report} writes {@link RetroAudit}'s report on the
 * retro pack drawn now to a new file under {@code vetsmod/dumps/retro/}, and says in one chat
 * line where it went and what it found; {@code report ingredients} lists every ingredient stem,
 * with the Wynntils ingredients it draws, rather than the ones that need a look. A bare
 * {@code retro} prints its usage line. Neither leaf has a gate: they only read, and write a
 * file. Each needs the retro pack drawn now ({@code RetroModeManager.isPackLive()}), since the
 * report reads that pack's generation and the packs it was built over, and refuses while a report
 * is being written.</p>
 *
 * <p>The report reads thousands of files and decodes hundreds of images, so a daemon thread
 * builds and writes it. The handler reads on the render thread what the thread needs from the
 * game (the resources, the vanilla pack and, for {@code ingredients}, Wynntils' ingredients); the
 * chat lines are handed back to it.</p>
 */
public final class RetroDebugCommands {

    /** Whether a report is being written; one at a time. */
    private static final AtomicBoolean WRITING = new AtomicBoolean();

    private RetroDebugCommands() {}

    /**
     * Builds the {@code retro} literal, which the caller mounts under {@code /wv debug tree}, so
     * every path here is {@code /wv debug tree retro …}: the usage line on {@code retro}, and the
     * two leaves {@code report} and {@code report ingredients}. Caller is
     * {@link org.wynnvets.debug.DebugCommands#buildCommandTree() DebugCommands#buildCommandTree()}.
     *
     * @return the node
     */
    public static LiteralArgumentBuilder<FabricClientCommandSource> buildCommandTree() {
        return ClientCommandManager.literal("retro")
                .executes(CommandUsage.usage("/wv debug tree retro report [ingredients]"))
                .then(
                        ClientCommandManager.literal("report")
                                .executes(ctx -> report(false))
                                .then(
                                        ClientCommandManager.literal("ingredients")
                                                .executes(ctx -> report(true))));
    }

    /**
     * {@code /wv debug trigger retroDump}: prints the setting, whether the retro pack is drawn now,
     * why it stopped if it did, the last generation's summary, the parts that are off and the
     * Wynntils settings retro has changed.
     *
     * @return the command's result
     */
    public static int dump() {
        boolean enabled = RetroModeManager.isEnabled();
        String latchReason = RetroModeManager.latchReason();
        RetroReport report = RetroModeManager.lastReport();

        MutableComponent msg = Component.empty();
        msg.append(line(ChatFormatting.GOLD, "Retro textures: "));
        msg.append(enabled ? line(ChatFormatting.GREEN, "on") : line(ChatFormatting.GRAY, "off"));
        msg.append(line(ChatFormatting.GRAY, "\nDrawn now: "));
        if (RetroModeManager.isPackLive()) {
            msg.append(line(ChatFormatting.GREEN, "yes"));
        } else if (enabled && latchReason == null && !RetroPackInjector.isWynncraftLoaded()) {
            msg.append(line(ChatFormatting.GRAY, "no, not until Wynncraft's resource pack loads"));
        } else {
            msg.append(line(ChatFormatting.GRAY, "no"));
        }
        if (latchReason != null) {
            msg.append(
                    line(
                            ChatFormatting.RED,
                            "\nStopped until the game restarts: " + latchReason + "."));
        }
        msg.append(
                report == null
                        ? line(ChatFormatting.DARK_GRAY, "\nNot built this session.")
                        : line(ChatFormatting.DARK_GRAY, "\nLast built: " + report.summary()));
        Set<RetroPart> off = RetroPart.allBut(RetroModeManager.partsOn());
        if (!off.isEmpty()) {
            msg.append(line(ChatFormatting.GRAY, "\nParts off: " + RetroPart.names(off, ", ")));
        }
        List<String> changed = WynntilsConfigOverrides.changed();
        if (!changed.isEmpty()) {
            msg.append(
                    line(
                            ChatFormatting.GRAY,
                            "\nWynntils settings switched: " + String.join(", ", changed)));
        }
        ChatUtils.sendLocalMessageNewBlock(msg);
        return 1;
    }

    private static MutableComponent line(ChatFormatting colour, String text) {
        return Component.literal(text).withStyle(colour);
    }

    /** {@code report} and {@code report ingredients}: starts the thread that writes it. */
    private static int report(boolean allIngredients) {
        RetroReport report = RetroModeManager.lastReport();
        if (!RetroModeManager.isPackLive() || report == null) {
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    "The retro report is about the retro pack drawn now, and none"
                                            + " is; /wv debug trigger retroDump says why.")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        Minecraft mc = McUtils.mc();
        ResourceManager resources = mc.getResourceManager();
        PackResources vanilla = mc.getVanillaPackResources();
        Map<String, ItemStack> ingredients = allIngredients ? wynntilsIngredients() : Map.of();
        if (!WRITING.compareAndSet(false, true)) {
            ChatUtils.sendLocalMessage(
                    Component.literal("A retro report is already being written.")
                            .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        ChatUtils.sendLocalMessageNewBlock(
                Component.literal("Writing the retro report…").withStyle(ChatFormatting.GRAY));
        Thread thread =
                new Thread(
                        () -> write(report, resources, vanilla, ingredients, allIngredients),
                        "VetsMod-RetroReport");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    /** Builds and writes the report, then says where it went. On the report's own thread. */
    private static void write(
            RetroReport report,
            ResourceManager resources,
            PackResources vanilla,
            Map<String, ItemStack> ingredients,
            boolean allIngredients) {
        try {
            RetroAudit.Result result =
                    RetroAudit.run(report, resources, vanilla, ingredients, allIngredients);
            String name =
                    "report"
                            + (allIngredients ? "-ingredients-" : "-")
                            + LocalDateTime.now()
                                    .format(
                                            DateTimeFormatter.ofPattern(
                                                    "yyyyMMdd-HHmmss", Locale.ROOT))
                            + ".txt";
            Path file = DumpFiles.write("retro", name, result.text());
            VetsLogger.info("Retro report written to {}", file);
            ChatUtils.sendLocalMessage(
                    Component.literal("Retro report written to ")
                            .withStyle(ChatFormatting.GREEN)
                            .append(
                                    Component.literal(DumpFiles.shown(file))
                                            .withStyle(ChatFormatting.YELLOW))
                            .append(
                                    Component.literal(": " + result.summary())
                                            .withStyle(ChatFormatting.GRAY)));
        } catch (Throwable t) {
            VetsLogger.warn("Could not write the retro report", t);
            ChatUtils.sendLocalMessage(
                    Component.literal("Could not write the retro report: " + t)
                            .withStyle(ChatFormatting.RED));
        } finally {
            WRITING.set(false);
        }
    }

    /**
     * Wynntils' ingredients, each name with the stack of its icon, read on the render thread;
     * none, with a debug line, when Wynntils cannot give them.
     */
    private static Map<String, ItemStack> wynntilsIngredients() {
        Map<String, ItemStack> icons = new TreeMap<>();
        try {
            Models.Ingredient.getAllIngredientInfos()
                    .forEach(info -> icons.put(info.name(), info.material().itemStack()));
        } catch (RuntimeException | LinkageError e) {
            VetsLogger.debug("Retro report: Wynntils' ingredients could not be read", e);
        }
        return icons;
    }
}
