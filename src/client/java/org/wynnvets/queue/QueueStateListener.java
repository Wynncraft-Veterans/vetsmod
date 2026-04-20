package org.wynnvets.queue;

/**
 * Listener for {@link QueueStateManager} state transitions.
 *
 * <p>Default methods mean implementers only override the callbacks they care
 * about.  New callbacks added later will not break existing listeners.</p>
 */
public interface QueueStateListener {

    /**
     * Called when the client enters a Wynncraft world queue.
     *
     * @param worldName the target world name as reported by the queue title
     *                  (e.g. {@code "NA30"}), or empty if it could not be parsed
     */
    default void onQueueEntered(String worldName) {}

    /**
     * Called when the client exits the world queue — whether because the
     * queued world was reached, the client disconnected, or the state timed
     * out.
     *
     * @param reason machine-readable reason tag (e.g. {@code "world"},
     *               {@code "disconnect"}, {@code "timeout"})
     */
    default void onQueueExited(String reason) {}
}
