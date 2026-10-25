package org.wynnvets.chat;

import com.wynntils.utils.mc.McUtils;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.wynnvets.logging.VetsLogger;

/**
 * Lets {@code ChatLogMixin} run once per chat line instead of once per {@link ChatComponent}.
 *
 * <p>The mixin targets the {@link ChatComponent} class, so every instance carries the hook.
 * With Wynntils' Chat Tabs on, {@code minecraft.gui.chat} is Wynntils' {@code
 * WrappingChatComponent}: it forwards each message to the hidden vanilla component, then calls
 * the one-argument {@code addMessage(Component)} on every matching tab's own
 * {@link ChatComponent}. The hook therefore ran once on the HUD component and once per
 * matching tab. {@link #shouldRun} lets only the HUD component's pass through; the tab re-adds
 * are skipped. Messages a rewriter cancels and re-displays re-enter through the HUD component
 * with the internal-dispatch flag set, so the primary pass still sees every line.</p>
 *
 * <p>When {@code McUtils.mc().gui} is not up the gate fails open (every pass
 * runs). Under {@link VetsLogger#isDebugEnabled()} each call logs one {@code chat hook:} line
 * with running counters and the line's identity hash, which is what the in-game check reads.</p>
 */
public final class ChatHookGate {
    private static final int PREVIEW_CHARS = 40;
    private static final AtomicInteger primaryPasses = new AtomicInteger();
    private static final AtomicInteger skippedPasses = new AtomicInteger();

    private ChatHookGate() {}

    /**
     * True when {@code self} is the HUD chat component, or when there is no HUD chat component
     * to compare with (fail open). Identity, not {@code equals}.
     */
    static boolean isPrimary(Object self, Object primary) {
        return primary == null || self == primary;
    }

    /**
     * Whether the hook running on {@code self} should process {@code message}.
     *
     * @param self the {@code ChatComponent} the hook is running on
     * @param message the line passed to {@code addMessage(Component)}
     */
    public static boolean shouldRun(Object self, Component message) {
        boolean primary = isPrimary(self, hudChat());
        int primaryCount = primary ? primaryPasses.incrementAndGet() : primaryPasses.get();
        int skippedCount = primary ? skippedPasses.get() : skippedPasses.incrementAndGet();
        if (VetsLogger.isDebugEnabled()) {
            VetsLogger.debug(
                    "chat hook: {} (self={}@{}, line@{}, primary={} skipped={}): {}",
                    primary ? "primary pass" : "skipped re-add",
                    self.getClass().getSimpleName(),
                    Integer.toHexString(System.identityHashCode(self)),
                    Integer.toHexString(System.identityHashCode(message)),
                    primaryCount,
                    skippedCount,
                    preview(message));
        }
        return primary;
    }

    private static ChatComponent hudChat() {
        Minecraft minecraft = McUtils.mc();
        return minecraft == null || minecraft.gui == null ? null : minecraft.gui.getChat();
    }

    private static String preview(Component message) {
        String text = message.getString().replace('\n', ' ');
        return text.length() <= PREVIEW_CHARS ? text : text.substring(0, PREVIEW_CHARS);
    }
}
