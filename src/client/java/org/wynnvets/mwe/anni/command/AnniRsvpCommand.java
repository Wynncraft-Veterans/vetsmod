package org.wynnvets.mwe.anni.command;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.network.AnniQueryClient;
import org.wynnvets.mwe.anni.network.AnniRsvpClient;
import org.wynnvets.mwe.anni.render.AnniHoverBuilder;

/**
 * S6 brigadier handler for {@code /wv anni rsvp <hard|soft|revoke>}.
 *
 * <p>Three subcommands, single shape: shoot the inbound frame, await the
 * ack, render success or error in chat. Lights up the rsvpUpgradePrompt
 * {@code [Hard]} / {@code [Soft]} buttons in
 * {@link org.wynnvets.mwe.anni.render.AnniCommandRenderer} (their
 * {@code suggestCommand} strings target this command verbatim).</p>
 *
 * <p>Client-side guard: {@link GuildStateManager#isAuthenticatedThisSession()}
 * must be true (no auth frame ever sent otherwise, so temp-server has no
 * session to read the {@code mc_uuid} from). UX-only — the server-side
 * trust chain is the actual gate.</p>
 *
 * <p>Unauthenticated message uses spec wording per the S6 plan:
 * <em>"Use \rsvp on discord — or run ~vetsmod first."</em> Surfaces both
 * the Discord fallback and the in-game link path so users who can't or
 * won't run {@code ~vetsmod} still know where to go.</p>
 */
public final class AnniRsvpCommand {

    private AnniRsvpCommand() {}

    public static int hard(CommandContext<FabricClientCommandSource> ctx) {
        return dispatch("hard");
    }

    public static int soft(CommandContext<FabricClientCommandSource> ctx) {
        return dispatch("soft");
    }

    public static int revoke(CommandContext<FabricClientCommandSource> ctx) {
        return dispatch("revoke");
    }

    // ── Internals ──────────────────────────────────────────────────────

    private static int dispatch(String notice) {
        if (!ensureAuthenticated()) return 0;
        reply("Sending " + label(notice) + " RSVP…", ChatFormatting.GRAY);
        AnniRsvpClient.send(notice)
                .whenComplete((ack, throwable) -> renderAck(notice, ack, throwable));
        return 1;
    }

    private static boolean ensureAuthenticated() {
        if (GuildStateManager.isAuthenticatedThisSession()) return true;
        reply("Use \\rsvp on discord — or run ~vetsmod first.", ChatFormatting.RED);
        return false;
    }

    private static void renderAck(String notice, AnniRsvpClient.Ack ack, Throwable throwable) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(
                () -> {
                    if (throwable != null || ack == null) {
                        reply("RSVP request failed (no response).", ChatFormatting.RED);
                        VetsLogger.debug(
                                "rsvp ack threw or null: {}",
                                throwable != null ? throwable.getMessage() : "null");
                        return;
                    }
                    if (ack.ok()) {
                        ChatUtils.sendLocalMessage(successComponent(notice));
                        // Outside the T-2h hot window the push poller runs at 5-min
                        // cadence, so without a fire-and-forget refresh here the
                        // cached snapshot would still report the pre-RSVP state
                        // (e.g. "EARLY WALK-IN") for up to 5 minutes — confusing
                        // immediately after the user committed. The query() pull
                        // hits temp-server's anni_query handler, which serves a
                        // cached snapshot if <15s old or re-fetches from vets-anni
                        // synchronously. Either way the new RSVP shows up on the
                        // very next `/wv anni` / boss bar tick.
                        AnniQueryClient.query();
                    } else {
                        String detail = ack.detail() != null ? ack.detail() : "unknown error";
                        reply("RSVP rejected: " + detail, ChatFormatting.RED);
                    }
                });
    }

    /** Build the success line: notice token coloured via {@link
     *  AnniHoverBuilder#noticeColor(String)}; rest of the line gray. */
    private static MutableComponent successComponent(String notice) {
        if ("revoke".equals(notice)) {
            return Component.literal("Your RSVP has been withdrawn.")
                    .withStyle(ChatFormatting.GRAY);
        }
        MutableComponent token =
                Component.literal(label(notice)).withStyle(AnniHoverBuilder.noticeColor(notice));
        return Component.literal("You have ")
                .withStyle(ChatFormatting.GRAY)
                .append(token)
                .append(
                        Component.literal(" RSVP'd for the next anni.")
                                .withStyle(ChatFormatting.GRAY));
    }

    private static String label(String notice) {
        if ("hard".equals(notice)) return "HARD";
        if ("soft".equals(notice)) return "SOFT";
        return notice == null ? "?" : notice.toUpperCase();
    }

    private static void reply(String text, ChatFormatting style) {
        ChatUtils.sendLocalMessage(Component.literal(text).withStyle(style));
    }
}
