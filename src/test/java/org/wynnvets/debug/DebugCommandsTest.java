package org.wynnvets.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/**
 * Pins the three {@code /wv debug set} value lines as they render today.
 *
 * <p>Scaffolding for the collapse that follows, and the debug-side half of what
 * {@code ConfigCommandsTest} does for {@code /wv config}. The three are
 * byte-identical to their {@code /wv config} counterparts modulo the label, so
 * the point of pinning both is that the collapse has to keep them that way
 * across a package boundary.</p>
 *
 * <p>{@link DebugCommands} is the one class in this chunk with a non-trivial
 * static initializer — {@code CHAT_PREFIX_FONT} constructs a
 * {@code FontDescription.Resource}. That runs at class-init and is fine here:
 * {@code NickResolverTest} already builds one in a green test. The three
 * handlers stay private; they read {@code VetsConfig} and are not testable
 * either way, so only the build halves are widened.</p>
 */
class DebugCommandsTest {

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

    @Test
    void debugListBoolLine_indentsAndUsesTheEqualsVerb() {
        assertValueLine(
                DebugCommands.debugListBoolLine("itemDump", true),
                "  itemDump = ",
                "true",
                ChatFormatting.GREEN);
        assertValueLine(
                DebugCommands.debugListBoolLine("itemDump", false),
                "  itemDump = ",
                "false",
                ChatFormatting.RED);
    }

    @Test
    void debugGetBoolLine_carriesNoIndent() {
        assertValueLine(
                DebugCommands.debugGetBoolLine("itemDump", true),
                "itemDump = ",
                "true",
                ChatFormatting.GREEN);
        assertValueLine(
                DebugCommands.debugGetBoolLine("itemDump", false),
                "itemDump = ",
                "false",
                ChatFormatting.RED);
    }

    @Test
    void debugSetBoolLine_usesTheSetToVerb() {
        assertValueLine(
                DebugCommands.debugSetBoolLine("itemDump", true),
                "itemDump set to ",
                "true",
                ChatFormatting.GREEN);
        assertValueLine(
                DebugCommands.debugSetBoolLine("itemDump", false),
                "itemDump set to ",
                "false",
                ChatFormatting.RED);
    }

    /**
     * {@code String.valueOf(boolean)} and {@code Boolean.toString(boolean)} are
     * the same method, so swapping one for the other in the renderer is not a
     * mutation any test can catch. Pinning the exact text is what there is;
     * recorded as a result rather than as a gap.
     */
    @Test
    void theValueTextIsExactlyTrueOrFalse() {
        assertEquals(
                "itemDump = true", DebugCommands.debugGetBoolLine("itemDump", true).getString());
        assertEquals(
                "itemDump = false", DebugCommands.debugGetBoolLine("itemDump", false).getString());
    }
}
