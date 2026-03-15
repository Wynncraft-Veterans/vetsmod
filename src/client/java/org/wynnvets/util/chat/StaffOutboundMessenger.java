package org.wynnvets.util.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wynnvets.constants.WVApi;
import org.wynnvets.util.GuildInfoListener;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dispatches /v outbound fanout to staff users and suppresses resulting /msg feedback lines.
 *
 * <p>All outbound /msg commands are serialized through a single-threaded executor
 * to eliminate race conditions: only one /msg is ever in-flight at a time, so the
 * suppression ACK system is unambiguous. Rapid /v commands are queued and processed
 * in strict FIFO order. Staff members detected as offline are skipped for all
 * remaining messages in the current batch.</p>
 */
public final class StaffOutboundMessenger {

    private static final Logger LOGGER = LoggerFactory.getLogger("vetsmod");
    private static final String LOCK_PREFIX = "🔐";
    private static final String PRIVATE_SEPARATOR_GLYPH = "\uE003";
    private static final long SUPPRESSION_TTL_MS = 15000L;
    private static final long OFFLINE_GUIDANCE_SUPPRESSION_WINDOW_MS = 4000L;
    private static final long INTER_SEND_DELAY_MS = 600L;
    private static final long SUPPRESSION_WAIT_MS = 1800L;
    private static final int MAX_DISPATCH_RETRIES = 3;
    private static final String STAFF_CHAT_WAIT_ONLINE_STATUS_MESSAGE =
        "Please wait until the server updates your online status before using staff chat.";
    private static final Gson GSON = new Gson();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private static final HttpRequest STAFF_REQUEST = HttpRequest.newBuilder()
        .uri(WVApi.Staff)
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    // Single-threaded executor ensures exactly one /msg is in-flight at a time.
    private static final ExecutorService DISPATCH_EXECUTOR =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VetsMod-StaffOutboundDispatch");
            t.setDaemon(true);
            return t;
        });

    // FIFO queue of messages waiting to be broadcast to all staff.
    private static final ConcurrentLinkedQueue<String> MESSAGE_QUEUE = new ConcurrentLinkedQueue<>();

    // Whether a dispatch batch is currently running on the executor.
    private static final AtomicBoolean BATCH_IN_PROGRESS = new AtomicBoolean(false);

    // Suppression state — safe because dispatch is single-threaded.
    private static final ConcurrentLinkedQueue<PendingSuppression> PENDING_SUPPRESSIONS = new ConcurrentLinkedQueue<>();
    private static final Object SUPPRESSION_ACK_LOCK = new Object();
    private static volatile AwaitingSuppression awaitingSuppression;
    private static volatile long suppressOfflineGuidanceUntilMs;

    // Per-world eligibility gate for the self-presence check.
    private static volatile boolean selfSeenInStaffFeedThisWorld;
    private static final AtomicBoolean SELF_PRESENCE_CHECK_IN_FLIGHT = new AtomicBoolean(false);

    private StaffOutboundMessenger() {
    }

    // ──────────────────────────── Public API ────────────────────────────

    /**
     * Sends /v chat only after confirming this player has appeared in the WV online staff list.
     * Once confirmed in the current world context, subsequent /v messages skip the check.
     */
    public static void dispatchStaffChatWithEligibilityGate(String displayName, String message, String rank) {
        if (message == null || message.isBlank()) {
            return;
        }

        if (selfSeenInStaffFeedThisWorld) {
            ChatUtils.sendStaffChannelMessage(displayName, message, rank);
            enqueueAndDispatch(message);
            return;
        }

        if (!SELF_PRESENCE_CHECK_IN_FLIGHT.compareAndSet(false, true)) {
            showStaffOnlineStatusWaitMessage();
            return;
        }

        new Thread(() -> {
            try {
                boolean listed = selfSeenInStaffFeedThisWorld || isSelfListedInOnlineStaffFeed();
                if (!listed) {
                    showStaffOnlineStatusWaitMessage();
                    return;
                }

                selfSeenInStaffFeedThisWorld = true;
                ChatUtils.sendStaffChannelMessage(displayName, message, rank);
                enqueueAndDispatch(message);
            } catch (Exception e) {
                LOGGER.warn("Failed to verify online staff status for /v: {}", e.getMessage());
                showStaffOnlineStatusWaitMessage();
            } finally {
                SELF_PRESENCE_CHECK_IN_FLIGHT.set(false);
            }
        }, "VetsMod-StaffPresenceCheck").start();
    }

    /**
     * Clears the per-world /v eligibility cache so the next message re-verifies feed presence.
     */
    public static void resetStaffChatEligibilityCache() {
        selfSeenInStaffFeedThisWorld = false;
        SELF_PRESENCE_CHECK_IN_FLIGHT.set(false);
    }

    /**
     * Enqueues a message for broadcast and starts a dispatch batch if one isn't already running.
     * Multiple rapid calls safely enqueue; the single dispatch thread drains them in order.
     */
    public static void enqueueAndDispatch(String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        MESSAGE_QUEUE.add(message);
        startBatchIfIdle();
    }

    // ──────────────────────────── Batch dispatch ────────────────────────────

    private static void startBatchIfIdle() {
        if (BATCH_IN_PROGRESS.compareAndSet(false, true)) {
            DISPATCH_EXECUTOR.submit(StaffOutboundMessenger::processBatch);
        }
        // If already running, the running batch will pick up queued messages.
    }

    /**
     * Drains all queued messages, sending each to every online staff member in order.
     * Staff list is fetched once per batch; offline users are tracked and skipped.
     */
    private static void processBatch() {
        try {
            List<String> staffUsernames = fetchOnlineStaffUsernames();
            Minecraft minecraft = Minecraft.getInstance();
            LocalPlayer selfPlayer = minecraft.player;

            if (selfPlayer == null) {
                LOGGER.warn("Cannot dispatch staff broadcast: player is null");
                return;
            }

            // Remove self from recipients
            staffUsernames.removeIf(u -> isSelfRecipient(u, selfPlayer));

            if (staffUsernames.isEmpty()) {
                // Drain the queue and warn for each message
                while (MESSAGE_QUEUE.poll() != null) {
                    // drained
                }
                showNoRecipientsWarning();
                return;
            }

            // Track users confirmed offline during this batch
            Set<String> offlineUsers = new HashSet<>();
            boolean deliveredAnyMessageToAnyone = false;

            String message;
            while ((message = MESSAGE_QUEUE.poll()) != null) {
                String payload = LOCK_PREFIX + message;
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
                            LOGGER.info("Staff member {} is offline, skipping for remaining messages.", recipient);
                            break;
                        case FAILED:
                            LOGGER.warn("Failed to deliver message to {} after {} retries.", recipient, MAX_DISPATCH_RETRIES);
                            break;
                    }

                    // Rate-limit between sends to avoid Wynncraft throttling
                    sleepQuietly(INTER_SEND_DELAY_MS);
                }

                if (deliveredThisMessage) {
                    deliveredAnyMessageToAnyone = true;
                }
            }

            if (!deliveredAnyMessageToAnyone) {
                showNoRecipientsWarning();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to dispatch staff /v broadcast batch: {}", e.getMessage());
            // Drain remaining messages to avoid retrying a failed batch indefinitely
            while (MESSAGE_QUEUE.poll() != null) {
                // drained
            }
            showNoRecipientsWarning();
        } finally {
            BATCH_IN_PROGRESS.set(false);
            // If messages arrived while we were processing (after our last poll),
            // start a new batch to handle them.
            if (!MESSAGE_QUEUE.isEmpty()) {
                startBatchIfIdle();
            }
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
    private static FeedbackResult dispatchWithRetries(Minecraft minecraft, String recipient, String payload) {
        for (int attempt = 1; attempt <= MAX_DISPATCH_RETRIES; attempt++) {
            FeedbackResult result = tryDispatchOnce(minecraft, recipient, payload);

            if (result == FeedbackResult.DELIVERED || result == FeedbackResult.OFFLINE) {
                return result;
            }

            // FAILED (timeout) — retry after a delay
            if (attempt < MAX_DISPATCH_RETRIES) {
                LOGGER.warn("No /msg feedback for {} attempt {}. Retrying...", recipient, attempt);
                sleepQuietly(INTER_SEND_DELAY_MS);
            }
        }

        return FeedbackResult.FAILED;
    }

    /**
     * Sends a single /msg command and waits for the server's feedback to determine the result.
     * Because dispatch is single-threaded, only one awaiting suppression exists at a time.
     */
    private static FeedbackResult tryDispatchOnce(Minecraft minecraft, String recipient, String payload) {
        AtomicBoolean commandSent = new AtomicBoolean(false);
        AtomicBoolean skippedSelf = new AtomicBoolean(false);
        CountDownLatch submitted = new CountDownLatch(1);
        String recipientLower = recipient.toLowerCase(Locale.ROOT);

        minecraft.execute(() -> {
            try {
                LocalPlayer player = minecraft.player;
                if (player == null || player.connection == null) {
                    return;
                }

                if (isSelfRecipient(recipient, player)) {
                    skippedSelf.set(true);
                    return;
                }

                synchronized (SUPPRESSION_ACK_LOCK) {
                    queueSuppression(recipient, payload);
                    awaitingSuppression = new AwaitingSuppression(recipientLower, payload);
                }

                player.connection.sendCommand("msg " + recipient + " " + payload);
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
                if (awaiting != null && awaiting.matches(usernameLower, lockPayload) && awaiting.resultReady) {
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

    // ──────────────────────────── Suppression (called from ChatLogMixin) ────────────────────────────

    /**
     * Called from {@code ChatLogMixin} on the render thread for every incoming chat message.
     * Matches outbound /msg echo lines and offline-player errors, suppresses them from display,
     * and signals the dispatch thread so it can proceed strategically.
     */
    public static boolean shouldSuppressFeedback(String message) {
        if (message == null || message.isEmpty()) {
            cleanupExpired();
            return false;
        }

        long now = System.currentTimeMillis();
        String lower = message.toLowerCase(Locale.ROOT);
        String lockPrefixLower = LOCK_PREFIX.toLowerCase(Locale.ROOT);

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
            boolean isDirectMessageEcho = payloadEchoMatch
                && (isOutboundRecipientTarget(lower, pending.usernameLower) || lower.contains(lockPrefixLower));
            boolean isOfflineRecipientError = isOfflineRecipientMessage(lower, pending.usernameLower);

            if (!isDirectMessageEcho && !isOfflineRecipientError) {
                continue;
            }

            iterator.remove();
            removeDuplicateSuppressions(pending);

            if (isOfflineRecipientError) {
                suppressOfflineGuidanceUntilMs = now + OFFLINE_GUIDANCE_SUPPRESSION_WINDOW_MS;
            }

            signalFeedbackReceived(pending.usernameLower, pending.lockPayload, isOfflineRecipientError);
            return true;
        }

        return false;
    }

    // ──────────────────────────── Suppression internals ────────────────────────────

    private static void queueSuppression(String username, String payload) {
        cleanupExpired();
        PENDING_SUPPRESSIONS.add(new PendingSuppression(username.toLowerCase(Locale.ROOT), payload, System.currentTimeMillis()));
    }

    private static void removeDuplicateSuppressions(PendingSuppression matched) {
        Iterator<PendingSuppression> iterator = PENDING_SUPPRESSIONS.iterator();
        while (iterator.hasNext()) {
            PendingSuppression pending = iterator.next();
            if (pending.usernameLower.equals(matched.usernameLower) && pending.lockPayload.equals(matched.lockPayload)) {
                iterator.remove();
            }
        }
    }

    /**
     * Signals the dispatch thread that feedback was received for a pending message.
     * Distinguishes between delivery confirmation and offline-user detection.
     */
    private static void signalFeedbackReceived(String usernameLower, String lockPayload, boolean isOffline) {
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

    // ──────────────────────────── Helpers ────────────────────────────

    private static void showNoRecipientsWarning() {
        ChatUtils.sendLocalMessage(
            Component.literal("Nobody saw your message, the vets api is probably restarting")
                .withStyle(ChatFormatting.YELLOW)
        );
    }

    private static void showStaffOnlineStatusWaitMessage() {
        ChatUtils.sendLocalMessage(
            Component.literal(STAFF_CHAT_WAIT_ONLINE_STATUS_MESSAGE)
                .withStyle(ChatFormatting.YELLOW)
        );
    }

    private static boolean isSelfListedInOnlineStaffFeed() throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer selfPlayer = minecraft.player;
        if (selfPlayer == null) {
            return false;
        }

        HttpResponse<String> response = HTTP_CLIENT.send(STAFF_REQUEST, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            return false;
        }

        JsonArray staffMembers = GSON.fromJson(response.body(), JsonArray.class);
        if (staffMembers == null) {
            return false;
        }

        for (JsonElement element : staffMembers) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject staffMember = element.getAsJsonObject();
            if (!isOnlineStaffMember(staffMember)) {
                continue;
            }

            String username = stringOrNull(staffMember, "username");
            if (username == null || username.isBlank()) {
                continue;
            }

            if (isSelfRecipient(username, selfPlayer)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Fetches staff usernames from the API, filtering to only those marked as online.
     */
    private static List<String> fetchOnlineStaffUsernames() throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(STAFF_REQUEST, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            return new ArrayList<>();
        }

        JsonArray staffMembers = GSON.fromJson(response.body(), JsonArray.class);
        if (staffMembers == null) {
            return new ArrayList<>();
        }

        List<String> usernames = new ArrayList<>();
        for (JsonElement element : staffMembers) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject staffMember = element.getAsJsonObject();
            if (!isOnlineStaffMember(staffMember)) {
                continue;
            }

            String username = stringOrNull(staffMember, "username");
            if (username != null && !username.isBlank()) {
                usernames.add(username);
            }
        }

        return usernames;
    }

    private static boolean isOnlineStaffMember(JsonObject staffMember) {
        if (staffMember.has("online") && !staffMember.get("online").isJsonNull()) {
            return staffMember.get("online").getAsBoolean();
        }

        if (staffMember.has("isOnline") && !staffMember.get("isOnline").isJsonNull()) {
            return staffMember.get("isOnline").getAsBoolean();
        }

        String status = stringOrNull(staffMember, "status");
        if (status != null) {
            if (status.equalsIgnoreCase("online")) {
                return true;
            }
            if (status.equalsIgnoreCase("offline")) {
                return false;
            }
        }

        String world = firstNonBlank(
            stringOrNull(staffMember, "world"),
            stringOrNull(staffMember, "server")
        );
        if (world != null) {
            return true;
        }

        return true;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static String stringOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }

        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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

    // ──────────────────────────── Message matching ────────────────────────────

    private static boolean isOutboundRecipientTarget(String lowerMessage, String usernameLower) {
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

    private static boolean isOfflineRecipientMessage(String lowerMessage, String usernameLower) {
        return lowerMessage.contains(usernameLower + " is not online")
            || lowerMessage.contains(usernameLower + " is not currently online");
    }

    private static boolean isOfflineGuidanceMessage(String lowerMessage) {
        return lowerMessage.contains("be sure to use exact names, prediction does not work if")
            || lowerMessage.contains("the user is on a separate server");
    }

    private static boolean containsNormalizedPayload(String message, String payload) {
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

        List<String> messageTokens = extractEchoTokens(normalizedMessage);
        List<String> payloadTokens = extractEchoTokens(normalizedPayload);
        if (messageTokens.isEmpty() || payloadTokens.isEmpty()) {
            return false;
        }

        return containsTokenSubsequence(messageTokens, payloadTokens);
    }

    private static List<String> extractEchoTokens(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        input.codePoints().forEach(cp -> {
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

    private static boolean containsTokenSubsequence(List<String> messageTokens, List<String> payloadTokens) {
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

    private static String normalizeForEchoComparison(String input) {
        StringBuilder normalized = new StringBuilder(input.length());
        input.codePoints().forEach(cp -> {
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

    // ──────────────────────────── Identity matching ────────────────────────────

    private static boolean isSelfRecipient(String recipient, LocalPlayer player) {
        if (recipient == null || recipient.isBlank() || player == null) {
            return false;
        }

        if (matchesIdentityVariant(recipient, GuildInfoListener.playerName())) {
            return true;
        }

        if (matchesIdentityVariant(recipient, player.getGameProfile().name())) {
            return true;
        }

        return matchesIdentityVariant(recipient, player.getName().getString());
    }

    private static boolean matchesIdentityVariant(String recipient, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }

        String[] variants = candidate.split("/");
        for (String variant : variants) {
            String normalizedVariant = variant.trim();
            if (!normalizedVariant.isEmpty() && recipient.equalsIgnoreCase(normalizedVariant)) {
                return true;
            }
        }

        return false;
    }

    // ──────────────────────────── Internal records ────────────────────────────

    private record PendingSuppression(String usernameLower, String lockPayload, long createdAtMs) {
    }

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
            return this.usernameLower.equals(otherUsernameLower) && this.lockPayload.equals(otherLockPayload);
        }
    }
}