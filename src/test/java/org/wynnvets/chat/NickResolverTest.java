package org.wynnvets.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NickResolver}, the repo's real-name and component-flattening
 * authority — three rewriters call it and none keeps a copy of the walk.
 *
 * <p>The two {@code ancestorHover…} cases carry over from the retired
 * {@code EncourageUpdateRewriterTest} and are the only cases here that
 * discriminate {@link NickResolver#flattenComponent}'s style orientation. They
 * pin <em>child wins, ancestor fills gaps</em>: a hover on an ancestor must not
 * mask a "real name is …" hover on a descendant.
 * {@link #flattenComponent_siblingWithoutColorInheritsParent()} holds under
 * <em>either</em> orientation and is not coverage of it.</p>
 *
 * <p>The four cases below the pattern divider pin properties of
 * {@link NickResolver#REAL_NAME_PATTERN} and of the walk that feeds it, and
 * also carry over from that class.</p>
 */
class NickResolverTest {

    private static final String REAL_NAME = "RealUser";
    private static final String NICK = "Nickname";
    private static final Style AQUA_STYLE = Style.EMPTY.withColor(ChatFormatting.AQUA);
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
}
