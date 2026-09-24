package org.wynnvets.chat.dispatcher;

import com.wynntils.core.components.Handlers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Handles {@code /find} batch lookups, serialized on the shared dispatch executor
 * so they never interleave with {@code /msg} fanout.
 *
 * <p>{@link #enqueueFindBatch} is called directly by callers outside this package (not
 * through {@link CommandDispatcher}); {@link CommandDispatcher}'s batch coordinator then
 * runs each queued batch. This class's suppression hook,
 * {@link #shouldSuppressFindResponse}, is reached from
 * {@link org.wynnvets.mixin.client.chat.ChatLogMixin ChatLogMixin} through
 * {@link CommandDispatcher#shouldSuppressFindResponse}.</p>
 */
public final class FindDispatcher {

    /**
     * Sentinel server value this dispatcher records when a {@code /find} reply says the
     * player is on a private server.
     */
    public static final String PRIVATE_SERVER = "PRIVATE";

    static final ConcurrentLinkedQueue<FindBatch> FIND_BATCH_QUEUE = new ConcurrentLinkedQueue<>();

    private static final Object FIND_RESPONSE_LOCK = new Object();
    private static volatile AwaitingFindResponse awaitingFindResponse;
    private static final long FIND_RESPONSE_WAIT_MS = 6_000L;

    private FindDispatcher() {}

    // ──────────────────────────── Public API ────────────────────────────

    /**
     * Enqueues a batch of {@code /find} lookups to run on the shared dispatch executor.
     * The lookups are driven from the same single dispatch thread as {@code /msg}
     * fan-out, so the two never interleave; see {@link CommandDispatcher} for how a
     * dispatch pass orders them. Completes the caller-supplied {@code resultFuture} with
     * a map of username → lower-cased server name, {@link #PRIVATE_SERVER} for a private
     * server, or {@code null} otherwise (for example, the player is reported offline or
     * no matching reply arrives in time). A null or empty list, or no local player,
     * completes it with an empty map; an exception while the batch runs completes it
     * exceptionally.
     */
    public static void enqueueFindBatch(
            List<String> usernames, CompletableFuture<Map<String, String>> resultFuture) {
        if (usernames == null || usernames.isEmpty()) {
            resultFuture.complete(Map.of());
            return;
        }

        FIND_BATCH_QUEUE.add(new FindBatch(new ArrayList<>(usernames), resultFuture));
        CommandDispatcher.startBatchIfIdle();
    }

    // ──────────────────────────── Find-batch dispatch ────────────────────────────

    /**
     * Processes a single /find batch: sends {@code /find <username>} for each name,
     * waits for the server response, and collects results into the future.
     */
    static void processFindBatch(FindBatch batch) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (player == null) {
            batch.resultFuture().complete(Map.of());
            return;
        }

        Map<String, String> results = new LinkedHashMap<>();

        for (String username : batch.usernames()) {
            String server = tryFindUser(minecraft, player, username);
            results.put(username, server);
            // Wynntils command queue handles rate-limiting between sends
        }

        batch.resultFuture().complete(results);
    }

    /**
     * Sends {@code /find <username>} and waits for the server response.
     * Returns the server name, lower-cased as matched (e.g. {@code "as25"}),
     * {@link #PRIVATE_SERVER} for a private server, or {@code null} otherwise (for
     * example, the player is reported offline or no matching reply arrives in time).
     */
    private static String tryFindUser(Minecraft minecraft, LocalPlayer player, String username) {
        CountDownLatch submitted = new CountDownLatch(1);
        AtomicBoolean commandSent = new AtomicBoolean(false);
        String usernameLower = username.toLowerCase(Locale.ROOT);

        minecraft.execute(
                () -> {
                    try {
                        if (player.connection == null) {
                            return;
                        }

                        synchronized (FIND_RESPONSE_LOCK) {
                            awaitingFindResponse = new AwaitingFindResponse(usernameLower);
                        }

                        Handlers.Command.queueCommand("find " + username);
                        commandSent.set(true);
                    } finally {
                        submitted.countDown();
                    }
                });

        try {
            if (!submitted.await(2, TimeUnit.SECONDS)) {
                clearAwaitingFind(usernameLower);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            clearAwaitingFind(usernameLower);
            return null;
        }

        if (!commandSent.get()) {
            clearAwaitingFind(usernameLower);
            return null;
        }

        return waitForFindResponse(usernameLower);
    }

    /**
     * Blocks until the find-response system signals that the server responded to a
     * {@code /find} command, or until {@link #FIND_RESPONSE_WAIT_MS} passes or the wait
     * is interrupted, in which case it returns {@code null}.
     */
    private static String waitForFindResponse(String usernameLower) {
        long deadline = System.currentTimeMillis() + FIND_RESPONSE_WAIT_MS;

        synchronized (FIND_RESPONSE_LOCK) {
            while (true) {
                AwaitingFindResponse awaiting = awaitingFindResponse;
                if (awaiting != null
                        && awaiting.usernameLower.equals(usernameLower)
                        && awaiting.resultReady) {
                    awaitingFindResponse = null;
                    return awaiting.server;
                }

                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    clearAwaitingFind(usernameLower);
                    return null;
                }

                try {
                    FIND_RESPONSE_LOCK.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    clearAwaitingFind(usernameLower);
                    return null;
                }
            }
        }
    }

    private static void clearAwaitingFind(String usernameLower) {
        synchronized (FIND_RESPONSE_LOCK) {
            AwaitingFindResponse awaiting = awaitingFindResponse;
            if (awaiting != null && awaiting.usernameLower.equals(usernameLower)) {
                awaitingFindResponse = null;
            }
        }
    }

    // ──────────────────────────── Find-response suppression (direct caller: CommandDispatcher)
    // ────────────────────────────

    /**
     * Reached from {@link org.wynnvets.mixin.client.chat.ChatLogMixin ChatLogMixin} on the render
     * thread for incoming chat messages that reach its single-argument
     * {@code ChatComponent#addMessage(Component)} hook and are not consumed by one of the
     * suppression checks it runs before this one. Matches {@code /find} response lines,
     * suppresses them from display, and signals the dispatch thread with the result.
     *
     * @return {@code true} if the message was consumed (should be suppressed)
     */
    public static boolean shouldSuppressFindResponse(String message) {
        AwaitingFindResponse awaiting = awaitingFindResponse;
        if (awaiting == null) {
            return false;
        }

        String sanitized = stripFormattingAndPua(message).toLowerCase(Locale.ROOT);
        String target = awaiting.usernameLower;

        // "username is currently on server XX##"
        String onlineMarker = target + " is currently on server ";
        int onlineIdx = sanitized.indexOf(onlineMarker);
        if (onlineIdx >= 0) {
            String afterMarker = sanitized.substring(onlineIdx + onlineMarker.length()).trim();
            String server = afterMarker.split("\\s")[0];
            signalFindResponse(target, server.isEmpty() ? null : server);
            return true;
        }

        // "username is currently on a private server."
        if (sanitized.contains(target + " is currently on a private server")) {
            signalFindResponse(target, PRIVATE_SERVER);
            return true;
        }

        // "username is not currently online"
        if (sanitized.contains(target + " is not currently online")) {
            signalFindResponse(target, null);
            return true;
        }

        return false;
    }

    private static void signalFindResponse(String usernameLower, String server) {
        synchronized (FIND_RESPONSE_LOCK) {
            AwaitingFindResponse awaiting = awaitingFindResponse;
            if (awaiting != null && awaiting.usernameLower.equals(usernameLower)) {
                awaiting.server = server;
                awaiting.resultReady = true;
                FIND_RESPONSE_LOCK.notifyAll();
            }
        }
    }

    /**
     * Strips {@code §x} formatting codes and PUA/surrogate characters for reliable
     * text matching against Wynncraft server responses.
     *
     * <p><b>Deliberately wider than
     * {@link org.wynnvets.chat.PillCodec#isCustomGlyph(int)
     * PillCodec.isCustomGlyph}.</b> It adds {@code SURROGATE} and {@code FORMAT},
     * and its {@code UNASSIGNED} clause carries no {@code > 0xFFFF} bound — so it
     * also drops unassigned codepoints inside the BMP, which the canonical
     * predicate keeps. That is the point: this normalises a {@code /find}
     * response down to its prose before matching, and nothing downstream reads a
     * codepoint. Narrowing it to the canonical predicate would change what
     * {@code /find} recognises. Pinned by {@code FindDispatcherTest}.</p>
     */
    // Package-private for unit tests. See FindDispatcherTest.
    static String stripFormattingAndPua(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);

            if (cp == '\u00a7' && i + 1 < text.length()) {
                i += 2;
                continue;
            }

            int type = Character.getType(cp);
            if (type == Character.PRIVATE_USE
                    || type == Character.SURROGATE
                    || type == Character.UNASSIGNED
                    || type == Character.FORMAT) {
                i += len;
                continue;
            }

            sb.appendCodePoint(cp);
            i += len;
        }
        return sb.toString();
    }

    // ──────────────────────────── Internal records ────────────────────────────

    record FindBatch(List<String> usernames, CompletableFuture<Map<String, String>> resultFuture) {}

    private static final class AwaitingFindResponse {
        private final String usernameLower;
        private volatile String server;
        private volatile boolean resultReady;

        private AwaitingFindResponse(String usernameLower) {
            this.usernameLower = usernameLower;
        }
    }
}
