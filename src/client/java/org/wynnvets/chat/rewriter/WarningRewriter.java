package org.wynnvets.chat.rewriter;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.Prepend;
import org.wynnvets.logging.VetsLogger;

/**
 * Renders an inbound {@code "warning"} chat-bus frame -- a server-pushed
 * formal-warning or eject notice routed only to the warned player's
 * vetsmod clients (see v1_protocol.md §2.5).
 *
 * <p>Visually styled distinctly from regular guild chat so the player
 * can't miss it: gold/yellow palette with a "WARNING" / "EJECTED"
 * banner, the actor's name, the formal message body, and the running
 * caution-point total. The Discord DM that fires in parallel from
 * dazebot ({@code lib.staff_actions._deliver_dm}) carries the same
 * text, so a player who is offline still gets the information.</p>
 *
 * <p>The frame's {@code target_uuid} is filtered server-side -- by the
 * time it reaches this rewriter, the receiving session is already
 * known to be the warned player. No additional client-side filtering
 * is needed (or possible: the receiver doesn't always know its own
 * authoritative mc_uuid).</p>
 */
public final class WarningRewriter {

    private WarningRewriter() {}

    /**
     * Renders the warning frame in chat. Called from
     * {@link org.wynnvets.chat.OutboundDisplayHandler#onOutboundMessage
     * OutboundDisplayHandler#onOutboundMessage} when the inbound frame's type is {@code "warning"}.
     *
     * @param json the raw frame from the v1/outbound WebSocket
     */
    public static void render(JsonObject json) {
        String triggered = optString(json, "triggered", "warning");
        String actor = optString(json, "actor", "staff");
        String message = optString(json, "message", "");
        int pointsAfter =
                json.has("points_after") && !json.get("points_after").isJsonNull()
                        ? json.get("points_after").getAsInt()
                        : -1;

        // Per spec: ejects reuse the formal-warning message format (same
        // "⚠ WARNING ⚠ <message>" prefix) so the warned player sees one
        // consistent shape regardless of which command landed on them.
        // The eject context (added to blocklist) is appended after.
        boolean isEject = "eject".equalsIgnoreCase(triggered);
        ChatFormatting bannerColor = isEject ? ChatFormatting.RED : ChatFormatting.GOLD;
        ChatFormatting bodyColor = ChatFormatting.YELLOW;

        MutableComponent line = Component.literal("⚠ WARNING ⚠").withStyle(bannerColor);
        if (!message.isEmpty()) {
            line.append(Component.literal(" " + message).withStyle(bodyColor));
        }

        StringBuilder tail = new StringBuilder("\nIssued by ").append(actor).append(".");
        if (pointsAfter >= 0) {
            tail.append(" You now have ")
                    .append(pointsAfter)
                    .append(" caution point")
                    .append(pointsAfter == 1 ? "" : "s")
                    .append(".");
        }
        if (isEject) {
            tail.append(" You have been added to the dazebot blocklist.");
        }
        line.append(Component.literal(tail.toString()).withStyle(ChatFormatting.GRAY));

        ChatUtils.sendLocalMessage(line, Prepend.DEFAULT);
        VetsLogger.info(
                "Rendered {} frame from {} (points_after={})", triggered, actor, pointsAfter);
    }

    private static String optString(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        return obj.get(key).getAsString();
    }
}
