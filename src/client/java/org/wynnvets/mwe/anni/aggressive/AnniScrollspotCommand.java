package org.wynnvets.mwe.anni.aggressive;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.network.AnniScrollspotClient;

/**
 * S5 brigadier handler for {@code /wv anni scrollspot set|here|clear}.
 *
 * <p>Three subcommands, all single-step: shoot the inbound frame, await
 * the ack, render success or error in chat.</p>
 *
 * <ul>
 *   <li><b>set &lt;x&gt; &lt;y&gt; &lt;z&gt;</b> — pin the coord.</li>
 *   <li><b>here</b> — pin the player's current block-position.</li>
 *   <li><b>clear</b> — remove the pinned coord.</li>
 * </ul>
 *
 * <p>Client-side guard: {@link GuildStateManager#isAuthenticatedThisSession()}
 * must be true (we never sent an auth frame otherwise so the server has
 * no session to read the {@code mc_uuid} from). This is UX only — the
 * server independently verifies the actor is the party host via
 * vets-anni's {@code anni-party-scrollspot} endpoint.</p>
 *
 * <p>The ack future resolves with {@link AnniScrollspotClient.Ack}: on
 * failure, the {@code detail} string is surfaced verbatim so the user
 * sees vets-anni's reason ("only the party host can set scroll_spot",
 * "actor is not in a party for the active event", etc.).</p>
 */
public final class AnniScrollspotCommand {

    private AnniScrollspotCommand() {
    }

    public static int set(CommandContext<FabricClientCommandSource> ctx) {
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        return dispatchSet(x, y, z);
    }

    public static int here(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc != null ? mc.player : null;
        if (player == null) {
            reply("Can't read your position right now.", ChatFormatting.RED);
            return 0;
        }
        BlockPos pos = player.blockPosition();
        return dispatchSet(pos.getX(), pos.getY(), pos.getZ());
    }

    public static int clear(CommandContext<FabricClientCommandSource> ctx) {
        if (!ensureAuthenticated()) return 0;
        reply("Clearing scroll spot…", ChatFormatting.GRAY);
        AnniScrollspotClient.clear()
                .whenComplete((ack, throwable) -> renderAck(ack, throwable, null));
        return 1;
    }

    // ── Internals ──────────────────────────────────────────────────────

    private static int dispatchSet(int x, int y, int z) {
        if (!ensureAuthenticated()) return 0;
        reply(
                "Pinning scroll spot at " + x + " " + y + " " + z + "…",
                ChatFormatting.GRAY);
        String coord = x + " " + y + " " + z;
        AnniScrollspotClient.set(x, y, z)
                .whenComplete((ack, throwable) -> renderAck(ack, throwable, coord));
        return 1;
    }

    private static boolean ensureAuthenticated() {
        if (GuildStateManager.isAuthenticatedThisSession()) return true;
        reply(
                "Run ~vetsmod to authenticate before using /wv anni scrollspot.",
                ChatFormatting.RED);
        return false;
    }

    private static void renderAck(AnniScrollspotClient.Ack ack, Throwable throwable, String coord) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (throwable != null || ack == null) {
                reply("Scroll spot request failed (no response).", ChatFormatting.RED);
                VetsLogger.debug(
                        "scrollspot ack threw or null: {}",
                        throwable != null ? throwable.getMessage() : "null");
                return;
            }
            if (ack.ok()) {
                String body = coord != null
                        ? "Scroll spot set to " + coord + "."
                        : "Scroll spot cleared.";
                reply(body, ChatFormatting.GREEN);
            } else {
                String detail = ack.detail() != null ? ack.detail() : "unknown error";
                reply("Scroll spot rejected: " + detail, ChatFormatting.RED);
            }
        });
    }

    private static void reply(String text, ChatFormatting style) {
        ChatUtils.sendLocalMessage(Component.literal(text).withStyle(style));
    }
}
