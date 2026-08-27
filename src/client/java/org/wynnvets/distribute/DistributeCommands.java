package org.wynnvets.distribute;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.wynntils.core.components.Managers;
import com.wynntils.core.components.Models;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.distribute.command.NameOrSelectorArgument;
import org.wynnvets.distribute.distributor.GraidsDistributor;
import org.wynnvets.distribute.distributor.MemberSlotPresser;
import org.wynnvets.distribute.distributor.ObjectivesDistributor;
import org.wynnvets.distribute.distributor.RandomDistributor;
import org.wynnvets.distribute.distributor.SplitDistributor;
import org.wynnvets.distribute.opener.GuildManageOpener;
import org.wynnvets.distribute.utils.NameResolver;
import org.wynnvets.distribute.utils.NoAspectsFilter;
import org.wynnvets.distribute.walker.MembersListSearcher;
import org.wynnvets.guild.GuildStateManager;

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
 *   <li>{@link MemberSlotPresser} synthesises {@code <count>} hotbar-key
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
 *       {@code @graids}, {@code @objectives}, {@code @random}
 *       sequentially, each with a third (modulo distributed randomly
 *       among the three pools). {@code @graids} runs first by
 *       necessity, not by accident &mdash; see {@link SplitDistributor}
 *       for why reordering the phases loses graid records.</li>
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

    /**
     * Dispatch signature shared by the selector heads: every one of them exposes a
     * {@code dispatch(int, Resource)} taking the parsed {@code <count>} and the chosen
     * resource. Three of them also carry a three-argument overload with a completion
     * callback; that one belongs to {@link SplitDistributor}'s phase chaining and is never
     * reached from here.
     */
    @FunctionalInterface
    private interface SelectorDispatch {
        void dispatch(int count, MemberSlotPresser.Resource resource);
    }

    /** One {@code @}-selector: the token as typed, and the head it hands the command to. */
    private record Selector(String token, SelectorDispatch head) {}

    /**
     * The four {@code @}-selectors, in the order they are offered for tab-completion. What
     * {@code <count>} means differs per head &mdash; see the {@code @-selectors} section of
     * this class's Javadoc.
     *
     * <p>This order is the suggestion order and nothing more. Dispatch matches one whole
     * token, so no row can shadow another, and it is <em>not</em>
     * {@link SplitDistributor}'s phase order, which is fixed for a reason that lives in that
     * class.</p>
     */
    private static final List<Selector> SELECTORS =
            List.of(
                    // N random guild members, one of the resource each.
                    new Selector("@random", RandomDistributor::dispatch),
                    // Members who completed their guild objective, with N spread evenly
                    // across them.
                    new Selector("@objectives", ObjectivesDistributor::dispatch),
                    // Members appearing in the guild log's graid completions, with N spread
                    // proportionally to participation frequency.
                    new Selector("@graids", GraidsDistributor::dispatch),
                    // N split three ways, running @graids, @objectives and @random
                    // back-to-back with a third each (random remainder). The phase order is
                    // fixed and load-bearing; see SplitDistributor.
                    new Selector("@split", SplitDistributor::dispatch));

    private DistributeCommands() {}

    public static LiteralArgumentBuilder<FabricClientCommandSource> buildCommandTree() {
        return ClientCommandManager.literal("distribute")
                // Visibility tier: any staff (Captain+) in any guild, with a
                // vets-confirmed-staff bypass so the command shows reliably in
                // autocomplete for Returners staff even when Wynntils' live
                // guild model is briefly null after a world transition.
                // Execution is still gated to Chief/Owner via ensureChief().
                .requires(src -> GuildStateManager.isStaffOfAnyGuild())
                .then(
                        ClientCommandManager.argument(
                                        "name", NameOrSelectorArgument.nameOrSelector())
                                .suggests(DistributeCommands::suggestGuildMembers)
                                .then(resourceLeaf("aspects", MemberSlotPresser.Resource.ASPECTS))
                                .then(resourceLeaf("tomes", MemberSlotPresser.Resource.TOMES))
                                .then(
                                        resourceLeaf(
                                                "emeralds", MemberSlotPresser.Resource.EMERALDS)));
    }

    /** Builds one {@code <literal> <count>} branch under the {@code name} argument. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> resourceLeaf(
            String literal, MemberSlotPresser.Resource resource) {
        return ClientCommandManager.literal(literal)
                .then(
                        ClientCommandManager.argument(
                                        "count", IntegerArgumentType.integer(COUNT_MIN, COUNT_MAX))
                                .suggests(DistributeCommands::suggestCounts)
                                .executes(ctx -> distribute(ctx, resource)));
    }

    // ── Executor ─────────────────────────────────────────────────────────

    private static int distribute(
            CommandContext<FabricClientCommandSource> ctx, MemberSlotPresser.Resource resource) {
        if (!ensureChief()) return 0;
        String name = NameOrSelectorArgument.get(ctx, "name");
        int count = IntegerArgumentType.getInteger(ctx, "count");

        // Selector heads, checked before the literal-name fan-out: an exact,
        // case-insensitive whole-token match hands the command to one
        // distributor, and what <count> means from there is that head's
        // business. See SELECTORS.
        for (Selector selector : SELECTORS) {
            if (selector.token().equalsIgnoreCase(name)) {
                selector.head().dispatch(count, resource);
                return 1;
            }
        }

        // Fan out two HTTP calls in parallel: legacy-name resolution (so we
        // know the canonical tile name for renamed targets) and the
        // NoAspects opt-out list (so we can reject before opening the menu).
        // We deliberately wait on both before arming the searcher — even
        // though it costs ~200-500ms upfront, rejecting after the menu has
        // already opened would be a confusing UX. Per-command refresh and
        // fail-open semantics (see NoAspectsFilter) keep this safe.
        CompletableFuture<String> resolveF = NameResolver.resolveLegacyName(name);
        CompletableFuture<Set<String>> excludeF = NoAspectsFilter.fetchExcludedLegacyNames();
        resolveF.thenCombine(
                excludeF,
                (resolved, excludeNames) -> {
                    Managers.TickScheduler.scheduleLater(
                            () ->
                                    dispatchSingleTarget(
                                            name, resolved, excludeNames, resource, count),
                            0);
                    return null;
                });
        return 1;
    }

    /**
     * Runs on the Minecraft tick thread. Rejects the dispatch with a chat
     * line if either the literal input or the wapi-resolved name is on
     * the NoAspects opt-out list; otherwise arms the searcher (with both
     * forms when a rename is detected) and opens the Members menu.
     */
    private static void dispatchSingleTarget(
            String name,
            String resolved,
            Set<String> excludeNames,
            MemberSlotPresser.Resource resource,
            int count) {
        // Check both forms — staff could have opted out the player by
        // current name OR legacy name, depending on which was in the live
        // roster at !noaspects-add time.
        String optedOutForm = null;
        if (excludeNames.contains(name)) optedOutForm = name;
        else if (resolved != null && excludeNames.contains(resolved)) optedOutForm = resolved;
        if (optedOutForm != null) {
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    optedOutForm
                                            + " is on the NoAspects list. Ask staff to run !noaspects remove "
                                            + optedOutForm
                                            + " if this is in error.")
                            .withStyle(ChatFormatting.RED));
            return;
        }
        // Arm with the literal input — covers the case where the user
        // already typed the legacy name. addAlternative covers the rename
        // case where the wapi resolution returns a different canonical name.
        MembersListSearcher.armSearch(
                name, slot -> MemberSlotPresser.fire(slot, resource, count, name));
        if (resolved != null && !resolved.equalsIgnoreCase(name)) {
            MembersListSearcher.addAlternative(resolved);
        }
        GuildManageOpener.openManageMembers();
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
        // cold. `remaining` is already case-folded and the tokens are
        // lowercase literals, so this is the prefix test the four branches
        // this loop replaced each did by hand.
        for (Selector selector : SELECTORS) {
            if (selector.token().startsWith(remaining)) {
                builder.suggest(selector.token());
            }
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
                        .withStyle(ChatFormatting.RED));
        return false;
    }
}
