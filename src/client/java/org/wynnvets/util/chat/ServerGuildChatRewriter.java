package org.wynnvets.util.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import org.wynnvets.util.SupportersFetcher;
import org.wynnvets.util.colors.AnimatedGradientSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects server-originating guild chat messages from supporters and re-renders
 * them with gradient pill styling.
 *
 * <p>Server guild chat messages use the {@code banner/pill} font to composite a
 * two-layer badge: <b>background glyphs</b> (aqua-coloured, forming the pill
 * shape) and <b>foreground glyphs</b> (dark-coloured text letters).  For
 * supporters, this rewriter applies a gradient to the background glyphs while
 * keeping the foreground letters dark so the text remains legible.</p>
 */
public final class ServerGuildChatRewriter {

    /** The {@code banner/pill} font used for rank badge rendering. */
    private static final Style PILL_FONT = Style.EMPTY
            .withFont(ResourceLocation.parse("banner/pill"));

    /** Aqua color value used by the server for pill background glyphs (§b). */
    private static final int SERVER_AQUA = ChatFormatting.AQUA.getColor();

    /** Dark foreground style for pill letter glyphs (preserves legibility). */
    private static final Style DARK_FG_STYLE = PILL_FONT
            .withColor(TextColor.fromRgb(0x000000));

    private ServerGuildChatRewriter() {
    }

    /**
     * Attempts to rewrite a server guild chat message for a supporter.
     *
     * @param component     the original chat Component (preserves colour info)
     * @param messageString the plain-text form of the message
     * @return {@code true} if rewritten (caller should cancel the original)
     */
    public static boolean tryRewrite(Component component, String messageString) {
        ParsedGuildChat parsed = parseGuildChat(messageString);
        if (parsed == null) {
            return false;
        }

        if (!SupportersFetcher.isSupporter(parsed.username)) {
            return false;
        }

        // Walk the Component tree and collect flattened (text, resolvedStyle) pairs
        // that make up the pill section.
        List<StyledFragment> pillFragments = extractPillFragments(component);
        if (pillFragments.isEmpty() || pillFragments.stream().noneMatch(StyledFragment::isBackground)) {
            return false;
        }

        // Build gradient pill from the extracted fragments
        MutableComponent gradientPill = buildGradientPill(pillFragments);

        MutableComponent body = Component.empty()
                .append(Prepend.GUILD.get())
                .append(gradientPill)
                .append(" ")
                .append(Component.literal(parsed.username).setStyle(ChatUtils.NAME_STYLE))
                .append(Component.literal(": ").setStyle(ChatUtils.RANK_STYLE))
                .append(Component.literal(parsed.message).setStyle(ChatUtils.RANK_STYLE));

        ChatUtils.dispatchAnimatedChat(body);
        return true;
    }

    // ── Pill fragment extraction ──────────────────────────────────────

    /**
     * A flattened piece of the Component tree: literal text + its fully resolved style.
     */
    private static final class StyledFragment {
        final String text;
        final Style style;

        StyledFragment(String text, Style style) {
            this.text = text;
            this.style = style;
        }

        boolean isBackground() {
            TextColor color = style.getColor();
            return color != null && color.getValue() == SERVER_AQUA;
        }
    }

    /**
     * Walks the Component tree depth-first and collects every leaf whose
     * resolved style uses the {@code banner/pill} font.  This correctly
     * separates the pill fragments (both background and foreground layers)
     * from the guild badge ({@code chat/prefix}) and the username/message
     * (default font).
     */
    private static List<StyledFragment> extractPillFragments(Component root) {
        List<StyledFragment> all = new ArrayList<>();
        flattenComponent(root, root.getStyle(), all);

        ResourceLocation pillFontId = PILL_FONT.getFont();
        List<StyledFragment> pill = new ArrayList<>();
        for (StyledFragment frag : all) {
            if (pillFontId.equals(frag.style.getFont())) {
                pill.add(frag);
            }
        }
        return pill;
    }

    /**
     * Recursively flattens a Component tree into (text, resolved style) pairs.
     */
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

    /**
     * Extracts only the direct literal text of a component (not its children).
     */
    private static String getDirectText(Component component) {
        // Component.literal stores its text in contents; getString() includes children.
        // We use the ComponentContents to get just the direct text.
        StringBuilder sb = new StringBuilder();
        component.getContents().visit(s -> {
            sb.append(s);
            return java.util.Optional.empty();
        });
        return sb.toString();
    }

    // ── Gradient pill builder ─────────────────────────────────────────

    /**
     * Builds a pill component from the extracted fragments.
     *
     * <p>Background fragments (aqua-coloured) are marked with the animation
     * sentinel colour so that {@code AnimatedChatMixin} can animate them.
     * Foreground fragments (dark-coloured letters) are kept dark so the
     * text remains legible against the gradient background.</p>
     */
    private static MutableComponent buildGradientPill(List<StyledFragment> fragments) {
        if (fragments.isEmpty()) {
            return Component.empty();
        }

        MutableComponent result = Component.empty();

        for (StyledFragment frag : fragments) {
            if (frag.isBackground()) {
                // Mark background fragments with the animation sentinel.
                // AnimatedChatMixin will replace this with animated gradient
                // colours at render time.
                result.append(Component.literal(frag.text)
                        .setStyle(PILL_FONT.withColor(
                                TextColor.fromRgb(AnimatedGradientSequence.MARKER_COLOR))));
            } else {
                // Foreground letters — keep dark
                result.append(Component.literal(frag.text)
                        .setStyle(DARK_FG_STYLE));
            }
        }

        return result;
    }

    // ── Guild chat parsing ────────────────────────────────────────────

    private static ParsedGuildChat parseGuildChat(String message) {
        int colonIndex = message.indexOf(':');
        if (colonIndex <= 0) {
            return null;
        }

        int usernameStart = message.lastIndexOf(' ', colonIndex - 1);
        if (usernameStart <= 0) {
            return null;
        }

        String username = message.substring(usernameStart + 1, colonIndex).trim();
        if (username.isEmpty()) {
            return null;
        }

        int rankStart = message.lastIndexOf(' ', usernameStart - 1);
        if (rankStart <= 0) {
            return null;
        }

        String rankIndicator = message.substring(rankStart + 1, usernameStart).trim();
        if (rankIndicator.isEmpty() || !containsCustomFontGlyph(rankIndicator)) {
            return null;
        }

        String messageContent = message.substring(colonIndex + 1).trim();
        return new ParsedGuildChat(rankIndicator, username, messageContent);
    }

    private static boolean containsCustomFontGlyph(String text) {
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int type = Character.getType(codePoint);
            if (type == Character.PRIVATE_USE
                    || (type == Character.UNASSIGNED && codePoint > 0xFFFF)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static final class ParsedGuildChat {
        final String rankIndicator;
        final String username;
        final String message;

        ParsedGuildChat(String rankIndicator, String username, String message) {
            this.rankIndicator = rankIndicator;
            this.username = username;
            this.message = message;
        }
    }
}
