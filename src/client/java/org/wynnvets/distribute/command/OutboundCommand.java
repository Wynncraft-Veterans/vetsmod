package org.wynnvets.distribute.command;

import com.wynntils.core.components.Handlers;
import com.wynntils.handlers.command.CommandHandler;
import org.wynnvets.logging.VetsLogger;

import java.lang.reflect.Field;
import java.util.Deque;
import java.util.Queue;

/**
 * Thin wrapper around {@link com.wynntils.handlers.command.CommandHandler}
 * that gives user-initiated distribute commands {@em front-of-queue}
 * priority over background traffic ({@code /v} fanout from
 * {@code MessageFanoutDispatcher}, {@code /find} batches, etc.).
 *
 * <h2>Why we need this</h2>
 * <p>Wynntils' {@code Handlers.Command.queueCommand} appends to an
 * internal {@code LinkedList} &mdash; FIFO with no priority API. If the
 * user starts a {@code /wv distribute} flow while staff chat is still
 * draining a fanout, our {@code /guild manage} would queue behind every
 * pending {@code /msg}, delaying the visible response by several
 * seconds at the 7-tick-per-command server rate. Distribute is
 * user-initiated and should jump the line.</p>
 *
 * <h2>How</h2>
 * <p>Wynntils' command queue field is declared {@code private final
 * Queue<String>} but instantiated as a {@code LinkedList}, which
 * <em>is</em> a {@code Deque}. We reflect once, cache the {@code Field}
 * lookup, and {@code addFirst} on subsequent calls. The next
 * {@code TickEvent} drains it through the same rate-limited path as
 * every other queued command &mdash; we don't bypass the 7-tick
 * spacing, just the FIFO ordering.</p>
 *
 * <p>If the reflection fails (e.g. Wynntils refactors the field name
 * or type), we fall back to {@link Handlers#Command}'s public
 * {@code queueCommand} so commands still get sent. Front-of-queue
 * priority becomes degraded but nothing breaks.</p>
 */
public final class OutboundCommand {

    /** Cached reflection handle for the {@code commandQueue} field on
     *  {@link CommandHandler}. {@code null} on first call; either a
     *  populated handle or {@code FIELD_LOOKUP_FAILED} after init. */
    private static volatile Field commandQueueField;
    /** Sentinel marker so we don't re-attempt reflection every call
     *  after an init failure. */
    private static final Field FIELD_LOOKUP_FAILED;

    static {
        try {
            FIELD_LOOKUP_FAILED = OutboundCommand.class.getDeclaredField("FIELD_LOOKUP_FAILED");
        } catch (NoSuchFieldException e) {
            // Self-reflection of a field we just declared; can only fail
            // if the class file is corrupted.
            throw new IllegalStateException(e);
        }
    }

    private OutboundCommand() {}

    /**
     * Queues {@code command} at the front of Wynntils' outbound queue
     * so it runs ahead of any already-queued items, still subject to
     * the 7-tick-per-command rate limit. Falls back to a normal
     * back-of-queue {@code queueCommand} if Wynntils' internal layout
     * changes.
     *
     * @param command command text without leading slash
     */
    public static void queueFront(String command) {
        Deque<String> deque = obtainDeque();
        if (deque != null) {
            deque.addFirst(command);
            return;
        }
        // Reflection unavailable — degrade to standard back-of-queue
        // behavior. Commands still send, just without priority.
        Handlers.Command.queueCommand(command);
    }

    private static Deque<String> obtainDeque() {
        Field field = commandQueueField;
        if (field == null) {
            field = lookupField();
            commandQueueField = field;
        }
        if (field == FIELD_LOOKUP_FAILED) return null;
        try {
            Object value = field.get(Handlers.Command);
            if (value instanceof Deque<?>) {
                @SuppressWarnings("unchecked")
                Deque<String> deque = (Deque<String>) value;
                return deque;
            }
            VetsLogger.debug("OutboundCommand: commandQueue is not a Deque ({}), falling back",
                    value == null ? "null" : value.getClass().getName());
        } catch (IllegalAccessException e) {
            VetsLogger.debug("OutboundCommand: failed to read commandQueue: {}", e.getMessage());
        }
        return null;
    }

    private static Field lookupField() {
        try {
            Field field = CommandHandler.class.getDeclaredField("commandQueue");
            field.setAccessible(true);
            // Sanity-check that what we got is actually queue-shaped.
            // If Wynntils ever swaps the type to a non-Queue we want
            // the fallback to kick in immediately, not on every call.
            Object value = field.get(Handlers.Command);
            if (!(value instanceof Queue<?>)) {
                VetsLogger.warn("OutboundCommand: Wynntils commandQueue is not a Queue ({}), using fallback",
                        value == null ? "null" : value.getClass().getName());
                return FIELD_LOOKUP_FAILED;
            }
            return field;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            VetsLogger.warn("OutboundCommand: could not access Wynntils commandQueue ({}); "
                    + "front-of-queue priority disabled, using queueCommand fallback",
                    e.getMessage());
            return FIELD_LOOKUP_FAILED;
        }
    }
}
