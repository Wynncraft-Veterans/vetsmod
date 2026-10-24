package org.wynnvets.mwe.anni.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.wynnvets.mwe.anni.state.AnniSnapshot;

/**
 * Goldens for {@link AnniOnboardingBlocks#vetsNotAnnouncedRegistrationBlock}, the registration
 * block of the not-announced {@code /wv anni} render, on the shapes a test can build: the
 * unregistered nudge and the fill-only explainer.
 *
 * <p>Each golden is the block's {@code Component.toString()}, which spells out every segment's
 * text, colour, flags (an explicit {@code false} included), click and hover. That departs from
 * {@code HelpCommandsTest}'s walk over sibling text, because here the click and hover targets are
 * part of what the render produces. The string form is Minecraft 1.21.11's, so a Minecraft update
 * that changes {@code Component.toString()} will need the goldens regenerated.</p>
 *
 * <p>For a player with roles the block is {@code null}, since the roles go beside the dashboard
 * link, and {@link AnniOnboardingBlocks#vetsNotAnnouncedRoles} is {@code null} for everyone else;
 * both are pinned. Not covered: the roles line itself. Its role chips come from
 * {@code AnniHoverBuilder.roleChip}, which reads the role style (through {@code displayRole}) and
 * whether hover details are on from {@code VetsConfig}, and {@code VetsConfig} cannot initialise
 * in a test.</p>
 */
class AnniOnboardingBlocksTest {

    private static AnniSnapshot snapshot(String json) {
        return AnniSnapshot.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    private static final String UNREGISTERED_NUDGE =
            "literal{You have not yet chosen your roles on }[style={color=red,bold}, "
                    + "siblings=[literal{anni.wynnvets.org/me}[style={color=yellow,bold,"
                    + "underlined,clickEvent=OpenUrl[uri=https://anni.wynnvets.org/me]}], "
                    + "literal{!}[style={color=red,!bold}], literal{\n}, "
                    + "literal{Doing so unlocks all sorts of features, "
                    + "and helps us organise vets guild annis!}[style={color=dark_red,!bold,"
                    + "italic}]]]";

    @Test
    void registrationBlock_withNoRegistration_isTheUnregisteredNudge() {
        assertEquals(
                UNREGISTERED_NUDGE,
                AnniOnboardingBlocks.vetsNotAnnouncedRegistrationBlock(snapshot("{}")).toString());
    }

    @Test
    void registrationBlock_whenNotRegistered_isTheSameNudge() {
        AnniSnapshot notRegistered = snapshot("{\"registration\": {\"registered\": false}}");

        assertEquals(
                UNREGISTERED_NUDGE,
                AnniOnboardingBlocks.vetsNotAnnouncedRegistrationBlock(notRegistered).toString());
    }

    @Test
    void registrationBlock_registeredWithNoRoles_isTheFillOnlyExplainer() {
        AnniSnapshot fillOnly =
                snapshot("{\"registration\": {\"registered\": true, \"roles\": []}}");

        assertEquals(
                "literal{You have not yet indicated any role capabilities!}[style={color=yellow,"
                        + "bold,italic}, siblings=[literal{\n}, "
                        + "literal{We will try our best to slot you in a }[style={color=gold,"
                        + "!bold,italic}], literal{fill slot}[style={color=gold,!bold,italic,"
                        + "underlined,clickEvent=OpenUrl[uri=https://www.wynnvets.org/docs/guild/a"
                        + "nni/#fill-builds]}], literal{, "
                        + "but our capacity to do this is unfortunately "
                        + "limited!}[style={color=gold,!bold,italic}], literal{\n}, "
                        + "literal{In the likely event we are unable to offer enough fill slots, "
                        + "we will prioritise those who have attended the fewest "
                        + "Annis.}[style={color=#7E7E7E,!bold,italic}], literal{\n}, "
                        + "literal{If you believe you are able to claim a role, "
                        + "please do so }[style={color=white,bold,italic}], "
                        + "literal{here}[style={color=aqua,bold,italic,underlined,"
                        + "clickEvent=OpenUrl[uri=https://anni.wynnvets.org/me]}], "
                        + "literal{!}[style={color=white,bold,italic}]]]",
                AnniOnboardingBlocks.vetsNotAnnouncedRegistrationBlock(fillOnly).toString());
    }

    @Test
    void registrationBlock_withRoles_isNull() {
        AnniSnapshot withRoles =
                snapshot(
                        "{\"registration\": {\"registered\": true, \"roles\": [{\"role\":"
                                + " \"TANK\"}]}}");

        assertNull(AnniOnboardingBlocks.vetsNotAnnouncedRegistrationBlock(withRoles));
    }

    @Test
    void rolesLine_isNullWithoutRoles() {
        assertNull(AnniOnboardingBlocks.vetsNotAnnouncedRoles(snapshot("{}")));
        assertNull(
                AnniOnboardingBlocks.vetsNotAnnouncedRoles(
                        snapshot("{\"registration\": {\"registered\": false}}")));
        assertNull(
                AnniOnboardingBlocks.vetsNotAnnouncedRoles(
                        snapshot("{\"registration\": {\"registered\": true, \"roles\": []}}")));
    }
}
