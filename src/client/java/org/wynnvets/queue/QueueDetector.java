package org.wynnvets.queue;

import com.wynntils.core.WynntilsMod;
import com.wynntils.mc.event.TitleSetTextEvent;
import com.wynntils.models.worlds.event.WorldStateEvent;
import com.wynntils.models.worlds.type.WorldState;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.logging.VetsLogger;

/**
 * Detects world-queue state from a handful of client-side signals and feeds
 * {@link QueueStateManager}.
 *
 * <p>Signals:</p>
 * <ul>
 *   <li><b>Entry</b> — Wynntils {@link TitleSetTextEvent} matching the
 *       Wynncraft queue title (e.g. {@code Queueing for NA30.}).  The queue
 *       server re-sends this title every few ticks, so only the first match
 *       produces a state change thanks to the idempotent
 *       {@link QueueStateManager#enter(String)}.</li>
 *   <li><b>Exit — world reached</b> — Wynntils {@link WorldStateEvent}
 *       transitioning to {@link WorldState#WORLD}.</li>
 *   <li><b>Exit — disconnected</b> — Fabric
 *       {@link ClientPlayConnectionEvents#DISCONNECT}.</li>
 *   <li><b>Exit — safety timeout</b> — if the state somehow gets stuck
 *       (detection regex drift, signal loss), a title packet that arrives
 *       after {@link #MAX_QUEUE_DURATION_MS} without matching the queue
 *       regex exits the state.</li>
 * </ul>
 *
 * <p>This class deliberately contains no chat or command logic — it only
 * raises/lowers the queue flag.  Consumers of queue state read
 * {@link QueueStateManager} or register a {@link QueueStateListener}.</p>
 */
public final class QueueDetector {

    /**
     * Matches Wynncraft's queue title.  The text has a leading green colour
     * code in the component, but we match on the plain-text form.  Wynncraft
     * animates a trailing "…"/"..."; {@code .*} absorbs that suffix as well
     * as any future additions.
     */
    private static final Pattern QUEUE_TITLE_PATTERN =
            Pattern.compile("Queueing for ([A-Z]{2}\\d{1,2}).*");

    /**
     * Fail-safe upper bound on queue duration.  Real Wynncraft queues finish
     * within a few minutes at worst; if we somehow never see an exit signal
     * this prevents the client from being stuck in queue mode forever.
     */
    private static final long MAX_QUEUE_DURATION_MS = TimeUnit.MINUTES.toMillis(10);

    private static final QueueDetector INSTANCE = new QueueDetector();
    private static boolean registered;

    private QueueDetector() {}

    /**
     * Subscribes the detector to the Wynntils event bus and Fabric client
     * networking events.  Safe to call multiple times — only the first call
     * takes effect.
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        WynntilsMod.registerEventListener(INSTANCE);
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> QueueStateManager.exit("disconnect"));
        VetsLogger.debug("QueueDetector registered");
    }

    /**
     * Processes a raw title string for queue detection.  Called from both
     * the Wynntils event handler and the direct mixin fallback so that
     * queue detection works regardless of whether another mod (e.g.
     * WynnLimbo) cancels the title packet before Wynntils sees it.
     *
     * @param text the plain-text title string
     */
    public static void handleTitleText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher m = QUEUE_TITLE_PATTERN.matcher(text);
        if (m.find()) {
            QueueStateManager.enter(m.group(1));
            return;
        }
        // Title packet that is NOT a queue title after the timeout window
        // exits the state — this is the safety net for signal-drift cases.
        if (QueueStateManager.isInQueue()) {
            long age = System.currentTimeMillis() - QueueStateManager.getEnteredAtMs();
            if (age > MAX_QUEUE_DURATION_MS) {
                QueueStateManager.exit("timeout");
            }
        }
    }

    @SubscribeEvent
    public void onTitleSet(TitleSetTextEvent event) {
        handleTitleText(event.getComponent().getString());
    }

    @SubscribeEvent
    public void onWorldStateChanged(WorldStateEvent event) {
        if (event.getNewState() == WorldState.WORLD) {
            QueueStateManager.exit("world");
        }
    }
}
