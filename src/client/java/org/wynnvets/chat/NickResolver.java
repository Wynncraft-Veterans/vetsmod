package org.wynnvets.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a player's real Minecraft username from chat-line hover events.
 *
 * <p>Wynncraft attaches a {@link HoverEvent.ShowText} of the form
 * {@code "<nick>'s real name is <username>"} to the player-name span of every
 * chat line.  When a player is nicked, the visible name in the chat line is
 * the nickname; the real username lives only in the hover.  Reading the
 * hover is the only client-side way to recover the real name for players who
 * are nicked and not running a name-revealing client mod.</p>
 */
public final class NickResolver {

    public static final Pattern REAL_NAME_PATTERN =
            Pattern.compile("real\\s+name\\s+is\\s+([A-Za-z0-9_]{1,16})", Pattern.CASE_INSENSITIVE);

    /** A flattened leaf of a {@link Component} tree: literal text plus its resolved style. */
    public record FlatPart(String text, Style style) {
    }

    private NickResolver() {
    }

    /**
     * Walks {@code root} for the first hover event whose text matches
     * {@link #REAL_NAME_PATTERN} and returns the captured username, or
     * {@code fallback} when none match.
     */
    public static String realUsernameOrFallback(Component root, String fallback) {
        List<FlatPart> parts = new ArrayList<>();
        flattenComponent(root, root.getStyle(), parts);
        for (FlatPart part : parts) {
            String real = realUsernameFromHover(part.style().getHoverEvent());
            if (real != null) {
                return real;
            }
        }
        return fallback;
    }

    /**
     * Walks {@code root} for the first part that carries a real-name hover and
     * returns its {@link Style}, or {@code fallback} when none match.  The
     * returned style carries the original italic flag, colour, and hover event
     * — letting callers rebuild a name span that hovers and renders like the
     * vanilla Wynncraft nick.
     */
    public static Style realNameSpanStyleOrFallback(Component root, Style fallback) {
        List<FlatPart> parts = new ArrayList<>();
        flattenComponent(root, root.getStyle(), parts);
        for (FlatPart part : parts) {
            if (realUsernameFromHover(part.style().getHoverEvent()) != null) {
                return part.style();
            }
        }
        return fallback;
    }

    /**
     * Extracts the real username from a {@link HoverEvent}, or {@code null} if the
     * hover is not a {@code SHOW_TEXT} match for {@link #REAL_NAME_PATTERN}.
     */
    public static String realUsernameFromHover(HoverEvent hover) {
        if (hover instanceof HoverEvent.ShowText st) {
            Matcher matcher = REAL_NAME_PATTERN.matcher(st.value().getString());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /**
     * Recursively flattens a {@link Component} tree into (text, resolved-style)
     * pairs.  Each leaf's resolved style is the child style with the inherited
     * parent style filling in gaps — matching Minecraft's own
     * {@link Style#applyTo} semantics (this overrides, parent fills) and the
     * convention used by the sibling rewriters that hold their own copies of
     * this helper.
     */
    public static void flattenComponent(Component component, Style inherited, List<FlatPart> out) {
        Style resolved = component.getStyle().applyTo(inherited);
        StringBuilder sb = new StringBuilder();
        component.getContents().visit(s -> {
            sb.append(s);
            return java.util.Optional.empty();
        });
        String text = sb.toString();
        if (!text.isEmpty()) {
            out.add(new FlatPart(text, resolved));
        }
        for (Component sibling : component.getSiblings()) {
            flattenComponent(sibling, resolved, out);
        }
    }
}
