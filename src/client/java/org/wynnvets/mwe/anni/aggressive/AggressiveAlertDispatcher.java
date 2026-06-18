package org.wynnvets.mwe.anni.aggressive;

import com.wynntils.core.components.Models;
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
import org.wynnvets.mwe.anni.zone.AnniZone;

import java.time.Instant;
import java.util.Objects;

/**
 * S5 — aggressive-mode chat alerts.
 *
 * <p>Two trigger families:</p>
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
 *       world-mismatch alert (once per stamp_epoch). At T-5m, if the
 *       player isn't in the anni zone, fire a zone-absence alert (once per
 *       stamp_epoch). These are advisory pings the user asked for in S5
 *       planning — they're NOT gates, just nudges.</li>
 * </ul>
 *
 * <p>All chat output bounces to the main thread via
 * {@link Minecraft#execute(Runnable)} before calling
 * {@link ChatUtils#sendLocalMessage(Component)} — snapshot listeners run on
 * the WS reader thread per the architecture-as-built doc's S5
 * recommendation.</p>
 *
 * <p>Gated on {@link AnniAggressiveTicker#isAggressiveActive()} AND
 * {@link VetsConfig#VETS_ANNI_CHAT_ALERTS}.</p>
 */
public final class AggressiveAlertDispatcher {

    /** Per-field cooldown — collapses rapid snapshot churn into one
     *  alert per field per window. */
    private static final long FIELD_COOLDOWN_MS = 5_000L;

    /** Time-trigger boundaries — wall-clock seconds before stamp_epoch. */
    private static final long T_MINUS_WORLD_READY_S = 10L * 60L;
    private static final long T_MINUS_ZONE_READY_S  = 5L * 60L;

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

    private AggressiveAlertDispatcher() {
    }

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

        String role = extractRole(snapshot);
        if (roleObserved
                && !Objects.equals(lastRole, role)
                && now - lastRoleFiredMs >= FIELD_COOLDOWN_MS) {
            fireRoleAlert(lastRole, role);
            lastRoleFiredMs = now;
        }
        roleObserved = true;
        lastRole = role;

        String world = extractPartyWorld(snapshot);
        if (worldObserved
                && !Objects.equals(lastWorld, world)
                && now - lastWorldFiredMs >= FIELD_COOLDOWN_MS) {
            fireWorldAlert(lastWorld, world);
            lastWorldFiredMs = now;
        }
        worldObserved = true;
        lastWorld = world;

        Integer party = extractPartyOrdinal(snapshot);
        if (partyObserved
                && !Objects.equals(lastParty, party)
                && now - lastPartyFiredMs >= FIELD_COOLDOWN_MS) {
            firePartyAlert(snapshot, lastParty, party);
            lastPartyFiredMs = now;
        }
        partyObserved = true;
        lastParty = party;

        String rsvp = extractRsvp(snapshot);
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
        lastRole = extractRole(snapshot);
        lastWorld = extractPartyWorld(snapshot);
        lastParty = extractPartyOrdinal(snapshot);
        lastRsvp = extractRsvp(snapshot);
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
        if (!worldReadinessFired
                && secondsUntil > 0
                && secondsUntil <= T_MINUS_WORLD_READY_S) {
            String assigned = extractPartyWorld(snapshot);
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
        if (!zoneReadinessFired
                && secondsUntil > 0
                && secondsUntil <= T_MINUS_ZONE_READY_S) {
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

    // ── Field extractors ───────────────────────────────────────────────

    private static String extractRole(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        if (board != null && board.role() != null) return board.role();
        return null;
    }

    private static String extractPartyWorld(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        if (board == null || board.party() == null) return null;
        return board.party().world();
    }

    private static Integer extractPartyOrdinal(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        if (board == null || board.party() == null) return null;
        int o = board.party().ordinal();
        return o > 0 ? o : null;
    }

    private static String extractRsvp(AnniSnapshot snapshot) {
        AnniSnapshot.Rsvp rsvp = snapshot.rsvp();
        if (rsvp == null || rsvp.revoked()) return null;
        return rsvp.notice();
    }

    // ── Alert emitters ─────────────────────────────────────────────────

    private static void fireRoleAlert(String prev, String next) {
        String prevTxt = prev != null ? prev : "TBD";
        String nextTxt = next != null ? next : "TBD";
        MutableComponent msg = Component.literal("Role: ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(prevTxt).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" → ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(nextTxt).withStyle(ChatFormatting.WHITE));
        ChatUtils.sendLocalMessage(msg);
    }

    private static void fireWorldAlert(String prev, String next) {
        String prevTxt = prev != null ? prev : "—";
        String nextTxt = next != null ? next : "unassigned";
        MutableComponent msg = Component.literal("Party world: ")
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
        MutableComponent msg = Component.literal("Assignment: ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(prevTxt).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" → ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(nextTxt).withStyle(ChatFormatting.WHITE));
        ChatUtils.sendLocalMessage(msg);
    }

    private static void fireRsvpAlert(String prev, String next) {
        String prevTxt = prev != null ? prev.toUpperCase() : "none";
        String nextTxt = next != null ? next.toUpperCase() : "none";
        MutableComponent msg = Component.literal("RSVP: ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(prevTxt).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" → ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(nextTxt).withStyle(ChatFormatting.WHITE));
        ChatUtils.sendLocalMessage(msg);
    }

    private static void fireWorldReadinessAlert(String assigned, String current) {
        MutableComponent msg = Component.literal("T-10m: get to ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(assigned).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(current != null
                        ? " (you're on " + current + ")"
                        : ""
                ).withStyle(ChatFormatting.GRAY));
        ChatUtils.sendLocalMessage(msg);
    }

    private static void fireZoneReadinessAlert(AnniSnapshot snapshot) {
        AnniSnapshot.ScrollSpot spot = null;
        AnniSnapshot.Board board = snapshot.board();
        if (board != null && board.party() != null) {
            spot = board.party().scrollSpot();
        }
        String coord = spot != null
                ? spot.x() + " " + spot.y() + " " + spot.z()
                : "345 45 -1315";
        MutableComponent msg = Component.literal("T-5m: get to the anni zone (")
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
        mc.execute(() -> {
            switch (field) {
                case "role":  fireRoleAlert("TBD", "TANK"); break;
                case "world": fireWorldAlert(null, "EU5"); break;
                case "party": firePartyAlert(
                        AnniSnapshotCache.latest() != null
                                ? AnniSnapshotCache.latest()
                                : emptySnapshot(), null, 2); break;
                case "rsvp":  fireRsvpAlert(null, "hard"); break;
                case "zone":
                    fireZoneReadinessAlert(
                            AnniSnapshotCache.latest() != null
                                    ? AnniSnapshotCache.latest()
                                    : emptySnapshot()); break;
                case "world_ready":
                    fireWorldReadinessAlert("EU5", "WC1"); break;
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
