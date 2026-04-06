package org.wynnvets.chat.rewriter;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.SpoilerCodec;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.guild.GuildStateManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites any incoming chat message that contains PUA-encoded spoiler blocks
 * ({@code \uF600…\uF601}), replacing them with hoverable
 * "[Spoiler - Hover to see]" labels.
 *
 * <p>This rewriter fires for server-originating messages (guild chat from
 * other players).  Bridge and mod-constructed messages are handled by
 * {@link org.wynnvets.chat.SpoilerFormatter} through the
 * {@link ChatUtils#formatMessageBody} path instead.</p>
 */
public final class SpoilerRewriter {

    private static final Style SPOILER_LABEL_STYLE = Style.EMPTY.withColor(ChatFormatting.GREEN);

    private SpoilerRewriter() {}

    /**
     * Attempts to rewrite a chat message containing PUA-encoded spoiler blocks.
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
        if (!SpoilerCodec.containsEncodedSpoiler(messageString)) {
            return false;
        }

        MutableComponent result = rebuildWithDecodedSpoilers(component);
        ChatUtils.dispatchToChat(result, Style.EMPTY);
        return true;
    }

    // ── Component rebuild ─────────────────────────────────────────────

    /**
     * Walks the component tree, replacing any PUA-encoded spoiler blocks in
     * text fragments with hoverable spoiler labels.  All other fragments
     * (badges, pills, interactive elements) are preserved as-is.
     *
     * <p>When Wynncraft wraps a long message across multiple lines, the encoded
     * spoiler ({@code \uF600…\uF601}) may be split across separate component
     * fragments.  If no single fragment contains a complete spoiler pair, we
     * fall back to accumulating the message body across fragments and processing
     * it through {@link ChatUtils#formatMessageBody}, which strips continuation
     * markers and handles spoiler formatting correctly.</p>
     */
    private static MutableComponent rebuildWithDecodedSpoilers(Component component) {
        List<StyledFragment> fragments = new ArrayList<>();
        flattenComponent(component, component.getStyle(), fragments);

        // Fast path: if any single fragment contains a complete spoiler pair,
        // process fragments individually (works for short, unwrapped spoilers).
        boolean anySingleFragmentHasSpoiler = false;
        for (StyledFragment frag : fragments) {
            if (SpoilerCodec.containsEncodedSpoiler(frag.text)) {
                anySingleFragmentHasSpoiler = true;
                break;
            }
        }

        if (!anySingleFragmentHasSpoiler) {
            // Spoiler spans multiple fragments (Wynncraft line-wrapping).
            return rebuildCrossFragmentSpoilers(fragments);
        }

        MutableComponent result = Component.empty();
        for (StyledFragment frag : fragments) {
            if (!SpoilerCodec.containsEncodedSpoiler(frag.text)) {
                result.append(Component.literal(frag.text).setStyle(frag.style));
                continue;
            }

            int cursor = 0;
            String text = frag.text;
            while (cursor < text.length()) {
                int start = text.indexOf(SpoilerCodec.SPOILER_START, cursor);
                if (start < 0) {
                    result.append(Component.literal(text.substring(cursor)).setStyle(frag.style));
                    break;
                }
                int end = text.indexOf(SpoilerCodec.SPOILER_END, start + 1);
                if (end < 0) {
                    result.append(Component.literal(text.substring(cursor)).setStyle(frag.style));
                    break;
                }

                if (start > cursor) {
                    result.append(Component.literal(text.substring(cursor, start)).setStyle(frag.style));
                }

                String encoded = text.substring(start + 1, end);
                String decoded = SpoilerCodec.decodeContent(encoded);
                Style hoverStyle = SPOILER_LABEL_STYLE.withHoverEvent(
                        new HoverEvent.ShowText(Component.literal(decoded)));
                result.append(Component.literal("[Spoiler - Hover to see]").setStyle(hoverStyle));

                cursor = end + 1;
            }
        }
        return result;
    }

    /**
     * Handles spoilers that span multiple component fragments due to Wynncraft
     * line-wrapping.  Prefix fragments (rank pill, badge, display name, colon)
     * are preserved as-is; body fragments are accumulated and processed through
     * {@link ChatUtils#formatMessageBody} which strips continuation markers and
     * handles spoiler formatting via {@link org.wynnvets.chat.SpoilerFormatter}.
     */
    private static MutableComponent rebuildCrossFragmentSpoilers(List<StyledFragment> fragments) {
        MutableComponent result = Component.empty();

        // Find the colon that separates the prefix (rank/name) from the body.
        int colonFragIdx = -1;
        int colonInFrag = -1;
        for (int i = 0; i < fragments.size(); i++) {
            int pos = fragments.get(i).text.indexOf(':');
            if (pos >= 0) {
                colonFragIdx = i;
                colonInFrag = pos;
                break;
            }
        }

        if (colonFragIdx < 0) {
            // No colon found — pass through unchanged.
            for (StyledFragment frag : fragments) {
                result.append(Component.literal(frag.text).setStyle(frag.style));
            }
            return result;
        }

        // Emit prefix fragments (before the colon fragment) as-is.
        for (int i = 0; i < colonFragIdx; i++) {
            result.append(Component.literal(fragments.get(i).text).setStyle(fragments.get(i).style));
        }

        // Split the colon fragment: prefix up to and including ": ", body after.
        StyledFragment colonFrag = fragments.get(colonFragIdx);
        int bodyStart = colonInFrag + 1;
        boolean hadSpace = false;
        if (bodyStart < colonFrag.text.length() && colonFrag.text.charAt(bodyStart) == ' ') {
            bodyStart++;
            hadSpace = true;
        }
        result.append(Component.literal(colonFrag.text.substring(0, bodyStart)).setStyle(colonFrag.style));
        // Ensure a space follows the colon even when the fragment boundary
        // falls right after ":" (Wynncraft often starts a new style there).
        if (!hadSpace) {
            result.append(Component.literal(" ").setStyle(colonFrag.style));
        }

        // Accumulate body text from remaining fragments.
        StringBuilder bodyBuilder = new StringBuilder();
        bodyBuilder.append(colonFrag.text.substring(bodyStart));
        for (int i = colonFragIdx + 1; i < fragments.size(); i++) {
            bodyBuilder.append(fragments.get(i).text);
        }

        // Process accumulated body through formatMessageBody, which strips
        // continuation markers, detects URLs, and formats spoilers.
        result.append(ChatUtils.formatMessageBody(bodyBuilder.toString(), ChatUtils.RANK_STYLE));

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
