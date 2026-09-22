package org.wynnvets.distribute.distributor;

import java.util.Deque;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.distribute.opener.GuildManageOpener;
import org.wynnvets.distribute.utils.NameResolver;
import org.wynnvets.distribute.walker.GuildLogWalker;
import org.wynnvets.distribute.walker.MembersListSearcher;
import org.wynnvets.distribute.walker.MembersListWalker;
import org.wynnvets.logging.VetsLogger;

/**
 * The per-recipient send loop shared by {@code /wv distribute}'s three multi-recipient heads
 * &mdash; {@link RandomDistributor}, {@link ObjectivesDistributor} and
 * {@link GraidsDistributor}. Each head decides <em>who</em> is owed <em>how many</em>; this
 * class drains the resulting queue, one recipient per {@link #processNext} call, and closes
 * the Members menu when it runs dry.
 *
 * <p>One pop is: {@code MembersListSearcher.armSearch} for the head of the queue, then
 * {@code MemberSlotPresser.fire} on whatever slot it matches, with the next
 * {@link #processNext} threaded through <em>both</em> the send's completion and the
 * searcher's not-found callback &mdash; so a member who cannot be located costs the queue
 * one entry rather than the whole run. The recursion is the loop; there is no other driver.
 *
 * <h2>Why no name resolution happens here</h2>
 *
 * <p>Every queued name is already in the <em>legacy</em> form the Members GUI tiles carry, so
 * {@link MembersListSearcher}'s literal-input arm matches directly and no per-recipient
 * {@link NameResolver} call is needed. That holds for all three heads, by three different
 * routes:
 *
 * <table border="1">
 *   <caption>Where each head's queued names come from</caption>
 *   <tr><th>Head</th><th>Source</th><th>Already legacy because</th></tr>
 *   <tr>
 *     <td>{@link RandomDistributor}</td>
 *     <td>{@link NameResolver#fetchAllLegacyNames()}</td>
 *     <td>it returns each member's tile name &mdash; wapi's {@code legacyName}, or the current
 *         name when there is none. Picking from {@code Models.Guild.getGuildMembers()} instead
 *         would hand this loop current names and need a resolve per pick.</td>
 *   </tr>
 *   <tr>
 *     <td>{@link ObjectivesDistributor}</td>
 *     <td>{@link MembersListWalker}'s tile hover names</td>
 *     <td>they were read off the Members GUI itself, so they are the tile name by
 *         construction.</td>
 *   </tr>
 *   <tr>
 *     <td>{@link GraidsDistributor}</td>
 *     <td>{@link NameResolver#fetchNameIndex()}</td>
 *     <td>the index maps both name forms onto the legacy one; the log's username tokens are
 *         the keys, and what comes back out is always the value.</td>
 *   </tr>
 * </table>
 *
 * <h2>What stays with the heads</h2>
 *
 * <p>Two things about the three loops looked shared and are not.
 *
 * <ul>
 *   <li><b>The arming order.</b> {@link RandomDistributor} and {@link GraidsDistributor}
 *       call {@link #processNext} <em>before</em>
 *       {@link GuildManageOpener#openManageMembers()}, so the searcher is armed by the time
 *       {@code MenuOpenedEvent.Pre} fires and binds on it. {@link ObjectivesDistributor}
 *       calls it with the Members menu already open &mdash; its walk ended there &mdash; and
 *       so takes the searcher's re-arm fast path instead. Only the <em>first</em> pop
 *       differs: a later one normally re-arms into a menu the previous send left open,
 *       whichever head queued it. (If that menu has gone by then, nothing here reopens it;
 *       see {@code distribute-escape-mid-run-drains-through-watchdog}.)</li>
 *   <li><b>Closing the menu on an early out.</b> Of this loop's own paths, only the
 *       {@code queue.isEmpty()} terminal below closes the Members menu. Each head's own
 *       no-recipient exits answer that question separately, and all three answers are
 *       right:
 *       {@link ObjectivesDistributor} closes, because its walk left the Members menu open;
 *       {@link GraidsDistributor} does not, because none of its early-outs has opened the
 *       Members menu, so there is no Members menu for it to close &mdash; the Guild Log, if
 *       {@link GuildLogWalker} bound it, the walker closes itself, and a Manage menu the Log
 *       route left open is a different menu
 *       ({@code graids-log-never-reached-reported-as-empty-log});
 *       {@link RandomDistributor} does not,
 *       because at that point it has opened nothing at all.</li>
 * </ul>
 */
final class DistributionQueue {

    /** One queued recipient and the per-user count we owe them. */
    // Package-private because the three heads build their queues from it
    // (GraidsDistributorTest and ObjectivesDistributorTest name it too). Deliberately not
    // the "Package-private for unit tests" marker, which asserts that narrowing back to
    // private is safe once the test goes; here it would break all three heads.
    record Distribution(String legacyName, int count) {}

    private DistributionQueue() {}

    /**
     * Sends to the head of {@code queue} and re-enters itself when that send finishes, or
     * finishes the run when the queue is empty: green {@code "Distribution complete."},
     * {@link MemberSlotPresser#closeMembersScreen()}, then {@code onComplete}.
     *
     * @param queue drained in place; the caller keeps no other reference to it
     * @param resource which hotbar button the presser synthesises
     * @param onComplete fired at most once, after the terminal close, and only if the queue
     *     drains &mdash; a send or search that ends without calling back ends the run without it
     *     ({@code member-slot-presser-drops-completion}, and the searcher and presser
     *     {@code no} rows of {@code vetsmod_distribute.md} §7); may be {@code null} for the
     *     heads' two-argument
     *     {@code dispatch} entry points, which chain nothing
     * @param logTag prefix for this run's debug lines &mdash; the calling head's simple
     *     class name, so an {@code @split} run's three phases stay distinguishable in the
     *     log
     */
    static void processNext(
            Deque<Distribution> queue,
            MemberSlotPresser.Resource resource,
            Runnable onComplete,
            String logTag) {
        if (queue.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Distribution complete.").withStyle(ChatFormatting.GREEN));
            MemberSlotPresser.closeMembersScreen();
            if (onComplete != null) onComplete.run();
            return;
        }
        Distribution d = queue.poll();
        VetsLogger.debug(
                "{}: queue popped [{}] (count={}), {} left",
                logTag,
                d.legacyName(),
                d.count(),
                queue.size());

        // Names are already canonical legacy (see the table above), so the searcher's
        // literal-input arm matches the Members GUI tile directly — no per-pick
        // NameResolver call.
        MembersListSearcher.armSearch(
                d.legacyName(),
                slot ->
                        MemberSlotPresser.fire(
                                slot,
                                resource,
                                d.count(),
                                d.legacyName(),
                                () -> processNext(queue, resource, onComplete, logTag)),
                () -> processNext(queue, resource, onComplete, logTag));
    }
}
