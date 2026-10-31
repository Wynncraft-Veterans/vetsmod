package org.wynnvets.retro;

/**
 * Lets a suite in another package reach what {@code org.wynnvets.retro} keeps package-private.
 * {@code RetroPackResourcesTest} resets {@link RetroModeManager}'s session state (a failed
 * generation there sets the session latch).
 */
public final class RetroTestState {

    private RetroTestState() {}

    /** Resets {@link RetroModeManager}'s session state, the latch included. */
    public static void clear() {
        RetroModeManager.clearForTesting();
    }
}
