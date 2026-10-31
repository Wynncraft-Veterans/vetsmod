package org.wynnvets.retro.render;

/**
 * When {@link RetroHud}'s hearts flash, by vanilla's rules ({@code Gui.renderPlayerHealth}) on
 * Wynncraft's health: a row that falls flashes for a second, on and off every three ticks, and
 * while on shows the hearts from before the change behind the new ones.
 *
 * <p>Vanilla flashes when its twenty health points fall; here, when the row's twenty halves do.
 * Unlike vanilla, a row that rises does not flash. Health that moves without moving the row does
 * not flash, and neither does a row that moves only because the maximum did, such as on changing
 * armour. Render thread only; on plain numbers, so unit-tested.</p>
 */
final class RetroHeartFlash {

    /** How long a fall flashes, in the HUD's ticks. */
    private static final int FALL_TICKS = 20;

    /** How many ticks each flash and each gap between them lasts. */
    private static final int TICKS_PER_TOGGLE = 3;

    /** How long after the last change the row from before it is kept. */
    private static final long SHOWN_HOLD_MILLIS = 1000;

    private boolean started;
    private int lastCurrent;
    private int lastHalves;
    private int shownHalves;
    private long lastChangeMillis;
    private long flashEndTick;

    /**
     * Takes in this frame's row. The first frame after {@link #reset} starts from it, unflashing.
     *
     * @param current the health
     * @param halves the halves of the row it fills
     * @param tick the HUD's tick
     * @param millis the time, in milliseconds
     */
    void update(int current, int halves, long tick, long millis) {
        if (!started) {
            started = true;
            shownHalves = halves;
            lastChangeMillis = millis;
        } else if (halves < lastHalves && current < lastCurrent) {
            lastChangeMillis = millis;
            flashEndTick = tick + FALL_TICKS;
        }
        if (millis - lastChangeMillis > SHOWN_HOLD_MILLIS) {
            shownHalves = halves;
            lastChangeMillis = millis;
        }
        lastCurrent = current;
        lastHalves = halves;
    }

    /**
     * Whether the hearts draw flashing this tick.
     *
     * @param tick the HUD's tick
     * @return whether a flash is on
     */
    boolean flashing(long tick) {
        return flashEndTick > tick && (flashEndTick - tick) / TICKS_PER_TOGGLE % 2 == 1;
    }

    /**
     * The row from before the last changes, which a flash shows behind the current one.
     *
     * @return its filled halves
     */
    int shownHalves() {
        return shownHalves;
    }

    /** Forgets the row and any flash, while there is no health to show. */
    void reset() {
        started = false;
        flashEndTick = 0;
    }
}
