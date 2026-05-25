package org.wynnvets.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VetsLoggerTest {

    @AfterEach
    void resetDebugFlag() {
        VetsLogger.setDebugEnabled(false);
    }

    @Test
    void defaultIsFalse() {
        assertFalse(VetsLogger.isDebugEnabled());
    }

    @Test
    void setEnabledTogglesFlag() {
        VetsLogger.setDebugEnabled(true);
        assertTrue(VetsLogger.isDebugEnabled());
    }

    @Test
    void setDisabledRestoresFalse() {
        VetsLogger.setDebugEnabled(true);
        VetsLogger.setDebugEnabled(false);
        assertFalse(VetsLogger.isDebugEnabled());
    }
}
