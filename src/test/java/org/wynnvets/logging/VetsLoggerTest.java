package org.wynnvets.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pins that {@link VetsLogger}'s debug logging starts off: a player gets debug lines only once
 * something turns it on, as {@code VetsmodClient} does from the stored config.
 */
class VetsLoggerTest {

    @AfterEach
    void resetDebugFlag() {
        VetsLogger.setDebugEnabled(false);
    }

    @Test
    void defaultIsFalse() {
        assertFalse(VetsLogger.isDebugEnabled());
    }
}
