package org.wynnvets.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * The one way this mod renders <em>"this config key is currently that value"</em> to chat.
 *
 * <p>Eighteen sites built this line for themselves &mdash; fifteen across {@code /wv config}'s
 * list, get and set paths, three more across {@code /wv debug set}'s. Six of them, the boolean
 * ones, were byte-identical modulo indentation and the verb. The other twelve differed only in
 * how the <em>value</em> half is coloured. The line lives here once so a reader comparing two
 * call sites is comparing the key and the kind rather than re-deriving that both start the
 * same way.</p>
 *
 * <h2>The line is two runs, and that is the contract</h2>
 * <p>Every method here returns a component whose own run is the GRAY label and which carries
 * exactly one sibling: the value, in the colour its kind dictates. A label and a value
 * concatenated into a single styled run would render identically to a player and would be a
 * different component &mdash; different sibling count, and the value would inherit the label's
 * grey. The two-run shape is what the callers had before this class existed and is what they
 * must still have after it.</p>
 *
 * <h2>Colour is a property of the kind, not of the caller</h2>
 * <ul>
 *   <li><b>boolean</b> &mdash; GREEN when true, RED when false.</li>
 *   <li><b>tri-state</b> &mdash; a YELLOW {@code "default"} when unset, otherwise exactly the
 *       boolean colours. {@link #triStateLine} delegates its non-null case to
 *       {@link #booleanLine}'s value run for that reason: the two are not merely similar, the
 *       tri-state <em>is</em> the boolean once a value is present.</li>
 *   <li><b>int</b> &mdash; always AQUA. Nothing about the number changes it.</li>
 *   <li><b>string</b> &mdash; the caller supplies the value already styled, and it is appended
 *       untouched. It has to be: colour-name keys resolve through
 *       {@code VetsConfig.getColorRgb} to an arbitrary RGB, which reaches {@code LegacyItemStyle}
 *       and so cannot be resolved anywhere this class could be tested.</li>
 * </ul>
 *
 * <p>{@link Form} and {@link Verb} are enums rather than a {@code boolean} and a {@code String}
 * because they are closed sets that the call sites read back: three verbs are in use and a
 * fourth would be a deliberate addition, and the indent exists only in the list form, where it
 * is what nests a key under the {@code Configuration:} header.</p>
 */
public final class ConfigValueText {

    private ConfigValueText() {}

    /** Whether the line stands alone or is one key among many under a header. */
    public enum Form {
        /** One key among many. Indented, so the block reads as belonging to its header. */
        LIST("  "),
        /** A line about a single key, flush left. */
        SINGLE("");

        private final String indent;

        Form(String indent) {
            this.indent = indent;
        }
    }

    /** How a line introduces its value. */
    public enum Verb {
        /** Reporting the current value. */
        EQUALS(" = "),
        /** Acknowledging a value the player just set. */
        SET_TO(" set to "),
        /** Acknowledging a return to the compiled default. */
        RESET_TO(" reset to ");

        private final String text;

        Verb(String text) {
            this.text = text;
        }
    }

    /** {@code "  key = "}, {@code "key set to "}, and so on. */
    private static String label(Form form, String key, Verb verb) {
        return form.indent + key + verb.text;
    }

    /** The GRAY label with the value as its single sibling. */
    private static Component line(Form form, String key, Verb verb, Component value) {
        return Component.literal(label(form, key, verb))
                .withStyle(ChatFormatting.GRAY)
                .append(value);
    }

    /** GREEN when true, RED when false. */
    public static Component booleanLine(Form form, String key, Verb verb, boolean value) {
        return line(form, key, verb, booleanValue(value));
    }

    /** AQUA, whatever the number. */
    public static Component intLine(Form form, String key, Verb verb, long value) {
        return line(
                form,
                key,
                verb,
                Component.literal(String.valueOf(value)).withStyle(ChatFormatting.AQUA));
    }

    /** A YELLOW {@code "default"} when {@code null}, otherwise the boolean colours. */
    public static Component triStateLine(Form form, String key, Verb verb, Boolean value) {
        Component rendered =
                value == null
                        ? Component.literal("default").withStyle(ChatFormatting.YELLOW)
                        : booleanValue(value);
        return line(form, key, verb, rendered);
    }

    /**
     * The value arrives already styled and is appended untouched &mdash; see the class note on
     * why the resolution cannot happen here.
     */
    public static Component stringLine(Form form, String key, Verb verb, Component value) {
        return line(form, key, verb, value);
    }

    private static Component booleanValue(boolean value) {
        return Component.literal(String.valueOf(value))
                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED);
    }
}
