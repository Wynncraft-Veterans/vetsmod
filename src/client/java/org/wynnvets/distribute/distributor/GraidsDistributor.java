package org.wynnvets.distribute.distributor;

import com.wynntils.core.components.Managers;
import com.wynntils.core.text.StyledText;
import com.wynntils.models.items.items.gui.GuildLogItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.distribute.opener.GuildManageOpener;
import org.wynnvets.distribute.utils.NameResolver;
import org.wynnvets.distribute.utils.NoAspectsFilter;
import org.wynnvets.distribute.walker.GuildLogWalker;
import org.wynnvets.distribute.walker.MembersListSearcher;
import org.wynnvets.logging.VetsLogger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implements {@code /wv distribute @graids <resource> <count>}: opens
 * the guild log, scans every entry that records a guild-raid completion,
 * counts how many graids each member participated in across the log's
 * window (Wynncraft caps at roughly 100 most-recent entries), and
 * spreads {@code <count>} rewards proportionally to that frequency.
 *
 * <h2>How the log is walked</h2>
 * <p>{@link GuildLogWalker} opens the in-game log GUI via
 * {@code /guild log} and lets Wynntils' own auto-pagination drive the
 * pages; we just collect each {@link GuildLogItem} as it streams in via
 * {@code ContainerSetSlot} and stop on a quiescence timer.</p>
 *
 * <h2>What counts as a graid</h2>
 * <p>Graid-completion entries follow the shape
 * {@code "<u1>, <u2>, <u3>, and <u4> <raid name> and claimed <rewards>"}
 * in the General log. Other entries ("X rewarded an Aspect to Y", rank
 * changes, etc.) don't contain the literal substring {@code "and claimed"},
 * which is enough to filter them out without a brittle full-line regex.</p>
 *
 * <h2>Username extraction</h2>
 * <p>For each graid entry, every word matching the Mojang username
 * shape ({@code [A-Za-z0-9_]{3,16}}) is looked up in a name index
 * built from {@link NameResolver#fetchNameIndex()}. Index keys are both
 * current and legacy names from wapi, so a player who renamed since
 * the entry was written is still correctly attributed. Non-username
 * words (resource names like "Aspects", connectives like "claimed",
 * raid words) simply won't be in the index and are ignored.</p>
 *
 * <h2>Distribution</h2>
 * <p>Each participant's share is
 * {@code floor(frequency * count / totalGraidParticipations)}. The
 * remainder ({@code count - sum(shares)}) is distributed by shuffling
 * the participants and bumping the first {@code remainder} by +1,
 * matching {@link ObjectivesDistributor}'s "random modulo" convention.
 * Participants ending up at zero are dropped from the visit queue so
 * we don't open and re-paginate just to send nothing.</p>
 */
public final class GraidsDistributor {

    /** Substring filter that singles out graid-completion entries. */
    private static final String GRAID_MARKER = "and claimed";

    /** Mojang username shape: 3-16 alphanumeric/underscore characters. */
    private static final Pattern USERNAME_TOKEN = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private static final Random RNG = new Random();

    /** One queued recipient and the per-user count we owe them. */
    private record Distribution(String legacyName, int count) {}

    private GraidsDistributor() {}

    /**
     * Kicks off the full @graids flow: fetch the name index, open the
     * log, walk it, compute per-user shares, then dispatch.
     */
    public static void dispatch(int count, MemberSlotPresser.Resource resource) {
        dispatch(count, resource, null);
    }

    /**
     * Variant with a completion callback. Fires on every exit path so
     * multi-phase chains like {@link SplitDistributor} advance even
     * when this phase finds no graids or fails to read the roster.
     */
    public static void dispatch(int count, MemberSlotPresser.Resource resource,
                                Runnable onComplete) {
        // Fetch the name index and the NoAspects opt-out list in
        // parallel; strip excluded legacy names from the index so they
        // never match a graid-log username token. Per-command refresh,
        // fail-open via NoAspectsFilter on any HTTP error.
        NameResolver.fetchNameIndex().thenCombine(
                NoAspectsFilter.fetchExcludedLegacyNames(),
                GraidsDistributor::filterIndex)
                .thenAccept(index ->
                        Managers.TickScheduler.scheduleLater(
                                () -> beginWalk(index, count, resource, onComplete), 0));
    }

    private static Map<String, String> filterIndex(Map<String, String> index,
                                                   Set<String> excludeNames) {
        if (excludeNames.isEmpty()) return index;
        Map<String, String> out = new HashMap<>(index.size());
        for (Map.Entry<String, String> e : index.entrySet()) {
            // Drop both lookup keys (current + legacy lowercase) whose
            // canonical legacy name is opted-out. The frequency counter
            // in countGraidFrequencies will then miss the username token
            // and skip the member entirely.
            if (!excludeNames.contains(e.getValue())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static void beginWalk(Map<String, String> nameIndex, int count,
                                  MemberSlotPresser.Resource resource,
                                  Runnable onComplete) {
        if (nameIndex.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Could not read guild roster from wapi — try again.")
                            .withStyle(ChatFormatting.YELLOW));
            if (onComplete != null) onComplete.run();
            return;
        }
        ChatUtils.sendLocalMessage(
                Component.literal("Reading guild log…").withStyle(ChatFormatting.GRAY));

        GuildLogWalker.armWalk(entries ->
                onLogReady(entries, nameIndex, count, resource, onComplete));
        // Open via /guild manage → click Log tile rather than direct
        // /guild log. Empirically Wynncraft drops /guild log if it
        // lands while there's any session state left over from a
        // recent menu close — even when typed in chat by the user
        // themselves. /guild manage doesn't have the same constraint,
        // and the Manage GUI's "Guild Log" tile routes to the same
        // Log container. See GuildManageOpener.openGuildLog javadoc.
        GuildManageOpener.openGuildLog();
    }

    private static void onLogReady(List<GuildLogItem> entries, Map<String, String> nameIndex,
                                   int count, MemberSlotPresser.Resource resource,
                                   Runnable onComplete) {
        Map<String, Integer> freq = countGraidFrequencies(entries, nameIndex);
        VetsLogger.debug("GraidsDistributor: scanned {} log entries, {} distinct graid participants",
                entries.size(), freq.size());

        if (freq.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("No graid completions found in the log window.")
                            .withStyle(ChatFormatting.YELLOW));
            if (onComplete != null) onComplete.run();
            return;
        }

        Deque<Distribution> queue = buildDistribution(freq, count);
        if (queue.isEmpty()) {
            // count == 0 (excluded by brigadier bounds) or everyone rounded to 0
            // and no remainder — nothing to send.
            if (onComplete != null) onComplete.run();
            return;
        }

        int totalParticipations = freq.values().stream().mapToInt(Integer::intValue).sum();
        ChatUtils.sendLocalMessage(
                Component.literal("Distributing " + count + "x " + resource.displayName()
                        + " proportionally to " + freq.size()
                        + " graid participants (" + totalParticipations + " total participations)…")
                        .withStyle(ChatFormatting.AQUA));

        processNext(queue, resource, onComplete);
        GuildManageOpener.openManageMembers();
    }

    /**
     * Counts how many graid entries each member's legacy name appears
     * in. A member appearing twice in the same entry (shouldn't happen,
     * but defensive) counts as one participation for that entry.
     */
    private static Map<String, Integer> countGraidFrequencies(
            List<GuildLogItem> entries, Map<String, String> nameIndex) {
        Map<String, Integer> freq = new HashMap<>();
        for (GuildLogItem entry : entries) {
            String body = joinLore(entry);
            if (!body.contains(GRAID_MARKER)) continue;

            Set<String> namesInEntry = new HashSet<>();
            Matcher m = USERNAME_TOKEN.matcher(body);
            while (m.find()) {
                String legacy = nameIndex.get(m.group().toLowerCase(Locale.ROOT));
                if (legacy != null) {
                    namesInEntry.add(legacy);
                }
            }
            for (String name : namesInEntry) {
                freq.merge(name, 1, Integer::sum);
            }
        }
        return freq;
    }

    /**
     * Floored-proportional split with a random remainder. Each
     * participant gets {@code floor(frequency * count /
     * totalParticipations)}; the {@code count - sum(shares)} left over
     * by that flooring is awarded {@code +1} at a time to a random
     * subset of participants, each at most once.
     */
    private static Deque<Distribution> buildDistribution(Map<String, Integer> freq, int count) {
        int totalParticipations = 0;
        for (int f : freq.values()) totalParticipations += f;
        if (totalParticipations <= 0) return new ArrayDeque<>();

        // Stable iteration order: highest-frequency first, alphabetical
        // tiebreaker. Doesn't affect totals; just keeps the visit order
        // (and so the chat output) predictable.
        List<Map.Entry<String, Integer>> participants = new ArrayList<>(freq.entrySet());
        participants.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareToIgnoreCase(b.getKey());
        });

        Map<String, Integer> shares = new HashMap<>();
        int flooredTotal = 0;
        for (Map.Entry<String, Integer> e : participants) {
            int share = (e.getValue() * count) / totalParticipations;
            shares.put(e.getKey(), share);
            flooredTotal += share;
        }
        int remainder = count - flooredTotal;

        if (remainder > 0) {
            // Random bonus +1 to `remainder` participants (each at most one).
            List<String> bonusPool = new ArrayList<>(shares.keySet());
            Collections.shuffle(bonusPool, RNG);
            for (int i = 0; i < remainder && i < bonusPool.size(); i++) {
                shares.merge(bonusPool.get(i), 1, Integer::sum);
            }
        }

        // Queue in the same stable order as `participants` so the visit
        // sequence is predictable.
        Deque<Distribution> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : participants) {
            int s = shares.getOrDefault(e.getKey(), 0);
            if (s > 0) queue.add(new Distribution(e.getKey(), s));
        }
        return queue;
    }

    private static String joinLore(GuildLogItem entry) {
        StringBuilder sb = new StringBuilder();
        for (StyledText line : entry.getLogInfo()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(line.getStringWithoutFormatting());
        }
        return sb.toString();
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
        VetsLogger.debug("GraidsDistributor: queue popped [{}] (count={}), {} left",
                d.legacyName(), d.count(), queue.size());

        // Names are already canonical legacy (looked up via the wapi
        // index), so the searcher's literal-input arm matches the
        // Members GUI tile directly — no per-pick NameResolver call.
        MembersListSearcher.armSearch(
                d.legacyName(),
                slot -> MemberSlotPresser.fire(slot, resource, d.count(), d.legacyName(),
                        () -> processNext(queue, resource, onComplete)),
                () -> processNext(queue, resource, onComplete));
    }
}
