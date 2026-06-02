package org.wynnvets.distribute.distributor;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.distribute.opener.GuildManageOpener;
import org.wynnvets.distribute.walker.MembersListSearcher;
import org.wynnvets.distribute.walker.MembersListWalker;
import org.wynnvets.logging.VetsLogger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * line immediately following the {@code Guild Objective:} header. If
 * the lore doesn't contain the header (e.g. a freshly-joined recruit),
 * the member is treated as not-completed and skipped.</p>
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
    private static final Pattern OBJECTIVE_PROGRESS =
            Pattern.compile("^- .+?: (\\d+)/(\\d+)$");

    private static final Random RNG = new Random();

    /** One queued recipient and the per-user count we owe them. */
    private record Distribution(String legacyName, int count) {}

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
     * Variant with a completion callback. Fires on every exit path
     * (success, no completers, count drop-out) so multi-phase chains
     * like {@link SplitDistributor} can advance without stalling.
     */
    public static void dispatch(int count, MemberSlotPresser.Resource resource,
                                Runnable onComplete) {
        MembersListWalker.armWalk(members ->
                onWalkComplete(members, count, resource, onComplete));
        GuildManageOpener.openManageMembers();
    }

    private static void onWalkComplete(List<MembersListWalker.MemberEntry> members,
                                       int count, MemberSlotPresser.Resource resource,
                                       Runnable onComplete) {
        List<String> completers = new ArrayList<>();
        for (MembersListWalker.MemberEntry m : members) {
            if (hasCompletedObjective(m.loreLines())) {
                completers.add(m.legacyName());
            }
        }
        VetsLogger.debug("ObjectivesDistributor: {} / {} members completed their objective",
                completers.size(), members.size());

        if (completers.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("No members have completed their guild objective.")
                            .withStyle(ChatFormatting.YELLOW));
            MemberSlotPresser.closeMembersScreen();
            if (onComplete != null) onComplete.run();
            return;
        }

        Deque<Distribution> queue = buildDistribution(completers, count);
        if (queue.isEmpty()) {
            // Can happen if count == 0 (excluded by brigadier bounds, but
            // defensive); nothing to send.
            MemberSlotPresser.closeMembersScreen();
            if (onComplete != null) onComplete.run();
            return;
        }

        ChatUtils.sendLocalMessage(
                Component.literal("Distributing " + count + "x " + resource.displayName()
                        + " across " + completers.size() + " objective-completers…")
                        .withStyle(ChatFormatting.AQUA));

        processNext(queue, resource, onComplete);
    }

    /**
     * Builds the per-recipient queue using even split + random bonus for
     * the modulo leftover. Recipients ending up with zero (when N &lt; K
     * and they didn't draw the bonus) are dropped.
     */
    private static Deque<Distribution> buildDistribution(List<String> completers, int total) {
        int k = completers.size();
        int base = total / k;
        int remainder = total % k;

        // Shuffle once; the first `remainder` slots get the bonus +1.
        List<String> shuffled = new ArrayList<>(completers);
        Collections.shuffle(shuffled, RNG);

        Deque<Distribution> queue = new ArrayDeque<>();
        for (int i = 0; i < shuffled.size(); i++) {
            int perUser = base + (i < remainder ? 1 : 0);
            if (perUser <= 0) continue;
            queue.add(new Distribution(shuffled.get(i), perUser));
        }
        return queue;
    }

    private static void processNext(Deque<Distribution> queue,
                                    MemberSlotPresser.Resource resource,
                                    Runnable onComplete) {
        if (queue.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Distribution complete.")
                            .withStyle(ChatFormatting.GREEN));
            MemberSlotPresser.closeMembersScreen();
            if (onComplete != null) onComplete.run();
            return;
        }
        Distribution d = queue.poll();
        VetsLogger.debug("ObjectivesDistributor: queue popped [{}] (count={}), {} left",
                d.legacyName(), d.count(), queue.size());

        // Names are already legacy (walker reads tile hover names), so
        // the literal-input arm matches the menu directly. No per-pick
        // NameResolver needed.
        MembersListSearcher.armSearch(
                d.legacyName(),
                slot -> MemberSlotPresser.fire(slot, resource, d.count(), d.legacyName(),
                        () -> processNext(queue, resource, onComplete)),
                () -> processNext(queue, resource, onComplete));
    }

    /**
     * Returns {@code true} if the member's lore shows {@code current >= total}
     * on the line immediately following the {@code Guild Objective:}
     * header. Returns {@code false} if the header is missing or the
     * progress line is malformed.
     */
    private static boolean hasCompletedObjective(List<String> loreLines) {
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
                // Anything else right after the header (blank, or a
                // surprise non-objective line) — bail out.
                if (!line.isBlank()) return false;
            }
            if (line.equals(OBJECTIVE_HEADER)) {
                afterHeader = true;
            }
        }
        return false;
    }
}
