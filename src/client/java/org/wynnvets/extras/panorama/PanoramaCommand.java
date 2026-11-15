package org.wynnvets.extras.panorama;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.rendering.panorama.PanoramaDownloads;
import org.wynnvets.retro.RetroModeManager;

/**
 * Builds {@code /wv extras panorama}: typed bare it says whether the Vetlas panorama is on, and
 * {@code on} and {@code off} turn it on and off ({@link ExtrasPanorama}). See
 * {@code vetsmod_extras.md}.
 */
public final class PanoramaCommand {

    private PanoramaCommand() {}

    /**
     * Builds the {@code panorama} literal, with {@code on} and {@code off}. Every node runs when
     * typed bare, so none of them goes to the server.
     */
    public static LiteralArgumentBuilder<FabricClientCommandSource> buildCommandTree() {
        return ClientCommandManager.literal("panorama")
                .executes(ctx -> answer(ExtrasPanorama.isOn()))
                .then(ClientCommandManager.literal("on").executes(ctx -> set(true)))
                .then(ClientCommandManager.literal("off").executes(ctx -> set(false)));
    }

    private static int set(boolean on) {
        ExtrasPanorama.setOn(on);
        return answer(on);
    }

    private static int answer(boolean on) {
        ChatUtils.sendLocalMessageNewBlock(
                line(
                        on,
                        RetroModeManager.isEnabled(),
                        ExtrasPanorama.hasFailed(),
                        ExtrasPanorama.downloadState()));
        return 1;
    }

    /**
     * The line the command answers with: red when Vetlas failed this session, yellow when its
     * download, or reading its kept faces, did, green otherwise.
     *
     * @param on whether the egg is on
     * @param retro whether retro is on, which the egg gives way to
     * @param failed whether Vetlas failed to load or draw this session
     * @param download where Vetlas's download stands
     * @return the line
     */
    // Package-private for unit tests. See PanoramaCommandTest.
    static Component line(
            boolean on, boolean retro, boolean failed, PanoramaDownloads.State download) {
        if (!on) return line(ChatFormatting.GREEN, "The Vetlas panorama is off.");
        if (failed) {
            return line(
                    ChatFormatting.RED,
                    "The Vetlas panorama is on, but could not be drawn: it returns after a"
                            + " restart.");
        }
        if (download == PanoramaDownloads.State.FAILED) {
            return line(
                    ChatFormatting.YELLOW,
                    "The Vetlas panorama is on, but could not be downloaded or read: the next"
                            + " start-up tries again.");
        }
        if (retro) {
            return line(
                    ChatFormatting.GREEN,
                    "The Vetlas panorama is on, and shows once retro is off.");
        }
        return line(
                ChatFormatting.GREEN,
                download == PanoramaDownloads.State.HERE
                        ? "The Vetlas panorama is on: it shows behind the main menu."
                        : "The Vetlas panorama is on: it shows behind the main menu once it is"
                                + " downloaded.");
    }

    private static Component line(ChatFormatting colour, String text) {
        return Component.literal(text).withStyle(colour);
    }
}
