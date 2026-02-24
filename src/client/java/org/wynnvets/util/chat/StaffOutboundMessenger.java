package org.wynnvets.util.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dispatches /v outbound fanout to staff users and suppresses resulting /msg feedback lines.
 */
public final class StaffOutboundMessenger {

    private static final Logger LOGGER = LoggerFactory.getLogger("vetsmod");
    private static final String LOCK_PREFIX = "🔐";
    private static final String PRIVATE_SEPARATOR_GLYPH = "\uE003";
    private static final long SUPPRESSION_TTL_MS = 15000L;
    private static final long OFFLINE_GUIDANCE_SUPPRESSION_WINDOW_MS = 4000L;
    private static final long BASE_DISPATCH_DELAY_MS = 400L;
    private static final long MAX_DISPATCH_DELAY_MS = 2000L;
    private static final long DISPATCH_DELAY_STEP_MS = 100L;
    private static final long MIN_SUPPRESSION_WAIT_MS = 1200L;
    private static final int MAX_DISPATCH_RETRIES = 6;
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

    private static final ConcurrentLinkedQueue<PendingSuppression> PENDING_SUPPRESSIONS = new ConcurrentLinkedQueue<>();
    private static final Object SUPPRESSION_ACK_LOCK = new Object();
    private static volatile AwaitingSuppression awaitingSuppression;
    private static volatile long suppressOfflineGuidanceUntilMs;
    private static volatile long adaptiveDispatchDelayMs = BASE_DISPATCH_DELAY_MS;

    private StaffOutboundMessenger() {
    }

    public static void dispatchStaffBroadcast(String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        new Thread(() -> {
            try {
                List<String> usernames = fetchStaffUsernames();
                if (usernames.isEmpty()) {
                    return;
                }

                Minecraft minecraft = Minecraft.getInstance();
                String payload = LOCK_PREFIX + message;
                long dispatchDelayMs = adaptiveDispatchDelayMs;

                for (String username : usernames) {
                    if (username == null || username.isBlank()) {
                        continue;
                    }

                    final String recipient = username;
                    boolean delivered = false;
                    int attempt = 0;

                    while (!delivered && attempt < MAX_DISPATCH_RETRIES) {
                        attempt++;
                        long suppressionWaitMs = Math.max(MIN_SUPPRESSION_WAIT_MS, dispatchDelayMs * 2L);
                        delivered = tryDispatchOnce(minecraft, recipient, payload, suppressionWaitMs);

                        if (delivered) {
                            adaptiveDispatchDelayMs = dispatchDelayMs;
                            break;
                        }

                        dispatchDelayMs = Math.min(dispatchDelayMs + DISPATCH_DELAY_STEP_MS, MAX_DISPATCH_DELAY_MS);
                        adaptiveDispatchDelayMs = dispatchDelayMs;
                        LOGGER.warn(
                            "No /msg feedback for {} attempt {}. Increasing dispatch delay to {}ms.",
                            recipient,
                            attempt,
                            dispatchDelayMs
                        );

                        if (attempt < MAX_DISPATCH_RETRIES) {
                            if (!sleepQuietly(dispatchDelayMs)) {
                                return;
                            }
                        }
                    }

                    if (!delivered) {
                        LOGGER.warn("Dropping outbound staff message to {} after {} attempts.", recipient, MAX_DISPATCH_RETRIES);
                    }

                    if (!sleepQuietly(dispatchDelayMs)) {
                        return;
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to dispatch staff /v broadcast: {}", e.getMessage());
            }
        }, "VetsMod-StaffOutboundDispatch").start();
    }

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

            acknowledgeSuppression(pending.usernameLower, pending.lockPayload);
            return true;
        }

        return false;
    }

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

    private static void acknowledgeSuppression(String usernameLower, String lockPayload) {
        synchronized (SUPPRESSION_ACK_LOCK) {
            AwaitingSuppression awaiting = awaitingSuppression;
            if (awaiting == null) {
                return;
            }

            if (awaiting.matches(usernameLower, lockPayload)) {
                awaiting.acknowledged = true;
                SUPPRESSION_ACK_LOCK.notifyAll();
            }
        }
    }

    private static boolean tryDispatchOnce(Minecraft minecraft, String recipient, String payload, long suppressionWaitMs) {
        AtomicBoolean commandSent = new AtomicBoolean(false);
        AtomicBoolean skippedSelfRecipient = new AtomicBoolean(false);
        CountDownLatch dispatchSubmitted = new CountDownLatch(1);
        String recipientLower = recipient.toLowerCase(Locale.ROOT);

        minecraft.execute(() -> {
            try {
                LocalPlayer player = minecraft.player;
                if (player == null || player.connection == null) {
                    return;
                }

                if (isSelfRecipient(recipient, player)) {
                    skippedSelfRecipient.set(true);
                    return;
                }

                synchronized (SUPPRESSION_ACK_LOCK) {
                    queueSuppression(recipient, payload);
                    awaitingSuppression = new AwaitingSuppression(recipientLower, payload);
                }

                player.connection.sendCommand("msg " + recipient + " " + payload);
                commandSent.set(true);
            } finally {
                dispatchSubmitted.countDown();
            }
        });

        try {
            if (!dispatchSubmitted.await(2, TimeUnit.SECONDS)) {
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        if (skippedSelfRecipient.get()) {
            clearAwaitingSuppressionIfMatches(recipientLower, payload);
            return true;
        }

        if (!commandSent.get()) {
            clearAwaitingSuppressionIfMatches(recipientLower, payload);
            return false;
        }

        return waitForSuppressionAck(recipientLower, payload, suppressionWaitMs);
    }

    private static boolean waitForSuppressionAck(String usernameLower, String lockPayload, long timeoutMs) {
        long deadlineMs = System.currentTimeMillis() + timeoutMs;

        synchronized (SUPPRESSION_ACK_LOCK) {
            while (true) {
                AwaitingSuppression awaiting = awaitingSuppression;
                if (awaiting != null && awaiting.matches(usernameLower, lockPayload) && awaiting.acknowledged) {
                    awaitingSuppression = null;
                    return true;
                }

                long remainingMs = deadlineMs - System.currentTimeMillis();
                if (remainingMs <= 0) {
                    clearAwaitingSuppressionIfMatches(usernameLower, lockPayload);
                    return false;
                }

                try {
                    SUPPRESSION_ACK_LOCK.wait(remainingMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    clearAwaitingSuppressionIfMatches(usernameLower, lockPayload);
                    return false;
                }
            }
        }
    }

    private static void clearAwaitingSuppressionIfMatches(String usernameLower, String lockPayload) {
        synchronized (SUPPRESSION_ACK_LOCK) {
            AwaitingSuppression awaiting = awaitingSuppression;
            if (awaiting != null && awaiting.matches(usernameLower, lockPayload)) {
                awaitingSuppression = null;
            }
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
        return lowerMessage.contains(usernameLower + " is not online");
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

        return normalizedMessage.contains(normalizedPayload);
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

    private static boolean isSelfRecipient(String recipient, LocalPlayer player) {
        if (recipient == null || recipient.isBlank() || player == null) {
            return false;
        }

        if (matchesIdentityVariant(recipient, GuildInfoListener.playerName())) {
            return true;
        }

        if (matchesIdentityVariant(recipient, player.getGameProfile().getName())) {
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

    private static List<String> fetchStaffUsernames() throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(STAFF_REQUEST, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            return List.of();
        }

        JsonArray staffMembers = GSON.fromJson(response.body(), JsonArray.class);
        if (staffMembers == null) {
            return List.of();
        }

        List<String> usernames = new ArrayList<>();
        for (JsonElement element : staffMembers) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject staffMember = element.getAsJsonObject();
            if (!staffMember.has("username")) {
                continue;
            }

            String username = staffMember.get("username").getAsString();
            if (username != null && !username.isBlank()) {
                usernames.add(username);
            }
        }

        return usernames;
    }

    private record PendingSuppression(String usernameLower, String lockPayload, long createdAtMs) {
    }

    private static final class AwaitingSuppression {
        private final String usernameLower;
        private final String lockPayload;
        private boolean acknowledged;

        private AwaitingSuppression(String usernameLower, String lockPayload) {
            this.usernameLower = usernameLower;
            this.lockPayload = lockPayload;
            this.acknowledged = false;
        }

        private boolean matches(String otherUsernameLower, String otherLockPayload) {
            return this.usernameLower.equals(otherUsernameLower) && this.lockPayload.equals(otherLockPayload);
        }
    }
}