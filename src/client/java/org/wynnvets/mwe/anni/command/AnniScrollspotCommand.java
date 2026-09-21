package org.wynnvets.mwe.anni.command;

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
 * S5 handler for the scroll-spot host writes — {@code set}, {@code here},
 * {@code clear}.
 *
 * <p>⚠️ <b>The command path is {@code /wv debug tree anni scrollspot …}, not
 * {@code /wv anni scrollspot …}</b>, and this class registers nothing. The
 * only {@code "scrollspot"} literal in the tree is in
 * {@link org.wynnvets.mwe.anni.debug.AnniDebugCommands AnniDebugCommands},
 * which owns the brigadier node and delegates the three host writes here.
 * {@code vetsmod_mwe_anni.md} §"Aggressive mode" locked decision 6 states
 * this, and states it as a negative — the command is deliberately hidden from
 * the main tree because it is staff-only and rarely used. An earlier version
 * of this paragraph called the class an "S5 brigadier handler for
 * {@code /wv anni scrollspot set|here|clear}", which is a path that does not
 * resolve.</p>
 *
 * <ul>
 *   <li><b>set &lt;x&gt; &lt;y&gt; &lt;z&gt;</b> — pin the coord.</li>
 *   <li><b>here</b> — pin the player's current block-position.</li>
 *   <li><b>clear</b> — remove the pinned coord.</li>
 * </ul>
 *
 * <p>Those are the three real host writes. The debug node carries two more
 * leaves, {@code localinject} and {@code localclear}, which paint the marker
 * provider directly and never reach this class.</p>
 *
 * <h2>Three gates, and this class holds the weakest</h2>
 *
 * <p>Each delegating wrapper in {@code AnniDebugCommands} applies
 * {@code requireDebug} and then {@code requireStaffOrOrganiser} before calling
 * in here, so by the time {@link GuildStateManager#isAuthenticatedThisSession()}
 * is consulted two stronger gates have already passed. That guard is UX only
 * — without an auth frame the server has no session to read the
 * {@code mc_uuid} from — and the real authority is the fourth check, server
 * side: vets-anni's {@code anni-party-scrollspot} endpoint independently
 * verifies the actor is the party host.</p>
 *
 * <p><b>The three entry points do not share a guard order.</b> {@code set} and
 * {@code clear} reach the auth check first; {@code here} reads the player
 * position first and can answer "Can't read your position right now." to a
 * caller who would also have failed the auth check.</p>
 *
 * <p>On failure the {@link AnniScrollspotClient.Ack}'s {@code detail} string is
 * surfaced verbatim, so the user sees vets-anni's own reason ("only the party
 * host can set scroll_spot", "actor is not in a party for the active event").</p>
 */
public final class AnniScrollspotCommand {

    private AnniScrollspotCommand() {}

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
        reply("Pinning scroll spot at " + x + " " + y + " " + z + "…", ChatFormatting.GRAY);
        String coord = x + " " + y + " " + z;
        AnniScrollspotClient.set(x, y, z)
                .whenComplete((ack, throwable) -> renderAck(ack, throwable, coord));
        return 1;
    }

    private static boolean ensureAuthenticated() {
        if (GuildStateManager.isAuthenticatedThisSession()) return true;
        reply("Run ~vetsmod to authenticate before using /wv anni scrollspot.", ChatFormatting.RED);
        return false;
    }

    /**
     * Render the ack on the main thread.
     *
     * <p>⚠️ The opening {@code throwable != null || ack == null} arm is not
     * belt-and-braces: it is the reason this command still works. The client's
     * future completes <em>exceptionally</em> on its deadline rather than with
     * {@code null}, because {@code CompletableFuture#orTimeout} returns
     * {@code this} and the {@code .exceptionally} stage derived from it is
     * discarded. This site and its twin in the sibling command are the two
     * consumers written against the real behaviour, which is what settles
     * which side of the contradiction is wrong. Filed as
     * {@code anni-ack-clients-ortimeout-completes-exceptionally}; whichever way
     * that is resolved, this arm moves with it — if the contract becomes
     * "completes with null", the {@code throwable} half becomes dead.</p>
     */
    private static void renderAck(AnniScrollspotClient.Ack ack, Throwable throwable, String coord) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(
                () -> {
                    if (throwable != null || ack == null) {
                        reply("Scroll spot request failed (no response).", ChatFormatting.RED);
                        VetsLogger.debug(
                                "scrollspot ack threw or null: {}",
                                throwable != null ? throwable.getMessage() : "null");
                        return;
                    }
                    if (ack.ok()) {
                        String body =
                                coord != null
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
