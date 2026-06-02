package org.wynnvets.distribute;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import java.util.Collection;
import java.util.List;

/**
 * Brigadier argument type for the {@code <name>} slot of
 * {@code /wv distribute <name> <resource> <count>}. Reads one
 * non-empty, space-delimited token so {@code @}-prefixed selectors
 * ({@code @random}, and future {@code @graids} / {@code @objectives} /
 * {@code @split}) parse alongside regular Wynncraft usernames.
 *
 * <p>{@link com.mojang.brigadier.arguments.StringArgumentType#string()
 * StringArgumentType.string()} restricts unquoted input to
 * {@code [0-9A-Za-z_.+-]}; typing (or tab-completing) {@code @random}
 * trips Brigadier's "Expected whitespace to end one argument" at the
 * {@code @}. This type widens the allowed set to "anything but a space"
 * while staying non-greedy, so the {@code <resource> <count>} children
 * still parse after the name.</p>
 *
 * <p>The set of recognised selectors is intentionally <em>not</em>
 * enforced here &mdash; this type only fixes the lexical shape of one
 * token. Dispatch (selector vs. literal username) lives in
 * {@link DistributeCommands}, which keeps adding a new {@code @foo}
 * down to a single executor branch rather than a coordinated change
 * across two files.</p>
 */
public final class NameOrSelectorArgument implements ArgumentType<String> {

  private static final NameOrSelectorArgument INSTANCE = new NameOrSelectorArgument();

  private static final Collection<String> EXAMPLES = List.of("PlayerName", "@random");

  private static final SimpleCommandExceptionType EMPTY = new SimpleCommandExceptionType(
      new LiteralMessage("Expected a member name or @selector"));

  private NameOrSelectorArgument() {}

  public static NameOrSelectorArgument nameOrSelector() {
    return INSTANCE;
  }

  /** Mirrors {@link com.mojang.brigadier.arguments.StringArgumentType#getString}
   *  so executors don't need to know which argument type produced the value. */
  public static String get(CommandContext<?> ctx, String name) {
    return ctx.getArgument(name, String.class);
  }

  @Override
  public String parse(StringReader reader) throws CommandSyntaxException {
    final int start = reader.getCursor();
    // Brigadier separates arguments by literal space (ARGUMENT_SEPARATOR_CHAR);
    // matching that exactly keeps trailing-data behaviour consistent with the
    // built-in string types.
    while (reader.canRead() && reader.peek() != ' ') {
      reader.skip();
    }
    if (reader.getCursor() == start) {
      throw EMPTY.createWithContext(reader);
    }
    return reader.getString().substring(start, reader.getCursor());
  }

  @Override
  public Collection<String> getExamples() {
    return EXAMPLES;
  }
}
