package org.wynnvets.mwe.anni.bossbar;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.BossEvent;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.zone.AnniZone;

/**
 * Content builder for the synthetic vets-anni boss bar.
 *
 * <p>Does no I/O, so it is safe for the per-tick loop — but it is not a
 * pure function of its arguments: {@code build} reads {@link AnniZone}'s
 * volatile disc cache and, through {@link FlashTracker#styleFor}, both a
 * time-bounded flash window and a 250 ms pulse bit. The same arguments
 * yield different Components at different instants.</p>
 *
 * <p>Maps {@code (AnniSnapshot, secondsUntilAnni, playerPos)} to one of
 * the four spec text variants (or {@code null} for "deactivate"):</p>
 *
 * <ul>
 *   <li><b>T-20s gate</b> (one of the two T-20s gates per parent plan
 *       §"Risks"; the other is {@link VetsBossBarManager}'s wall-clock
 *       watchdog) — returns {@code null} when
 *       {@code secondsUntilAnni <= T_MINUS_20_GATE_SECONDS}.
 *       Caller deactivates.</li>
 *   <li><b>Countdown</b> — when
 *       {@code secondsUntilAnni <= COUNTDOWN_THRESHOLD_SECONDS} (5 min,
 *       user-iterated up from the spec's 2 on 2026-06-16)
 *       AND the player is inside the anni zone
 *       (per {@link AnniZone#isInZone}). Spec §3.1.1.3:
 *       {@code &3anni.wynnvets.org&8: &cANNI IN &f&lAAs&8 | &d&l&nSCROLLS IN BBs&d!}</li>
 *   <li><b>Assigned</b> — when the snapshot's board carries a party
 *       OR a committed slot (specific role / FILL). Spec §3.1.1.2:
 *       {@code &3anni.wynnvets.org/me&8: &7Role: <ROLE>&8 | &7Party <N>&8 | &7World: <W>}</li>
 *   <li><b>Seeking</b> — otherwise. Spec §3.1.1.1:
 *       {@code &7Seeking a <RSVP> <SLOT> &7for &3&nanni.wynnvets.org&7 in &f&l<XX>&fmin}</li>
 * </ul>
 *
 * <p>The flash decoration ({@code &l ↔ &n&l}) is applied per-field via
 * {@link FlashTracker#styleFor} to four chips: role, party and world in
 * the assigned variant, rsvp in the seeking one.</p>
 *
 * <p>{@code colorFor(AnniSnapshot)} lives here too, and the manager calls
 * it every tick alongside the text.</p>
 */
public final class VetsBossBarContentBuilder {

    /** Spec §3.1.1.4 — STOP MESSING WITH THE BOSS BAR threshold. Once
     *  the countdown reaches this many seconds, return {@code null} so
     *  the manager calls {@code deactivate()} and Wynntils' anni health
     *  bar takes over unobstructed. Also doubles as the offset from
     *  anni-time to scroll-time: scrolls happen this many seconds
     *  before anni starts. */
    private static final long T_MINUS_20_GATE_SECONDS = 20L;

    /** Switch from the assigned/seeking variant to the countdown
     *  variant this many seconds before anni. (Spec called for 2 min,
     *  user-iterated to 5 min on 2026-06-16.) */
    private static final long COUNTDOWN_THRESHOLD_SECONDS = 5L * 60L;

    /** Show the literal "SCROLLS IN XXXs" countdown when there are this
     *  many seconds left until scrolls (i.e. until T-20s). Above this,
     *  the bar still shows the countdown variant but the right half
     *  reads "Get ready for scrolls!" instead. */
    private static final long SCROLL_COUNTDOWN_START_SECONDS = 70L;

    private VetsBossBarContentBuilder() {}

    /**
     * Recommend a {@link BossEvent.BossBarColor} that mirrors the
     * "Attendance Chance" pill on {@code /wv anni}:
     * <ul>
     *   <li>board state {@code party} (ASSIGNED) → {@link BossEvent.BossBarColor#PURPLE}
     *       (NOT pink — Wynncraft's resource pack overrides
     *       {@code boss_bar/pink_background.png} to be fully transparent,
     *       so any pink bar renders as text-only; reproducible on bare
     *       vanilla MC with Wynncraft connected. Confirmed empirically
     *       in {@code .claude/ephemeral/BossBarExploration/} — every
     *       Wynncraft text-only bar (Returners XP, Corrupted Road, etc.)
     *       carries {@code color=PINK overlay=PROGRESS}; mob health bars
     *       use non-pink colours and render normally. Purple is the
     *       nearest visual neighbour that isn't suppressed.)</li>
     *   <li>board state {@code wont_assign} (COULD NOT ASSIGN) → RED</li>
     *   <li>otherwise → quantised from {@link AnniSnapshot.Attendance#band()}
     *       (1-2 = RED, 3-4 = YELLOW, 5-6 = GREEN). Mirrors the dashboard
     *       general-module bar exactly enough that the user can read the
     *       boss bar as a proxy for their attendance pill.</li>
     *   <li>fallback (no snapshot / no band) → PURPLE — distinct from all
     *       state-driven values so a coding bug is visually obvious.</li>
     * </ul>
     */
    public static BossEvent.BossBarColor colorFor(AnniSnapshot snapshot) {
        if (snapshot == null) return BossEvent.BossBarColor.PURPLE;
        AnniSnapshot.Board board = snapshot.board();
        String state =
                (board != null && board.state() != null) ? board.state().toLowerCase() : null;
        // PURPLE rather than PINK — pink is suppressed by Wynncraft's
        // resource pack; see the class-level javadoc for the empirical
        // evidence.
        if ("party".equals(state)) return BossEvent.BossBarColor.PURPLE;
        if ("wont_assign".equals(state)) return BossEvent.BossBarColor.RED;
        AnniSnapshot.Attendance att = snapshot.attendance();
        if (att != null && att.band() > 0) {
            return bandToBarColor(att.band());
        }
        return BossEvent.BossBarColor.PURPLE;
    }

    private static BossEvent.BossBarColor bandToBarColor(int band) {
        if (band <= 2) return BossEvent.BossBarColor.RED; // dark_red / red
        if (band <= 4) return BossEvent.BossBarColor.YELLOW; // gold / yellow
        return BossEvent.BossBarColor.GREEN; // dark_green / green
    }

    /**
     * Build the boss-bar name component for the current tick.
     *
     * @param snapshot         the latest {@link AnniSnapshot} (non-null).
     * @param secondsUntilAnni seconds until {@code event.stamp_epoch}
     *                         (may be negative if the stamp is past).
     * @param playerX          player block-X for zone test.
     * @param playerZ          player block-Z for zone test.
     * @return the styled name component, or {@code null} if the boss
     *         bar should not be shown this tick.
     */
    public static MutableComponent build(
            AnniSnapshot snapshot, long secondsUntilAnni, double playerX, double playerZ) {
        if (snapshot == null) return null;

        // Gate 1 of 3: hard T-20s cut-off — non-negotiable.
        if (secondsUntilAnni <= T_MINUS_20_GATE_SECONDS) {
            return null;
        }

        // T-5m + in-zone → countdown variant.
        if (secondsUntilAnni <= COUNTDOWN_THRESHOLD_SECONDS
                && AnniZone.isInZone(playerX, playerZ)) {
            return buildCountdown(secondsUntilAnni);
        }

        if (isAssigned(snapshot)) {
            return buildAssigned(snapshot);
        }
        return buildSeeking(snapshot, secondsUntilAnni);
    }

    // ── State predicates ────────────────────────────────────────────────

    /** "Assigned" = either a party row exists OR a slot has been
     *  committed (any specific role in registration.roles, or board.role
     *  set to a fill commit). Mirrors the spec's distinction between the
     *  five "Role: X | Party N | World: W" variants and the "Seeking …"
     *  variants. */
    private static boolean isAssigned(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        if (board != null) {
            if (board.party() != null) return true;
            if (board.role() != null) return true;
        }
        AnniSnapshot.Registration reg = snapshot.registration();
        if (reg != null && !reg.roles().isEmpty()) {
            for (AnniSnapshot.RoleEntry r : reg.roles()) {
                if (r.role() != null && !"FILL".equalsIgnoreCase(r.role())) {
                    return true; // specific role = committed CORE slot
                }
            }
        }
        return false;
    }

    // ── Variant: countdown (T-2m → T-20s) ───────────────────────────────

    /** Countdown variant (spec §3.1.1.3, with user-iterated 5 min /
     *  70 s scroll threshold):
     *  {@code &3anni.wynnvets.org&8: &cANNI IN &f&lAAs&8 | <right>}
     *  where {@code AA = secondsUntilAnni} (zero-padded to 3). The
     *  right half is the literal countdown
     *  {@code &d&l&nSCROLLS IN BBs&d!} once scrolls are inside 70 s
     *  (BB = max(0, AA - 20)); above that it's a plain italic
     *  {@code &d&oGet ready for scrolls!} to alert the user without
     *  pretending we know the exact moment. */
    private static MutableComponent buildCountdown(long secondsUntilAnni) {
        long anniSecs = Math.max(0, secondsUntilAnni);
        long scrollSecs = Math.max(0, anniSecs - T_MINUS_20_GATE_SECONDS);

        MutableComponent out =
                Component.literal("anni.wynnvets.org")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA));
        out.append(Component.literal(": ").withStyle(ChatFormatting.DARK_GRAY));
        out.append(Component.literal("ANNI IN ").withStyle(ChatFormatting.RED));
        out.append(
                Component.literal(format3(anniSecs) + "s")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE).withBold(true)));
        out.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));

        if (scrollSecs <= SCROLL_COUNTDOWN_START_SECONDS) {
            Style scrollStyle =
                    Style.EMPTY
                            .withColor(ChatFormatting.LIGHT_PURPLE)
                            .withBold(true)
                            .withUnderlined(true);
            out.append(
                    Component.literal("SCROLLS IN " + format3(scrollSecs) + "s")
                            .withStyle(scrollStyle));
            out.append(
                    Component.literal("!")
                            .withStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE)));
        } else {
            Style readyStyle = Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(true);
            out.append(Component.literal("Get ready for scrolls!").withStyle(readyStyle));
        }
        return out;
    }

    // ── Variant: assigned (Role | Party | World) ────────────────────────

    /** Spec §3.1.1.2 — assigned: Role | Party | World. Null fields are
     *  rendered as a gray "TBD". Role colour follows the spec mapping
     *  (TANK=&9, HEAL=&a, PRIM=&c, SUNK=&e, MOBK=&d, FILL=&3). The
     *  link drops the {@code /me} path for compactness on the bar
     *  (the spec writes it as {@code anni.wynnvets.org/me} but on a
     *  ~40-char boss bar every glyph counts). */
    private static MutableComponent buildAssigned(AnniSnapshot snapshot) {
        MutableComponent out =
                Component.literal("anni.wynnvets.org")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA));
        out.append(Component.literal(": ").withStyle(ChatFormatting.DARK_GRAY));

        // Role
        out.append(Component.literal("Role: ").withStyle(ChatFormatting.GRAY));
        out.append(roleChip(snapshot));
        out.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));

        // Party
        out.append(Component.literal("Party ").withStyle(ChatFormatting.GRAY));
        out.append(partyChip(snapshot));
        out.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));

        // World
        out.append(Component.literal("World: ").withStyle(ChatFormatting.GRAY));
        out.append(worldChip(snapshot));
        return out;
    }

    private static MutableComponent roleChip(AnniSnapshot snapshot) {
        String roleCode = resolveRoleCode(snapshot);
        if (roleCode == null) {
            return Component.literal("TBD").withStyle(ChatFormatting.GRAY);
        }
        String shortLabel = toShortLabel(roleCode);
        Style style = Style.EMPTY.withColor(roleColor(roleCode)).withBold(true);
        style = FlashTracker.styleFor("role", style);
        return Component.literal(shortLabel).withStyle(style);
    }

    private static MutableComponent partyChip(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        Integer ordinal = (board != null && board.party() != null) ? board.party().ordinal() : null;
        if (ordinal == null || ordinal <= 0) {
            return Component.literal("TBD").withStyle(ChatFormatting.GRAY);
        }
        Style style = Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(true);
        style = FlashTracker.styleFor("party", style);
        return Component.literal(Integer.toString(ordinal)).withStyle(style);
    }

    private static MutableComponent worldChip(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        String world = (board != null && board.party() != null) ? board.party().world() : null;
        if (world == null || world.isEmpty()) {
            return Component.literal("TBD").withStyle(ChatFormatting.GRAY);
        }
        Style style = Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true);
        style = FlashTracker.styleFor("world", style);
        return Component.literal(world).withStyle(style);
    }

    // ── Variant: seeking ────────────────────────────────────────────────

    /** Spec §3.1.1.1 — "Seeking a {RSVP} {SLOT} for anni.wynnvets.org in {MIN}min". */
    private static MutableComponent buildSeeking(AnniSnapshot snapshot, long secondsUntilAnni) {
        MutableComponent out = Component.literal("Seeking a ").withStyle(ChatFormatting.GRAY);

        // RSVP / WALKIN / LATE chip
        out.append(rsvpChip(snapshot));
        out.append(Component.literal(" ").withStyle(ChatFormatting.GRAY));

        // CORE / FILL slot chip
        out.append(slotChip(snapshot));
        out.append(Component.literal(" for ").withStyle(ChatFormatting.GRAY));

        // Link badge
        out.append(
                Component.literal("anni.wynnvets.org")
                        .withStyle(
                                Style.EMPTY
                                        .withColor(ChatFormatting.DARK_AQUA)
                                        .withUnderlined(true)));
        out.append(Component.literal(" in ").withStyle(ChatFormatting.GRAY));

        // Minutes remaining (ceil)
        long minutes = Math.max(1, (secondsUntilAnni + 59) / 60);
        Style minStyle = Style.EMPTY.withColor(ChatFormatting.WHITE).withBold(true);
        out.append(Component.literal(Long.toString(minutes)).withStyle(minStyle));
        out.append(Component.literal("min").withStyle(ChatFormatting.WHITE));
        return out;
    }

    private static MutableComponent rsvpChip(AnniSnapshot snapshot) {
        // Prefer explicit rsvp notice; fall back to attendance band as
        // implied notice ("walkin"/"late" come from there).
        AnniSnapshot.Rsvp rsvp = snapshot.rsvp();
        String notice = (rsvp != null && !rsvp.revoked()) ? rsvp.notice() : null;
        if (notice == null) {
            AnniSnapshot.Attendance att = snapshot.attendance();
            if (att != null) notice = att.noticeEffective();
        }

        String label;
        ChatFormatting color;
        if (notice == null) {
            label = "WALKIN";
            color = ChatFormatting.YELLOW;
        } else {
            switch (notice.toLowerCase()) {
                case "hard":
                    label = "HRSVP";
                    color = ChatFormatting.BLUE;
                    break;
                case "soft":
                    label = "SRSVP";
                    color = ChatFormatting.GREEN;
                    break;
                case "walkin":
                    label = "WALKIN";
                    color = ChatFormatting.YELLOW;
                    break;
                case "late":
                    label = "LATE";
                    color = ChatFormatting.RED;
                    break;
                default:
                    label = notice.toUpperCase();
                    color = ChatFormatting.GRAY;
            }
        }
        Style style = Style.EMPTY.withColor(color).withBold(true);
        style = FlashTracker.styleFor("rsvp", style);
        return Component.literal(label).withStyle(style);
    }

    private static MutableComponent slotChip(AnniSnapshot snapshot) {
        // CORE if any specific (non-FILL) role appears in registration;
        // FILL otherwise. Mirrors {@link #isAssigned} but capped to
        // the "seeking" path's needs.
        AnniSnapshot.Registration reg = snapshot.registration();
        boolean core = false;
        if (reg != null) {
            for (AnniSnapshot.RoleEntry r : reg.roles()) {
                if (r.role() != null && !"FILL".equalsIgnoreCase(r.role())) {
                    core = true;
                    break;
                }
            }
        }
        if (core) {
            return Component.literal("CORE SLOT")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN));
        }
        return Component.literal("FILL SLOT")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW));
    }

    // ── Role label / colour helpers ─────────────────────────────────────

    /** Role to display on the bar. Only the actually-assigned role
     *  ({@code board.role}, populated by vets-anni when the user is
     *  placed) counts — eligible roles from {@code registration.roles}
     *  are explicitly NOT a fallback. "Eligible TANK | HEALER" doesn't
     *  mean they'll tank; surfacing one would mislead. Until the user
     *  is placed, role is TBD. */
    private static String resolveRoleCode(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        if (board != null && board.role() != null && !board.role().isEmpty()) {
            return board.role();
        }
        return null;
    }

    /** Spec short codes (per feedback_anni_short_codes_reserved_for_bossbar):
     *  TANK / HEAL / SUNK / MOBK / PRIM / FILL. Maps the snapshot's
     *  canonical {@code SECONDARY/TERTIARY} / {@code HEALER} / etc. into
     *  the boss-bar abbreviations. */
    private static String toShortLabel(String roleCode) {
        switch (roleCode.toUpperCase()) {
            case "TANK":
                return "TANK";
            case "HEAL":
            case "HEALER":
                return "HEAL";
            case "PRIMARY":
            case "BOSSKILL":
            case "PRIM":
                return "PRIM";
            case "SECONDARY":
            case "SUNKILL":
            case "SUNK":
                return "SUNK";
            case "TERTIARY":
            case "MOBKILL":
            case "MOBK":
                return "MOBK";
            case "FILL":
                return "FILL";
            default:
                return roleCode.toUpperCase();
        }
    }

    /** Spec §3.1.1.2 mapping — TANK=&9, HEAL=&a, PRIM=&c, SUNK=&e,
     *  MOBK=&d, FILL=&3. Distinct from S4's outline colours. */
    private static ChatFormatting roleColor(String roleCode) {
        switch (roleCode.toUpperCase()) {
            case "TANK":
                return ChatFormatting.BLUE; // &9
            case "HEAL":
            case "HEALER":
                return ChatFormatting.GREEN; // &a
            case "PRIMARY":
            case "BOSSKILL":
            case "PRIM":
                return ChatFormatting.RED; // &c
            case "SECONDARY":
            case "SUNKILL":
            case "SUNK":
                return ChatFormatting.YELLOW; // &e
            case "TERTIARY":
            case "MOBKILL":
            case "MOBK":
                return ChatFormatting.LIGHT_PURPLE; // &d
            case "FILL":
                return ChatFormatting.DARK_AQUA; // &3
            default:
                return ChatFormatting.GRAY;
        }
    }

    private static String format3(long n) {
        if (n < 0) n = 0;
        if (n > 999) return Long.toString(n);
        return String.format("%03d", n);
    }
}
