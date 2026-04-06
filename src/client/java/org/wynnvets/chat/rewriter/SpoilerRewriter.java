package org.wynnvets.chat.rewriter;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.SpoilerFormatter;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.guild.GuildStateManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites server guild chat messages that contain {@code ||spoiler||} markers,
 * replacing them with hoverable "[Spoiler - Hover to see]" labels.
 *
 * <p>This rewriter fires for non-supporter guild chat messages (supporter
 * messages are already handled by {@link ServerGuildChatRewriter}, which
 * processes spoilers via {@link ChatUtils#formatMessageBody}).  The original
 * component structure (badge, pill, username styling) is preserved; only the
 * message body is reprocessed for spoiler rendering.</p>
 */
public final class SpoilerRewriter {

    /** Aqua style matching the server guild chat badge, used for continuation markers. */
    private static final Style PREPEND_STYLE = Style.EMPTY
            .withFont(new FontDescription.Resource(Identifier.parse("chat/prefix")))
            .withoutShadow()
            .withColor(ChatFormatting.AQUA);

    private SpoilerRewriter() {}

    /**
     * Attempts to rewrite a guild chat message containing spoiler markers.
     *
     * @param component     the original chat component
     * @param messageString the plain-text form of the message
     * @return {@code true} if rewritten (caller should cancel the original)
     */
    public static boolean tryRewrite(Component component, String messageString) {
        if (!VetsConfig.get(VetsConfig.HANDLE_SPOILERS)) {
            return false;
        }
        if (!isEligible()) {
            return false;
        }
        if (!SpoilerFormatter.containsSpoilers(messageString)) {
            return false;
        }

        int colonIndex = findGuildChatColon(messageString);
        if (colonIndex < 0) {
            return false;
        }

        int bodyStart = colonIndex + 1;
        while (bodyStart < messageString.length() && messageString.charAt(bodyStart) == ' ') {
            bodyStart++;
        }
        if (bodyStart >= messageString.length()) {
            return false;
        }

        String bodyText = messageString.substring(bodyStart);
        if (!SpoilerFormatter.containsSpoilers(bodyText)) {
            return false;
        }

        MutableComponent result = rebuildWithSpoilers(component, bodyStart);
        ChatUtils.dispatchToChat(result, PREPEND_STYLE);
        return true;
    }

    // ── Guild chat detection ──────────────────────────────────────────

    /**
     * Finds the colon separating the username from the message body in a guild
     * chat message.  Returns -1 if the message is not in guild chat format
     * (requires PUA glyphs before the colon, indicating a badge/pill prefix).
     */
    private static int findGuildChatColon(String message) {
        int colonIndex = message.indexOf(':');
        if (colonIndex <= 0) {
            return -1;
        }

        int lastGlyphEnd = -1;
        int idx = 0;
        while (idx < colonIndex) {
            int cp = message.codePointAt(idx);
            int charCount = Character.charCount(cp);
            int type = Character.getType(cp);
            boolean isCustomGlyph = type == Character.PRIVATE_USE
                    || (type == Character.UNASSIGNED && cp > 0xFFFF);
            if (isCustomGlyph) {
                lastGlyphEnd = idx + charCount;
            }
            idx += charCount;
        }

        if (lastGlyphEnd <= 0 || lastGlyphEnd >= colonIndex) {
            return -1;
        }
        return colonIndex;
    }

    // ── Body extraction and rebuild ───────────────────────────────────

    /**
     * Rebuilds the message component, copying prefix fragments (badge, pill,
     * username) as-is and reprocessing body fragments through
     * {@link ChatUtils#formatMessageBody} for spoiler detection.
     */
    private static MutableComponent rebuildWithSpoilers(Component component, int bodyStart) {
        List<StyledFragment> fragments = new ArrayList<>();
        flattenComponent(component, component.getStyle(), fragments);

        MutableComponent result = Component.empty();
        int charOffset = 0;
        StringBuilder bodyAccum = new StringBuilder();
        Style bodyStyle = ChatUtils.RANK_STYLE;

        for (StyledFragment frag : fragments) {
            int fragEnd = charOffset + frag.text.length();

            if (fragEnd <= bodyStart) {
                result.append(Component.literal(frag.text).setStyle(frag.style));
            } else if (charOffset >= bodyStart) {
                boolean isInteractive = frag.style.getClickEvent() != null
                        || frag.style.getHoverEvent() != null;
                if (isInteractive && !ChatUtils.isWrapStructure(frag.text)) {
                    if (bodyAccum.length() > 0) {
                        result.append(ChatUtils.formatMessageBody(bodyAccum.toString(), bodyStyle));
                        bodyAccum.setLength(0);
                    }
                    result.append(Component.literal(frag.text).setStyle(frag.style));
                } else {
                    bodyAccum.append(frag.text);
                }
            } else {
                String prefix = frag.text.substring(0, bodyStart - charOffset);
                String body = frag.text.substring(bodyStart - charOffset);
                result.append(Component.literal(prefix).setStyle(frag.style));
                bodyAccum.append(body);
            }

            charOffset = fragEnd;
        }

        if (bodyAccum.length() > 0) {
            result.append(ChatUtils.formatMessageBody(bodyAccum.toString(), bodyStyle));
        }

        return result;
    }

    // ── Component tree helpers ────────────────────────────────────────

    private static void flattenComponent(Component component, Style inherited, List<StyledFragment> out) {
        Style resolved = component.getStyle().applyTo(inherited);
        String content = getDirectText(component);
        if (!content.isEmpty()) {
            out.add(new StyledFragment(content, resolved));
        }
        for (Component child : component.getSiblings()) {
            flattenComponent(child, resolved, out);
        }
    }

    private static String getDirectText(Component component) {
        StringBuilder sb = new StringBuilder();
        component.getContents().visit(s -> {
            sb.append(s);
            return java.util.Optional.empty();
        });
        return sb.toString();
    }

    private static final class StyledFragment {
        final String text;
        final Style style;

        StyledFragment(String text, Style style) {
            this.text = text;
            this.style = style;
        }
    }

    private static boolean isEligible() {
        return GuildStateManager.isReturners()
            || (GuildStateManager.isGuildless() && GuildStateManager.isWaitlistUnlocked())
            || GuildStateManager.isHonouraryUnlocked();
    }
}
