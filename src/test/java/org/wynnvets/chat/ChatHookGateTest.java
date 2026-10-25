package org.wynnvets.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ChatHookGate#isPrimary(Object, Object)}, the identity check that lets
 * {@code ChatLogMixin} run once per message instead of once per {@code ChatComponent} that
 * Wynntils' Chat Tabs re-adds it to.
 *
 * <p>NOTE: {@link ChatHookGate#shouldRun} reads {@code McUtils.mc().gui}, which has
 * no running client under JUnit, so only the pure identity check is covered here. The HUD lookup
 * and the mixin wiring are verified in game through the {@code chat hook:} debug lines.</p>
 */
class ChatHookGateTest {

    @Test
    void isPrimary_sameInstance_true() {
        Object hud = new Object();
        assertTrue(ChatHookGate.isPrimary(hud, hud));
    }

    @Test
    void isPrimary_differentInstance_false() {
        Object tab = new Object();
        Object hud = new Object();
        assertFalse(ChatHookGate.isPrimary(tab, hud));
    }

    @Test
    void isPrimary_equalButDistinctInstances_false() {
        // Identity, not equals(): two tabs that compare equal are still different components.
        String tab = new String("same");
        String hud = new String("same");
        assertFalse(ChatHookGate.isPrimary(tab, hud));
    }

    @Test
    void isPrimary_nullPrimary_failsOpen() {
        assertTrue(ChatHookGate.isPrimary(new Object(), null));
    }
}
