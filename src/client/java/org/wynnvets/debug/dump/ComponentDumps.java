package org.wynnvets.debug.dump;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Renders a {@link Component} in the forms an item dump records: a JSON tree of the component
 * and its siblings, a string with a colour marker per coloured node, a code-point list, and the
 * fonts a line uses. Reads nothing but the components and text it is given.
 */
final class ComponentDumps {

    private ComponentDumps() {}

    // ── Component serialization and helpers ──────────────────────────
    //
    // componentToJson renders a Component as a JSON tree, and
    // formatComponentWithCodes/appendFormatted as a string with a §[colour]
    // marker per coloured node. toCodepointString is a general helper that
    // also sits here.

    static JsonObject componentToJson(Component comp) {
        JsonObject obj = new JsonObject();
        obj.addProperty("text", comp.getString());
        obj.addProperty("literalContent", comp.getContents().toString());

        Style style = comp.getStyle();
        if (!style.isEmpty()) {
            JsonObject styleObj = new JsonObject();
            TextColor color = style.getColor();
            if (color != null) {
                String name = color.serialize();
                styleObj.addProperty("color", name);
                styleObj.addProperty("color_value", color.getValue());
            }
            if (style.isBold()) styleObj.addProperty("bold", true);
            if (style.isItalic()) styleObj.addProperty("italic", true);
            if (style.isUnderlined()) styleObj.addProperty("underlined", true);
            if (style.isStrikethrough()) styleObj.addProperty("strikethrough", true);
            if (style.isObfuscated()) styleObj.addProperty("obfuscated", true);
            // Wynncraft disables text shadow on its boxed tooltip headers by
            // setting an explicit shadowColor. Serialized as both the raw ARGB
            // and hex because whether it is set at all — and with which alpha —
            // is otherwise invisible when diffing our output against theirs.
            Integer shadowColor = style.getShadowColor();
            if (shadowColor != null) {
                styleObj.addProperty("shadowColor", shadowColor);
                styleObj.addProperty("shadowColor_hex", String.format("#%08X", shadowColor));
            }
            styleObj.addProperty("font", style.getFont().toString());
            obj.add("style", styleObj);
        }

        List<Component> siblings = comp.getSiblings();
        if (!siblings.isEmpty()) {
            JsonArray sibs = new JsonArray();
            for (Component sib : siblings) {
                sibs.add(componentToJson(sib));
            }
            obj.add("siblings", sibs);
        }

        return obj;
    }

    static String formatComponentWithCodes(Component comp) {
        StringBuilder sb = new StringBuilder();
        appendFormatted(sb, comp);
        return sb.toString();
    }

    static void appendFormatted(StringBuilder sb, Component comp) {
        Style style = comp.getStyle();
        TextColor color = style.getColor();
        if (color != null) {
            sb.append("\u00A7[").append(color.serialize()).append("]");
        }
        String contents = comp.getContents().toString();
        if (contents.startsWith("literal{")) {
            sb.append(contents.substring(8, contents.length() - 1));
        }
        for (Component sib : comp.getSiblings()) {
            appendFormatted(sb, sib);
        }
    }

    static String toCodepointString(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("U+%04X", cp));
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /**
     * Dumps a list of components as an array of per-line objects, each with
     * its list index, raw text, formatted text, codepoint string, font usage
     * summary, and the full component tree.
     */
    static JsonArray dumpComponentLines(List<Component> lines) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            JsonObject lineObj = new JsonObject();
            lineObj.addProperty("index", i);
            lineObj.addProperty("raw", line.getString());
            lineObj.addProperty("formatted", formatComponentWithCodes(line));
            lineObj.addProperty("codepoints", toCodepointString(line.getString()));
            lineObj.add("fonts", collectFontsAsArray(line));
            lineObj.add("tree", componentToJson(line));
            arr.add(lineObj);
        }
        return arr;
    }

    /**
     * Walks a Component tree and collects the distinct {@code FontDescription}
     * {@code toString()} values encountered (an unstyled node carries
     * {@code FontDescription.DEFAULT}, so it reports that record's text, not a bare
     * {@code minecraft:default} identifier). Order is insertion order to make diffs stable.
     */
    static JsonArray collectFontsAsArray(Component root) {
        Set<String> fonts = new LinkedHashSet<>();
        root.visit(
                (Style style, String text) -> {
                    fonts.add(style.getFont().toString());
                    return Optional.empty();
                },
                Style.EMPTY);
        JsonArray arr = new JsonArray();
        for (String f : fonts) arr.add(f);
        return arr;
    }

    /**
     * Approximates {@link org.wynnvets.items.NewFormatRenderer#isNewFormatItem
     * NewFormatRenderer#isNewFormatItem} without coupling the dump command to that package-private
     * class. Returns true if any line carries a font whose {@code toString()} contains
     * {@code tooltip/emblem/frame} or {@code banner/box} (the markers Wynncraft's new tooltip
     * format relies on). The original matches those two {@code FontDescription}s exactly, and on a
     * hit also sets {@code LegacyItemHandler.newTooltipStylesAvailable}; this probe does neither.
     */
    static boolean containsNewFormatFont(List<Component> lines) {
        for (Component line : lines) {
            boolean[] hit = {false};
            line.visit(
                    (Style style, String text) -> {
                        String s = style.getFont().toString();
                        if (s.contains("tooltip/emblem/frame") || s.contains("banner/box")) {
                            hit[0] = true;
                            return Optional.of(Boolean.TRUE);
                        }
                        return Optional.empty();
                    },
                    Style.EMPTY);
            if (hit[0]) return true;
        }
        return false;
    }
}
