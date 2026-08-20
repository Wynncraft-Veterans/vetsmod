package org.wynnvets.chat.rewriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EncourageUpdateRewriter#resolveRealUsername(Component, String)}.
 *
 * <p>KNOWN BUG (pinned, not fixed): {@code flattenParts} resolves each span as
 * {@code inherited.applyTo(component.getStyle())}, the opposite orientation
 * from its three siblings ({@code org.wynnvets.chat.NickResolver
 * NickResolver.flattenComponent}, {@code SpoilerRewriter} and
 * {@code ServerGuildChatRewriter}). {@code Style#applyTo} keeps the
 * <em>receiver's</em> non-null fields, so here a parent's hover wins over a
 * child's and masks the "real name is …" span the resolver is looking for. See
 * {@code
 * .claude/ephemeral/bugs-found-via-mellow-rain/encourage-update-rewriter-style-precedence-inverted.md}.</p>
 *
 * <p>The two cases under "The discriminating shape" are deliberately
 * <b>red-after</b>: they assert today's inverted answer, and the scheduled fix
 * (pointing the class at {@code NickResolver.flattenComponent}) flips both to
 * {@code REAL_NAME}. That flip is the signal the fix landed, and it is the only
 * mechanical check there is — every other case here holds under <em>either</em>
 * orientation and must not be mistaken for coverage of the bug. That is exactly
 * the trap the sibling test
 * {@code NickResolverTest.flattenComponent_siblingWithoutColorInheritsParent}
 * falls into.</p>
 *
 * <p>{@code tryRewrite} cannot observe any of this: a body matching
 * {@code ENCOURAGE_PATTERN} reaches Wynntils {@code ComponentUtils}, and a body
 * that does not returns {@code false} under both orientations. The resolver is
 * therefore driven directly.</p>
 *
 * <p>NOTE: {@link EncourageUpdateRewriter} imports Wynntils, but its static
 * state is two {@code Pattern} constants, so nothing loads during class-init.
 * If a future contributor adds a static initializer that loads MC or Wynntils,
 * this test starts failing at class-init time and the fix is to extract the
 * pure method.</p>
 */
class EncourageUpdateRewriterTest {

    private static final String REAL_NAME = "RealUser";
    private static final String NICK = "Nickname";

    /** A hover that is not a real-name hover — enough to mask one. */
    private static final HoverEvent DECOY_HOVER =
            new HoverEvent.ShowText(Component.literal("Click to view profile"));

    private static HoverEvent realNameHover() {
        return new HoverEvent.ShowText(Component.literal(NICK + "'s real name is " + REAL_NAME));
    }

    /** Root carrying {@code rootStyle}, with a nick child carrying the
     *  real-name hover. Both spans hold non-empty text, so both produce a part. */
    private static MutableComponent rootWithNickChild(Style rootStyle) {
        MutableComponent root = Component.literal("badge ").setStyle(rootStyle);
        root.append(
                Component.literal(NICK)
                        .setStyle(
                                Style.EMPTY
                                        .withColor(ChatFormatting.DARK_AQUA)
                                        .withHoverEvent(realNameHover())));
        return root;
    }

    // ----- The discriminating shape -----

    @Test
    void resolveRealUsername_ancestorHoverMasksTheNameSpan_returnsTheNicknameFallback() {
        // The root style is not Style.EMPTY (applyTo short-circuits on
        // reference equality, which would hide the inversion) and it carries a
        // hover of its own. Under the inverted orientation the root's decoy
        // hover is copied down over the child's real-name hover, no part
        // matches, and the fallback is returned.
        MutableComponent root =
                rootWithNickChild(
                        Style.EMPTY.withColor(ChatFormatting.AQUA).withHoverEvent(DECOY_HOVER));

        assertEquals(
                NICK,
                EncourageUpdateRewriter.resolveRealUsername(root, NICK),
                "inherited.applyTo(child) lets the parent hover win — the fix flips this to"
                        + " the real name");
    }

    @Test
    void resolveRealUsername_maskingCompoundsThroughNestedAncestors() {
        // Same defect one level deeper: the short-circuit only ever applies at
        // the root, so an EMPTY root does not rescue a hovering mid-span.
        MutableComponent leaf =
                Component.literal(NICK).setStyle(Style.EMPTY.withHoverEvent(realNameHover()));
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
                NICK,
                EncourageUpdateRewriter.resolveRealUsername(root, NICK),
                "the mid-span's hover masks the leaf's regardless of the root");
    }

    // ----- Shapes that hold under either orientation -----

    @Test
    void resolveRealUsername_ancestorWithoutAHoverDoesNotMask() {
        // Only non-null receiver fields win, so a coloured-but-hoverless
        // ancestor leaves the child's hover intact. This is why the bug is
        // data-dependent rather than always-on — and why it is not coverage.
        MutableComponent root = rootWithNickChild(Style.EMPTY.withColor(ChatFormatting.AQUA));

        assertEquals(REAL_NAME, EncourageUpdateRewriter.resolveRealUsername(root, NICK));
    }

    @Test
    void resolveRealUsername_emptyRootStyleDoesNotMask() {
        MutableComponent root = rootWithNickChild(Style.EMPTY);

        assertEquals(REAL_NAME, EncourageUpdateRewriter.resolveRealUsername(root, NICK));
    }

    @Test
    void resolveRealUsername_noHoverAnywhereReturnsTheFallback() {
        MutableComponent root = Component.literal("badge ").setStyle(Style.EMPTY);
        root.append(Component.literal(NICK));

        assertEquals(NICK, EncourageUpdateRewriter.resolveRealUsername(root, NICK));
    }

    @Test
    void resolveRealUsername_nonMatchingHoverTextIsIgnored() {
        MutableComponent root = Component.literal("badge ").setStyle(Style.EMPTY);
        root.append(Component.literal(NICK).setStyle(Style.EMPTY.withHoverEvent(DECOY_HOVER)));

        assertEquals(NICK, EncourageUpdateRewriter.resolveRealUsername(root, NICK));
    }

    // ----- The pattern the walk feeds -----

    @Test
    void resolveRealUsername_matchesAnywhereInTheHoverAndFoldsCase() {
        // find(), not matches(), and CASE_INSENSITIVE — so the leading
        // "Nickname's" prose and an odd capitalisation both survive.
        MutableComponent root = Component.literal("").setStyle(Style.EMPTY);
        root.append(
                Component.literal(NICK)
                        .setStyle(
                                Style.EMPTY.withHoverEvent(
                                        new HoverEvent.ShowText(
                                                Component.literal(
                                                        "prefix Real   Name  Is  "
                                                                + REAL_NAME
                                                                + " suffix")))));

        assertEquals(
                REAL_NAME,
                EncourageUpdateRewriter.resolveRealUsername(root, NICK),
                "whitespace between the words is \\s+, not a single space");
    }

    @Test
    void resolveRealUsername_capturesAtMostSixteenNameCharacters() {
        // The capture group is [A-Za-z0-9_]{1,16}: a longer run is truncated
        // rather than rejected.
        String seventeen = "Abcdefghijklmnopq";
        MutableComponent root = Component.literal("").setStyle(Style.EMPTY);
        root.append(
                Component.literal(NICK)
                        .setStyle(
                                Style.EMPTY.withHoverEvent(
                                        new HoverEvent.ShowText(
                                                Component.literal("real name is " + seventeen)))));

        assertEquals(
                seventeen.substring(0, 16),
                EncourageUpdateRewriter.resolveRealUsername(root, NICK));
    }

    @Test
    void resolveRealUsername_returnsTheFirstMatchingSpanInWalkOrder() {
        // Parent before child, siblings left to right — the walk emits the
        // root's own text first, so a hover on the root wins the scan.
        MutableComponent root =
                Component.literal("badge ")
                        .setStyle(
                                Style.EMPTY.withHoverEvent(
                                        new HoverEvent.ShowText(
                                                Component.literal("real name is FirstOne"))));
        root.append(Component.literal(NICK).setStyle(Style.EMPTY.withHoverEvent(realNameHover())));

        assertEquals("FirstOne", EncourageUpdateRewriter.resolveRealUsername(root, NICK));
    }

    @Test
    void resolveRealUsername_emptyRootTextContributesNoPartButStillPassesItsStyleDown() {
        // flattenParts skips zero-length text, so the root itself yields no
        // FlatPart — but the style it carries is still what the child inherits,
        // so the hover is reached through the child rather than lost.
        MutableComponent root =
                Component.literal("")
                        .setStyle(
                                Style.EMPTY.withHoverEvent(
                                        new HoverEvent.ShowText(
                                                Component.literal("real name is Ghost"))));
        root.append(Component.literal(NICK).setStyle(Style.EMPTY));

        assertEquals("Ghost", EncourageUpdateRewriter.resolveRealUsername(root, NICK));
    }
}
