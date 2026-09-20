package org.wynnvets.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

/**
 * Pins the fifteen {@code /wv config} value lines as they render today.
 *
 * <p>Scaffolding for the collapse that follows. Every expectation here is
 * written from the pre-split bodies rather than read back from the build halves,
 * so these check the build/send split as well as the collapse after it.</p>
 *
 * <p>A value line is one component whose own run is the GRAY label and which
 * carries exactly one sibling, the value. That shape is the thing being pinned:
 * a label and a value concatenated into a single styled run would read
 * identically and would be a different component.</p>
 *
 * <p>Each build half takes the <em>key</em> and assembles its own label, so the
 * indent and the verb are part of what is pinned rather than something a caller
 * — including this test — supplies. That matters: each site has exactly one
 * correct verb, and passing the label in would let a test assert a line no site
 * can actually produce.</p>
 *
 * <p>{@link ConfigCommands}' two static fields are lambda <em>expressions</em>,
 * so every {@code VetsConfig} reference inside them is evaluated when a
 * suggestion runs, never at class-init. Nothing loads here. The build halves
 * take their value as a parameter for the same reason — the reading half stays
 * in the handlers, which are not testable and are not pinned.</p>
 */
class ConfigCommandsTest {

    /** The label run and its single value sibling, asserted together. */
    private static void assertValueLine(
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

    // ----- boolean: GREEN when true, RED when false -----

    @Test
    void listBoolLine_indentsAndUsesTheEqualsVerb() {
        assertValueLine(
                ConfigCommands.listBoolLine("printMOTD", true),
                "  printMOTD = ",
                "true",
                ChatFormatting.GREEN);
        assertValueLine(
                ConfigCommands.listBoolLine("printMOTD", false),
                "  printMOTD = ",
                "false",
                ChatFormatting.RED);
    }

    @Test
    void getBoolLine_carriesNoIndent() {
        assertValueLine(
                ConfigCommands.getBoolLine("printMOTD", true),
                "printMOTD = ",
                "true",
                ChatFormatting.GREEN);
        assertValueLine(
                ConfigCommands.getBoolLine("printMOTD", false),
                "printMOTD = ",
                "false",
                ChatFormatting.RED);
    }

    @Test
    void setBoolLine_usesTheSetToVerb() {
        assertValueLine(
                ConfigCommands.setBoolLine("printMOTD", true),
                "printMOTD set to ",
                "true",
                ChatFormatting.GREEN);
        assertValueLine(
                ConfigCommands.setBoolLine("printMOTD", false),
                "printMOTD set to ",
                "false",
                ChatFormatting.RED);
    }

    // ----- int: always AQUA -----

    @Test
    void listIntLine_indentsAndRendersTheNumberAqua() {
        assertValueLine(
                ConfigCommands.listIntLine("glintOpacity", 69L),
                "  glintOpacity = ",
                "69",
                ChatFormatting.AQUA);
    }

    @Test
    void getIntLine_rendersTheNumberAqua() {
        assertValueLine(
                ConfigCommands.getIntLine("glintOpacity", 0L),
                "glintOpacity = ",
                "0",
                ChatFormatting.AQUA);
    }

    @Test
    void setIntLine_usesTheSetToVerb() {
        assertValueLine(
                ConfigCommands.setIntLine("glintOpacity", 100L),
                "glintOpacity set to ",
                "100",
                ChatFormatting.AQUA);
    }

    @Test
    void resetIntLine_usesTheResetToVerb() {
        assertValueLine(
                ConfigCommands.resetIntLine("glintOpacity", 50L),
                "glintOpacity reset to ",
                "50",
                ChatFormatting.AQUA);
    }

    // ----- tri-state: YELLOW "default" for null, otherwise the boolean colours -----

    @Test
    void listTriStateLine_rendersNullAsAYellowDefault() {
        assertValueLine(
                ConfigCommands.listTriStateLine("vetsAnniRsvp", null),
                "  vetsAnniRsvp = ",
                "default",
                ChatFormatting.YELLOW);
    }

    @Test
    void listTriStateLine_rendersTrueAndFalseLikeABoolean() {
        assertValueLine(
                ConfigCommands.listTriStateLine("vetsAnniRsvp", Boolean.TRUE),
                "  vetsAnniRsvp = ",
                "true",
                ChatFormatting.GREEN);
        assertValueLine(
                ConfigCommands.listTriStateLine("vetsAnniRsvp", Boolean.FALSE),
                "  vetsAnniRsvp = ",
                "false",
                ChatFormatting.RED);
    }

    @Test
    void getTriStateLine_matchesTheListFormWithoutTheIndent() {
        assertValueLine(
                ConfigCommands.getTriStateLine("vetsAnniRsvp", null),
                "vetsAnniRsvp = ",
                "default",
                ChatFormatting.YELLOW);
        assertValueLine(
                ConfigCommands.getTriStateLine("vetsAnniRsvp", Boolean.TRUE),
                "vetsAnniRsvp = ",
                "true",
                ChatFormatting.GREEN);
    }

    @Test
    void setTriStateLine_usesTheSetToVerb() {
        assertValueLine(
                ConfigCommands.setTriStateLine("vetsAnniRsvp", null),
                "vetsAnniRsvp set to ",
                "default",
                ChatFormatting.YELLOW);
        assertValueLine(
                ConfigCommands.setTriStateLine("vetsAnniRsvp", Boolean.FALSE),
                "vetsAnniRsvp set to ",
                "false",
                ChatFormatting.RED);
    }

    // ----- string: the value component is supplied already styled -----
    //
    // The resolution that produces it reaches VetsConfig.getColorRgb and
    // LegacyItemStyle, which cannot load in a test. The build half's contract is
    // only that it appends whatever it is handed, unmodified.

    @Test
    void listStringLine_indentsAndAppendsTheSuppliedValueUntouched() {
        Component sprite = Component.literal("diamond").withStyle(ChatFormatting.AQUA);
        assertValueLine(
                ConfigCommands.listStringLine("legacyItemForegroundSprite", sprite),
                "  legacyItemForegroundSprite = ",
                "diamond",
                ChatFormatting.AQUA);
    }

    @Test
    void theThreeSingleFormStringLinesCarryTheirOwnVerbs() {
        Component value = Component.literal("emerald").withStyle(ChatFormatting.AQUA);
        assertValueLine(
                ConfigCommands.setSpriteLine("legacyItemForegroundSprite", value),
                "legacyItemForegroundSprite set to ",
                "emerald",
                ChatFormatting.AQUA);
        assertValueLine(
                ConfigCommands.setColourLine("vetsAnniGradientTop", value),
                "vetsAnniGradientTop set to ",
                "emerald",
                ChatFormatting.AQUA);
        assertValueLine(
                ConfigCommands.resetStringLine("vetsAnniGradientTop", value),
                "vetsAnniGradientTop reset to ",
                "emerald",
                ChatFormatting.AQUA);
    }

    /**
     * Colour-name keys resolve to an arbitrary RGB rather than a
     * {@link ChatFormatting} constant, so this one is asserted directly.
     */
    @Test
    void getStringLine_preservesAnArbitraryRgbRatherThanRecolouringIt() {
        Component colour =
                Component.literal("rebeccapurple")
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x663399)));
        Component line = ConfigCommands.getStringLine("vetsAnniGradientTop", colour);
        assertEquals("vetsAnniGradientTop = rebeccapurple", line.getString(), "rendered text");
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

    /**
     * GRAY is applied to the label only. A line that styled the whole component
     * would render the value grey, which is the mistake the split exists to make
     * visible.
     */
    @Test
    void theLabelStyleDoesNotLeakIntoTheValue() {
        Component line = ConfigCommands.getBoolLine("printMOTD", true);
        assertEquals(
                ChatFormatting.GREEN.getColor().intValue(),
                line.getSiblings().get(0).getStyle().getColor().getValue(),
                "the value keeps its own colour, not the label's GRAY");
    }
}
