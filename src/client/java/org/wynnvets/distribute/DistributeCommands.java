package org.wynnvets.distribute;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.wynntils.core.components.Models;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.guild.GuildStateManager;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Builds the {@code /wv distribute <user> <aspects|tomes|emeralds> <count>}
 * client command.
 *
 * <p>Drives the in-game "send to member" hand-over by composing the
 * sibling helpers in this package:</p>
 * <ol>
 *   <li>{@link GuildManageOpener} sends {@code /guild manage} and clicks
 *       the top-left "Manage Members" tile.</li>
 *   <li>{@link MembersListSearcher} paginates through the resulting
 *       members list until it locates {@code <user>}'s player-head slot.</li>
 *   <li>{@link MemberDistributor} synthesises {@code <count>} hotbar-key
 *       presses against that slot &mdash; one press per resource send
 *       (1 Aspect, 1 Guild Tome, or 1024 Emeralds depending on resource).</li>
 * </ol>
 *
 * <p>The {@code <user>} argument may be either the current Mojang username
 * or the {@code legacyName} shown on the in-game tile. {@link NameResolver}
 * translates current&rarr;legacy via the Wynncraft API so renamed players
 * are locatable by either form.</p>
 *
 * <h2>{@code @-selectors}</h2>
 * <ul>
 *   <li>{@code @random} &mdash; pick {@code <count>} random guild
 *       members and give each one of the resource. Count means
 *       "recipients" in this mode. See {@link RandomDistributor}.</li>
 *   <li>{@code @objectives} &mdash; spread {@code <count>} rewards
 *       evenly across members who've finished their guild objective.
 *       Count means "total rewards", with {@code count % completers}
 *       random completers receiving a bonus +1. See
 *       {@link ObjectivesDistributor}.</li>
 *   <li>{@code @graids} &mdash; spread {@code <count>} rewards
 *       proportionally to each member's graid participation count in
 *       the guild log (Wynncraft caps at ~100 most-recent entries).
 *       See {@link GraidsDistributor}.</li>
 *   <li>{@code @split} &mdash; divide {@code <count>} by 3 and run
 *       {@code @random}, {@code @graids}, {@code @objectives}
 *       sequentially, each with a third (modulo distributed randomly
 *       among the three pools). See {@link SplitDistributor}.</li>
 * </ul>
 *
 * <h2>Gating</h2>
 * <p>Visibility ({@code .requires}) uses
 * {@link GuildStateManager#isStaffOfAnyGuild()} so the command surfaces in
 * autocomplete reliably for vets-confirmed staff (server-confirmed, stable
 * across world transitions). Execution is gated more tightly by
 * {@link GuildStateManager#isChiefOfAnyGuild()} via {@link #ensureChief()}
 * &mdash; a Returners captain sees the command but gets a red error if
 * they try to run it.</p>
 */
public final class DistributeCommands {

  /** Bounds on {@code <count>} &mdash; one press at minimum, capped at the
   *  unsigned-byte range to keep accidental "500-aspect" typos from
   *  spamming the server. */
  private static final int COUNT_MIN = 1;
  private static final int COUNT_MAX = 255;

  /** Common counts surfaced in the {@code <count>} suggester. */
  private static final int[] COMMON_COUNTS = {1, 5, 10, 25, 50, 100};

  /** Selector token that means "pick N random guild members". */
  private static final String RANDOM_SELECTOR = "@random";

  /** Selector token that means "members who completed their guild
   *  objective, with N spread evenly across them". */
  private static final String OBJECTIVES_SELECTOR = "@objectives";

  /** Selector token that means "members who appear in the guild log's
   *  graid completions, with N spread proportionally to participation
   *  frequency". */
  private static final String GRAIDS_SELECTOR = "@graids";

  /** Selector token that means "split N three ways and run @random,
   *  @graids, @objectives back-to-back with a third each (random
   *  remainder)". */
  private static final String SPLIT_SELECTOR = "@split";

  private DistributeCommands() {}

  public static LiteralArgumentBuilder<FabricClientCommandSource> buildCommandTree() {
    return ClientCommandManager.literal("distribute")
        // Visibility tier: any staff (Captain+) in any guild, with a
        // vets-confirmed-staff bypass so the command shows reliably in
        // autocomplete for Returners staff even when Wynntils' live
        // guild model is briefly null after a world transition.
        // Execution is still gated to Chief/Owner via ensureChief().
        .requires(src -> GuildStateManager.isStaffOfAnyGuild())
        .then(ClientCommandManager.argument("name", NameOrSelectorArgument.nameOrSelector())
            .suggests(DistributeCommands::suggestGuildMembers)
            .then(resourceLeaf("aspects", MemberDistributor.Resource.ASPECTS))
            .then(resourceLeaf("tomes", MemberDistributor.Resource.TOMES))
            .then(resourceLeaf("emeralds", MemberDistributor.Resource.EMERALDS)));
  }

  /** Builds one {@code <literal> <count>} branch under the {@code name} argument. */
  private static LiteralArgumentBuilder<FabricClientCommandSource> resourceLeaf(
      String literal, MemberDistributor.Resource resource) {
    return ClientCommandManager.literal(literal)
        .then(ClientCommandManager.argument("count",
                IntegerArgumentType.integer(COUNT_MIN, COUNT_MAX))
            .suggests(DistributeCommands::suggestCounts)
            .executes(ctx -> distribute(ctx, resource)));
  }

  // ── Executor ─────────────────────────────────────────────────────────

  private static int distribute(CommandContext<FabricClientCommandSource> ctx,
                                MemberDistributor.Resource resource) {
    if (!ensureChief()) return 0;
    String name = NameOrSelectorArgument.get(ctx, "name");
    int count = IntegerArgumentType.getInteger(ctx, "count");

    // @random selector: delegate to RandomDistributor (count = recipients).
    if (RANDOM_SELECTOR.equalsIgnoreCase(name)) {
      RandomDistributor.dispatch(count, resource);
      return 1;
    }

    // @objectives selector: delegate to ObjectivesDistributor (count =
    // total rewards, spread evenly across objective-completers).
    if (OBJECTIVES_SELECTOR.equalsIgnoreCase(name)) {
      ObjectivesDistributor.dispatch(count, resource);
      return 1;
    }

    // @graids selector: delegate to GraidsDistributor (count = total
    // rewards, spread proportionally to graid participation in the log).
    if (GRAIDS_SELECTOR.equalsIgnoreCase(name)) {
      GraidsDistributor.dispatch(count, resource);
      return 1;
    }

    // @split selector: divide count by 3 and run @random + @graids +
    // @objectives sequentially, each with a third (remainder randomised).
    if (SPLIT_SELECTOR.equalsIgnoreCase(name)) {
      SplitDistributor.dispatch(count, resource);
      return 1;
    }

    // Arm with the literal input first so the search starts immediately —
    // covers the case where the user already typed the legacy name.
    MembersListSearcher.armSearch(name,
        slot -> MemberDistributor.fire(slot, resource, count, name));

    // Fire async current→legacy resolution against wapi.  When it
    // resolves to a different name (e.g. user typed a current Mojang
    // username for a renamed player), add it as a search alternative so
    // the in-flight pagination picks it up.
    NameResolver.resolveLegacyName(name).thenAccept(resolved -> {
      if (resolved != null && !resolved.equalsIgnoreCase(name)) {
        MembersListSearcher.addAlternative(resolved);
      }
    });

    GuildManageOpener.openManageMembers();
    return 1;
  }

  // ── Suggestion providers ─────────────────────────────────────────────

  /**
   * Suggests current Wynncraft usernames of the local player's guild for
   * the {@code <name>} argument.
   *
   * <p>Reads {@link com.wynntils.models.guild.GuildModel}'s synchronous
   * {@code getGuildMembers()} cache and triggers a refresh request when
   * empty &mdash; the first keystroke may show no suggestions while the
   * background fetch resolves, but subsequent keystrokes will hit the
   * populated set.</p>
   *
   * <p>Returns current Mojang usernames rather than legacy names; the
   * {@link NameResolver} step on execution converts either form to the
   * legacy name shown on the in-game tile, so users can tab-complete by
   * whichever name they remember.</p>
   */
  private static CompletableFuture<Suggestions> suggestGuildMembers(
      CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
    String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);

    // Always offer the @-selectors — their dispatchers read the live
    // guild roster, so they work even when the Wynntils member cache is
    // cold.
    if (RANDOM_SELECTOR.startsWith(remaining)) {
      builder.suggest(RANDOM_SELECTOR);
    }
    if (OBJECTIVES_SELECTOR.startsWith(remaining)) {
      builder.suggest(OBJECTIVES_SELECTOR);
    }
    if (GRAIDS_SELECTOR.startsWith(remaining)) {
      builder.suggest(GRAIDS_SELECTOR);
    }
    if (SPLIT_SELECTOR.startsWith(remaining)) {
      builder.suggest(SPLIT_SELECTOR);
    }

    if (!GuildStateManager.isWynntilsReady()) {
      return builder.buildFuture();
    }
    Set<String> members = Models.Guild.getGuildMembers();
    if (members.isEmpty()) {
      Models.Guild.requestGuildMembers();
      return builder.buildFuture();
    }
    for (String name : members) {
      if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
        builder.suggest(name);
      }
    }
    return builder.buildFuture();
  }

  /**
   * Suggests {@link #COMMON_COUNTS} for the {@code <count>} argument.
   * {@code IntegerArgumentType} has no built-in tab-completion, so
   * without this the user sees only the {@code [count]} placeholder.
   */
  private static CompletableFuture<Suggestions> suggestCounts(
      CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
    for (int n : COMMON_COUNTS) {
      builder.suggest(n);
    }
    return builder.buildFuture();
  }

  // ── Permission ───────────────────────────────────────────────────────

  /**
   * Defensive double-check of the chief gate at executor time. Brigadier's
   * {@code .requires(...)} already filters the command from suggestions
   * for non-staff, but staff-but-not-chief users (Returners captains /
   * strategists) still parse-through to here and need the friendly error.
   * Mirrors the pattern used by {@code /wv check} and {@code /wv invite-force}.
   */
  private static boolean ensureChief() {
    if (GuildStateManager.isChiefOfAnyGuild()) return true;
    ChatUtils.sendLocalMessage(
        Component.literal("You must be a guild Chief or Owner to use /wv distribute.")
            .withStyle(ChatFormatting.RED)
    );
    return false;
  }
}
