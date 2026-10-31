package org.wynnvets.retro;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.net.URI;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.retro.manifest.PartSections;
import org.wynnvets.retro.manifest.RetroPart;

/**
 * Builds the {@code /wv retro} client command, which turns retro textures on and off and says
 * what state they are in. It has no gate: the textures are the player's own choice, and are built
 * from their own game files and the art the jar bundles.
 *
 * <ul>
 *   <li>{@code /wv retro on} and {@code /wv retro off} go through
 *       {@link RetroModeManager#setEnabled}, which reloads resources when that changes what is
 *       loaded. An answer that retro is on ends with where its textures come from, on hover.</li>
 *   <li>A bare {@code /wv retro} says whether it is on, why it stopped if it did, and the parts
 *       that are off. {@code /wv debug trigger retroDump} has the rest.</li>
 *   <li>{@code /wv retro parts} lists the parts; {@code /wv retro parts <part|all> <on|off>} and
 *       {@code /wv retro only <part>} go through {@link RetroModeManager#setPartsOff}, which also
 *       reloads resources when that changes what is loaded.</li>
 * </ul>
 */
public final class RetroCommands {

    /** What {@code parts} takes for every part at once. */
    private static final String ALL = "all";

    private static final String PART_ARGUMENT = "part";

    /** The README's credits section, which {@code on}'s credits open and which names credits.txt. */
    private static final URI CREDITS_PAGE =
            URI.create("https://github.com/Wynncraft-Veterans/vetsmod#credits");

    private static final String CREDITS_HOVER =
            "This feature relies on present and historical textures from wynntils.net,"
                    + " wynncraft.com, mojang.com and vanillatweaks.net. Licenses differ where"
                    + " applicable. For more info, see the Credits section of vetsmod's README:"
                    + " click to open it.";

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_PARTS =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (RetroPart part : RetroPart.values()) {
                    if (part.yamlName().startsWith(partial)) builder.suggest(part.yamlName());
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_PARTS_OR_ALL =
            (ctx, builder) -> {
                if (ALL.startsWith(builder.getRemaining().toLowerCase(Locale.ROOT))) {
                    builder.suggest(ALL);
                }
                return SUGGEST_PARTS.getSuggestions(ctx, builder);
            };

    private RetroCommands() {}

    /**
     * Builds the {@code retro} literal.
     *
     * @return the node, for {@code /wv} to mount
     */
    public static LiteralArgumentBuilder<FabricClientCommandSource> buildCommandTree() {
        return ClientCommandManager.literal("retro")
                .executes(RetroCommands::state)
                .then(ClientCommandManager.literal("on").executes(ctx -> toggle(true)))
                .then(ClientCommandManager.literal("off").executes(ctx -> toggle(false)))
                .then(
                        ClientCommandManager.literal("parts")
                                .executes(RetroCommands::parts)
                                .then(
                                        ClientCommandManager.argument(
                                                        PART_ARGUMENT, StringArgumentType.word())
                                                .suggests(SUGGEST_PARTS_OR_ALL)
                                                .then(
                                                        ClientCommandManager.literal("on")
                                                                .executes(
                                                                        ctx -> setPart(ctx, true)))
                                                .then(
                                                        ClientCommandManager.literal("off")
                                                                .executes(
                                                                        ctx ->
                                                                                setPart(
                                                                                        ctx,
                                                                                        false)))))
                .then(
                        ClientCommandManager.literal("only")
                                .then(
                                        ClientCommandManager.argument(
                                                        PART_ARGUMENT, StringArgumentType.word())
                                                .suggests(SUGGEST_PARTS)
                                                .executes(RetroCommands::only)));
    }

    private static int toggle(boolean enabled) {
        RetroModeManager.Outcome outcome = RetroModeManager.setEnabled(enabled);
        ChatUtils.sendLocalMessageNewBlock(toggleLine(enabled, outcome));
        return 1;
    }

    /** The one line {@code on} or {@code off} answers with. */
    // Package-private for unit tests. See RetroCommandsTest.
    static Component toggleLine(boolean enabled, RetroModeManager.Outcome outcome) {
        Component line = outcomeLine(enabled, outcome);
        if (!enabled || outcome == RetroModeManager.Outcome.LATCHED) return line;
        return Component.empty()
                .append(line)
                .append(
                        line(ChatFormatting.GRAY, " (Hover for credits)")
                                .withStyle(
                                        style ->
                                                style.withHoverEvent(
                                                                new HoverEvent.ShowText(
                                                                        line(
                                                                                ChatFormatting.GRAY,
                                                                                CREDITS_HOVER)))
                                                        .withClickEvent(
                                                                new ClickEvent.OpenUrl(
                                                                        CREDITS_PAGE))));
    }

    private static Component outcomeLine(boolean enabled, RetroModeManager.Outcome outcome) {
        return switch (outcome) {
            case RELOADING ->
                    line(
                            ChatFormatting.GREEN,
                            "Retro textures "
                                    + (enabled ? "on" : "off")
                                    + ". Reloading resources…");
            case UNCHANGED -> stateLine(enabled);
            case NO_WYNNCRAFT_PACK ->
                    line(
                            ChatFormatting.GREEN,
                            "Retro textures on. They show once Wynncraft's resource pack has"
                                    + " loaded.");
            case LATCHED -> latchedLine();
        };
    }

    private static Component stateLine(boolean enabled) {
        return line(ChatFormatting.GREEN, "Retro textures are " + (enabled ? "on." : "off."));
    }

    private static Component latchedLine() {
        return line(
                ChatFormatting.RED,
                "Retro textures are on, but stopped until the game restarts: "
                        + RetroModeManager.latchReason()
                        + ".");
    }

    /** A bare {@code retro}: whether it is on, why it stopped if it did, the parts off. */
    private static int state(CommandContext<FabricClientCommandSource> ctx) {
        boolean enabled = RetroModeManager.isEnabled();
        MutableComponent msg = Component.empty();
        msg.append(
                enabled && RetroModeManager.latchReason() != null
                        ? latchedLine()
                        : stateLine(enabled));
        Set<RetroPart> off = RetroPart.allBut(RetroModeManager.partsOn());
        if (!off.isEmpty()) {
            msg.append(line(ChatFormatting.GRAY, "\nParts off: " + RetroPart.names(off, ", ")));
        }
        ChatUtils.sendLocalMessageNewBlock(msg);
        return 1;
    }

    private static int parts(CommandContext<FabricClientCommandSource> ctx) {
        Set<RetroPart> on = RetroModeManager.partsOn();
        MutableComponent msg = Component.empty();
        msg.append(line(ChatFormatting.GOLD, "Retro parts"));
        if (!RetroModeManager.isEnabled()) {
            msg.append(line(ChatFormatting.GRAY, " (retro itself is off)"));
        }
        for (PartSections.Part row : RetroModeManager.manifest().parts().parts().values()) {
            msg.append(line(ChatFormatting.YELLOW, "\n" + row.part().yamlName() + " "));
            msg.append(
                    on.contains(row.part())
                            ? line(ChatFormatting.GREEN, "on")
                            : line(ChatFormatting.GRAY, "off"));
            msg.append(line(ChatFormatting.GRAY, " - " + row.about()));
        }
        if (!RetroPart.allBut(on).isEmpty()) {
            msg.append(line(ChatFormatting.GRAY, "\n/wv retro parts all on turns them all on."));
        }
        ChatUtils.sendLocalMessageNewBlock(msg);
        return 1;
    }

    /** {@code parts <part|all> <on|off>}. */
    private static int setPart(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        String name = StringArgumentType.getString(ctx, PART_ARGUMENT);
        String state = on ? "on" : "off";
        if (name.equalsIgnoreCase(ALL)) {
            return answer(on ? Set.of() : RetroPart.all(), "Every retro part " + state);
        }
        RetroPart part = RetroPart.fromName(name);
        if (part == null) return unknownPart(name);
        return answer(
                offAfter(RetroModeManager.partsOn(), part, on),
                "Retro part " + part.yamlName() + " " + state);
    }

    /**
     * The parts that are off once one part is turned on or off.
     *
     * @param partsOn the parts that are on now
     * @param part the part
     * @param on whether it goes on
     * @return the parts off then
     */
    // Package-private for unit tests. See RetroCommandsTest.
    static Set<RetroPart> offAfter(Set<RetroPart> partsOn, RetroPart part, boolean on) {
        Set<RetroPart> off = EnumSet.noneOf(RetroPart.class);
        off.addAll(RetroPart.allBut(partsOn));
        if (on) {
            off.remove(part);
        } else {
            off.add(part);
        }
        return off;
    }

    /** {@code only <part>}. */
    private static int only(CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, PART_ARGUMENT);
        RetroPart part = RetroPart.fromName(name);
        if (part == null) return unknownPart(name);
        return answer(RetroPart.allBut(Set.of(part)), "Only retro part " + part.yamlName() + " on");
    }

    private static int unknownPart(String name) {
        ChatUtils.sendLocalMessageNewBlock(
                line(
                        ChatFormatting.RED,
                        "No retro part " + name + ". /wv retro parts lists them."));
        return 0;
    }

    /** Stores the parts turned off and answers with one line by outcome. */
    private static int answer(Set<RetroPart> off, String done) {
        RetroModeManager.Outcome outcome = RetroModeManager.setPartsOff(off);
        ChatUtils.sendLocalMessageNewBlock(
                outcome == RetroModeManager.Outcome.LATCHED
                        ? latchedLine()
                        : line(
                                ChatFormatting.GREEN,
                                partsText(done, outcome, RetroModeManager.isEnabled())));
        return 1;
    }

    /**
     * The line a change of parts answers with, but for a stopped session's.
     *
     * @param done what was done, without a full stop
     * @param outcome what happened to the resources
     * @param enabled whether retro is on, without which no part shows
     * @return the line's text
     */
    // Package-private for unit tests. See RetroCommandsTest.
    static String partsText(String done, RetroModeManager.Outcome outcome, boolean enabled) {
        String text =
                switch (outcome) {
                    case RELOADING -> done + ". Reloading resources…";
                    case NO_WYNNCRAFT_PACK ->
                            done + ". It shows once Wynncraft's resource pack has loaded.";
                    case UNCHANGED, LATCHED -> done + ".";
                };
        return enabled ? text : text + " Retro itself is off: /wv retro on shows it.";
    }

    private static MutableComponent line(ChatFormatting colour, String text) {
        return Component.literal(text).withStyle(colour);
    }
}
