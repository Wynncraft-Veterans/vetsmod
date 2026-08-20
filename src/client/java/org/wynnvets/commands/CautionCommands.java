package org.wynnvets.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.Prepend;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

/**
 * Handlers for the in-game caution / warning / eject commands. These
 * are NOT registered with Brigadier (they share the {@code /v}-style
 * intercept path in {@link GuildChatDispatcher}, which catches the
 * raw chat-command before it reaches the server) -- so this class
 * only owns the body of each command, not the parsing or dispatch.
 *
 * <p>Wire shape and threshold logic live server-side in
 * {@code app.chat.inbound._handle_staff_commit} (preflight) and
 * {@code dazebot.lib.staff_actions.record_action} (commit). This
 * client only sends frames, displays results, and on a successful
 * eject dispatches the right {@code /gu kick} or
 * {@code /gu rank ... recruit} command.</p>
 *
 * <p>The staff gate everywhere here is
 * {@link GuildStateManager#isConfirmedStaff()} -- the server-confirmed
 * roster check resolved at WS auth time. The local {@code /gu rank}
 * cache is NOT consulted, because /eject dispatches real in-game
 * commands and a spoofed staff bit must not be load-bearing.</p>
 */
public final class CautionCommands {

    private CautionCommands() {}

    // ── Top-level command bodies ────────────────────────────────────────

    /**
     * {@code /caution <username> [message]} -- preflight + commit. The
     * server checks the target's running point total; if a +1 caution
     * would push them over the warning (or eject) threshold and the
     * staff member did not supply an explicit confirmation, the response
     * is {@code "would_trigger"} and we render a clickable prompt
     * instead of committing -- with any typed {@code message} prefilled
     * so the staff can confirm with one click.
     *
     * <p>If the staff supplies a {@code message} and the caution does
     * NOT cross a threshold, the server commits cleanly with the message
     * attached -- same shape as a direct {@code /caution-go}.</p>
     *
     * @param args the raw argument string after the command literal
     *             (e.g. {@code "Wenweia"} or
     *             {@code "Wenweia harassed someone in #general"})
     */
    public static void runCaution(String args) {
        ParsedArgs parsed = parseTwoPart(args);
        if (parsed.target.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Usage: /caution <username> [message]")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        JsonObject fields = new JsonObject();
        fields.addProperty("target_username", parsed.target);
        // No `confirm` -> server preflights. If a message was typed we
        // forward it; the server commits with it on the clean-threshold
        // path or returns would_trigger and the renderer below carries
        // the message into the prompt.
        if (!parsed.rest.isEmpty()) fields.addProperty("message", parsed.rest);
        V1ApiManager.sendStaffActionFrame(
                "caution_add",
                fields,
                ack ->
                        Minecraft.getInstance()
                                .execute(
                                        () ->
                                                renderCautionPreflightOrCommit(
                                                        parsed.target, parsed.rest, ack)));
    }

    /**
     * {@code /caution-go <username> [message]} -- explicit confirm path.
     * Always commits (no preflight). Used by the clickable confirmation
     * suggestions that {@link #renderCautionPreflightOrCommit} prints
     * when the preflight returns {@code would_trigger}, and whenever
     * staff want to skip the preflight.
     */
    public static void runCautionGo(String args) {
        ParsedArgs parsed = parseTwoPart(args);
        if (parsed.target.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Usage: /caution-go <username> [warning message]")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        JsonObject fields = new JsonObject();
        fields.addProperty("target_username", parsed.target);
        fields.addProperty("confirm", true);
        if (!parsed.rest.isEmpty()) fields.addProperty("message", parsed.rest);

        V1ApiManager.sendStaffActionFrame(
                "caution_add",
                fields,
                ack ->
                        Minecraft.getInstance()
                                .execute(
                                        () ->
                                                renderCommitResult(
                                                        "caution",
                                                        parsed.target,
                                                        parsed.rest,
                                                        ack)));
    }

    /**
     * {@code /warn <username> [message]} -- formal warning. Always
     * commits, always DMs the target.
     */
    public static void runWarn(String args) {
        ParsedArgs parsed = parseTwoPart(args);
        if (parsed.target.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Usage: /warn <username> [message]")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        JsonObject fields = new JsonObject();
        fields.addProperty("target_username", parsed.target);
        if (!parsed.rest.isEmpty()) fields.addProperty("message", parsed.rest);

        V1ApiManager.sendStaffActionFrame(
                "warn_add",
                fields,
                ack ->
                        Minecraft.getInstance()
                                .execute(
                                        () ->
                                                renderCommitResult(
                                                        "warning",
                                                        parsed.target,
                                                        parsed.rest,
                                                        ack)));
    }

    /**
     * {@code /eject <username> <message>} -- ban-threshold trip. Sends the
     * {@code eject_add} staff-action frame; the server is what adds the 6
     * caution points, DMs the target and blocklists. When the ack reports
     * {@code triggered="eject"}, {@link #dispatchEjectAction} sends
     * whichever of {@code /gu kick} or {@code /gu rank ... recruit} the
     * ack's {@code suggested_dispatch} field names, over the local game
     * connection. The client never evaluates the actor's rank -- that
     * choice is made server-side.
     *
     * <p>That dispatch is not exclusive to this command: it lives in the
     * shared {@code renderCommitResult}, so a {@code /caution} or
     * {@code /warn} whose commit crosses the ban threshold reports
     * {@code triggered="eject"} too and dispatches identically.</p>
     *
     * <p>Message is required (the user said so: ejects always carry a
     * formal reason).</p>
     */
    public static void runEject(String args) {
        ParsedArgs parsed = parseTwoPart(args);
        if (parsed.target.isEmpty() || parsed.rest.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Usage: /eject <username> <message>")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        JsonObject fields = new JsonObject();
        fields.addProperty("target_username", parsed.target);
        fields.addProperty("message", parsed.rest);

        V1ApiManager.sendStaffActionFrame(
                "eject_add",
                fields,
                ack ->
                        Minecraft.getInstance()
                                .execute(
                                        () ->
                                                renderCommitResult(
                                                        "eject", parsed.target, parsed.rest, ack)));
    }

    /**
     * {@code /wv check <username>} addendum. The legacy implementation
     * (in {@link GuildChatDispatcher#handleWvCheck} and
     * {@link CommandRegistry#check}) runs a {@link
     * org.wynnvets.fetcher.ondemand.UserInfoFetcher} lookup. This method
     * piggybacks a caution-history fetch onto the same command so
     * {@code /wv check Foo} now also displays Foo's running point total
     * and recent staff-action entries -- the same data the Discord
     * {@code ~warnings} command shows.
     */
    public static void runCheckCautions(String username) {
        if (username == null || username.isBlank()) return;
        // Silent gate: this method is also called as an addendum to the
        // legacy /wv check (which has its own client-side staff gate),
        // so we don't want to print a second "you must be staff" line
        // for users who passed the legacy gate but haven't WS-auth'd as
        // staff yet. We just skip the augmented readout entirely.
        if (!GuildStateManager.isConfirmedStaff()) return;

        JsonObject fields = new JsonObject();
        fields.addProperty("target_username", username);
        V1ApiManager.sendStaffActionFrame(
                "caution_check",
                fields,
                ack -> Minecraft.getInstance().execute(() -> renderCautionHistory(username, ack)));
    }

    // ── Response rendering ──────────────────────────────────────────────

    private static void renderCautionPreflightOrCommit(
            String target, String requestedMessage, JsonObject ack) {
        String status = optString(ack, "status", "error");
        if ("would_trigger".equals(status)) {
            String trigger = optString(ack, "trigger", "warning");
            int currentPoints = optInt(ack, "current_points", 0);
            String resolved = optString(ack, "target_username", target);

            MutableComponent line =
                    Component.literal("⚠ ")
                            .withStyle(ChatFormatting.GOLD)
                            .append(
                                    Component.literal("Adding this caution would trigger a formal ")
                                            .withStyle(ChatFormatting.YELLOW))
                            .append(
                                    Component.literal(trigger.toUpperCase())
                                            .withStyle(ChatFormatting.RED))
                            .append(Component.literal(" against ").withStyle(ChatFormatting.YELLOW))
                            .append(Component.literal(resolved).withStyle(ChatFormatting.AQUA))
                            .append(
                                    Component.literal(" (currently " + currentPoints + " pts).")
                                            .withStyle(ChatFormatting.GRAY));
            ChatUtils.sendLocalMessage(line);

            // If staff already typed a reason, carry it into both buttons so
            // the click commits (or pre-fills the chat box) with the reason
            // attached -- the server dropped the message when it returned
            // would_trigger, so the only place it still lives is here.
            boolean haveReason = !requestedMessage.isEmpty();
            String confirmCmd =
                    haveReason
                            ? "/caution-go " + resolved + " " + requestedMessage
                            : "/caution-go " + resolved;
            String editCmd =
                    haveReason
                            ? "/caution-go " + resolved + " " + requestedMessage
                            : "/caution-go " + resolved + " ";
            MutableComponent prompt =
                    Component.literal("  ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(
                                    clickRun(
                                            haveReason
                                                    ? "[Confirm and send]"
                                                    : "[Send generic warning]",
                                            confirmCmd,
                                            haveReason
                                                    ? "Click to commit this caution with your typed reason."
                                                    : "Click to commit this caution and send a generic warning DM.",
                                            ChatFormatting.GREEN))
                            .append(Component.literal("  ").withStyle(ChatFormatting.GRAY))
                            .append(
                                    clickSuggest(
                                            haveReason
                                                    ? "[Edit reason]"
                                                    : "[Type a custom warning]",
                                            editCmd,
                                            haveReason
                                                    ? "Click to edit your reason before sending."
                                                    : "Click to fill in a /caution-go command, then type your warning text.",
                                            ChatFormatting.AQUA));
            ChatUtils.sendLocalMessage(prompt);
            return;
        }
        // No threshold crossed -> server committed the caution. Render
        // the same way as an explicit /caution-go, including the message
        // the staff actually typed (if any) so the local readout matches
        // what landed in the audit row.
        renderCommitResult("caution", target, requestedMessage, ack);
    }

    private static void renderCommitResult(
            String requestedKind, String target, String requestedMessage, JsonObject ack) {
        String status = optString(ack, "status", "error");
        if (!"ok".equals(status)) {
            String detail = optString(ack, "detail", "unknown error");
            ChatUtils.sendLocalMessage(
                    Component.literal("[" + requestedKind + "] failed: " + detail)
                            .withStyle(ChatFormatting.RED));
            return;
        }

        String resolved = optString(ack, "target_username", target);
        String triggered = optString(ack, "triggered", "none");
        int newTotal = optInt(ack, "new_total", -1);
        boolean blocklisted =
                ack.has("blocklisted")
                        && !ack.get("blocklisted").isJsonNull()
                        && ack.get("blocklisted").getAsBoolean();
        String suggested =
                ack.has("suggested_dispatch") && !ack.get("suggested_dispatch").isJsonNull()
                        ? ack.get("suggested_dispatch").getAsString()
                        : "";

        ChatFormatting headerColor =
                switch (triggered) {
                    case "eject" -> ChatFormatting.RED;
                    case "warning" -> ChatFormatting.GOLD;
                    default -> ChatFormatting.YELLOW;
                };
        String headerLabel =
                switch (triggered) {
                    case "eject" -> "EJECTED";
                    case "warning" -> "WARNED";
                    default -> "Cautioned";
                };

        MutableComponent line =
                Component.literal(headerLabel + " ")
                        .withStyle(headerColor)
                        .append(Component.literal(resolved).withStyle(ChatFormatting.AQUA));
        if (newTotal >= 0) {
            line.append(
                    Component.literal(
                                    " (now "
                                            + newTotal
                                            + " caution pt"
                                            + (newTotal == 1 ? "" : "s")
                                            + ")")
                            .withStyle(ChatFormatting.GRAY));
        }
        if (blocklisted) {
            line.append(Component.literal(" [added to blocklist]").withStyle(ChatFormatting.RED));
        }
        ChatUtils.sendLocalMessage(line);

        if (!requestedMessage.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("  > " + requestedMessage).withStyle(ChatFormatting.GRAY));
        }

        if ("eject".equals(triggered) && !suggested.isEmpty()) {
            dispatchEjectAction(suggested, resolved);
        }
    }

    private static void renderCautionHistory(String requestedUsername, JsonObject ack) {
        // Mark the start of a new render block so the first line re-extends
        // the badge (compact-indicator dedup resumes from line 2).
        Prepend.resetDedup();

        String status = optString(ack, "status", "error");
        if (!"ok".equals(status)) {
            String detail = optString(ack, "detail", "unknown error");
            ChatUtils.sendLocalMessage(
                    Component.literal("Caution history lookup failed: " + detail)
                            .withStyle(ChatFormatting.RED));
            return;
        }

        String resolved = optString(ack, "target_username", requestedUsername);
        int total = optInt(ack, "total_points", 0);

        ChatFormatting totalColor;
        if (total >= 6) totalColor = ChatFormatting.RED;
        else if (total >= 3) totalColor = ChatFormatting.GOLD;
        else if (total > 0) totalColor = ChatFormatting.YELLOW;
        else totalColor = ChatFormatting.GREEN;

        MutableComponent header =
                Component.literal("Caution history -- ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(resolved).withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(" -- ").withStyle(ChatFormatting.GRAY))
                        .append(
                                Component.literal(total + " pt" + (total == 1 ? "" : "s"))
                                        .withStyle(totalColor));
        ChatUtils.sendLocalMessage(header);

        JsonArray entries =
                ack.has("entries") && ack.get("entries").isJsonArray()
                        ? ack.get("entries").getAsJsonArray()
                        : new JsonArray();

        if (entries.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("  (no entries)").withStyle(ChatFormatting.GRAY));
            return;
        }

        int shown = 0;
        for (JsonElement el : entries) {
            if (!el.isJsonObject() || shown >= 10) break;
            JsonObject e = el.getAsJsonObject();
            String kind = optString(e, "kind", "?");
            int points = optInt(e, "points", 0);
            String actor = optString(e, "actor_username_at_time", "?");
            String created = optString(e, "created_at", "");
            String snippet = optString(e, "message", "");

            ChatFormatting kindColor =
                    switch (kind) {
                        case "eject" -> ChatFormatting.RED;
                        case "warning" -> ChatFormatting.GOLD;
                        case "caution" -> ChatFormatting.YELLOW;
                        default -> ChatFormatting.GRAY;
                    };
            MutableComponent row =
                    Component.literal("  • ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(
                                    Component.literal(kind + " (+" + points + ")")
                                            .withStyle(kindColor))
                            .append(
                                    Component.literal(" by " + actor)
                                            .withStyle(ChatFormatting.AQUA));
            if (!created.isEmpty()) {
                // Server already sends ISO-8601; show the date prefix only to
                // keep the line compact. Full timestamp is on hover (server
                // returns it as the created_at field).
                String shortDate = created.length() >= 10 ? created.substring(0, 10) : created;
                row.append(Component.literal(" " + shortDate).withStyle(ChatFormatting.DARK_GRAY));
            }
            ChatUtils.sendLocalMessage(row);
            if (!snippet.isEmpty()) {
                String trim = snippet.length() > 100 ? snippet.substring(0, 100) + "..." : snippet;
                ChatUtils.sendLocalMessage(
                        Component.literal("    > " + trim).withStyle(ChatFormatting.GRAY));
            }
            shown++;
        }
        if (entries.size() > shown) {
            ChatUtils.sendLocalMessage(
                    Component.literal("  ... and " + (entries.size() - shown) + " older entries.")
                            .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    // ── /eject in-game dispatch ─────────────────────────────────────────

    private static void dispatchEjectAction(String suggested, String target) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || player.connection == null) {
            VetsLogger.warn("Eject dispatch: no live player connection");
            return;
        }
        String command;
        if ("kick".equals(suggested)) {
            command = "gu kick " + target;
            ChatUtils.sendLocalMessage(
                    Component.literal("Dispatching /gu kick " + target)
                            .withStyle(ChatFormatting.RED));
        } else if ("demote".equals(suggested)) {
            command = "gu rank " + target + " recruit";
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    "Dispatching /gu rank "
                                            + target
                                            + " recruit (chiefs will see a kick notice in #rank-alerts).")
                            .withStyle(ChatFormatting.RED));
        } else {
            VetsLogger.warn("Eject dispatch: unknown suggestion {}", suggested);
            return;
        }
        GuildChatDispatcher.sendCommandBypassed(command);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private record ParsedArgs(String target, String rest) {}

    /** Splits an argument string into {@code (firstWord, rest)}. The
     *  first word is the player name; the rest is the optional message.
     *  Handles leading/trailing whitespace. */
    private static ParsedArgs parseTwoPart(String args) {
        if (args == null) return new ParsedArgs("", "");
        String trimmed = args.trim();
        if (trimmed.isEmpty()) return new ParsedArgs("", "");
        int sp = trimmed.indexOf(' ');
        if (sp < 0) return new ParsedArgs(trimmed, "");
        return new ParsedArgs(trimmed.substring(0, sp), trimmed.substring(sp + 1).trim());
    }

    // Package-private for unit tests. See JsonAccessorTest.
    static String optString(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        return obj.get(key).getAsString();
    }

    // Package-private for unit tests. See JsonAccessorTest.
    static int optInt(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static MutableComponent clickRun(
            String text, String command, String hover, ChatFormatting color) {
        Style style =
                Style.EMPTY
                        .withColor(color)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover)));
        return Component.literal(text).withStyle(style);
    }

    private static MutableComponent clickSuggest(
            String text, String suggestion, String hover, ChatFormatting color) {
        Style style =
                Style.EMPTY
                        .withColor(color)
                        .withClickEvent(new ClickEvent.SuggestCommand(suggestion))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover)));
        return Component.literal(text).withStyle(style);
    }
}
