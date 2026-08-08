package org.wynnvets.chat.dispatcher;

import com.wynntils.core.components.Handlers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

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

/**
 * Handles {@code /find} batch lookups, serialized on the shared dispatch executor
 * so they never interleave with {@code /msg} fanout.
 *
 * <p>Called from {@link CommandDispatcher}'s batch coordinator; provides the public suppression
 * hook consumed by {@link org.wynnvets.mixin.client.chat.ChatLogMixin ChatLogMixin}.</p>
 */
public final class FindDispatcher {

    /**
     * Sentinel server value returned by {@code /find} when a player is on a
     * private server (media, dev, staff, etc.).
     */
    public static final String PRIVATE_SERVER = "PRIVATE";

    static final ConcurrentLinkedQueue<FindBatch> FIND_BATCH_QUEUE = new ConcurrentLinkedQueue<>();

    private static final Object FIND_RESPONSE_LOCK = new Object();
    private static volatile AwaitingFindResponse awaitingFindResponse;
    private static final long FIND_RESPONSE_WAIT_MS = 6_000L;

    private FindDispatcher() {
    }

    // ──────────────────────────── Public API ────────────────────────────

    /**
     * Enqueues a batch of {@code /find} lookups to run on the shared dispatch executor.
     * The find commands are serialized behind any pending {@code /msg} broadcasts so the
     * two never interleave. Results are delivered via the returned future as a map of
     * username → server (or {@code null} value for offline / not-found users).
     */
    public static void enqueueFindBatch(List<String> usernames, CompletableFuture<Map<String, String>> resultFuture) {
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
     * Returns the server name (e.g. "AS25") or {@code null} if offline/not found.
     */
    private static String tryFindUser(Minecraft minecraft, LocalPlayer player, String username) {
        CountDownLatch submitted = new CountDownLatch(1);
        AtomicBoolean commandSent = new AtomicBoolean(false);
        String usernameLower = username.toLowerCase(Locale.ROOT);

        minecraft.execute(() -> {
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
     * Blocks until the find-response system signals that the server responded
     * to a {@code /find} command.
     */
    private static String waitForFindResponse(String usernameLower) {
        long deadline = System.currentTimeMillis() + FIND_RESPONSE_WAIT_MS;

        synchronized (FIND_RESPONSE_LOCK) {
            while (true) {
                AwaitingFindResponse awaiting = awaitingFindResponse;
                if (awaiting != null && awaiting.usernameLower.equals(usernameLower) && awaiting.resultReady) {
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

    // ──────────────────────────── Find-response suppression (ChatLogMixin) ────────────────────────────

    /**
     * Called from {@link org.wynnvets.mixin.client.chat.ChatLogMixin ChatLogMixin} on the render
     * thread for every incoming chat message. Matches {@code /find} response lines, suppresses them
     * from display, and signals the dispatch thread with the result.
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
     */
    private static String stripFormattingAndPua(String text) {
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
            if (type == Character.PRIVATE_USE || type == Character.SURROGATE
                    || type == Character.UNASSIGNED || type == Character.FORMAT) {
                i += len;
                continue;
            }

            sb.appendCodePoint(cp);
            i += len;
        }
        return sb.toString();
    }

    // ──────────────────────────── Internal records ────────────────────────────

    record FindBatch(List<String> usernames, CompletableFuture<Map<String, String>> resultFuture) {
    }

    private static final class AwaitingFindResponse {
        private final String usernameLower;
        private volatile String server;
        private volatile boolean resultReady;

        private AwaitingFindResponse(String usernameLower) {
            this.usernameLower = usernameLower;
        }
    }
}
