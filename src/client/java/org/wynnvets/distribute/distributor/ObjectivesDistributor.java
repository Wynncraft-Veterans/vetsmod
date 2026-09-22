package org.wynnvets.distribute.distributor;

import com.wynntils.core.components.Managers;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.distribute.opener.GuildManageOpener;
import org.wynnvets.distribute.utils.NoAspectsFilter;
import org.wynnvets.distribute.walker.MembersListWalker;
import org.wynnvets.logging.VetsLogger;

/**
 * Implements {@code /wv distribute @objectives <resource> <count>}:
 * scans the Members GUI looking at each player-head's {@code Guild
 * Objective:} line, collects everyone who has finished their objective,
 * and evenly spreads {@code <count>} rewards across them. Any modulo
 * leftover ({@code count % completers}) is given as a bonus +1 to a
 * random subset of completers.
 *
 * <h2>Completion detection</h2>
 * <p>The Members GUI tile lore contains, near the bottom, the lines:</p>
 * <pre>
 * Guild Objective:
 * - &lt;objective name&gt;: &lt;current&gt;/&lt;total&gt;
 * - Streak: N
 * </pre>
 * <p>A completed objective is one where {@code current >= total} on the
 * first non-blank line following the {@code Guild Objective:} header
 * (blank separator lines are skipped, not treated as a miss). If the
 * lore doesn't contain the header (e.g. a freshly-joined recruit), the
 * member is treated as not-completed and skipped.</p>
 *
 * <h2>Distribution algorithm</h2>
 * <p>Given {@code N} rewards and {@code K} completers, each completer
 * gets {@code base = N / K} as a floor, and {@code remainder = N % K}
 * random completers get a bonus {@code +1}. Completers whose final
 * count is zero (which happens when {@code N < K} for the unlucky ones)
 * are dropped from the queue so we don't visit them just to send 0.</p>
 */
public final class ObjectivesDistributor {

    /** Header line that precedes the objective progress in member tiles. */
    private static final String OBJECTIVE_HEADER = "Guild Objective:";

    /** Pattern for the objective progress line, e.g.
     *  {@code "- Gather Ores: 200/200"}. The streak line ("- Streak: 76")
     *  has no slash and is correctly excluded by this pattern. */
    private static final Pattern OBJECTIVE_PROGRESS = Pattern.compile("^- .+?: (\\d+)/(\\d+)$");

    private static final Random RNG = new Random();

    private ObjectivesDistributor() {}

    /**
     * Opens the Members menu, walks it to collect everyone who's
     * completed their guild objective, computes the per-recipient
     * counts, and dispatches each in sequence.
     */
    public static void dispatch(int count, MemberSlotPresser.Resource resource) {
        dispatch(count, resource, null);
    }

    /**
     * Variant with a completion callback. Fires on each of this head's own
     * exits &mdash; no completers, nothing to send, and the send loop
     * draining &mdash; so multi-phase chains like {@link SplitDistributor}
     * can advance past an empty pool. It only gets that far if the walk
     * calls back, and {@link MembersListWalker} can abandon or stall a walk without
     * doing so ({@code members-list-walker-drops-completion}); the shared send loop
     * can end a run without it too ({@code member-slot-presser-drops-completion}).
     * {@code vetsmod_distribute.md} §7 has the rows.
     */
    public static void dispatch(
            int count, MemberSlotPresser.Resource resource, Runnable onComplete) {
        // Fire the NoAspects fetch in parallel with the GUI walk so the
        // HTTP round-trip overlaps the (much slower) menu pagination.
        // The walk callback waits on the fetch before filtering — by
        // the time the walker completes, the HTTP is almost always
        // already resolved. The scheduleLater(..., 0) keeps onWalkComplete
        // on the tick thread either way: thenAccept runs on whichever
        // background thread completes the fetch if it finishes after the
        // walk, and inline on the walker's tick-thread callback if it
        // finished first. Either way the 0 is there for the thread, not as
        // a settle delay.
        CompletableFuture<Set<String>> excludeF = NoAspectsFilter.fetchExcludedLegacyNames();
        MembersListWalker.armWalk(
                members ->
                        excludeF.thenAccept(
                                excludeNames ->
                                        Managers.TickScheduler.scheduleLater(
                                                () ->
                                                        onWalkComplete(
                                                                members,
                                                                count,
                                                                resource,
                                                                onComplete,
                                                                excludeNames),
                                                0)));
        GuildManageOpener.openManageMembers();
    }

    private static void onWalkComplete(
            List<MembersListWalker.MemberEntry> members,
            int count,
            MemberSlotPresser.Resource resource,
            Runnable onComplete,
            Set<String> excludeNames) {
        List<String> completers = new ArrayList<>();
        int skippedOptOut = 0;
        for (MembersListWalker.MemberEntry m : members) {
            if (excludeNames.contains(m.legacyName())) {
                skippedOptOut++;
                continue;
            }
            if (hasCompletedObjective(m.loreLines())) {
                completers.add(m.legacyName());
            }
        }
        if (skippedOptOut > 0) {
            VetsLogger.debug("ObjectivesDistributor: skipped {} opted-out members", skippedOptOut);
        }
        VetsLogger.debug(
                "ObjectivesDistributor: {} / {} members completed their objective",
                completers.size(),
                members.size());

        if (completers.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("No members have completed their guild objective.")
                            .withStyle(ChatFormatting.YELLOW));
            MemberSlotPresser.closeMembersScreen();
            if (onComplete != null) onComplete.run();
            return;
        }

        Deque<DistributionQueue.Distribution> queue = buildDistribution(completers, count);
        if (queue.isEmpty()) {
            // Can happen if count == 0 (excluded by brigadier bounds, but
            // defensive); nothing to send.
            MemberSlotPresser.closeMembersScreen();
            if (onComplete != null) onComplete.run();
            return;
        }

        ChatUtils.sendLocalMessage(
                Component.literal(
                                "Distributing "
                                        + count
                                        + "x "
                                        + resource.displayName()
                                        + " across "
                                        + completers.size()
                                        + " objective-completers…")
                        .withStyle(ChatFormatting.AQUA));

        DistributionQueue.processNext(queue, resource, onComplete, "ObjectivesDistributor");
    }

    /**
     * Builds the per-recipient queue using even split + random bonus for
     * the modulo leftover. Recipients ending up with zero (when N &lt; K
     * and they didn't draw the bonus) are dropped.
     */
    // Package-private for unit tests. See ObjectivesDistributorTest.
    static Deque<DistributionQueue.Distribution> buildDistribution(
            List<String> completers, int total) {
        int k = completers.size();
        int base = total / k;
        int remainder = total % k;

        // Shuffle once; the first `remainder` slots get the bonus +1.
        List<String> shuffled = new ArrayList<>(completers);
        Collections.shuffle(shuffled, RNG);

        Deque<DistributionQueue.Distribution> queue = new ArrayDeque<>();
        for (int i = 0; i < shuffled.size(); i++) {
            int perUser = base + (i < remainder ? 1 : 0);
            if (perUser <= 0) continue;
            queue.add(new DistributionQueue.Distribution(shuffled.get(i), perUser));
        }
        return queue;
    }

    /**
     * Returns {@code true} if the member's lore shows
     * {@code current >= total} on the first non-blank line following the
     * {@code Guild Objective:} header. Returns {@code false} if the
     * header is missing, if nothing non-blank follows it, or if the
     * first non-blank line fails {@link #OBJECTIVE_PROGRESS}.
     */
    // Package-private for unit tests. See ObjectivesDistributorTest.
    static boolean hasCompletedObjective(List<String> loreLines) {
        boolean afterHeader = false;
        for (String line : loreLines) {
            if (afterHeader) {
                Matcher m = OBJECTIVE_PROGRESS.matcher(line);
                if (m.matches()) {
                    try {
                        int current = Integer.parseInt(m.group(1));
                        int total = Integer.parseInt(m.group(2));
                        return current >= total;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                }
                // A surprise non-objective line after the header — bail
                // out. Blank lines fall through this guard and the scan
                // continues, so a blank separator between the header and
                // the progress line does not read as not-completed.
                if (!line.isBlank()) return false;
            }
            if (line.equals(OBJECTIVE_HEADER)) {
                afterHeader = true;
            }
        }
        return false;
    }
}
