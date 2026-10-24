package org.wynnvets.api;

import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicInteger;
import org.wynnvets.fetcher.polling.StaffRanksPoller;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.Json;
import org.wynnvets.util.TickDelays;

/**
 * Routes the outbound socket's untyped {@code {"status":...}} frames, which are the replies to
 * the {@code auth} frame {@link V1ApiManager#sendOutboundAuth(String)} sends. The outbound socket
 * sends nothing else that is acked.
 *
 * <p>Deliberately separate from {@link InboundAckRouter}: the inbound auth ack drives
 * {@link SessionAuthState} ({@code expectingAuthAck}, {@code confirmedStaff}), the unlock tier,
 * the staff-chat eligibility cache, {@code StaffRanksPoller.refreshNow} and the MWE auto-enable.
 * The outbound leg only changes what the server pushes to this socket, so its ack only logs and,
 * when ok, resets the staff presence overlay ({@code StaffRanksPoller.onOutboundAuthenticated}),
 * and a failure here is never shown to the player: the inbound leg has already reported the
 * key's verdict. A transient failure (temporary-server could not reach dazebot) gets one retry
 * about 30 s later, fenced by a generation {@link #reset()} bumps on every {@code connect()} and
 * {@code disconnect()}.</p>
 */
final class OutboundAckRouter {

    /** How long after a transient outbound auth failure the one retry runs. */
    static final long RETRY_DELAY_MS = 30_000L;

    private static final AtomicInteger generation = new AtomicInteger();

    /** Run on every ok ack: the server replays no presence to a newly authenticated socket, so
     *  the staff overlay is reset and refetched. Package-private so a test can stub the fetch. */
    static Runnable onAuthenticated = StaffRanksPoller::onOutboundAuthenticated;

    /** Whether the next error ack answers {@code endOutboundSession}'s empty-key logout. */
    private static volatile boolean loggingOut = false;

    /** Whether this connection's one transient retry has been spent; cleared by an ok ack. */
    private static volatile boolean retryUsed = false;

    private OutboundAckRouter() {}

    /** Routes one untyped outbound frame. A frame with no {@code status} is ignored. */
    static void route(JsonObject json) {
        if (!json.has("status")) return;
        String status = Json.stringOrEmpty(json, "status");
        if ("ok".equals(status)) {
            retryUsed = false;
            loggingOut = false;
            String wsTier = Json.stringOrNull(json, "ws_tier");
            V1ApiManager.outboundAuthenticated = wsTier != null && !wsTier.isBlank();
            onAuthenticated.run();
            if (wsTier == null || wsTier.isBlank()) {
                // The inbound ack confirmed a member tier before this frame was sent, so a null
                // ws_tier means dazebot's answer changed in between. The server now sends this
                // socket no chat at all; the next re-auth or reconnect corrects it.
                VetsLogger.info(
                        "Outbound auth ok but tier {} has no chat channel: no relayed chat will"
                                + " arrive until the next re-auth",
                        Json.optString(json, "tier", "other"));
            } else {
                VetsLogger.debug(
                        "Outbound auth ok: tier={} ws_tier={} staff={}",
                        Json.optString(json, "tier", "?"),
                        wsTier,
                        Json.optString(json, "is_staff", "?"));
            }
            return;
        }
        String detail = Json.optString(json, "detail", "unknown");
        V1ApiManager.outboundAuthenticated = false;
        if (loggingOut) {
            loggingOut = false;
            VetsLogger.debug("Outbound session ended: {}", detail);
            return;
        }
        VetsLogger.warn("Outbound auth rejected: {}", detail);
        if (isTransient(detail) && !retryUsed) {
            retryUsed = true;
            int gen = generation();
            TickDelays.runAfter(
                    RETRY_DELAY_MS,
                    "outbound auth retry",
                    fenced(gen, V1ApiManager::sendOutboundAuthIfReady));
        }
    }

    /**
     * Whether an outbound auth failure's detail is temporary-server failing to reach or read
     * dazebot's introspection endpoint rather than dazebot's verdict on the key. The same test
     * as {@code ReturnersReauth.isTransient} (package-private in {@code org.wynnvets.guild}).
     */
    static boolean isTransient(String detail) {
        return detail != null && detail.startsWith("auth rejected: introspection");
    }

    /** The reconnect count, for {@link #fenced}. */
    static int generation() {
        return generation.get();
    }

    /** Wraps {@code body} so it runs only if no {@link #reset()} came since {@code gen} was read
     *  from {@link #generation()}. */
    static Runnable fenced(int gen, Runnable body) {
        return () -> {
            if (gen == generation.get()) {
                body.run();
            } else {
                VetsLogger.debug("OutboundAckRouter: dropped a retry from before a reconnect");
            }
        };
    }

    /** Marks the next error ack as the answer to a deliberate logout: logged at debug, not
     *  retried. Called by {@code V1ApiManager.endOutboundSession}. */
    static void expectLogout() {
        loggingOut = true;
    }

    /** Drops any scheduled retry and restores the retry allowance. Called by {@code connect()}
     *  and {@code disconnect()}. */
    static void reset() {
        generation.incrementAndGet();
        retryUsed = false;
        loggingOut = false;
    }

    // Package-private for unit tests. See V1ApiManagerOutboundAuthTest.
    static boolean retryUsed() {
        return retryUsed;
    }
}
