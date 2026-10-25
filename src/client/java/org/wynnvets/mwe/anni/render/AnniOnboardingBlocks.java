package org.wynnvets.mwe.anni.render;

import java.net.URI;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.wynnvets.mwe.anni.state.AnniSnapshot;

/**
 * The registration part of the not-announced {@code /wv anni} render for a vets-tier user: the
 * role chips, shown beside the dashboard link, or, for a player without roles, the unregistered
 * nudge or the fill-only explainer under the prediction.
 *
 * <p>The registration in the snapshot picks the shape. The nudge and the explainer are fixed
 * prose, each with its own colours and links.</p>
 */
final class AnniOnboardingBlocks {

    private AnniOnboardingBlocks() {}

    /** The not-announced vets-user registration block under the prediction — two shapes
     *  depending on the snapshot's {@code registration} field, and none for a player with
     *  roles, whose roles ride on the link line ({@link #vetsNotAnnouncedRoles}).
     *
     *  <ul>
     *    <li><b>Unregistered</b> — no registration, or one not marked
     *        registered. vets-anni marks a registration registered only
     *        when the player holds a role capability, so a player with
     *        none lands here; a player with no AnniPlayer row gets no
     *        snapshot at all. Red prose nudging them to choose their roles
     *        on anni.wynnvets.org/me.</li>
     *    <li><b>Fill-only</b> — registered but no specific role
     *        capabilities, which vets-anni does not send today.
     *        Multi-line gold/grey explainer about how fill slots work +
     *        a click-through to claim a role.</li>
     *    <li><b>Has roles</b> — {@code null}: nothing under the prediction.</li>
     *  </ul> */
    static MutableComponent vetsNotAnnouncedRegistrationBlock(AnniSnapshot snapshot) {
        if (showsUnregisteredNudge(snapshot)) {
            return unregisteredNudgeBlock();
        }
        if (snapshot.registration().roles().isEmpty()) {
            return fillOnlyExplainerBlock();
        }
        return null;
    }

    /** Whether {@link #vetsNotAnnouncedRegistrationBlock} is the unregistered nudge: no
     *  registration, or one not marked registered. The nudge carries its own
     *  anni.wynnvets.org/me link, so the not-announced render then leaves out the dashboard link
     *  line above it. */
    static boolean showsUnregisteredNudge(AnniSnapshot snapshot) {
        AnniSnapshot.Registration reg = snapshot.registration();
        return reg == null || !reg.registered();
    }

    /** The {@code Eligible Roles} line for a registered vets user with roles, which the
     *  not-announced render puts beside the dashboard link; {@code null} for anyone else, who
     *  gets {@link #vetsNotAnnouncedRegistrationBlock} under the prediction instead. */
    static MutableComponent vetsNotAnnouncedRoles(AnniSnapshot snapshot) {
        AnniSnapshot.Registration reg = snapshot.registration();
        if (reg == null || !reg.registered() || reg.roles().isEmpty()) {
            return null;
        }
        return AnniHoverBuilder.eligibleRolesLine(reg.roles());
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

    /** Two-line prose for the Unregistered arm: true of every vets-tier user without a role
     *  capability, whether or not they have opened anni.wynnvets.org/me, since vets-anni
     *  marks a registration registered only when it holds a role. */
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
        // the redBold flag from the root "You have not yet chosen your roles on "
        // component — the user spec is &c! and &4&o, not &c&l! / &4&l&o.
        Style redPlain = Style.EMPTY.withColor(ChatFormatting.RED).withBold(false);
        Style darkRedItalic =
                Style.EMPTY.withColor(ChatFormatting.DARK_RED).withBold(false).withItalic(true);

        MutableComponent out =
                Component.literal("You have not yet chosen your roles on ").withStyle(redBold);
        out.append(Component.literal("anni.wynnvets.org/me").withStyle(yellowBoldUnderlinedLink));
        out.append(Component.literal("!").withStyle(redPlain));
        out.append(Component.literal("\n"));
        // Broken after the comma: unbroken, the default chat width wraps "organise" onto a
        // line of its own.
        out.append(
                Component.literal(
                                "Doing so unlocks all sorts of features,\nand helps us organise vets guild annis!")
                        .withStyle(darkRedItalic));
        return out;
    }
}
