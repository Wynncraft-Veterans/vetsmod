package org.wynnvets.chat.dispatcher;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.api.VetsApi;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.guild.GuildStateManager;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared single-threaded command dispatcher for serialized outbound Wynncraft commands.
 *
 * <p>Coordinates the shared dispatch executor that serializes
 * both {@code /msg} fanout (via {@link MessageFanoutDispatcher}) and {@code /find}
 * batches (via {@link FindDispatcher}).  Only one command is ever in-flight
 * at a time, so the suppression ACK systems are unambiguous.</p>
 *
 * <p>Callers outside this package should use only the public methods on this class.
 * The two dispatcher classes are implementation details.</p>
 *
 * <h3>Architecture — Threading and Data Flow</h3>
 * <pre>
 *   Render Thread                   Dispatch Thread            ChatLogMixin (Render Thread)
 *   ─────────────                   ───────────────            ────────────────────────────
 *
 *   /v command entered
 *        │
 *        ├─► eligibility gate
 *        │   (async staff API check,
 *        │    cached per world)
 *        │
 *        ▼
 *   enqueueAndDispatch(msg)
 *        │
 *        ├─► MESSAGE_QUEUE.add(msg)
 *        │
 *        ▼
 *   startBatchIfIdle()
 *        │                           processBatch()
 *        └─► DISPATCH_EXECUTOR ──►      │
 *                                       ├─► MessageFanoutDispatcher
 *                                       │     .processMessageBatch()
 *                                       │       │
 *                                       │       ├─► fetchOnlineStaffUsernames()
 *                                       │       │     (HTTP GET → staff API)
 *                                       │       │
 *                                       │       └─► for each recipient:
 *                                       │             /msg recipient 🔐message
 *                                       │                │
 *                                       │                ├─► queueSuppression()
 *                                       │                ├─► awaitingSuppression = ...
 *                                       │                └─► wait on SUPPRESSION_ACK_LOCK
 *                                       │                         ▲
 *                                       │                         │ notifyAll()
 *                                       │                         │
 *                                       │              shouldSuppressFeedback(msg) ◄── ChatLogMixin
 *                                       │                  │
 *                                       │                  ├─► match echo / offline error
 *                                       │                  └─► signalFeedbackReceived()
 *                                       │
 *                                       └─► FindDispatcher
 *                                             .processFindBatch()
 *                                               │
 *                                               └─► for each username:
 *                                                     /find username
 *                                                        │
 *                                                        └─► wait on FIND_RESPONSE_LOCK
 *                                                                 ▲
 *                                                                 │ notifyAll()
 *                                                                 │
 *                                                  shouldSuppressFindResponse(msg) ◄── ChatLogMixin
 * </pre>
 *
 * <h3>Key Invariants</h3>
 * <ul>
 *   <li>Exactly one command in-flight at a time (single-threaded executor).</li>
 *   <li>/msg batches drain before /find batches (priority ordering).</li>
 *   <li>Suppression matching uses the 🔐 lock prefix as a unique discriminator.</li>
 *   <li>Offline users are tracked per-batch and skipped for remaining messages.</li>
 *   <li>Self-presence in the staff API feed is verified once per world session.</li>
 * </ul>
 */
public final class CommandDispatcher {

    static final String LOCK_PREFIX = "🔐";

    private static final String STAFF_CHAT_WAIT_ONLINE_STATUS_MESSAGE =
        "Please wait until the server updates your online status before using staff chat.";
    private static final Gson GSON = new Gson();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private static final HttpRequest STAFF_REQUEST = HttpRequest.newBuilder()
        .uri(VetsApi.STAFF)
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    // Single-threaded executor ensures exactly one command is in-flight at a time.
    private static final ExecutorService DISPATCH_EXECUTOR =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VetsMod-StaffOutboundDispatch");
            t.setDaemon(true);
            return t;
        });

    // FIFO queue of messages waiting to be broadcast to all staff.
    static final ConcurrentLinkedQueue<String> MESSAGE_QUEUE = new ConcurrentLinkedQueue<>();

    // Whether a dispatch batch is currently running on the executor.
    private static final AtomicBoolean BATCH_IN_PROGRESS = new AtomicBoolean(false);

    // Per-world eligibility gate for the self-presence check.
    private static volatile boolean selfSeenInStaffFeedThisWorld;
    private static final AtomicBoolean SELF_PRESENCE_CHECK_IN_FLIGHT = new AtomicBoolean(false);

    private CommandDispatcher() {
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

        // Fast path: the v1 auth ack already told us the server resolved this
        // session as staff against the canonical roster. Skip the WAPI-probe
        // wait entirely -- the recipient-side rewriter consults its own
        // (push-fed) staff cache, so as long as auth succeeded, fanout will
        // be transformed correctly on the receiver.
        if (V1ApiManager.isConfirmedStaff()) {
            selfSeenInStaffFeedThisWorld = true;
            ChatUtils.sendStaffChannelMessage(displayName, message, rank);
            enqueueAndDispatch(message);
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
                VetsLogger.warn("Failed to verify online staff status for /v: {}", e.getMessage());
                showStaffOnlineStatusWaitMessage();
            } finally {
                SELF_PRESENCE_CHECK_IN_FLIGHT.set(false);
            }
        }, "VetsMod-StaffPresenceCheck").start();
    }

    /**
     * Runs the given action only after confirming this player appears in the WV online staff feed.
     * Shares the same per-world cache as {@link #dispatchStaffChatWithEligibilityGate}.
     * If the check must be performed asynchronously, the action is scheduled back on the render thread.
     */
    public static void executeWithStaffEligibilityGate(Runnable action) {
        // Fast path: same rationale as dispatchStaffChatWithEligibilityGate.
        if (V1ApiManager.isConfirmedStaff()) {
            selfSeenInStaffFeedThisWorld = true;
            action.run();
            return;
        }

        if (selfSeenInStaffFeedThisWorld) {
            action.run();
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
                Minecraft.getInstance().execute(action);
            } catch (Exception e) {
                VetsLogger.warn("Failed to verify online staff status: {}", e.getMessage());
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

    /**
     * Delegates to {@link FindDispatcher#enqueueFindBatch}.
     */
    public static void enqueueFindBatch(java.util.List<String> usernames,
                                        java.util.concurrent.CompletableFuture<java.util.Map<String, String>> resultFuture) {
        FindDispatcher.enqueueFindBatch(usernames, resultFuture);
    }

    /**
     * Delegates to {@link MessageFanoutDispatcher#shouldSuppressFeedback}.
     */
    public static boolean shouldSuppressFeedback(String message) {
        return MessageFanoutDispatcher.shouldSuppressFeedback(message);
    }

    /**
     * Delegates to {@link FindDispatcher#shouldSuppressFindResponse}.
     */
    public static boolean shouldSuppressFindResponse(String message) {
        return FindDispatcher.shouldSuppressFindResponse(message);
    }

    // ──────────────────────────── Batch dispatch ────────────────────────────

    static void startBatchIfIdle() {
        if (BATCH_IN_PROGRESS.compareAndSet(false, true)) {
            DISPATCH_EXECUTOR.submit(CommandDispatcher::processBatch);
        }
        // If already running, the running batch will pick up queued messages.
    }

    /**
     * Coordinates the shared dispatch executor: drains /msg broadcasts first, then any
     * queued /find batches.  Both use the same single thread to guarantee serial command
     * dispatch to Wynncraft.
     */
    private static void processBatch() {
        try {
            if (!MESSAGE_QUEUE.isEmpty()) {
                MessageFanoutDispatcher.processMessageBatch();
            }

            FindDispatcher.FindBatch findBatch;
            while ((findBatch = FindDispatcher.FIND_BATCH_QUEUE.poll()) != null) {
                try {
                    FindDispatcher.processFindBatch(findBatch);
                } catch (Exception e) {
                    VetsLogger.warn("Failed to process find batch: {}", e.getMessage());
                    findBatch.resultFuture().completeExceptionally(e);
                }
            }
        } finally {
            BATCH_IN_PROGRESS.set(false);
            if (!MESSAGE_QUEUE.isEmpty() || !FindDispatcher.FIND_BATCH_QUEUE.isEmpty()) {
                startBatchIfIdle();
            }
        }
    }

    // ──────────────────────────── Helpers (package-private for dispatchers) ────────────────────────────

    static void showNoRecipientsWarning() {
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
    static List<String> fetchOnlineStaffUsernames() throws Exception {
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

    static boolean sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ──────────────────────────── Identity matching ────────────────────────────

    static boolean isSelfRecipient(String recipient, LocalPlayer player) {
        if (recipient == null || recipient.isBlank() || player == null) {
            return false;
        }

        if (matchesIdentityVariant(recipient, GuildStateManager.playerName())) {
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
}
