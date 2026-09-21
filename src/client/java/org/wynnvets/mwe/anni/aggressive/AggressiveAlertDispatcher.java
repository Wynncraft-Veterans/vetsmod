package org.wynnvets.mwe.anni.aggressive;

import com.wynntils.core.components.Models;
import java.time.Instant;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;
import org.wynnvets.mwe.anni.state.AnniSnapshots;
import org.wynnvets.mwe.anni.zone.AnniZone;

/**
 * S5 — aggressive-mode chat alerts.
 *
 * <p>Two trigger families, and they do not share a thread, a latch policy or
 * a lifetime:</p>
 * <ul>
 *   <li><b>Snapshot-diff alerts.</b> {@code board.role / board.party.world
 *       / board.party.ordinal / rsvp.notice} transitions fire a one-line
 *       chat message. First observation per field in a session is silent
 *       (login-with-existing-state shouldn't bing on every reconnect — same
 *       {@code *Observed} sentinel pattern as
 *       {@link org.wynnvets.mwe.anni.bossbar.FlashTracker}). Per-field 5s
 *       cooldown so a rapid snapshot churn doesn't spam.</li>
 *   <li><b>Time-triggered readiness alerts.</b> At T-10m, if the player's
 *       current world doesn't match the assigned party world, fire a
 *       world-mismatch alert. At T-5m, if the player isn't in the anni zone,
 *       fire a zone-absence alert. Both are once per {@code stamp_epoch};
 *       neither fires past the stamp. These are advisory pings the user asked
 *       for in S5 planning — they're NOT gates, just nudges.</li>
 * </ul>
 *
 * <h2>The two readiness sentinels latch on different conditions</h2>
 *
 * <p>The T-10m world alert latches only once a party world is actually
 * assigned — the {@code worldReadinessFired = true} sits inside the
 * {@code assigned != null} branch — so an <b>unassigned player gets this alert
 * late, or not at all</b>, because the tick keeps retrying until a world
 * appears. The T-5m zone alert latches <b>unconditionally</b> on the first
 * tick inside T-5m, fired or not, so a player who is in the zone at T-5m and
 * leaves immediately afterwards gets nothing. Only the first of the two
 * carries a comment explaining its policy; they are not the same policy.</p>
 *
 * <p>Both sentinels are <b>in-memory</b> and reset when the observed
 * {@code stamp_epoch} moves, so they are once-per-anni <em>per client
 * session</em>: a restart mid-window re-fires them. This is the opposite of
 * the ghosts prompt, whose sentinel persists in
 * {@link VetsConfig#VETS_ANNI_GHOSTS_PROMPT_SHOWN_FOR_STAMP}.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>None of the six {@code fire*} methods calls
 * {@link Minecraft#execute(Runnable)}; each calls
 * {@link ChatUtils#sendLocalMessage(Component)} directly. <b>That is safe from
 * any thread, and not because of anything in this class:</b>
 * {@code sendLocalMessage} delegates to {@code ChatUtils.dispatchToChat}, which
 * wraps the {@code displayClientMessage} call in {@code minecraft.execute(...)}
 * unconditionally. There is no path around it. So the chat write is already on
 * the main thread one frame below every emitter, and a new emitter added here
 * inherits that for free.</p>
 *
 * <p>Which means the {@code mc.execute(...)} in {@code onSnapshot} is <b>not</b>
 * there for the chat write, and it is not there for field visibility either —
 * every field this class diffs is {@code volatile}. What it buys is
 * <em>serialisation</em>: {@code applyDiff} does read-compare-write across
 * several of those fields per snapshot, and volatile makes each read and write
 * visible without making the sequence atomic. Hopping to the client thread means
 * two pushes arriving close together cannot interleave their comparisons. That
 * reading is inferred from the code; no comment states it.</p>
 *
 * <p>{@code tick} needs no hop at all: it is registered on
 * {@code ClientTickEvents.END_CLIENT_TICK} and already runs on the client
 * thread. {@code forceAlert}'s own {@code mc.execute(...)} is likewise
 * redundant rather than required — Fabric dispatches client commands on the
 * client thread too. Harmless, and left alone.</p>
 *
 * <p><b>Gated on</b> {@link AnniAggressiveTicker#isAggressiveActive()} AND
 * {@link VetsConfig#VETS_ANNI_CHAT_ALERTS}. ⚠️ <b>The two trigger families
 * obey that gate differently.</b> For the readiness ticker it is absolute:
 * {@code tickInner} opens with {@code if (!gateHolds()) return;}, so the whole
 * tick is skipped. For the snapshot listener it governs <em>emission</em> only
 * — {@code applyDiff} resets the per-stamp sentinels and refreshes
 * {@code lastSeen*} <em>before</em> consulting the gate, so state stays current
 * while alerts are off. Without that, the first snapshot after aggressive mode
 * is switched on would diff against stale state and bing for changes the user
 * already saw.</p>
 */
public final class AggressiveAlertDispatcher {

    /** Per-field cooldown — collapses rapid snapshot churn into one
     *  alert per field per window. */
    private static final long FIELD_COOLDOWN_MS = 5_000L;

    /** Time-trigger boundaries — wall-clock seconds before stamp_epoch. */
    private static final long T_MINUS_WORLD_READY_S = 10L * 60L;

    private static final long T_MINUS_ZONE_READY_S = 5L * 60L;

    private static volatile boolean registered = false;

    // Snapshot-diff state.
    private static volatile String lastRole = null;
    private static volatile String lastWorld = null;
    private static volatile Integer lastParty = null;
    private static volatile String lastRsvp = null;
    private static volatile boolean roleObserved = false;
    private static volatile boolean worldObserved = false;
    private static volatile boolean partyObserved = false;
    private static volatile boolean rsvpObserved = false;

    // Per-field "last fired at" ms for the 5s cooldown.
    private static volatile long lastRoleFiredMs = 0L;
    private static volatile long lastWorldFiredMs = 0L;
    private static volatile long lastPartyFiredMs = 0L;
    private static volatile long lastRsvpFiredMs = 0L;

    // Per-stamp_epoch sentinels for the readiness alerts. A new stamp
    // resets both; -1 means "never fired".
    private static volatile long readinessTrackedStamp = -1L;
    private static volatile boolean worldReadinessFired = false;
    private static volatile boolean zoneReadinessFired = false;

    private AggressiveAlertDispatcher() {}

    public static void register() {
        if (registered) return;
        registered = true;
        AnniSnapshotCache.addListener(AggressiveAlertDispatcher::onSnapshot);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        VetsLogger.debug("AggressiveAlertDispatcher registered");
    }

    // ── Snapshot diff ──────────────────────────────────────────────────

    private static void onSnapshot(AnniSnapshot snapshot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> applyDiff(snapshot));
    }

    private static void applyDiff(AnniSnapshot snapshot) {
        if (snapshot == null) return;

        // Reset readiness sentinels whenever the stamp_epoch we observe
        // moves (anni was wiped/rescheduled): the readiness alerts are
        // per-stamp, not per-session.
        AnniSnapshot.Event event = snapshot.event();
        Long stamp = event != null ? event.stampEpoch() : null;
        long stampVal = stamp != null ? stamp : -1L;
        if (stampVal != readinessTrackedStamp) {
            readinessTrackedStamp = stampVal;
            worldReadinessFired = false;
            zoneReadinessFired = false;
        }

        if (!gateHolds()) {
            // Still mirror lastSeen so we don't fire on the FIRST aggressive
            // activation either — the *Observed pattern depends on us
            // updating state regardless of gate.
            updateLastSeen(snapshot);
            return;
        }

        long now = System.currentTimeMillis();

        String role = AnniSnapshots.role(snapshot);
        if (roleObserved
                && !Objects.equals(lastRole, role)
                && now - lastRoleFiredMs >= FIELD_COOLDOWN_MS) {
            fireRoleAlert(lastRole, role);
            lastRoleFiredMs = now;
        }
        roleObserved = true;
        lastRole = role;

        String world = AnniSnapshots.partyWorld(snapshot);
        if (worldObserved
                && !Objects.equals(lastWorld, world)
                && now - lastWorldFiredMs >= FIELD_COOLDOWN_MS) {
            fireWorldAlert(lastWorld, world);
            lastWorldFiredMs = now;
        }
        worldObserved = true;
        lastWorld = world;

        Integer party = AnniSnapshots.partyOrdinal(snapshot);
        if (partyObserved
                && !Objects.equals(lastParty, party)
                && now - lastPartyFiredMs >= FIELD_COOLDOWN_MS) {
            firePartyAlert(snapshot, lastParty, party);
            lastPartyFiredMs = now;
        }
        partyObserved = true;
        lastParty = party;

        String rsvp = AnniSnapshots.rsvpNotice(snapshot);
        if (rsvpObserved
                && !Objects.equals(lastRsvp, rsvp)
                && now - lastRsvpFiredMs >= FIELD_COOLDOWN_MS) {
            fireRsvpAlert(lastRsvp, rsvp);
            lastRsvpFiredMs = now;
        }
        rsvpObserved = true;
        lastRsvp = rsvp;
    }

    private static void updateLastSeen(AnniSnapshot snapshot) {
        lastRole = AnniSnapshots.role(snapshot);
        lastWorld = AnniSnapshots.partyWorld(snapshot);
        lastParty = AnniSnapshots.partyOrdinal(snapshot);
        lastRsvp = AnniSnapshots.rsvpNotice(snapshot);
        roleObserved = true;
        worldObserved = true;
        partyObserved = true;
        rsvpObserved = true;
    }

    // ── Readiness ticker ───────────────────────────────────────────────

    private static void tick() {
        try {
            tickInner();
        } catch (Exception e) {
            VetsLogger.debug("AggressiveAlertDispatcher.tick failed: {}", e.getMessage());
        }
    }

    private static void tickInner() {
        if (!gateHolds()) return;
        AnniSnapshot snapshot = AnniSnapshotCache.latest();
        if (snapshot == null) return;
        AnniSnapshot.Event event = snapshot.event();
        if (event == null) return;
        Long stamp = event.stampEpoch();
        if (stamp == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        LocalPlayer player = mc.player;
        if (player == null) return;

        long now = Instant.now().getEpochSecond();
        long secondsUntil = stamp - now;

        // T-10m world-mismatch.
        if (!worldReadinessFired && secondsUntil > 0 && secondsUntil <= T_MINUS_WORLD_READY_S) {
            String assigned = AnniSnapshots.partyWorld(snapshot);
            if (assigned != null) {
                String current = Models.WorldState.getCurrentWorldName();
                if (current == null || !current.equalsIgnoreCase(assigned)) {
                    fireWorldReadinessAlert(assigned, current);
                }
                // Latch the sentinel regardless — even if the user happens
                // to be on the right world at T-10m, we don't want a re-fire
                // if they briefly leave.
                worldReadinessFired = true;
            }
        }

        // T-5m zone-absence.
        if (!zoneReadinessFired && secondsUntil > 0 && secondsUntil <= T_MINUS_ZONE_READY_S) {
            if (!AnniZone.isInZone(player.getX(), player.getZ())) {
                fireZoneReadinessAlert(snapshot);
            }
            zoneReadinessFired = true;
        }
    }

    // ── Gating ─────────────────────────────────────────────────────────

    private static boolean gateHolds() {
        return AnniAggressiveTicker.isAggressiveActive()
                && VetsConfig.get(VetsConfig.VETS_ANNI_CHAT_ALERTS);
    }

    // ── Alert emitters ─────────────────────────────────────────────────

    private static void fireRoleAlert(String prev, String next) {
        String prevTxt = prev != null ? prev : "TBD";
        String nextTxt = next != null ? next : "TBD";
        MutableComponent msg =
                Component.literal("Role: ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(prevTxt).withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(" → ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(nextTxt).withStyle(ChatFormatting.WHITE));
        ChatUtils.sendLocalMessage(msg);
    }

    private static void fireWorldAlert(String prev, String next) {
        String prevTxt = prev != null ? prev : "—";
        String nextTxt = next != null ? next : "unassigned";
        MutableComponent msg =
                Component.literal("Party world: ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(prevTxt).withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(" → ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(nextTxt).withStyle(ChatFormatting.WHITE));
        ChatUtils.sendLocalMessage(msg);
    }

    private static void firePartyAlert(AnniSnapshot snapshot, Integer prev, Integer next) {
        String prevTxt = prev != null ? "Party " + prev : "unassigned";
        String nextTxt;
        if (next != null) {
            nextTxt = "Party " + next;
        } else {
            AnniSnapshot.Board board = snapshot.board();
            String state = board != null ? board.state() : null;
            nextTxt = "wont_assign".equals(state) ? "won't assign" : "unassigned";
        }
        MutableComponent msg =
                Component.literal("Assignment: ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(prevTxt).withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(" → ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(nextTxt).withStyle(ChatFormatting.WHITE));
        ChatUtils.sendLocalMessage(msg);
    }

    private static void fireRsvpAlert(String prev, String next) {
        String prevTxt = prev != null ? prev.toUpperCase() : "none";
        String nextTxt = next != null ? next.toUpperCase() : "none";
        MutableComponent msg =
                Component.literal("RSVP: ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(prevTxt).withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(" → ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(nextTxt).withStyle(ChatFormatting.WHITE));
        ChatUtils.sendLocalMessage(msg);
    }

    private static void fireWorldReadinessAlert(String assigned, String current) {
        MutableComponent msg =
                Component.literal("T-10m: get to ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(assigned).withStyle(ChatFormatting.YELLOW))
                        .append(
                                Component.literal(
                                                current != null
                                                        ? " (you're on " + current + ")"
                                                        : "")
                                        .withStyle(ChatFormatting.GRAY));
        ChatUtils.sendLocalMessage(msg);
    }

    /**
     * T-5m zone-absence alert: "get to the anni zone", with coordinates.
     *
     * <p>The coordinates are the party's pinned scroll spot when there is one.
     * <b>The {@code "345 45 -1315"} fallback is unguarded</b>, so this alert
     * prints that literal to a player with no party at all. Nothing states
     * whether that is intended; it is at least arguable (a partyless player
     * still needs somewhere to go) and the owning doc says only "do not
     * generalise 'only in a party' beyond the waypoint". Either way it makes the
     * fallback behave differently here than in the only other place the same
     * coordinate is hard-coded. ⚠️ It is not the same literal: this file holds
     * the whole string {@code "345 45 -1315"}, while
     * {@code ScrollSpotMarkerProvider} holds three separate {@code int}
     * constants. Nothing links them, which is the point.
     * {@code ScrollSpotMarkerProvider.computeEntry} returns
     * null on a null {@code board.party()} <em>before</em> it reads the spot,
     * so the waypoint shows nothing outside a party. "Only in a party" is true
     * of the marker and false of this alert; the two are not a shared
     * constant and do not move together.</p>
     */
    private static void fireZoneReadinessAlert(AnniSnapshot snapshot) {
        AnniSnapshot.ScrollSpot spot = null;
        AnniSnapshot.Board board = snapshot.board();
        if (board != null && board.party() != null) {
            spot = board.party().scrollSpot();
        }
        String coord = spot != null ? spot.x() + " " + spot.y() + " " + spot.z() : "345 45 -1315";
        MutableComponent msg =
                Component.literal("T-5m: get to the anni zone (")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(coord).withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(")").withStyle(ChatFormatting.GOLD));
        ChatUtils.sendLocalMessage(msg);
    }

    // ── Debug entry ────────────────────────────────────────────────────

    /** Debug hook — synthesise a chat alert without waiting for a
     *  snapshot diff or T-N boundary. Used by
     *  {@code /wv debug tree anni alert <field>}. */
    public static void forceAlert(String field) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(
                () -> {
                    switch (field) {
                        case "role":
                            fireRoleAlert("TBD", "TANK");
                            break;
                        case "world":
                            fireWorldAlert(null, "EU5");
                            break;
                        case "party":
                            firePartyAlert(
                                    AnniSnapshotCache.latest() != null
                                            ? AnniSnapshotCache.latest()
                                            : emptySnapshot(),
                                    null,
                                    2);
                            break;
                        case "rsvp":
                            fireRsvpAlert(null, "hard");
                            break;
                        case "zone":
                            fireZoneReadinessAlert(
                                    AnniSnapshotCache.latest() != null
                                            ? AnniSnapshotCache.latest()
                                            : emptySnapshot());
                            break;
                        case "world_ready":
                            fireWorldReadinessAlert("EU5", "WC1");
                            break;
                        default:
                            VetsLogger.debug("forceAlert: unknown field {}", field);
                    }
                });
    }

    /** Empty snapshot placeholder for debug paths when the cache is cold. */
    private static AnniSnapshot emptySnapshot() {
        return AnniSnapshot.fromJson(new com.google.gson.JsonObject());
    }
}
