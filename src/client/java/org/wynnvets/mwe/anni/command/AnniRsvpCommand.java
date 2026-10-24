package org.wynnvets.mwe.anni.command;

import com.mojang.brigadier.context.CommandContext;
import com.wynntils.utils.mc.McUtils;
import java.util.Locale;
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
 * S6 handler for {@code /wv anni rsvp <hard|soft|revoke>}.
 *
 * <p>Three subcommands, single shape: shoot the inbound frame, await the
 * ack, render success or error in chat. Lights up the rsvpUpgradePrompt
 * {@code [Hard]} / {@code [Soft]} buttons in
 * {@link org.wynnvets.mwe.anni.render.AnniStatusSections AnniStatusSections} (their
 * {@code suggestCommand} strings target this command verbatim).</p>
 *
 * <p><b>Registered twice.</b> The user-facing node is
 * {@link org.wynnvets.commands.CommandRegistry CommandRegistry}'s {@code /wv anni rsvp}; a second, debug-only
 * mirror hangs off {@code /wv debug tree anni rsvp} in
 * {@link org.wynnvets.debug.anni.AnniDebugCommands AnniDebugCommands}.
 * Both call straight in here. Contrast
 * {@link AnniScrollspotCommand}, the other class in this package, which has
 * <em>only</em> the debug registration — the two are not symmetric and the
 * package's shape should not be inferred from either one alone.</p>
 *
 * <p>Client-side guard: {@link GuildStateManager#isAuthenticatedThisSession()}
 * must be true. It is false until an ok auth ack arrives, and again after an
 * auth failure, a newly stored {@code /unlock} key or leaving the game server.
 * It does not follow the inbound socket, on which temp-server keeps the session
 * it reads the {@code mc_uuid} from, so it stays true across a socket drop.
 * UX-only — the server-side trust chain is the actual gate.</p>
 *
 * <p>Unauthenticated message uses spec wording per the S6 plan:
 * <em>"You aren't authenticated (~vetsmod on vetscord). For now, use the discord \rsvp on discord (or use ~vetsmod)."</em> Surfaces both
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
        AnniRsvpClient.send(notice).thenAccept(ack -> renderAck(notice, ack));
        return 1;
    }

    private static boolean ensureAuthenticated() {
        if (GuildStateManager.isAuthenticatedThisSession()) return true;
        reply(
                "You aren't authenticated (~vetsmod on vetscord). For now, use the discord \\rsvp on discord (or use ~vetsmod).",
                ChatFormatting.RED);
        return false;
    }

    /**
     * Render the ack on the main thread.
     *
     * <p>The client's future never completes exceptionally: it resolves
     * {@code null} when its deadline passes with no reply, which the opening
     * arm reports as a failed request.</p>
     */
    private static void renderAck(String notice, AnniRsvpClient.Ack ack) {
        Minecraft mc = McUtils.mc();
        if (mc == null) return;
        mc.execute(
                () -> {
                    if (ack == null) {
                        reply("Vets API error: RSVP request failed.", ChatFormatting.RED);
                        VetsLogger.debug("rsvp ack null: no reply before the deadline");
                        return;
                    }
                    if (ack.ok()) {
                        ChatUtils.sendLocalMessage(successComponent(notice, ack));
                        // A revoke of nothing still pulls: it usually means the cached snapshot
                        // shows an RSVP the server no longer has (withdrawn on Discord).
                        // Outside the T-2h hot window the push poller runs at 5-min
                        // cadence, so without a fire-and-forget refresh here the
                        // cached snapshot would still report the pre-RSVP state
                        // (e.g. a yellow "RSVP-NONE") for up to 5 minutes — confusing
                        // immediately after the user committed. A player whose outbound
                        // socket is not authenticated gets no anni_state push at all,
                        // so for them the pre-RSVP state would last until the next
                        // pull. The query() pull
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

    /**
     * Build the success line.
     *
     * <p>Three shapes, not one. A {@code revoke} whose ack says
     * {@code nothing_to_withdraw} renders a flat gray "You had no active RSVP to withdraw."
     * (the Discord cog's own wording); an older server sends no {@code result}, so its
     * revoke keeps the line below. {@code hard} and {@code soft} render
     * "You have &lt;TOKEN&gt; RSVP'd for the next anni.", with the token coloured
     * via {@link AnniHoverBuilder#noticeColor(String)} and the rest of the line
     * gray. {@code revoke} returns early with a flat gray
     * "Your RSVP has been withdrawn." and <b>no coloured token at all</b> — so
     * {@code noticeColor} is never asked about {@code "revoke"}, which is
     * correct, because {@code revoke} is a verb this command sends and not a
     * notice state any snapshot reports.</p>
     */
    // Package-private for unit tests. See AnniRsvpCommandTest.
    static MutableComponent successComponent(String notice, AnniRsvpClient.Ack ack) {
        if (isNothingToWithdraw(notice, ack)) {
            return Component.literal("You had no active RSVP to withdraw.")
                    .withStyle(ChatFormatting.GRAY);
        }
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

    /** A revoke the server answered with {@code result == "nothing_to_withdraw"}. */
    private static boolean isNothingToWithdraw(String notice, AnniRsvpClient.Ack ack) {
        return "revoke".equals(notice) && ack != null && "nothing_to_withdraw".equals(ack.result());
    }

    private static String label(String notice) {
        if ("hard".equals(notice)) return "HARD";
        if ("soft".equals(notice)) return "SOFT";
        return notice == null ? "?" : notice.toUpperCase(Locale.ROOT);
    }

    private static void reply(String text, ChatFormatting style) {
        ChatUtils.sendLocalMessage(Component.literal(text).withStyle(style));
    }
}
