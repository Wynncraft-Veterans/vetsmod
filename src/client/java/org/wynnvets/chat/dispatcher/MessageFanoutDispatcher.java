package org.wynnvets.chat.dispatcher;

import com.wynntils.core.components.Handlers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.wynnvets.logging.VetsLogger;

/**
 * Handles the actual {@code /msg} fanout dispatch and server-feedback suppression
 * for the staff chat system ({@code /v}).
 *
 * <p>All dispatch is serialized on the shared single-threaded executor owned
 * by {@link CommandDispatcher}.  The suppression ACK system ensures that
 * only one {@code /msg} is ever in-flight at a time.</p>
 */
public final class MessageFanoutDispatcher {

    private static final String PRIVATE_SEPARATOR_GLYPH = "\uE003";
    private static final long SUPPRESSION_TTL_MS = 15000L;
    private static final long OFFLINE_GUIDANCE_SUPPRESSION_WINDOW_MS = 4000L;
    private static final long INTER_SEND_DELAY_MS = 600L;
    private static final long SUPPRESSION_WAIT_MS = 5_000L;
    private static final int MAX_DISPATCH_RETRIES = 3;

    // Suppression state — safe because dispatch is single-threaded.
    private static final ConcurrentLinkedQueue<PendingSuppression> PENDING_SUPPRESSIONS =
            new ConcurrentLinkedQueue<>();
    private static final Object SUPPRESSION_ACK_LOCK = new Object();
    private static volatile AwaitingSuppression awaitingSuppression;
    private static volatile long suppressOfflineGuidanceUntilMs;

    private MessageFanoutDispatcher() {}

    // ──────────────────────────── Batch processing ────────────────────────────

    /**
     * Drains all queued /msg broadcasts, sending each to every online staff member in order.
     * Staff list is fetched once per batch; offline users are tracked and skipped.
     */
    static void processMessageBatch() {
        try {
            List<String> staffUsernames = CommandDispatcher.fetchOnlineStaffUsernames();
            Minecraft minecraft = Minecraft.getInstance();
            LocalPlayer selfPlayer = minecraft.player;

            if (selfPlayer == null) {
                VetsLogger.warn("Cannot dispatch staff broadcast: player is null");
                return;
            }

            // Remove self from recipients
            staffUsernames.removeIf(u -> CommandDispatcher.isSelfRecipient(u, selfPlayer));

            if (staffUsernames.isEmpty()) {
                // Drain the queue and warn for each message
                while (CommandDispatcher.MESSAGE_QUEUE.poll() != null) {
                    // drained
                }
                CommandDispatcher.showNoRecipientsWarning();
                return;
            }

            // Track users confirmed offline during this batch
            Set<String> offlineUsers = new HashSet<>();
            boolean deliveredAnyMessageToAnyone = false;

            String message;
            while ((message = CommandDispatcher.MESSAGE_QUEUE.poll()) != null) {
                String payload = CommandDispatcher.LOCK_PREFIX + message;
                boolean deliveredThisMessage = false;

                for (String recipient : staffUsernames) {
                    String recipientLower = recipient.toLowerCase(Locale.ROOT);

                    if (offlineUsers.contains(recipientLower)) {
                        continue;
                    }

                    FeedbackResult result = dispatchWithRetries(minecraft, recipient, payload);

                    switch (result) {
                        case DELIVERED:
                            deliveredThisMessage = true;
                            break;
                        case OFFLINE:
                            offlineUsers.add(recipientLower);
                            VetsLogger.debug(
                                    "Staff member {} is offline, skipping for remaining messages",
                                    recipient);
                            break;
                        case FAILED:
                            VetsLogger.warn(
                                    "Failed to deliver message to {} after {} retries",
                                    recipient,
                                    MAX_DISPATCH_RETRIES);
                            break;
                    }

                    // Wynntils command queue handles rate-limiting between sends
                }

                if (deliveredThisMessage) {
                    deliveredAnyMessageToAnyone = true;
                }
            }

            if (!deliveredAnyMessageToAnyone) {
                CommandDispatcher.showNoRecipientsWarning();
            }
        } catch (Exception e) {
            VetsLogger.warn("Failed to dispatch staff /v broadcast batch: {}", e.getMessage());
            // Drain remaining messages to avoid retrying a failed batch indefinitely
            while (CommandDispatcher.MESSAGE_QUEUE.poll() != null) {
                // drained
            }
            CommandDispatcher.showNoRecipientsWarning();
        }
    }

    // ──────────────────────────── Single-message dispatch ────────────────────────────

    private enum FeedbackResult {
        DELIVERED,
        OFFLINE,
        FAILED
    }

    /**
     * Attempts to send a single /msg to one recipient with retries.
     * Returns the outcome so the caller can track offline users and delivery status.
     */
    private static FeedbackResult dispatchWithRetries(
            Minecraft minecraft, String recipient, String payload) {
        for (int attempt = 1; attempt <= MAX_DISPATCH_RETRIES; attempt++) {
            FeedbackResult result = tryDispatchOnce(minecraft, recipient, payload);

            if (result == FeedbackResult.DELIVERED || result == FeedbackResult.OFFLINE) {
                return result;
            }

            // FAILED (timeout) — retry after a delay
            if (attempt < MAX_DISPATCH_RETRIES) {
                VetsLogger.debug(
                        "No /msg feedback for {} attempt {}. Retrying...", recipient, attempt);
                CommandDispatcher.sleepQuietly(INTER_SEND_DELAY_MS);
            }
        }

        return FeedbackResult.FAILED;
    }

    /**
     * Sends a single /msg command and waits for the server's feedback to determine the result.
     * Because dispatch is single-threaded, only one awaiting suppression exists at a time.
     */
    private static FeedbackResult tryDispatchOnce(
            Minecraft minecraft, String recipient, String payload) {
        AtomicBoolean commandSent = new AtomicBoolean(false);
        AtomicBoolean skippedSelf = new AtomicBoolean(false);
        CountDownLatch submitted = new CountDownLatch(1);
        String recipientLower = recipient.toLowerCase(Locale.ROOT);

        minecraft.execute(
                () -> {
                    try {
                        LocalPlayer player = minecraft.player;
                        if (player == null || player.connection == null) {
                            return;
                        }

                        if (CommandDispatcher.isSelfRecipient(recipient, player)) {
                            skippedSelf.set(true);
                            return;
                        }

                        synchronized (SUPPRESSION_ACK_LOCK) {
                            queueSuppression(recipient, payload);
                            awaitingSuppression = new AwaitingSuppression(recipientLower, payload);
                        }

                        Handlers.Command.queueCommand("msg " + recipient + " " + payload);
                        commandSent.set(true);
                    } finally {
                        submitted.countDown();
                    }
                });

        try {
            if (!submitted.await(2, TimeUnit.SECONDS)) {
                return FeedbackResult.FAILED;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FeedbackResult.FAILED;
        }

        if (skippedSelf.get()) {
            clearAwaitingIfMatches(recipientLower, payload);
            return FeedbackResult.DELIVERED;
        }

        if (!commandSent.get()) {
            clearAwaitingIfMatches(recipientLower, payload);
            return FeedbackResult.FAILED;
        }

        return waitForFeedback(recipientLower, payload);
    }

    /**
     * Blocks until the suppression system signals that server feedback was received,
     * distinguishing between successful delivery and offline-user errors.
     */
    private static FeedbackResult waitForFeedback(String usernameLower, String lockPayload) {
        long deadlineMs = System.currentTimeMillis() + SUPPRESSION_WAIT_MS;

        synchronized (SUPPRESSION_ACK_LOCK) {
            while (true) {
                AwaitingSuppression awaiting = awaitingSuppression;
                if (awaiting != null
                        && awaiting.matches(usernameLower, lockPayload)
                        && awaiting.resultReady) {
                    awaitingSuppression = null;
                    return awaiting.offline ? FeedbackResult.OFFLINE : FeedbackResult.DELIVERED;
                }

                long remainingMs = deadlineMs - System.currentTimeMillis();
                if (remainingMs <= 0) {
                    clearAwaitingIfMatches(usernameLower, lockPayload);
                    return FeedbackResult.FAILED;
                }

                try {
                    SUPPRESSION_ACK_LOCK.wait(remainingMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    clearAwaitingIfMatches(usernameLower, lockPayload);
                    return FeedbackResult.FAILED;
                }
            }
        }
    }

    private static void clearAwaitingIfMatches(String usernameLower, String lockPayload) {
        synchronized (SUPPRESSION_ACK_LOCK) {
            AwaitingSuppression awaiting = awaitingSuppression;
            if (awaiting != null && awaiting.matches(usernameLower, lockPayload)) {
                awaitingSuppression = null;
            }
        }
    }

    // ──────────────────────────── Suppression (called from ChatLogMixin)
    // ────────────────────────────

    /**
     * Called from {@link org.wynnvets.mixin.client.chat.ChatLogMixin ChatLogMixin} on the render
     * thread for every incoming chat message. Matches outbound /msg echo lines and offline-player
     * errors, suppresses them from display, and signals the dispatch thread so it can proceed
     * strategically.
     *
     * <p>Matching strategy (in order of attempt):</p>
     * <ol>
     *   <li>Offline guidance suppression — blanket-suppress "be sure to use exact names..."
     *       messages within a short window after an offline-user error.</li>
     *   <li>Direct payload echo — the server echoes our /msg back; match by normalized
     *       payload content AND (recipient name OR 🔐 lock prefix).</li>
     *   <li>Lock-prefix + recipient fallback — when Wynntils rewrites coordinates in the
     *       echo Component, payload comparison fails; fall back to prefix + recipient.</li>
     *   <li>Censored variant — Wynncraft's profanity filter replaces characters with
     *       {@code *}; match when non-star characters align with the payload.</li>
     *   <li>Token subsequence — last resort: tokenize both strings and check if the
     *       payload tokens appear as a subsequence in the message tokens.</li>
     * </ol>
     */
    public static boolean shouldSuppressFeedback(String message) {
        if (message == null || message.isEmpty()) {
            cleanupExpired();
            return false;
        }

        long now = System.currentTimeMillis();
        String lower = message.toLowerCase(Locale.ROOT);
        String lockPrefixLower = CommandDispatcher.LOCK_PREFIX.toLowerCase(Locale.ROOT);

        if (now <= suppressOfflineGuidanceUntilMs && isOfflineGuidanceMessage(lower)) {
            return true;
        }

        Iterator<PendingSuppression> iterator = PENDING_SUPPRESSIONS.iterator();
        while (iterator.hasNext()) {
            PendingSuppression pending = iterator.next();

            if (now - pending.createdAtMs > SUPPRESSION_TTL_MS) {
                iterator.remove();
                continue;
            }

            boolean payloadEchoMatch = containsNormalizedPayload(message, pending.lockPayload);
            boolean recipientMatch = isOutboundRecipientTarget(lower, pending.usernameLower);
            boolean lockPrefixPresent = lower.contains(lockPrefixLower);
            boolean isDirectMessageEcho = payloadEchoMatch && (recipientMatch || lockPrefixPresent);

            // Fallback: if payload comparison fails (e.g. Wynntils rewrites coordinates
            // in the Component before we see it), still accept when both the lock prefix
            // and the expected recipient are present. Safe because dispatch is serialized
            // and only VetsMod uses the 🔐 prefix.
            if (!isDirectMessageEcho && lockPrefixPresent && recipientMatch) {
                isDirectMessageEcho = true;
            }

            boolean isOfflineRecipientError =
                    isOfflineRecipientMessage(lower, pending.usernameLower);

            if (!isDirectMessageEcho && !isOfflineRecipientError) {
                continue;
            }

            iterator.remove();
            removeDuplicateSuppressions(pending);

            if (isOfflineRecipientError) {
                suppressOfflineGuidanceUntilMs = now + OFFLINE_GUIDANCE_SUPPRESSION_WINDOW_MS;
            }

            signalFeedbackReceived(
                    pending.usernameLower, pending.lockPayload, isOfflineRecipientError);
            return true;
        }

        return false;
    }

    // ──────────────────────────── Suppression internals ────────────────────────────

    private static void queueSuppression(String username, String payload) {
        cleanupExpired();
        PENDING_SUPPRESSIONS.add(
                new PendingSuppression(
                        username.toLowerCase(Locale.ROOT), payload, System.currentTimeMillis()));
    }

    private static void removeDuplicateSuppressions(PendingSuppression matched) {
        Iterator<PendingSuppression> iterator = PENDING_SUPPRESSIONS.iterator();
        while (iterator.hasNext()) {
            PendingSuppression pending = iterator.next();
            if (pending.usernameLower.equals(matched.usernameLower)
                    && pending.lockPayload.equals(matched.lockPayload)) {
                iterator.remove();
            }
        }
    }

    /**
     * Signals the dispatch thread that feedback was received for a pending message.
     * Distinguishes between delivery confirmation and offline-user detection.
     */
    private static void signalFeedbackReceived(
            String usernameLower, String lockPayload, boolean isOffline) {
        synchronized (SUPPRESSION_ACK_LOCK) {
            AwaitingSuppression awaiting = awaitingSuppression;
            if (awaiting == null) {
                return;
            }

            if (awaiting.matches(usernameLower, lockPayload)) {
                awaiting.offline = isOffline;
                awaiting.resultReady = true;
                SUPPRESSION_ACK_LOCK.notifyAll();
            }
        }
    }

    // ──────────────────────────── Message matching ────────────────────────────

    // Package-private for unit tests. See MessageFanoutDispatcherTest.
    static boolean isOutboundRecipientTarget(String lowerMessage, String usernameLower) {
        int separatorIndex = lowerMessage.indexOf(PRIVATE_SEPARATOR_GLYPH);
        if (separatorIndex < 0) {
            return false;
        }

        int colonIndex = lowerMessage.indexOf(':', separatorIndex + 1);
        if (colonIndex <= separatorIndex) {
            return false;
        }

        String recipientSegment = lowerMessage.substring(separatorIndex + 1, colonIndex);
        return recipientSegment.contains(usernameLower);
    }

    // Package-private for unit tests. See MessageFanoutDispatcherTest.
    static boolean isOfflineRecipientMessage(String lowerMessage, String usernameLower) {
        return lowerMessage.contains(usernameLower + " is not online")
                || lowerMessage.contains(usernameLower + " is not currently online");
    }

    // Package-private for unit tests. See MessageFanoutDispatcherTest.
    static boolean isOfflineGuidanceMessage(String lowerMessage) {
        return lowerMessage.contains("be sure to use exact names, prediction does not work if")
                || lowerMessage.contains("the user is on a separate server");
    }

    // Package-private for unit tests. See MessageFanoutDispatcherTest.
    static boolean containsNormalizedPayload(String message, String payload) {
        if (message == null || message.isEmpty() || payload == null || payload.isEmpty()) {
            return false;
        }

        String normalizedMessage = normalizeForEchoComparison(message);
        String normalizedPayload = normalizeForEchoComparison(payload);
        if (normalizedPayload.isEmpty()) {
            return false;
        }

        if (normalizedMessage.contains(normalizedPayload)) {
            return true;
        }

        if (containsCensoredVariant(normalizedMessage, normalizedPayload)) {
            return true;
        }

        List<String> messageTokens = extractEchoTokens(normalizedMessage);
        List<String> payloadTokens = extractEchoTokens(normalizedPayload);
        if (messageTokens.isEmpty() || payloadTokens.isEmpty()) {
            return false;
        }

        return containsTokenSubsequence(messageTokens, payloadTokens);
    }

    // Package-private for unit tests. See MessageFanoutDispatcherTest.
    static List<String> extractEchoTokens(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        input.codePoints()
                .forEach(
                        cp -> {
                            if (Character.isLetterOrDigit(cp)) {
                                current.appendCodePoint(Character.toLowerCase(cp));
                            } else if (!current.isEmpty()) {
                                tokens.add(current.toString());
                                current.setLength(0);
                            }
                        });

        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    // Package-private for unit tests. See MessageFanoutDispatcherTest.
    static boolean containsTokenSubsequence(
            List<String> messageTokens, List<String> payloadTokens) {
        int messageIndex = 0;
        int payloadIndex = 0;

        while (messageIndex < messageTokens.size() && payloadIndex < payloadTokens.size()) {
            if (messageTokens.get(messageIndex).equals(payloadTokens.get(payloadIndex))) {
                payloadIndex++;
            }
            messageIndex++;
        }

        return payloadIndex == payloadTokens.size();
    }

    /**
     * Checks whether {@code message} contains a censored variant of {@code payload}.
     * Wynncraft's profanity filter replaces characters with {@code *}, so
     * "hello" might echo back as "h***o". This matches when every non-{@code *}
     * character in the candidate region equals the corresponding payload character.
     */
    // Package-private for unit tests. See MessageFanoutDispatcherTest.
    static boolean containsCensoredVariant(String message, String payload) {
        if (payload.isEmpty()) {
            return false;
        }

        int payloadLen = payload.length();

        outer:
        for (int start = 0; start <= message.length() - payloadLen; start++) {
            for (int i = 0; i < payloadLen; i++) {
                char mc = message.charAt(start + i);
                char pc = payload.charAt(i);
                if (mc != '*' && mc != pc) {
                    continue outer;
                }
            }
            return true;
        }

        return false;
    }

    // Package-private for unit tests. See MessageFanoutDispatcherTest.
    static String normalizeForEchoComparison(String input) {
        StringBuilder normalized = new StringBuilder(input.length());
        input.codePoints()
                .forEach(
                        cp -> {
                            int type = Character.getType(cp);
                            if (Character.isWhitespace(cp)
                                    || type == Character.PRIVATE_USE
                                    || type == Character.CONTROL
                                    || type == Character.FORMAT
                                    || type == Character.SURROGATE
                                    || type == Character.UNASSIGNED) {
                                return;
                            }

                            normalized.appendCodePoint(Character.toLowerCase(cp));
                        });
        return normalized.toString();
    }

    private static void cleanupExpired() {
        long now = System.currentTimeMillis();
        Iterator<PendingSuppression> iterator = PENDING_SUPPRESSIONS.iterator();
        while (iterator.hasNext()) {
            PendingSuppression pending = iterator.next();
            if (now - pending.createdAtMs > SUPPRESSION_TTL_MS) {
                iterator.remove();
            }
        }
    }

    // ──────────────────────────── Internal records ────────────────────────────

    private record PendingSuppression(String usernameLower, String lockPayload, long createdAtMs) {}

    private static final class AwaitingSuppression {
        private final String usernameLower;
        private final String lockPayload;
        private boolean offline;
        private boolean resultReady;

        private AwaitingSuppression(String usernameLower, String lockPayload) {
            this.usernameLower = usernameLower;
            this.lockPayload = lockPayload;
        }

        private boolean matches(String otherUsernameLower, String otherLockPayload) {
            return this.usernameLower.equals(otherUsernameLower)
                    && this.lockPayload.equals(otherLockPayload);
        }
    }
}
