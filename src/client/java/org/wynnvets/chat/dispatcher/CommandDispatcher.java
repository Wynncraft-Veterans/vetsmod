package org.wynnvets.chat.dispatcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.api.VetsApi;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.HttpClients;
import org.wynnvets.util.Json;

/**
 * Shared single-threaded command dispatcher for serialized outbound Wynncraft commands.
 *
 * <p>Coordinates the shared dispatch executor that serializes
 * both {@code /msg} fanout (via {@link MessageFanoutDispatcher}) and {@code /find}
 * batches (via {@link FindDispatcher}).  At most one command at a time is awaiting
 * its reply, which is why each suppression system needs only a single awaiting slot.
 * A command whose wait times out is abandoned, not cancelled, so its reply can still
 * arrive while a later command is being awaited. A retry resends the same recipient
 * and payload, so a late echo of the earlier {@code /msg} attempt, arriving while the
 * retry is awaited, is taken as the retry's. Today a late {@code /find} reply is also
 * credited to the awaited name when the reply's name ends with it
 * ({@code find-response-matches-username-as-substring}).</p>
 *
 * <p>{@code /v} fan-out and both chat-hook suppression checks are entered through this
 * class. {@code /find} batches are not: callers outside this package enqueue them on
 * {@link FindDispatcher#enqueueFindBatch} directly and read
 * {@link FindDispatcher#PRIVATE_SERVER} there too.</p>
 *
 * <h3>Architecture — Threading and Data Flow</h3>
 * <pre>
 *   Render Thread                   Dispatch Thread            ChatLogMixin (Render Thread)
 *   ─────────────                   ───────────────            ────────────────────────────
 *
 *   /v command entered
 *        │
 *        ├─► eligibility gate: auth-ack fast path,
 *        │    else the cache, else an async staff-feed
 *        │    check, after which the steps below run
 *        │    on the VetsMod-StaffPresenceCheck thread
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
 *                                       │             ├─► minecraft.execute → render thread:
 *                                       │             │     queueSuppression()
 *                                       │             │     awaitingSuppression = ...
 *                                       │             │     /msg recipient 🔐message
 *                                       │             └─► wait on SUPPRESSION_ACK_LOCK
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
 *                                                     ├─► minecraft.execute → render thread:
 *                                                     │     awaitingFindResponse = ...
 *                                                     │     /find username
 *                                                     └─► wait on FIND_RESPONSE_LOCK
 *                                                                 ▲
 *                                                                 │ notifyAll()
 *                                                                 │
 *                                                  shouldSuppressFindResponse(msg) ◄── ChatLogMixin
 * </pre>
 *
 * <h3>Key Invariants</h3>
 * <ul>
 *   <li>At most one command awaiting its reply at a time (single-threaded executor
 *       plus each dispatcher's bounded wait).</li>
 *   <li>/msg broadcasts take priority over /find batches: each dispatch pass handles
 *       queued /msg broadcasts before it starts on queued /find batches. Today a /msg
 *       that arrives while a pass is working through /find batches waits for that pass
 *       to finish ({@code find-phase-does-not-yield-to-queued-msg}).</li>
 *   <li>Echo matching pairs signals: the payload with either the recipient named in
 *       the line's header or the 🔐 lock prefix, or the prefix with the recipient. The
 *       prefix alone is not enough, but a plain payload match always carries it, since
 *       the payload starts with it. Offline-recipient errors match on the recipient's
 *       name and an offline phrase. See
 *       {@link MessageFanoutDispatcher#shouldSuppressFeedback}.</li>
 *   <li>Offline users are tracked per-batch and skipped for remaining messages.</li>
 *   <li>A gated call skips the staff-feed check while the auth ack confirms staff, or
 *       once an earlier gated call has confirmed this player since
 *       {@link #resetStaffChatEligibilityCache()} last cleared the cache. A failed check
 *       is not cached.</li>
 * </ul>
 */
public final class CommandDispatcher {

    static final String LOCK_PREFIX = "🔐";

    private static final String STAFF_CHAT_WAIT_ONLINE_STATUS_MESSAGE =
            "Please wait until the server updates your online status before using staff chat.";

    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    private static final HttpRequest STAFF_REQUEST =
            HttpRequest.newBuilder()
                    .uri(VetsApi.STAFF)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

    // Single-threaded executor. With each dispatcher's wait-for-reply, at most one of
    // this class's commands is awaiting a reply at a time; a command whose wait times
    // out is abandoned, not cancelled, so its reply can still arrive later.
    private static final ExecutorService DISPATCH_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "VetsMod-StaffOutboundDispatch");
                        t.setDaemon(true);
                        return t;
                    });

    // FIFO queue of messages waiting to be broadcast to all staff.
    static final ConcurrentLinkedQueue<String> MESSAGE_QUEUE = new ConcurrentLinkedQueue<>();

    // Whether a dispatch batch is currently running on the executor.
    private static final AtomicBoolean BATCH_IN_PROGRESS = new AtomicBoolean(false);

    // Staff-eligibility cache shared by both gates: set by the auth-ack fast path or a
    // successful feed check, cleared by resetStaffChatEligibilityCache(). While the
    // in-flight flag is set, a gated call that needs a feed check is dropped; the reset
    // clears the flag too.
    private static volatile boolean selfSeenInStaffFeedThisWorld;
    private static final AtomicBoolean SELF_PRESENCE_CHECK_IN_FLIGHT = new AtomicBoolean(false);

    private CommandDispatcher() {}

    // ──────────────────────────── Public API ────────────────────────────

    /**
     * Sends /v chat once this player is known to be staff: either the v1 auth ack flagged
     * this session as staff ({@link V1ApiManager#isConfirmedStaff()}), or an earlier gated
     * call already confirmed it since the eligibility cache was last cleared
     * ({@link #resetStaffChatEligibilityCache()}). Otherwise, unless another check is
     * already running, the WV online staff feed is checked on a background thread
     * ({@code VetsMod-StaffPresenceCheck}), which sends the message if the player is
     * listed. The message is dropped with a wait notice if another check is running, the
     * player is not listed, or the check fails.
     */
    public static void dispatchStaffChatWithEligibilityGate(
            String displayName, String message, String rank) {
        if (message == null || message.isBlank()) {
            return;
        }

        // Fast path: the v1 auth ack already told us the server resolved this
        // session as staff against the canonical roster. Skip the WAPI-probe
        // wait entirely -- the recipient-side rewriter consults its own
        // (push-fed) staff cache, so as long as auth succeeded, fanout will
        // be transformed correctly on the receiver. Today that cache is fed only by
        // StaffRanksPoller's fetches, the 2-minute poll and the refresh on the receiver's own
        // auth ack (no staff_online push reaches vetsmod:
        // outbound-socket-never-authenticated), so a receiver can lack a newly
        // authenticated sender's rank until its next poll.
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

        new Thread(
                        () -> {
                            try {
                                boolean listed =
                                        selfSeenInStaffFeedThisWorld
                                                || isSelfListedInOnlineStaffFeed();
                                if (!listed) {
                                    showStaffOnlineStatusWaitMessage();
                                    return;
                                }

                                selfSeenInStaffFeedThisWorld = true;
                                ChatUtils.sendStaffChannelMessage(displayName, message, rank);
                                enqueueAndDispatch(message);
                            } catch (Exception e) {
                                VetsLogger.warn(
                                        "Failed to verify online staff status for /v: {}",
                                        e.getMessage());
                                showStaffOnlineStatusWaitMessage();
                            } finally {
                                SELF_PRESENCE_CHECK_IN_FLIGHT.set(false);
                            }
                        },
                        "VetsMod-StaffPresenceCheck")
                .start();
    }

    /**
     * Runs the given action once this player is known to be staff, by the same tests and
     * the same cache as {@link #dispatchStaffChatWithEligibilityGate}, and drops it with the
     * same wait notice in the same cases. When the feed has to be checked, the action is
     * scheduled back on the render thread; otherwise it runs on the caller's thread.
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

        new Thread(
                        () -> {
                            try {
                                boolean listed =
                                        selfSeenInStaffFeedThisWorld
                                                || isSelfListedInOnlineStaffFeed();
                                if (!listed) {
                                    showStaffOnlineStatusWaitMessage();
                                    return;
                                }

                                selfSeenInStaffFeedThisWorld = true;
                                Minecraft.getInstance().execute(action);
                            } catch (Exception e) {
                                VetsLogger.warn(
                                        "Failed to verify online staff status: {}", e.getMessage());
                                showStaffOnlineStatusWaitMessage();
                            } finally {
                                SELF_PRESENCE_CHECK_IN_FLIGHT.set(false);
                            }
                        },
                        "VetsMod-StaffPresenceCheck")
                .start();
    }

    /**
     * Clears the staff-eligibility cache shared by
     * {@link #dispatchStaffChatWithEligibilityGate} and
     * {@link #executeWithStaffEligibilityGate}, and releases their in-flight guard. The
     * next gated call then re-checks the feed, unless the auth ack confirms staff or a
     * check already running when this was called has since succeeded: such a check is
     * not cancelled, and its success refills the cache.
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
    public static void enqueueFindBatch(
            java.util.List<String> usernames,
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

    // ──────────────────────────── Helpers (some package-private, for the
    // dispatchers) ────────────────────────────

    static void showNoRecipientsWarning() {
        ChatUtils.sendLocalMessage(
                Component.literal("Nobody saw your message, the vets api is probably restarting")
                        .withStyle(ChatFormatting.YELLOW));
    }

    private static void showStaffOnlineStatusWaitMessage() {
        ChatUtils.sendLocalMessage(
                Component.literal(STAFF_CHAT_WAIT_ONLINE_STATUS_MESSAGE)
                        .withStyle(ChatFormatting.YELLOW));
    }

    private static boolean isSelfListedInOnlineStaffFeed() throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer selfPlayer = minecraft.player;
        if (selfPlayer == null) {
            return false;
        }

        HttpResponse<String> response =
                HTTP_CLIENT.send(STAFF_REQUEST, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            return false;
        }

        JsonArray staffMembers = Json.GSON.fromJson(response.body(), JsonArray.class);
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

            String username = Json.stringOrNull(staffMember, "username");
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
     * Fetches staff usernames from the API, dropping entries marked offline (by an
     * {@code online}, {@code isOnline} or {@code status} field); an entry with none
     * of those fields is kept.
     */
    static List<String> fetchOnlineStaffUsernames() throws Exception {
        HttpResponse<String> response =
                HTTP_CLIENT.send(STAFF_REQUEST, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            return new ArrayList<>();
        }

        JsonArray staffMembers = Json.GSON.fromJson(response.body(), JsonArray.class);
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

            String username = Json.stringOrNull(staffMember, "username");
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

        String status = Json.stringOrNull(staffMember, "status");
        if (status != null) {
            if (status.equalsIgnoreCase("online")) {
                return true;
            }
            if (status.equalsIgnoreCase("offline")) {
                return false;
            }
        }

        String world =
                firstNonBlank(
                        Json.stringOrNull(staffMember, "world"),
                        Json.stringOrNull(staffMember, "server"));
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
