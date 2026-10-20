package org.wynnvets.queue;

import org.wynnvets.api.V1ApiManager;
import org.wynnvets.guild.GuildStateManager;

/**
 * Tells the server when the player enters or leaves a Wynncraft world queue, by sending a
 * {@code queue_status} frame through
 * {@link org.wynnvets.api.V1ApiManager#sendQueueStatus(boolean, String) V1ApiManager}, while
 * {@link org.wynnvets.guild.GuildStateManager#isUnlocked() GuildStateManager.isUnlocked()} holds.
 *
 * <p>{@link #register()} subscribes one instance to {@link QueueStateManager}. It is called once,
 * from {@code VetsmodClient}'s {@code CLIENT_STARTED} phase, and has no guard against a second
 * call.</p>
 */
public final class QueueStatusReporter implements QueueStateListener {

    private QueueStatusReporter() {}

    /** Subscribes a new instance to {@link QueueStateManager}'s queue transitions. */
    public static void register() {
        QueueStateManager.addListener(new QueueStatusReporter());
    }

    @Override
    public void onQueueEntered(String worldName) {
        // Only report queue state from vets-context users (isUnlocked(): a
        // Returners member, or an unlocked waitlist or honourary user). The
        // server ignores queue_status from a socket that has not sent
        // `register`; this gate is the client's own and does not check that.
        if (!GuildStateManager.isUnlocked()) return;
        V1ApiManager.sendQueueStatus(true, worldName);
    }

    @Override
    public void onQueueExited(String reason) {
        if (!GuildStateManager.isUnlocked()) return;
        V1ApiManager.sendQueueStatus(false, "");
    }
}
