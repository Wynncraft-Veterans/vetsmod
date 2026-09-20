package org.wynnvets.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;
import org.wynnvets.util.ConfigValueText.Form;
import org.wynnvets.util.ConfigValueText.Verb;

/**
 * Tests for {@link ConfigValueText}'s four value kinds and its two label axes.
 *
 * <p>This is the coverage that outlives the chunk. Per-site pins in
 * {@code ConfigCommandsTest} and {@code DebugCommandsTest} existed only to prove
 * that the eighteen call sites had converged onto this class. They were deleted
 * once they had, so neither is in the tree; these are the tests that say what the
 * line is.</p>
 *
 * <p>One thing went with them and has not been replaced: nothing now pins which
 * {@link Form} and {@link Verb} any individual call site passes. This class
 * guarantees the renderer honours the pair it is handed, not that
 * {@code configList} asks for {@code LIST}/{@code EQUALS}. Those call sites read
 * {@code VetsConfig} and are not reachable from a test.</p>
 *
 * <p>{@link ConfigValueText} declares no static state beyond two enums, so nothing
 * loads at class-init. That is deliberate: the string kind takes an
 * already-resolved value precisely so this class never has to reach
 * {@code VetsConfig}.</p>
 */
class ConfigValueTextTest {

    /** The GRAY label run and its single value sibling, asserted together. */
    private static void assertLine(
            Component line, String label, String valueText, ChatFormatting valueColour) {
        assertEquals(label + valueText, line.getString(), "rendered text");
        assertEquals(
                1, line.getSiblings().size(), "a value line is a label plus one value sibling");
        assertEquals(
                ChatFormatting.GRAY.getColor().intValue(),
                line.getStyle().getColor().getValue(),
                "the label is GRAY");
        Component value = line.getSiblings().get(0);
        assertEquals(valueText, value.getString(), "value text");
        assertEquals(
                valueColour.getColor().intValue(),
                value.getStyle().getColor().getValue(),
                "value colour");
    }

    // ----- the label axes: form supplies the indent, verb the separator -----

    @Test
    void listFormIndentsAndSingleFormDoesNot() {
        assertLine(
                ConfigValueText.booleanLine(Form.LIST, "printMOTD", Verb.EQUALS, true),
                "  printMOTD = ",
                "true",
                ChatFormatting.GREEN);
        assertLine(
                ConfigValueText.booleanLine(Form.SINGLE, "printMOTD", Verb.EQUALS, true),
                "printMOTD = ",
                "true",
                ChatFormatting.GREEN);
    }

    @Test
    void allThreeVerbsRender() {
        assertLine(
                ConfigValueText.intLine(Form.SINGLE, "glintOpacity", Verb.EQUALS, 69L),
                "glintOpacity = ",
                "69",
                ChatFormatting.AQUA);
        assertLine(
                ConfigValueText.intLine(Form.SINGLE, "glintOpacity", Verb.SET_TO, 69L),
                "glintOpacity set to ",
                "69",
                ChatFormatting.AQUA);
        assertLine(
                ConfigValueText.intLine(Form.SINGLE, "glintOpacity", Verb.RESET_TO, 69L),
                "glintOpacity reset to ",
                "69",
                ChatFormatting.AQUA);
    }

    /** The two axes are independent: either form composes with any verb. */
    @Test
    void formAndVerbCompose() {
        assertLine(
                ConfigValueText.booleanLine(Form.LIST, "printANNI", Verb.RESET_TO, false),
                "  printANNI reset to ",
                "false",
                ChatFormatting.RED);
    }

    // ----- boolean -----

    @Test
    void booleanLine_isGreenWhenTrueAndRedWhenFalse() {
        assertLine(
                ConfigValueText.booleanLine(Form.SINGLE, "handleSpoilers", Verb.SET_TO, true),
                "handleSpoilers set to ",
                "true",
                ChatFormatting.GREEN);
        assertLine(
                ConfigValueText.booleanLine(Form.SINGLE, "handleSpoilers", Verb.SET_TO, false),
                "handleSpoilers set to ",
                "false",
                ChatFormatting.RED);
    }

    // ----- int -----

    @Test
    void intLine_isAquaWhateverTheNumber() {
        for (long value : new long[] {0L, 50L, 100L, -1L, Long.MAX_VALUE}) {
            assertLine(
                    ConfigValueText.intLine(Form.SINGLE, "glintOpacity", Verb.EQUALS, value),
                    "glintOpacity = ",
                    String.valueOf(value),
                    ChatFormatting.AQUA);
        }
    }

    // ----- tri-state -----

    @Test
    void triStateLine_rendersNullAsAYellowDefault() {
        assertLine(
                ConfigValueText.triStateLine(Form.LIST, "vetsAnniRsvp", Verb.EQUALS, null),
                "  vetsAnniRsvp = ",
                "default",
                ChatFormatting.YELLOW);
    }

    /**
     * Once a tri-state has a value it <em>is</em> the boolean, so the two must not
     * drift apart: same text, same colour, same tree.
     */
    @Test
    void triStateLine_matchesBooleanLineExactlyWhenSet() {
        for (boolean value : new boolean[] {true, false}) {
            Component tri =
                    ConfigValueText.triStateLine(Form.SINGLE, "vetsAnniRsvp", Verb.SET_TO, value);
            Component bool =
                    ConfigValueText.booleanLine(Form.SINGLE, "vetsAnniRsvp", Verb.SET_TO, value);
            assertEquals(bool.getString(), tri.getString(), "text");
            assertEquals(bool.getSiblings().size(), tri.getSiblings().size(), "sibling count");
            assertEquals(
                    bool.getSiblings().get(0).getStyle().getColor().getValue(),
                    tri.getSiblings().get(0).getStyle().getColor().getValue(),
                    "value colour");
        }
    }

    // ----- string -----

    @Test
    void stringLine_appendsANamedColourValueUntouched() {
        Component sprite = Component.literal("diamond").withStyle(ChatFormatting.AQUA);
        assertLine(
                ConfigValueText.stringLine(
                        Form.LIST, "legacyItemForegroundSprite", Verb.EQUALS, sprite),
                "  legacyItemForegroundSprite = ",
                "diamond",
                ChatFormatting.AQUA);
    }

    /**
     * Colour-name keys resolve to an arbitrary RGB rather than a
     * {@link ChatFormatting} constant, so this one is asserted directly. The
     * supplied style must survive: the label's GRAY applies to the label only.
     */
    @Test
    void stringLine_preservesAnArbitraryRgbAndDoesNotLeakTheLabelStyleIntoIt() {
        Component colour =
                Component.literal("rebeccapurple")
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x663399)));
        Component line =
                ConfigValueText.stringLine(
                        Form.SINGLE, "vetsAnniGradientTop", Verb.RESET_TO, colour);
        assertEquals("vetsAnniGradientTop reset to rebeccapurple", line.getString(), "text");
        assertEquals(1, line.getSiblings().size(), "label plus one value sibling");
        assertEquals(
                ChatFormatting.GRAY.getColor().intValue(),
                line.getStyle().getColor().getValue(),
                "the label is GRAY");
        assertEquals(
                0x663399,
                line.getSiblings().get(0).getStyle().getColor().getValue(),
                "the supplied RGB survives — it is not folded onto a named colour");
    }

    // ----- the shape itself -----

    /**
     * Every kind produces the same two-run shape. A collapse that merged the label
     * and value into one run would render identically and is the mistake the
     * per-site pins exist to catch; this states the rule once.
     */
    @Test
    void everyKindProducesALabelRunPlusExactlyOneValueSibling() {
        Component[] lines = {
            ConfigValueText.booleanLine(Form.LIST, "k", Verb.EQUALS, true),
            ConfigValueText.intLine(Form.SINGLE, "k", Verb.SET_TO, 1L),
            ConfigValueText.triStateLine(Form.SINGLE, "k", Verb.RESET_TO, null),
            ConfigValueText.stringLine(
                    Form.LIST,
                    "k",
                    Verb.EQUALS,
                    Component.literal("v").withStyle(ChatFormatting.AQUA)),
        };
        for (Component line : lines) {
            assertEquals(1, line.getSiblings().size(), line.getString());
            assertEquals(
                    ChatFormatting.GRAY.getColor().intValue(),
                    line.getStyle().getColor().getValue(),
                    line.getString());
            assertEquals(0, line.getSiblings().get(0).getSiblings().size(), "the value is flat");
        }
    }
}
