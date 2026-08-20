package org.wynnvets.distribute.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NameOrSelectorArgument} — the lexical shape of the
 * {@code <name>} token, and nothing else.
 *
 * <p>The subject is brigadier-only at runtime: its single vetsmod import
 * ({@code DistributeCommands}) exists to satisfy a Javadoc link and is never
 * resolved, which matters because that class pulls Wynntils. Its error is a
 * brigadier {@code LiteralMessage}, not a Minecraft {@code Component}, so
 * nothing here needs a booted client.</p>
 *
 * <p>The rule under test is deliberately permissive: <em>any non-empty run of
 * non-space characters</em>. There is no selector whitelist and no quote
 * handling — dispatch between {@code @selector} and a literal username happens
 * in the executor, so that adding a new {@code @foo} stays a one-file change.
 * A later pass that "tightens" this to a known-selector set, or that adopts
 * brigadier's quoted-string reader, changes which inputs reach the executor.</p>
 */
class NameOrSelectorArgumentTest {

    private static final NameOrSelectorArgument TYPE = NameOrSelectorArgument.nameOrSelector();

    /** The message on the private {@code SimpleCommandExceptionType}, reached
     *  through the raw message because the type itself is not visible. */
    private static final String EMPTY_MESSAGE = "Expected a member name or @selector";

    private static String parse(String input) throws CommandSyntaxException {
        return TYPE.parse(new StringReader(input));
    }

    // ── Accepted shapes ───────────────────────────────────────────────

    @Test
    void parse_plainUsername() throws CommandSyntaxException {
        assertEquals("PlayerName", parse("PlayerName"));
    }

    @Test
    void parse_atSelector() throws CommandSyntaxException {
        // The whole reason this type exists: StringArgumentType.string()
        // rejects '@' in an unquoted token.
        assertEquals("@random", parse("@random"));
    }

    @Test
    void parse_anyAtTokenNotJustTheKnownSelectors() throws CommandSyntaxException {
        // No whitelist — the executor decides what "@..." means.
        assertEquals("@a", parse("@a"));
        assertEquals("@anything", parse("@anything"));
        assertEquals("@@", parse("@@"));
    }

    @Test
    void parse_quoteIsAnOrdinaryCharacter() throws CommandSyntaxException {
        // Not brigadier's quoted-string reader: the '"' is data, and an
        // unbalanced one is not an error.
        assertEquals("\"quoted", parse("\"quoted"));
        assertEquals("\"a\"", parse("\"a\""));
    }

    @Test
    void parse_onlyTheLiteralSpaceTerminatesTheToken() throws CommandSyntaxException {
        // The loop tests `peek() != ' '`, matching brigadier's
        // ARGUMENT_SEPARATOR_CHAR — a tab or newline is part of the name.
        assertEquals("a\tb", parse("a\tb"));
        assertEquals("a\nb", parse("a\nb"));
    }

    // ── Non-greediness ────────────────────────────────────────────────

    @Test
    void parse_stopsAtTheFirstSpaceAndLeavesItUnconsumed() throws CommandSyntaxException {
        // <resource> and <count> still have to parse after the name, so the
        // separator must survive for brigadier to consume.
        StringReader reader = new StringReader("@random emeralds 64");
        assertEquals("@random", TYPE.parse(reader));
        assertEquals(7, reader.getCursor(), "cursor stops on the space, not past it");
        assertEquals(' ', reader.peek(), "the separator is left for brigadier");
    }

    @Test
    void parse_resumesOnTheNextTokenAfterTheSeparator() throws CommandSyntaxException {
        StringReader reader = new StringReader("Alice Bob");
        assertEquals("Alice", TYPE.parse(reader));
        reader.skip(); // brigadier eats the separator between arguments
        assertEquals("Bob", TYPE.parse(reader));
    }

    // ── Rejected shapes ───────────────────────────────────────────────

    @Test
    void parse_emptyInputThrows() {
        CommandSyntaxException e = assertThrows(CommandSyntaxException.class, () -> parse(""));
        assertEquals(EMPTY_MESSAGE, e.getRawMessage().getString());
    }

    @Test
    void parse_leadingSpaceThrowsBecauseZeroCharactersWereRead() {
        // The guard is `cursor == start`, not "input is empty" — a token
        // that begins on the separator reads nothing and fails the same way.
        CommandSyntaxException e = assertThrows(CommandSyntaxException.class, () -> parse(" x"));
        assertEquals(EMPTY_MESSAGE, e.getRawMessage().getString());
    }

    // ── Type plumbing ─────────────────────────────────────────────────

    @Test
    void getExamples_areTheTwoDocumentedForms() {
        // Brigadier uses these for ambiguity detection between sibling nodes.
        assertEquals(List.of("PlayerName", "@random"), List.copyOf(TYPE.getExamples()));
    }

    @Test
    void nameOrSelector_returnsTheSharedInstance() {
        assertSame(
                NameOrSelectorArgument.nameOrSelector(),
                NameOrSelectorArgument.nameOrSelector(),
                "the type is stateless and registered once");
    }
}
