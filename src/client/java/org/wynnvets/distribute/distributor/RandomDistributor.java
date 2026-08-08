package org.wynnvets.distribute.distributor;

import com.wynntils.core.components.Managers;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.distribute.opener.GuildManageOpener;
import org.wynnvets.distribute.utils.NameResolver;
import org.wynnvets.distribute.utils.NoAspectsFilter;
import org.wynnvets.distribute.walker.MembersListSearcher;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

/**
 * Implements the {@code /wv distribute @random <resource> <count>} flow:
 * picks {@code count} random members of the local player's guild, opens
 * the Members GUI once, and visits each pick sequentially &mdash; one
 * {@link MembersListSearcher} arm + one-press {@link MemberSlotPresser}
 * call per recipient &mdash; before closing the menu at the end.
 *
 * <h2>Count semantics</h2>
 * <p>For {@code @random}, {@code <count>} is the <em>number of distinct
 * recipients</em>; each gets exactly one of the resource. This differs
 * from the single-user form where {@code <count>} is "presses sent to
 * that one user", but matches what a raffle-style giveaway needs.</p>
 *
 * <h2>Picking from legacy names</h2>
 * <p>The picker reads {@link NameResolver#fetchAllLegacyNames()} rather
 * than {@code Models.Guild.getGuildMembers()} &mdash; the former returns
 * each member's tile-displayed name (legacy when renamed, current
 * otherwise), which is exactly what {@link MembersListSearcher} matches
 * against on the GUI. Picking by current names would race against the
 * per-pick async resolve and silently exclude renamed members on small
 * guilds whose searches finish in under a second.</p>
 *
 * <h2>Page coverage</h2>
 * <p>Random picks can live on any page, in any order. After the first
 * pick is found on some page P, subsequent picks may live on an earlier
 * page &mdash; {@link MembersListSearcher}'s bidirectional pagination
 * transparently switches direction so the queue can drain without ever
 * reopening the menu or paginating all the way back to page 1.</p>
 *
 * <h2>Failure tolerance</h2>
 * <p>If a picked member can't be located (e.g. they were just kicked
 * between the wapi fetch and the menu open), the searcher's not-found
 * callback advances the queue so the remaining picks still proceed.
 * Same for {@link MemberSlotPresser}'s refresh-timeout path. The user
 * gets a chat line per failure.</p>
 */
public final class RandomDistributor {

    private static final Random RNG = new Random();

    private RandomDistributor() {}

    /**
     * Fetches the guild's legacy-name roster from wapi, picks
     * {@code count} random members, and sends one of {@code resource} to
     * each. No-op (with a chat hint) if Wynntils isn't ready or the
     * wapi fetch returns no members.
     */
    public static void dispatch(int count, MemberSlotPresser.Resource resource) {
        dispatch(count, resource, null);
    }

    /**
     * Variant with a completion callback. The callback fires on every
     * exit path (success, no roster, no picks) so multi-phase chains
     * like {@link SplitDistributor} can advance to the next phase
     * without stalling on a partial failure.
     */
    public static void dispatch(
            int count, MemberSlotPresser.Resource resource, Runnable onComplete) {
        if (!GuildStateManager.isWynntilsReady()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Wynntils isn't ready yet — cannot read guild roster.")
                            .withStyle(ChatFormatting.RED));
            if (onComplete != null) onComplete.run();
            return;
        }

        // Fetch the guild roster and the NoAspects opt-out list in
        // parallel; combine into a filtered legacy-name pool before
        // dispatching. Per-command refresh keeps the list responsive to
        // staff mutations (see NoAspectsFilter for fail-open semantics).
        NameResolver.fetchAllLegacyNames()
                .thenCombine(
                        NoAspectsFilter.fetchExcludedLegacyNames(), RandomDistributor::filterNames)
                .thenAccept(
                        legacyNames ->
                                // Hop back to the Minecraft tick thread before touching the
                                // menu / event-bus state. The HTTP completion callback runs
                                // on the HttpClient's executor.
                                Managers.TickScheduler.scheduleLater(
                                        () -> beginPicks(legacyNames, count, resource, onComplete),
                                        0));
    }

    private static List<String> filterNames(List<String> names, Set<String> exclude) {
        if (exclude.isEmpty()) return names;
        List<String> out = new ArrayList<>(names.size());
        for (String name : names) {
            if (!exclude.contains(name)) out.add(name);
        }
        return out;
    }

    private static void beginPicks(
            List<String> legacyNames,
            int count,
            MemberSlotPresser.Resource resource,
            Runnable onComplete) {
        if (legacyNames.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Could not read guild roster from wapi — try again.")
                            .withStyle(ChatFormatting.YELLOW));
            if (onComplete != null) onComplete.run();
            return;
        }

        List<String> shuffled = new ArrayList<>(legacyNames);
        Collections.shuffle(shuffled, RNG);
        int picks = Math.min(count, shuffled.size());
        Deque<String> queue = new ArrayDeque<>(shuffled.subList(0, picks));

        ChatUtils.sendLocalMessage(
                Component.literal(
                                "Picking "
                                        + picks
                                        + " random members for "
                                        + resource.displayName()
                                        + "…")
                        .withStyle(ChatFormatting.AQUA));

        // Arm for the first pick BEFORE opening the menu so the searcher
        // is bound by the time MenuOpenedEvent.Pre fires for the Members
        // GUI. Subsequent picks rearm via the fast-path while the menu
        // is still open.
        processNext(queue, resource, onComplete);
        GuildManageOpener.openManageMembers();
    }

    private static void processNext(
            Deque<String> queue, MemberSlotPresser.Resource resource, Runnable onComplete) {
        if (queue.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Distribution complete.").withStyle(ChatFormatting.GREEN));
            MemberSlotPresser.closeMembersScreen();
            if (onComplete != null) onComplete.run();
            return;
        }
        String name = queue.poll();
        VetsLogger.debug(
                "RandomDistributor: searching for [{}], {} remaining after this",
                name,
                queue.size());

        // Names are already in the legacy form from fetchAllLegacyNames(),
        // so the literal-input arm matches the menu directly — no per-pick
        // NameResolver call needed.
        MembersListSearcher.armSearch(
                name,
                slot ->
                        MemberSlotPresser.fire(
                                slot,
                                resource,
                                1,
                                name,
                                () -> processNext(queue, resource, onComplete)),
                () -> processNext(queue, resource, onComplete));
    }
}
