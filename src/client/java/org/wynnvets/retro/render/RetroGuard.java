package org.wynnvets.retro.render;

import java.util.function.Supplier;
import org.wynnvets.logging.VetsLogger;

/**
 * A failure latch for code that draws: what it guards throws nothing into the render, and the first
 * failure is logged at warn and later ones at debug. A stopping guard then runs nothing more until
 * the game restarts, for code that would fail the same way on every frame; a retrying guard goes on
 * running, for code a later screen or stack may get through.
 *
 * <p>It catches every {@code Throwable}: a missing Wynntils class arrives as a
 * {@code LinkageError}, and a stack's model is picked behind a mixin, with no event bus to catch
 * what escapes. A guard holds only its strings, so a static one touches neither Minecraft nor
 * Wynntils. The reload's own guards ({@code RetroPackResources}, {@code RetroPackInjector}) are not
 * these: they protect the player's resource packs, and stop retro as a whole.</p>
 */
final class RetroGuard {

    private final String what;
    private final String consequence;
    private final boolean stopsOnFailure;
    private volatile boolean failed;

    private RetroGuard(String what, String consequence, boolean stopsOnFailure) {
        this.what = what;
        this.consequence = consequence;
        this.stopsOnFailure = stopsOnFailure;
    }

    /**
     * A guard that stops what it guards at the first failure, until the game restarts.
     *
     * @param what what failed, for the log, such as {@code "the HUD"}
     * @param consequence what the player sees from then on, for the log
     * @return the guard
     */
    static RetroGuard stopping(String what, String consequence) {
        return new RetroGuard(what, consequence, true);
    }

    /**
     * A guard that goes on running what it guards after a failure.
     *
     * @param what what failed, for the log
     * @param consequence what the player sees of each failure, for the log
     * @return the guard
     */
    static RetroGuard retrying(String what, String consequence) {
        return new RetroGuard(what, consequence, false);
    }

    /**
     * Whether a failure has stopped what this guards.
     *
     * @return {@code true} after a stopping guard's first failure; a retrying guard never stops
     */
    boolean stopped() {
        return stopsOnFailure && failed;
    }

    /**
     * Runs {@code body}, unless stopped.
     *
     * @param body what to run
     */
    void run(Runnable body) {
        call(
                () -> {
                    body.run();
                    return null;
                },
                null);
    }

    /**
     * Calls {@code body}, unless stopped.
     *
     * @param <T> what it gives
     * @param body what to call
     * @param fallback the answer when stopped, or when {@code body} fails
     * @return what {@code body} gave, or {@code fallback}
     */
    <T> T call(Supplier<T> body, T fallback) {
        return stopped() ? fallback : callEvenIfStopped(body, fallback);
    }

    /**
     * Calls {@code body} whether or not this has stopped, for what decides how to draw once it has.
     * A failure counts as any other does.
     *
     * @param <T> what it gives
     * @param body what to call
     * @param fallback the answer when {@code body} fails
     * @return what {@code body} gave, or {@code fallback}
     */
    <T> T callEvenIfStopped(Supplier<T> body, T fallback) {
        try {
            return body.get();
        } catch (Throwable cause) {
            fail(cause);
            return fallback;
        }
    }

    private void fail(Throwable cause) {
        if (failed) {
            VetsLogger.debug("Retro: {} failed again", what, cause);
            return;
        }
        failed = true;
        VetsLogger.warn("Retro: {} failed; {}", what, consequence, cause);
    }
}
