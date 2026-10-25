package org.wynnvets.mwe.anni.network;

import com.google.gson.JsonObject;
import java.util.List;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.logging.VetsLogger;

/**
 * Sends the anni frames on the v1 inbound socket: {@code anni_query}, {@code anni_scrollspot_set},
 * {@code anni_rsvp} and {@code anni_party_observation}. Each checks
 * {@link V1ApiManager#isInboundConnected()}, builds its frame, sends it through
 * {@link V1ApiManager#sendInboundUnchecked(JsonObject)} and reports whether the socket was up. The
 * replies come back as typed inbound frames, which {@link AnniWsHandler} routes.
 */
public final class AnniWsSender {

    private AnniWsSender() {}

    /**
     * Sends an {@code anni_query} control frame to the server.
     *
     * <p>The frame body omits {@code mc_uuid} — the server falls back to
     * the authenticated session's identity, so a key-authed vetsmod
     * client always asks "give me my own snapshot." (Unauthenticated
     * sessions get an {@code error: "mc_uuid required"} response, which
     * surfaces as a null snapshot via
     * {@link org.wynnvets.mwe.anni.network.AnniQueryClient#query() AnniQueryClient#query()}.)</p>
     *
     * <p>The pending-reply queue (a FIFO of per-call futures; nothing coalesces
     * concurrent calls) lives in
     * {@link org.wynnvets.mwe.anni.network.AnniQueryClient AnniQueryClient};
     * this method just sends the frame when the inbound socket is up and reports whether it was,
     * not whether the frame went out. The dedicated
     * frame type ({@code anni_query_response}) means we don't need to share the staff-action
     * callback queue or expose extra state-shaping hooks here — the reply comes back on the
     * inbound socket, and the inbound-listener fan-out hands it to
     * {@link org.wynnvets.mwe.anni.network.AnniWsHandler AnniWsHandler}, which passes it to
     * {@link org.wynnvets.mwe.anni.network.AnniQueryClient AnniQueryClient}.</p>
     *
     * @return true when the inbound connection was up at the check and the frame was
     *         passed to {@link org.wynnvets.api.WsClient#send(JsonObject)
     *         WsClient#send(JsonObject)}, which does not confirm
     *         delivery; false when it was down and the caller must fall back to a null
     *         snapshot.
     */
    public static boolean sendAnniQuery() {
        if (!V1ApiManager.isInboundConnected()) {
            VetsLogger.debug("sendAnniQuery: inbound not connected");
            return false;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "anni_query");
        V1ApiManager.sendInboundUnchecked(payload);
        VetsLogger.debug("Sent anni_query");
        return true;
    }

    /**
     * S5 — Sends an {@code anni_scrollspot_set} inbound control frame: the
     * local user (who must be a party host) pins or clears their party's
     * scroll-spot coord. Pass {@code null} for the triplet to clear the spot.
     *
     * <p>Authenticated only — temp-server reads the session's MC UUID and
     * forwards it to vets-anni as {@code actor_mc_uuid}. The client cannot
     * impersonate a different host; vets-anni double-checks host status
     * against {@code Party.host} before persisting.</p>
     *
     * <p>The response arrives as an {@code anni_scrollspot_response} frame
     * routed by {@link org.wynnvets.mwe.anni.network.AnniWsHandler} to the
     * {@link org.wynnvets.mwe.anni.network.AnniScrollspotClient}'s pending
     * {@link java.util.concurrent.CompletableFuture}.</p>
     *
     * @param x  block-X (nullable triplet = clear)
     * @param y  block-Y
     * @param z  block-Z
     * @return true when the inbound connection was up at the check and the frame was
     *         passed to {@link org.wynnvets.api.WsClient#send(JsonObject)
     *         WsClient#send(JsonObject)}, which does not confirm
     *         delivery; false when it was down.
     */
    public static boolean sendAnniScrollspotSet(Integer x, Integer y, Integer z) {
        if (!V1ApiManager.isInboundConnected()) {
            VetsLogger.debug("sendAnniScrollspotSet: inbound not connected");
            return false;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "anni_scrollspot_set");
        if (x == null || y == null || z == null) {
            payload.add("scroll_spot", com.google.gson.JsonNull.INSTANCE);
            VetsLogger.debug("Sent anni_scrollspot_set: clear");
        } else {
            JsonObject spot = new JsonObject();
            spot.addProperty("x", x);
            spot.addProperty("y", y);
            spot.addProperty("z", z);
            payload.add("scroll_spot", spot);
            VetsLogger.debug("Sent anni_scrollspot_set: {} {} {}", x, y, z);
        }
        V1ApiManager.sendInboundUnchecked(payload);
        return true;
    }

    /**
     * S6 — Sends an {@code anni_rsvp} inbound control frame: the local
     * authenticated user RSVPs (hard/soft) or withdraws (revoke) for the
     * next anni from in-game.
     *
     * <p>Authenticated only — temp-server reads the session's MC UUID and
     * forwards it to vets-anni as {@code actor_mc_uuid}. The client cannot
     * impersonate a different user; vets-anni's
     * {@code anni-rsvp-by-uuid} endpoint reuses the code a Discord {@code \rsvp} runs,
     * so the RSVP row and the auto-placement are the same, and the public
     * confirmation line, built in the cog's format, goes out through the cog's
     * {@code _post_public}.</p>
     *
     * <p>The response arrives as an {@code anni_rsvp_response} frame
     * routed by {@link org.wynnvets.mwe.anni.network.AnniWsHandler} to the
     * {@link org.wynnvets.mwe.anni.network.AnniRsvpClient}'s pending
     * {@link java.util.concurrent.CompletableFuture}.</p>
     *
     * @param notice {@code "hard"}, {@code "soft"}, or {@code "revoke"}
     * @return true when the inbound connection was up at the check and the frame was
     *         passed to {@link org.wynnvets.api.WsClient#send(JsonObject)
     *         WsClient#send(JsonObject)}, which does not confirm
     *         delivery; false when it was down.
     */
    public static boolean sendAnniRsvp(String notice) {
        if (!V1ApiManager.isInboundConnected()) {
            VetsLogger.debug("sendAnniRsvp: inbound not connected");
            return false;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "anni_rsvp");
        payload.addProperty("notice", notice);
        V1ApiManager.sendInboundUnchecked(payload);
        VetsLogger.debug("Sent anni_rsvp: {}", notice);
        return true;
    }

    /**
     * Sends an {@code anni_party_observation} frame (S7) describing the local
     * player's current Wynncraft party roster. The caller has already
     * verified an organiser username is in the party (per the snapshot's
     * {@code organiser_usernames}) and the anni stamp is in-window — this
     * method is a pure send wrapper.
     *
     * <p>Names go over the wire (not UUIDs) because Wynncraft only exposes
     * party members by username; vets-anni resolves them server-side via
     * its roster + legacy-alias caches. Temp-server stamps the
     * authenticated session's {@code mc_uuid} as the observer when
     * forwarding — vetsmod does NOT include {@code observer_mc_uuid} in
     * the frame body (would be ignored anyway).</p>
     *
     * <p>The response arrives as an {@code anni_party_observation_response}
     * frame routed by {@link org.wynnvets.mwe.anni.network.AnniWsHandler}
     * (debug-logged only; no consumer state today).</p>
     *
     * @param memberUsernames every party member's username (including the
     *                        leader and the local player — Wynntils'
     *                        convention), or empty list
     * @param leaderUsername  the party leader's username, or {@code ""}
     *                        if not in a party (the server will no-op on
     *                        an empty leader)
     * @param world           the Wynncraft world name (e.g. {@code WC1});
     *                        forwarded for observability
     * @return true when the inbound connection was up at the check and the frame was
     *         passed to {@link org.wynnvets.api.WsClient#send(JsonObject)
     *         WsClient#send(JsonObject)}, which does not confirm
     *         delivery; false when it was down.
     */
    public static boolean sendAnniPartyObservation(
            List<String> memberUsernames, String leaderUsername, String world) {
        if (!V1ApiManager.isInboundConnected()) {
            VetsLogger.debug("sendAnniPartyObservation: inbound not connected");
            return false;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "anni_party_observation");
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        if (memberUsernames != null) {
            for (String name : memberUsernames) {
                if (name != null && !name.isEmpty()) {
                    arr.add(name);
                }
            }
        }
        payload.add("party_member_usernames", arr);
        payload.addProperty("leader_username", leaderUsername != null ? leaderUsername : "");
        payload.addProperty("world", world != null ? world : "");
        V1ApiManager.sendInboundUnchecked(payload);
        VetsLogger.debug(
                "Sent anni_party_observation: leader={}, members={}, world={}",
                leaderUsername,
                arr.size(),
                world);
        return true;
    }
}
