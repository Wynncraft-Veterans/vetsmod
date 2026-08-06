package org.wynnvets.distribute.walker;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Models;
import com.wynntils.core.text.StyledText;
import com.wynntils.mc.event.ContainerSetSlotEvent;
import com.wynntils.mc.event.MenuEvent;
import com.wynntils.mc.event.TickEvent;
import com.wynntils.models.items.items.gui.GuildLogItem;
import com.wynntils.utils.mc.McUtils;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.logging.VetsLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Walks the in-game {@code "<guild>'s Log: <category>"} GUI and
 * collects every {@link GuildLogItem} that lands in the container,
 * piggybacking on Wynntils' own auto-pagination of the log screen.
 *
 * <h2>Why piggyback rather than paginate ourselves</h2>
 * <p>Wynntils ships a {@code GuildLogHolder} that wraps the vanilla log
 * GUI with a custom widget-based viewer and auto-clicks {@code Next
 * Page} (slot 45) every time a full batch of 32 entries lands. The
 * holder's collected items aren't exposed via the model API, so we
 * can't read them directly &mdash; but the {@code ContainerSetSlot.Post}
 * events that feed it are public, and Wynntils' {@code GuildLogAnnotator}
 * (registered with {@code Models.Item}) turns each paper item into a
 * {@link GuildLogItem} containing its parsed timestamp and lore. Two
 * subscribers on the same events don't conflict on reading; only the
 * pagination is side-effectful, and we leave that to Wynntils.</p>
 *
 * <h2>Completion detection</h2>
 * <p>Wynncraft caps the log at roughly 100 most-recent entries (about
 * 3-4 pages), and Wynntils stops paginating when a page returns fewer
 * than 32 items. We can't see that decision directly &mdash; the
 * holder field is private &mdash; so we use a settle-timer instead:
 * if no new {@link GuildLogItem} has been observed for
 * {@link #SETTLE_TICKS} ticks, the walk is considered complete.</p>
 *
 * <p>On completion the walker closes the wrapped screen client-side
 * (which sends the close packet and tears down Wynntils' wrap cleanly)
 * and invokes the {@link Completion} callback with an immutable
 * snapshot of the collected items.</p>
 */
public final class GuildLogWalker {

    /** Mirrors {@code GuildLogHolder.TITLE_PATTERN}. */
    private static final Pattern LOG_TITLE_PATTERN = Pattern.compile(".+'s? Log: (.+)");

    /** No new log item for this many ticks &rArr; walk is done. Picked
     *  long enough to cover Wynntils' inter-page request delay
     *  ({@code REQUEST_TIMEOUT = 5} + {@code FORCED_LOAD_DELAY = 20}). */
    private static final int SETTLE_TICKS = 40;

    /** Safety cap: if the walk hasn't finished in this many ticks
     *  total, abort. ~10s. */
    private static final int OVERALL_TIMEOUT_TICKS = 200;

    /** Completion callback fired once the settle timer expires. */
    @FunctionalInterface
    public interface Completion {
        void onComplete(List<GuildLogItem> entries);
    }

    private static final GuildLogWalker INSTANCE = new GuildLogWalker();

    private static volatile boolean active = false;
    private static volatile int containerId = -1;
    private static volatile Completion completion = null;
    private static final List<GuildLogItem> collected = new ArrayList<>();
    /** Tick on which we last saw a new GuildLogItem land. */
    private static volatile int lastItemTick = -1;
    /** Tick on which the walk was armed (for overall timeout). */
    private static volatile int startTick = -1;

    private GuildLogWalker() {}

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        VetsLogger.debug("Registered GuildLogWalker on Wynntils event bus");
    }

    /**
     * Arms the walker for the next guild log open. The caller is
     * expected to follow up with something that opens the log GUI. The
     * only caller today, {@code GraidsDistributor}, uses
     * {@code GuildManageOpener.openGuildLog()}, which sends
     * {@code /guild manage} and clicks the Guild Log tile &mdash; a
     * direct {@code /guild log} is unreliable this soon after a menu
     * close, which is the whole reason that route exists.
     */
    public static void armWalk(Completion onComplete) {
        active = true;
        completion = onComplete;
        containerId = -1;
        collected.clear();
        lastItemTick = -1;
        startTick = McUtils.player() != null ? McUtils.player().tickCount : 0;
        VetsLogger.debug("GuildLogWalker: armed, awaiting menu open");
    }

    private static void stop() {
        active = false;
        completion = null;
        containerId = -1;
        collected.clear();
        lastItemTick = -1;
        startTick = -1;
    }

    @SubscribeEvent
    public void onMenuOpenPost(MenuEvent.MenuOpenedEvent.Post event) {
        if (!active) return;
        if (containerId != -1) return;
        String titleText = StyledText.fromComponent(event.getTitle()).getStringWithoutFormatting();
        if (!StyledText.fromComponent(event.getTitle()).matches(LOG_TITLE_PATTERN)) {
            VetsLogger.debug("GuildLogWalker: ignored menu open id={} title=[{}] (no log-title match)",
                    event.getContainerId(), titleText);
            return;
        }
        containerId = event.getContainerId();
        // Set the last-item tick to "now" so we don't immediately settle
        // before any items have had time to arrive.
        lastItemTick = currentTick();
        VetsLogger.debug("GuildLogWalker: bound to menu id={} title=[{}]", containerId, titleText);
    }

    @SubscribeEvent
    public void onMenuClose(MenuEvent.MenuClosedEvent event) {
        if (!active) return;
        if (event.getContainerId() != containerId) return;
        // Screen closed under us (user pressed escape, or the underlying
        // container went away). Finish with whatever we've collected.
        VetsLogger.debug("GuildLogWalker: menu closed mid-walk after {} entries",
                collected.size());
        finishWalk(false);
    }

    @SubscribeEvent
    public void onSetSlot(ContainerSetSlotEvent.Post event) {
        if (!active) return;
        if (event.getContainerId() != containerId) return;
        ItemStack stack = event.getItemStack();
        Optional<GuildLogItem> opt = Models.Item.asWynnItem(stack, GuildLogItem.class);
        if (opt.isEmpty()) return;

        GuildLogItem item = opt.get();
        // Dedupe by (instant, first-line-text). Wynntils' annotator can
        // produce equivalent items if a page re-renders during scroll.
        if (alreadyCollected(item)) return;
        collected.add(item);
        lastItemTick = currentTick();
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!active) return;
        int now = currentTick();
        // Overall-timeout check is first and outside the containerId
        // guard so a walk that's armed but never sees a menu open
        // (e.g. the open command was silently dropped server-side)
        // still cleans up its `active=true` state after the deadline
        // — otherwise the next walk's menu open events would all
        // appear "stale" and trigger spurious match attempts.
        if (now - startTick > OVERALL_TIMEOUT_TICKS) {
            VetsLogger.debug("GuildLogWalker: overall timeout reached (boundId={}, {} entries)",
                    containerId, collected.size());
            finishWalk(containerId != -1);
            return;
        }
        if (containerId == -1) return;
        if (lastItemTick > 0 && now - lastItemTick > SETTLE_TICKS) {
            VetsLogger.debug("GuildLogWalker: settled after {} entries", collected.size());
            finishWalk(true);
        }
    }

    private static boolean alreadyCollected(GuildLogItem item) {
        for (GuildLogItem existing : collected) {
            if (!existing.getLogInstant().equals(item.getLogInstant())) continue;
            if (existing.getLogInfo().isEmpty() || item.getLogInfo().isEmpty()) continue;
            if (existing.getLogInfo().get(0).equals(item.getLogInfo().get(0))) return true;
        }
        return false;
    }

    private static void finishWalk(boolean closeScreen) {
        Completion cb = completion;
        List<GuildLogItem> snapshot = new ArrayList<>(collected);
        stop();
        if (closeScreen && McUtils.mc().screen != null) {
            // Closing the wrapped screen propagates to Wynntils' holder
            // (via WrappedScreenHandler.onScreenClose) which sends the
            // ServerboundContainerClosePacket. No double-close needed.
            McUtils.mc().setScreen(null);
        }
        if (cb != null) cb.onComplete(snapshot);
    }

    private static int currentTick() {
        return McUtils.player() != null ? McUtils.player().tickCount : 0;
    }
}
