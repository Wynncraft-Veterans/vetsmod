package org.wynnvets.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NickResolver}, the real-name lookup and component-flattening walk the chat
 * rewriters share.
 *
 * <p><b>Five cases tell {@link NickResolver#flattenComponent}'s style orientation apart</b>:
 * <em>child wins, ancestor fills gaps</em>. Each fails if the walk applies the parent's style
 * over the child's instead, and each pins the rule through a different {@link Style} field:
 * {@link #realNameSpanStyleOrFallback_childColorOverridesParent()} and
 * {@link #flattenComponent_childFieldsOverrideParent()} through colour, italic and shadow,
 * {@link #flattenComponent_childFontOverridesTheParentFont()} through the font, and
 * {@link #realUsernameOrFallback_ancestorHoverDoesNotMaskTheNameSpan()} and
 * {@link #realUsernameOrFallback_aNestedAncestorHoverDoesNotMaskTheLeafEither()} through
 * {@code hoverEvent}, the field the "real name is …" scan reads.
 * {@link #flattenComponent_siblingWithoutColorInheritsParent()} holds under <em>either</em>
 * orientation: it fails only when the inheritance is dropped altogether.</p>
 *
 * <p>The four cases below the pattern divider pin {@link NickResolver#REAL_NAME_PATTERN} and the
 * walk order that feeds it.</p>
 *
 * <p>The two {@code …Font} cases at the end pin the field
 * {@code org.wynnvets.chat.rewriter.ServerGuildChatRewriter ServerGuildChatRewriter} reads: it
 * selects the pill fragments of a chat line by comparing each resolved style's {@code font}
 * against {@code banner/pill}, so which span carries that font decides whether the pill
 * renders.</p>
 */
class NickResolverTest {

    private static final String REAL_NAME = "RealUser";
    private static final String NICK = "Nickname";
    private static final Style AQUA_STYLE = Style.EMPTY.withColor(ChatFormatting.AQUA);

    /** The font the server composites its rank pill in. */
    private static final FontDescription PILL_FONT =
            new FontDescription.Resource(Identifier.parse("banner/pill"));

    /** The font the guild badge and continuation markers use. */
    private static final FontDescription PREFIX_FONT =
            new FontDescription.Resource(Identifier.parse("chat/prefix"));

    private static final Style DARK_AQUA_ITALIC =
            Style.EMPTY.withColor(ChatFormatting.DARK_AQUA).withItalic(true);
    private static final Style FALLBACK = Style.EMPTY.withColor(ChatFormatting.WHITE);

    private static HoverEvent realNameHover(String realName) {
        return new HoverEvent.ShowText(Component.literal(NICK + "'s real name is " + realName));
    }

    private static MutableComponent aquaRootWithNickChild(Style nickStyle) {
        MutableComponent root = Component.literal("badge ").setStyle(AQUA_STYLE);
        root.append(Component.literal(NICK).setStyle(nickStyle));
        return root;
    }

    /** A hover that is not a real-name hover — enough to mask one if masking happened. */
    private static final HoverEvent DECOY_HOVER =
            new HoverEvent.ShowText(Component.literal("Click to view profile"));

    /** Root carrying {@code rootStyle}, with a nick child carrying the real-name
     *  hover. Both spans hold non-empty text, so both produce a part. */
    private static MutableComponent rootWithNickChild(Style rootStyle) {
        MutableComponent root = Component.literal("badge ").setStyle(rootStyle);
        root.append(
                Component.literal(NICK)
                        .setStyle(
                                Style.EMPTY
                                        .withColor(ChatFormatting.DARK_AQUA)
                                        .withHoverEvent(realNameHover(REAL_NAME))));
        return root;
    }

    /** A hover carrying {@code text} verbatim, on a nick child under an empty root. */
    private static MutableComponent rootWithHoverText(String text) {
        MutableComponent root = Component.literal("").setStyle(Style.EMPTY);
        root.append(
                Component.literal(NICK)
                        .setStyle(
                                Style.EMPTY.withHoverEvent(
                                        new HoverEvent.ShowText(Component.literal(text)))));
        return root;
    }

    @Test
    void realNameSpanStyleOrFallback_childColorOverridesParent() {
        Style nickStyle = DARK_AQUA_ITALIC.withHoverEvent(realNameHover(REAL_NAME));
        MutableComponent root = aquaRootWithNickChild(nickStyle);

        Style resolved = NickResolver.realNameSpanStyleOrFallback(root, FALLBACK);

        assertNotNull(resolved.getColor(), "color must be preserved");
        assertEquals(
                ChatFormatting.DARK_AQUA.getColor().intValue(),
                resolved.getColor().getValue(),
                "child DARK_AQUA must win over parent AQUA");
        assertTrue(resolved.isItalic(), "child italic must be preserved");
        assertNotNull(resolved.getHoverEvent(), "hover event must be preserved");
    }

    @Test
    void realNameSpanStyleOrFallback_fallsBackWhenNoHover() {
        MutableComponent root = aquaRootWithNickChild(DARK_AQUA_ITALIC);

        Style resolved = NickResolver.realNameSpanStyleOrFallback(root, FALLBACK);

        assertEquals(
                FALLBACK,
                resolved,
                "no hover-carrying span → return the caller-provided fallback verbatim");
    }

    @Test
    void realUsernameOrFallback_findsHoverUsername() {
        Style nickStyle = DARK_AQUA_ITALIC.withHoverEvent(realNameHover(REAL_NAME));
        MutableComponent root = aquaRootWithNickChild(nickStyle);

        String username = NickResolver.realUsernameOrFallback(root, "fallback-user");

        assertEquals(REAL_NAME, username);
    }

    @Test
    void realUsernameOrFallback_returnsFallbackWhenNoHover() {
        MutableComponent root = aquaRootWithNickChild(DARK_AQUA_ITALIC);

        String username = NickResolver.realUsernameOrFallback(root, "fallback-user");

        assertEquals("fallback-user", username);
    }

    @Test
    void realUsernameFromHover_matchesCaseInsensitively() {
        HoverEvent hover =
                new HoverEvent.ShowText(Component.literal("Nickname's Real Name Is " + REAL_NAME));

        assertEquals(REAL_NAME, NickResolver.realUsernameFromHover(hover));
    }

    @Test
    void realUsernameFromHover_returnsNullForNonMatchingText() {
        HoverEvent hover = new HoverEvent.ShowText(Component.literal("just a hover"));

        assertNull(NickResolver.realUsernameFromHover(hover));
    }

    @Test
    void flattenComponent_childFieldsOverrideParent() {
        MutableComponent root = Component.literal("badge ").setStyle(AQUA_STYLE);
        Style nickStyle = DARK_AQUA_ITALIC.withShadowColor(1);
        root.append(Component.literal(NICK).setStyle(nickStyle));

        List<NickResolver.FlatPart> parts = new ArrayList<>();
        NickResolver.flattenComponent(root, root.getStyle(), parts);

        assertEquals(2, parts.size(), "one FlatPart per non-empty leaf");

        NickResolver.FlatPart badge = parts.get(0);
        assertEquals("badge ", badge.text());
        assertEquals(
                ChatFormatting.AQUA.getColor().intValue(), badge.style().getColor().getValue());

        NickResolver.FlatPart nick = parts.get(1);
        assertEquals(NICK, nick.text());
        assertEquals(
                ChatFormatting.DARK_AQUA.getColor().intValue(),
                nick.style().getColor().getValue(),
                "child DARK_AQUA must win over parent AQUA");
        assertTrue(nick.style().isItalic(), "child italic must survive");
        assertNotNull(
                nick.style().getShadowColor(), "child shadowColor must survive (parent has none)");
    }

    @Test
    void flattenComponent_siblingWithoutColorInheritsParent() {
        MutableComponent root = Component.literal("").setStyle(AQUA_STYLE);
        root.append(Component.literal("child-no-color").setStyle(Style.EMPTY.withItalic(true)));

        List<NickResolver.FlatPart> parts = new ArrayList<>();
        NickResolver.flattenComponent(root, root.getStyle(), parts);

        assertEquals(1, parts.size(), "empty root literal contributes no FlatPart");
        NickResolver.FlatPart child = parts.get(0);
        assertEquals(
                ChatFormatting.AQUA.getColor().intValue(),
                child.style().getColor().getValue(),
                "parent AQUA fills the gap when child has no color");
        assertTrue(child.style().isItalic(), "child italic must survive");
    }

    // ----- The discriminating shape: an ancestor hover must not mask a descendant's -----

    @Test
    void realUsernameOrFallback_ancestorHoverDoesNotMaskTheNameSpan() {
        // The root style is not Style.EMPTY (applyTo short-circuits on
        // reference equality, which would hide either orientation) and it
        // carries a hover of its own. Under the inverted orientation the root's
        // decoy hover was copied down over the child's real-name hover, no part
        // matched, and the fallback came back. Child-wins keeps the child's.
        MutableComponent root =
                rootWithNickChild(
                        Style.EMPTY.withColor(ChatFormatting.AQUA).withHoverEvent(DECOY_HOVER));

        assertEquals(
                REAL_NAME,
                NickResolver.realUsernameOrFallback(root, NICK),
                "child.applyTo(inherited) keeps the child's hover — inverting it back"
                        + " returns the nickname instead");
    }

    @Test
    void realUsernameOrFallback_aNestedAncestorHoverDoesNotMaskTheLeafEither() {
        // The same shape one level deeper. Under the inversion the masking
        // compounded with depth and the EMPTY root did not rescue the hovering
        // mid-span, because the short-circuit only ever applies at the root.
        MutableComponent leaf =
                Component.literal(NICK)
                        .setStyle(Style.EMPTY.withHoverEvent(realNameHover(REAL_NAME)));
        MutableComponent mid =
                Component.literal("mid ")
                        .setStyle(
                                Style.EMPTY
                                        .withColor(ChatFormatting.GOLD)
                                        .withHoverEvent(DECOY_HOVER));
        mid.append(leaf);
        MutableComponent root = Component.literal("").setStyle(Style.EMPTY);
        root.append(mid);

        assertEquals(
                REAL_NAME,
                NickResolver.realUsernameOrFallback(root, NICK),
                "the mid-span's hover must not mask the leaf's, at any depth");
    }

    // ----- The pattern the walk feeds, and the walk's own order -----

    @Test
    void realUsernameOrFallback_matchesAnywhereInTheHoverAndFoldsCase() {
        // find(), not matches(), and CASE_INSENSITIVE — so the leading
        // "Nickname's" prose and an odd capitalisation both survive.
        MutableComponent root =
                rootWithHoverText("prefix Real   Name  Is  " + REAL_NAME + " suffix");

        assertEquals(
                REAL_NAME,
                NickResolver.realUsernameOrFallback(root, NICK),
                "the whitespace between the words is a multi-space class, not one literal space");
    }

    @Test
    void realUsernameOrFallback_capturesAtMostSixteenNameCharacters() {
        // The capture group is [A-Za-z0-9_]{1,16}: a longer run is truncated
        // rather than rejected.
        String seventeen = "Abcdefghijklmnopq";
        MutableComponent root = rootWithHoverText("real name is " + seventeen);

        assertEquals(seventeen.substring(0, 16), NickResolver.realUsernameOrFallback(root, NICK));
    }

    @Test
    void realUsernameOrFallback_returnsTheFirstMatchingSpanInWalkOrder() {
        // Parent before child, siblings left to right — the walk emits the
        // root's own text first, so a hover on the root wins the scan.
        MutableComponent root =
                Component.literal("badge ")
                        .setStyle(
                                Style.EMPTY.withHoverEvent(
                                        new HoverEvent.ShowText(
                                                Component.literal("real name is FirstOne"))));
        root.append(
                Component.literal(NICK)
                        .setStyle(Style.EMPTY.withHoverEvent(realNameHover(REAL_NAME))));

        assertEquals("FirstOne", NickResolver.realUsernameOrFallback(root, NICK));
    }

    @Test
    void realUsernameOrFallback_emptyRootTextContributesNoPartButStillPassesItsStyleDown() {
        // flattenComponent skips zero-length text, so the root itself yields no
        // FlatPart — but the style it carries is still what the child inherits,
        // so the hover is reached through the child rather than lost.
        MutableComponent root =
                Component.literal("")
                        .setStyle(
                                Style.EMPTY.withHoverEvent(
                                        new HoverEvent.ShowText(
                                                Component.literal("real name is Ghost"))));
        root.append(Component.literal(NICK).setStyle(Style.EMPTY));

        assertEquals("Ghost", NickResolver.realUsernameOrFallback(root, NICK));
    }

    // ----- Font resolution, which decides what reads as a pill fragment -----

    @Test
    void flattenComponent_childWithoutAFontInheritsTheParentFont() {
        // The load-bearing direction. Inside a server pill the letter glyphs
        // carry their own colour but no font of their own — the banner/pill
        // font is set once on an ancestor span. If the walk did not push it
        // down, extractPillFragments would select nothing and the supporter
        // path would bail on an empty fragment list.
        //
        // The child style is non-empty on purpose: Style.EMPTY.applyTo returns
        // its argument outright, so an empty child would inherit through the
        // short-circuit rather than through the field merge under test.
        MutableComponent root = Component.literal("").setStyle(Style.EMPTY.withFont(PILL_FONT));
        root.append(
                Component.literal("letters").setStyle(Style.EMPTY.withColor(ChatFormatting.BLACK)));

        List<NickResolver.FlatPart> parts = new ArrayList<>();
        NickResolver.flattenComponent(root, root.getStyle(), parts);

        assertEquals(1, parts.size(), "empty root literal contributes no FlatPart");
        assertEquals(
                PILL_FONT,
                parts.get(0).style().getFont(),
                "a fontless child must resolve to the ancestor's font");
    }

    @Test
    void flattenComponent_childFontOverridesTheParentFont() {
        // The other direction, and the reason the selection can discriminate at
        // all: a span that names its own font keeps it. The guild badge sits in
        // the same tree in chat/prefix and must not be collected as a pill
        // fragment.
        MutableComponent root = Component.literal("").setStyle(Style.EMPTY.withFont(PILL_FONT));
        root.append(Component.literal("badge").setStyle(Style.EMPTY.withFont(PREFIX_FONT)));

        List<NickResolver.FlatPart> parts = new ArrayList<>();
        NickResolver.flattenComponent(root, root.getStyle(), parts);

        assertEquals(1, parts.size());
        assertEquals(
                PREFIX_FONT,
                parts.get(0).style().getFont(),
                "child chat/prefix must win over the ancestor's banner/pill");
    }
}
