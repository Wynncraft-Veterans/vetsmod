package org.wynnvets.chat.rewriter;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import org.wynnvets.chat.ChatLogger;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.NickResolver;
import org.wynnvets.chat.PillCodec;
import org.wynnvets.chat.Prepend;
import org.wynnvets.chat.RankDisplayMap;
import org.wynnvets.fetcher.polling.SupportersPoller;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.rendering.colors.AnimatedGradientSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rewrites server-originating guild chat messages: swaps the raw Wynn
 * rank pill to the client-facing display label (Steward/Returner) and,
 * for supporters, applies the animated gradient styling.
 *
 * <p>Server guild chat messages use the {@code banner/pill} font to composite a
 * two-layer badge: <b>background glyphs</b> (aqua-coloured, forming the pill
 * shape) and <b>foreground glyphs</b> (dark-coloured text letters).</p>
 *
 * <p>2026-07 permission restructure: expanded from the supporter-only path
 * to fire on ALL guild chat with a decodable rank pill. Strategist / Chief
 * / Owner now render as "Steward"; Recruiter / Captain render as
 * "Returner". The pill is rebuilt client-side using the {@code chat/prefix}
 * font (same path as bridge messages), so the visual style changes from
 * the aqua server-native pill to the ASCII-encoded label pill. Supporter
 * gradients still compose on top when the sender has glints enabled.</p>
 *
 * <p>The whole rewriter is gated on {@link GuildStateManager#isVetsGuildChat()}.
 * Wynn's pill glyphs carry no guild identity, so for an honourary member —
 * a vets community member whose in-game guild is somewhere else — every
 * line of their own guild's chat decodes to a rank this class would happily
 * relabel. Their guild's staff are not our Stewards, and their guildmates
 * don't earn a glint by sharing a channel with a vets supporter. Real vets
 * chat arrives at honourary members over the WebSocket bridge, not this
 * channel, and keeps the full treatment on that path.</p>
 */
public final class ServerGuildChatRewriter {

    /** The {@code banner/pill} font used for rank badge rendering. */
    private static final Style PILL_FONT = Style.EMPTY
            .withFont(new FontDescription.Resource(Identifier.parse("banner/pill")))
            .withoutShadow();

    /** Aqua color value used by the server for pill background glyphs (§b). */
    private static final int SERVER_AQUA = ChatFormatting.AQUA.getColor();

    /** Dark foreground style for pill letter glyphs (preserves legibility). */
    private static final Style DARK_FG_STYLE = PILL_FONT
            .withColor(TextColor.fromRgb(0x000000));

    private ServerGuildChatRewriter() {
    }

    /**
     * Attempts to rewrite a server guild chat message. Requires the channel
     * to be VETS' own, then fires when either (a) the sender's raw rank maps
     * to a different display label (Strategist/Chief/Owner → Steward,
     * Captain/Recruiter → Returner) or (b) the sender is a supporter with
     * gradient glints enabled.
     *
     * @param component     the original chat Component (preserves colour info)
     * @param messageString the plain-text form of the message
     * @return {@code true} if rewritten (caller should cancel the original)
     */
    public static boolean tryRewrite(Component component, String messageString) {
        // Everything below this line restyles VETS' guild chat. The channel
        // only carries the guild we're actually in, so one check up front
        // covers the whole rewriter — and skips the component-tree walk
        // entirely for players whose guild chat isn't ours.
        if (!GuildStateManager.isVetsGuildChat()) {
            return false;
        }

        ParsedGuildChat parsed = parseGuildChat(messageString);
        if (parsed == null) {
            return false;
        }

        // Decode the raw Wynn rank from the pill's PUA sequence. Only proceed
        // if we recognise the pill — unknown pill glyphs are left untouched
        // (they aren't guild chat we're responsible for rewriting).
        String rawRank = decodeRawRank(parsed.rankIndicator);
        if (rawRank == null) {
            return false;
        }

        // Nicked players appear in chat as the nickname alone (no slash form)
        // when they aren't running a name-revealing client mod, so the visible
        // username won't match the supporter list.  The real username is
        // attached as a hover event on the name span.
        String lookupUsername = NickResolver.realUsernameOrFallback(component, parsed.username);
        boolean isSupporter = SupportersPoller.isSupporter(lookupUsername)
                && org.wynnvets.config.VetsConfig.get(
                        org.wynnvets.config.VetsConfig.SHOW_SUPPORTER_GLINTS);

        String displayLabel = RankDisplayMap.displayFor(rawRank);
        boolean needsRemap = !displayLabel.equalsIgnoreCase(rawRank);

        if (!needsRemap && !isSupporter) {
            // No display remap AND no supporter styling to apply — leave the
            // original server-rendered message alone.
            return false;
        }

        MutableComponent pill;
        if (needsRemap) {
            // Rebuild the pill in the "local" dark-on-light style — the
            // same visual family as Wynncraft's native guild pill and
            // the [Vetsmod] pill in /wv help. Local chat (arriving via
            // Wynncraft's actual guild channel through the mixin) uses
            // this style; remote messages (bridge / honourary / queue)
            // still get the light-on-dark ASCII pill via
            // OutboundDisplayHandler + ChatUtils.encodePillIfAscii.
            Style frameStyle = isSupporter
                    ? ChatUtils.RANK_STYLE.withColor(
                            TextColor.fromRgb(AnimatedGradientSequence.MARKER_COLOR))
                            .withoutShadow()
                    : ChatUtils.RANK_STYLE.withoutShadow();
            pill = ChatUtils.buildFramedPill(displayLabel, frameStyle);
        } else {
            // No remap — supporter-only path retains the original pill's
            // extracted background/foreground fragments so the aqua+dark
            // two-tone rendering survives the gradient overlay.
            List<StyledFragment> pillFragments = extractPillFragments(component);
            if (pillFragments.isEmpty()
                    || pillFragments.stream().noneMatch(StyledFragment::isBackground)) {
                return false;
            }
            pill = buildGradientPill(pillFragments);
        }

        MutableComponent badge = Prepend.GUILD.get();

        MutableComponent messageBody = extractBodyComponent(
                component, parsed.bodyCharStart, ChatUtils.RANK_STYLE);

        // Preserve the original name span's italic + hover for nicked players;
        // fall back to the flat NAME_STYLE for non-nicked supporters.
        Style nameStyle = NickResolver.realNameSpanStyleOrFallback(component, ChatUtils.NAME_STYLE);

        MutableComponent body = Component.empty()
                .append(badge)
                .append(pill)
                .append(" ")
                .append(Component.literal(parsed.username).setStyle(nameStyle))
                .append(Component.literal(": ").setStyle(ChatUtils.RANK_STYLE))
                .append(messageBody);

        if (isSupporter) {
            ChatUtils.dispatchAnimatedChat(body, badge.getStyle());
        } else {
            ChatUtils.dispatchToChat(body, badge.getStyle());
        }
        return true;
    }

    /**
     * Return the raw Wynn rank name encoded in {@code rankIndicator}, or
     * {@code null} if it carries no pill we can read.
     *
     * <p>Tries the known-sequence table in {@link ChatLogger#rankMap()}
     * first, then falls back to decoding the pill structurally with
     * {@link PillCodec#decodeServerPill(String)}. The table is an exact
     * match on whole sequences and so is both faster and impossible to
     * fool; the codec covers ranks the table has never seen, which is what
     * keeps a future Wynncraft rank from silently losing its rewrite.
     * Anything the codec returns still passes through
     * {@link RankDisplayMap}, so an unrecognised rank maps to itself and
     * ends up rendering exactly as the server sent it.</p>
     */
    private static String decodeRawRank(String rankIndicator) {
        for (Map.Entry<String, String> entry : ChatLogger.rankMap().entrySet()) {
            if (rankIndicator.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return PillCodec.decodeServerPill(rankIndicator);
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

        FontDescription pillFontId = PILL_FONT.getFont();
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

    // ── Message body extraction ───────────────────────────────────────

    /**
     * Extracts the message body from the original component tree, preserving
     * click and hover events from interactive elements (links, items).
     * Non-interactive text is processed through {@link ChatUtils#formatMessageBody}
     * for marker stripping and URL detection.
     *
     * @param root           the original chat Component
     * @param bodyCharStart  char offset in the flattened string where the body begins
     * @param defaultStyle   style for non-interactive body text
     * @return a component containing the message body with preserved interactivity
     */
    private static MutableComponent extractBodyComponent(Component root, int bodyCharStart, Style defaultStyle) {
        List<StyledFragment> allFragments = new ArrayList<>();
        flattenComponent(root, root.getStyle(), allFragments);

        MutableComponent result = Component.empty();
        StringBuilder accumulated = new StringBuilder();
        int charOffset = 0;

        for (StyledFragment frag : allFragments) {
            int fragEnd = charOffset + frag.text.length();
            if (fragEnd <= bodyCharStart) {
                charOffset = fragEnd;
                continue;
            }

            String text;
            if (charOffset < bodyCharStart) {
                text = frag.text.substring(bodyCharStart - charOffset);
            } else {
                text = frag.text;
            }

            boolean isInteractive = frag.style.getClickEvent() != null
                    || frag.style.getHoverEvent() != null;
            if (isInteractive && !ChatUtils.isWrapStructure(text)) {
                if (accumulated.length() > 0) {
                    result.append(ChatUtils.formatMessageBody(accumulated.toString(), defaultStyle));
                    accumulated.setLength(0);
                }
                result.append(Component.literal(text).setStyle(frag.style));
            } else {
                accumulated.append(text);
            }

            charOffset = fragEnd;
        }

        if (accumulated.length() > 0) {
            result.append(ChatUtils.formatMessageBody(accumulated.toString(), defaultStyle));
        }

        return result;
    }

    // ── Guild chat parsing ────────────────────────────────────────────

    private static ParsedGuildChat parseGuildChat(String message) {
        int colonIndex = message.indexOf(':');
        if (colonIndex <= 0) {
            return null;
        }

        // Find the last custom-font glyph before the colon.  The rank
        // indicator always ends with such a glyph; everything after it
        // (trimmed) up to the colon is the display name — which may
        // contain spaces (e.g. "EYAL5555/First Mage").
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
            return null;
        }

        String username = message.substring(lastGlyphEnd, colonIndex).trim();
        if (username.isEmpty()) {
            return null;
        }

        String rankIndicator = message.substring(0, lastGlyphEnd);
        int bodyStart = colonIndex + 1;
        while (bodyStart < message.length() && message.charAt(bodyStart) == ' ') {
            bodyStart++;
        }
        String messageContent = message.substring(colonIndex + 1).trim();
        return new ParsedGuildChat(rankIndicator, username, messageContent, bodyStart);
    }

    private static final class ParsedGuildChat {
        final String rankIndicator;
        final String username;
        final String message;
        final int bodyCharStart;

        ParsedGuildChat(String rankIndicator, String username, String message, int bodyCharStart) {
            this.rankIndicator = rankIndicator;
            this.username = username;
            this.message = message;
            this.bodyCharStart = bodyCharStart;
        }
    }
}
