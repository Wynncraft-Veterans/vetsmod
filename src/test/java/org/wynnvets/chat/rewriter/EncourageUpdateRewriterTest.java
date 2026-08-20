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
 * <p>FIXED HERE (was: KNOWN BUG, pinned). {@code flattenParts} used to resolve
 * each span as {@code inherited.applyTo(component.getStyle())}, the opposite
 * orientation from its three siblings ({@code org.wynnvets.chat.NickResolver
 * NickResolver.flattenComponent}, {@code SpoilerRewriter} and
 * {@code ServerGuildChatRewriter}). {@code Style#applyTo} keeps the
 * <em>receiver's</em> non-null fields, so a parent's hover won over a child's and
 * masked the "real name is …" span the resolver looks for; the method then fell
 * back to the nickname and the staff gate in {@code tryRewrite} ran against the
 * nick. Receiver and argument are now the right way round, so child fields win
 * and the ancestor only fills gaps.</p>
 *
 * <p>The two cases under "The discriminating shape" are the whole proof. They
 * asserted the inverted answer up to the commit that flipped them, and they are
 * the only mechanical check there is — every other case here holds under
 * <em>either</em> orientation and must not be mistaken for coverage of it. That
 * is exactly the trap the sibling test
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
    void resolveRealUsername_ancestorHoverDoesNotMaskTheNameSpan() {
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
                EncourageUpdateRewriter.resolveRealUsername(root, NICK),
                "child.applyTo(inherited) keeps the child's hover — inverting it back"
                        + " returns the nickname instead");
    }

    @Test
    void resolveRealUsername_aNestedAncestorHoverDoesNotMaskTheLeafEither() {
        // The same shape one level deeper. Under the inversion the masking
        // compounded with depth and the EMPTY root did not rescue the hovering
        // mid-span, because the short-circuit only ever applies at the root.
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
                REAL_NAME,
                EncourageUpdateRewriter.resolveRealUsername(root, NICK),
                "the mid-span's hover must not mask the leaf's, at any depth");
    }

    // ----- Shapes that hold under either orientation -----

    @Test
    void resolveRealUsername_ancestorWithoutAHoverDoesNotMask() {
        // Only non-null receiver fields win, so a coloured-but-hoverless
        // ancestor leaves the child's hover intact under either orientation.
        // That is why the bug was data-dependent rather than always-on — and
        // why this case is not coverage of it.
        MutableComponent root = rootWithNickChild(Style.EMPTY.withColor(ChatFormatting.AQUA));

        assertEquals(REAL_NAME, EncourageUpdateRewriter.resolveRealUsername(root, NICK));
    }

    @Test
    void resolveRealUsername_emptyRootStyleDoesNotMask() {
        // Style.EMPTY.applyTo short-circuits on reference equality, so a
        // literally-EMPTY root behaved correctly by accident even inverted.
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
