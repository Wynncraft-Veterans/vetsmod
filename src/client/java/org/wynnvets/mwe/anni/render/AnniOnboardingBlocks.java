package org.wynnvets.mwe.anni.render;

import java.net.URI;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.wynnvets.mwe.anni.state.AnniSnapshot;

/**
 * The registration block of the not-announced {@code /wv anni} render for a vets-tier user: the
 * unregistered nudge, the fill-only explainer, or the role chips with the has-roles affirmation.
 *
 * <p>The registration in the snapshot picks the shape. The nudge, the explainer and the affirmation
 * are fixed prose, each with its own colours and links.</p>
 */
final class AnniOnboardingBlocks {

    private AnniOnboardingBlocks() {}

    /** The not-announced vets-user registration block — three shapes
     *  depending on the snapshot's {@code registration} field.
     *
     *  <ul>
     *    <li><b>Unregistered</b> — no registration, or one not marked
     *        registered. vets-anni marks a registration registered only
     *        when the player holds a role capability, so a player with
     *        none lands here; a player with no AnniPlayer row gets no
     *        snapshot at all. Red prose nudging them to open
     *        anni.wynnvets.org/me.</li>
     *    <li><b>Fill-only</b> — registered but no specific role
     *        capabilities, which vets-anni does not send today.
     *        Multi-line gold/grey explainer about how fill slots work +
     *        a click-through to claim a role.</li>
     *    <li><b>Has roles</b> — the existing role-chip line plus a
     *        dark-green affirmation pointing at the #annihilation
     *        Discord channel.</li>
     *  </ul> */
    static MutableComponent vetsNotAnnouncedRegistrationBlock(AnniSnapshot snapshot) {
        AnniSnapshot.Registration reg = snapshot.registration();
        if (reg == null || !reg.registered()) {
            return unregisteredNudgeBlock();
        }
        if (reg.roles().isEmpty()) {
            return fillOnlyExplainerBlock();
        }
        MutableComponent line = AnniHoverBuilder.label("Eligible Roles", ChatFormatting.GRAY);
        boolean first = true;
        for (AnniSnapshot.RoleEntry role : reg.roles()) {
            if (!first) {
                line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            }
            line.append(AnniHoverBuilder.roleChip(role));
            first = false;
        }
        line.append(Component.literal("\n"));
        line.append(allSetAffirmation());
        return line;
    }

    /** Dark-green italic affirmation appended after the role chips
     *  when the user is registered + has roles + no announced anni.
     *  Links to the #annihilation Discord channel. */
    private static MutableComponent allSetAffirmation() {
        Style base = Style.EMPTY.withColor(ChatFormatting.DARK_GREEN).withItalic(true);
        // Link gets the Discord-channel dark-aqua to read as a channel
        // reference rather than blending into the dark-green affirmation.
        Style link =
                Style.EMPTY
                        .withColor(ChatFormatting.DARK_AQUA)
                        .withItalic(true)
                        .withUnderlined(true)
                        .withClickEvent(
                                new ClickEvent.OpenUrl(
                                        URI.create(
                                                "https://discord.com/channels/1313769181321236490/1339393368672702567")))
                        .withHoverEvent(
                                new HoverEvent.ShowText(
                                        Component.literal("Open #annihilation in Discord")
                                                .withStyle(ChatFormatting.GRAY)));
        return Component.literal("You're all set for the next ")
                .withStyle(base)
                .append(Component.literal("#Annihilation").withStyle(link));
    }

    /** Four-line prose for the registered-but-fill-only case. */
    private static MutableComponent fillOnlyExplainerBlock() {
        Style yellowBoldItalic =
                Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(true).withItalic(true);
        // Lines 2 and 3 explicitly clear bold so they don't inherit
        // the &l from the &e&l&o root line above. Spec is &6&o and
        // &#7e7e7e&o — italic, NOT bold.
        Style goldItalic =
                Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(false).withItalic(true);
        Style goldItalicLink =
                goldItalic
                        .withUnderlined(true)
                        .withClickEvent(
                                new ClickEvent.OpenUrl(
                                        URI.create(
                                                "https://www.wynnvets.org/docs/guild/anni/#fill-builds")));
        Style mediumGrayItalic =
                Style.EMPTY.withColor(TextColor.fromRgb(0x7E7E7E)).withBold(false).withItalic(true);
        Style whiteBoldItalic =
                Style.EMPTY.withColor(ChatFormatting.WHITE).withBold(true).withItalic(true);
        Style aquaBoldItalicLink =
                Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withBold(true)
                        .withItalic(true)
                        .withUnderlined(true)
                        .withClickEvent(
                                new ClickEvent.OpenUrl(URI.create("https://anni.wynnvets.org/me")));

        MutableComponent out =
                Component.literal("You have not yet indicated any role capabilities!")
                        .withStyle(yellowBoldItalic);
        out.append(Component.literal("\n"));
        out.append(
                Component.literal("We will try our best to slot you in a ").withStyle(goldItalic));
        out.append(Component.literal("fill slot").withStyle(goldItalicLink));
        out.append(
                Component.literal(", but our capacity to do this is unfortunately limited!")
                        .withStyle(goldItalic));
        out.append(Component.literal("\n"));
        out.append(
                Component.literal(
                                "In the likely event we are unable to offer enough "
                                        + "fill slots, we will prioritise those who have attended the fewest Annis.")
                        .withStyle(mediumGrayItalic));
        out.append(Component.literal("\n"));
        out.append(
                Component.literal("If you believe you are able to claim a role, please do so ")
                        .withStyle(whiteBoldItalic));
        out.append(Component.literal("here").withStyle(aquaBoldItalicLink));
        out.append(Component.literal("!").withStyle(whiteBoldItalic));
        return out;
    }

    /** Two-line prose for a vets-tier user who has not opened
     *  anni.wynnvets.org/me at all. */
    private static MutableComponent unregisteredNudgeBlock() {
        Style redBold = Style.EMPTY.withColor(ChatFormatting.RED).withBold(true);
        Style yellowBoldUnderlinedLink =
                Style.EMPTY
                        .withColor(ChatFormatting.YELLOW)
                        .withBold(true)
                        .withUnderlined(true)
                        .withClickEvent(
                                new ClickEvent.OpenUrl(URI.create("https://anni.wynnvets.org/me")));
        // Both styles below explicitly clear bold so they don't inherit
        // the redBold flag from the root "You have not yet opened "
        // component — the user spec is &c! and &4&o, not &c&l! / &4&l&o.
        Style redPlain = Style.EMPTY.withColor(ChatFormatting.RED).withBold(false);
        Style darkRedItalic =
                Style.EMPTY.withColor(ChatFormatting.DARK_RED).withBold(false).withItalic(true);

        MutableComponent out = Component.literal("You have not yet opened ").withStyle(redBold);
        out.append(Component.literal("anni.wynnvets.org/me").withStyle(yellowBoldUnderlinedLink));
        out.append(Component.literal("!").withStyle(redPlain));
        out.append(Component.literal("\n"));
        out.append(
                Component.literal(
                                "Doing so unlocks all sorts of features, and helps us organise vets guild annis!")
                        .withStyle(darkRedItalic));
        return out;
    }
}
