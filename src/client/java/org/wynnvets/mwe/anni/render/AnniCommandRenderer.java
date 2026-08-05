package org.wynnvets.mwe.anni.render;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import org.wynnvets.config.VetsConfig;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.mwe.anni.state.AnniSnapshot;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Renderer for {@code /wv anni} (no args) when an
 * {@link AnniSnapshot} is available.
 *
 * <p>Implements the three branches from the integration spec:</p>
 * <ul>
 *   <li>§"If the next anni has not yet been announced" — show the
 *       {@code \guess}-style prediction window, registration status,
 *       and either listed roles or a register-now link.</li>
 *   <li>§"If the next anni has been announced and is 2+ hours in the
 *       future" — relative timestamp, registration, RSVP widget, and
 *       attendance bar; expands with party details when assigned.</li>
 *   <li>§"If the next anni has been announced and within the next 2h" —
 *       the same body as the 2h+ branch, plus a [silent|passive|
 *       aggressive] mode-switch widget as its own second block.</li>
 * </ul>
 *
 * <p><b>Not</b> a pure function: it reads the wall clock, the mutable
 * {@link #externalOverride}, and live {@link GuildStateManager} state, so
 * one snapshot can render more than one way. Caller dispatches each
 * returned block through {@code ChatUtils} — see {@link #render} for the
 * list-and-{@code null} contract.</p>
 */
public final class AnniCommandRenderer {

    /** Compact stamp used in the announced header — "14:00, Fri 19". */
    private static final DateTimeFormatter STAMP_FMT =
            DateTimeFormatter.ofPattern("HH:mm, EEE d")
                    .withZone(ZoneId.systemDefault());

    /** Day portion of the prediction date — "Jun 19". Abbreviated
     *  month (per latest user format). */
    private static final DateTimeFormatter MEDIAN_DATE_FMT =
            DateTimeFormatter.ofPattern("MMM d")
                    .withZone(ZoneId.systemDefault());

    /** Time portion of the prediction date — "05:51". Split from the
     *  date so the renderer can punctuate them with a darker '@' chip. */
    private static final DateTimeFormatter MEDIAN_TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(ZoneId.systemDefault());

    private static final long TWO_HOURS_SECONDS = 2L * 60L * 60L;

    /** Debug-only override for {@link #isExternal(AnniSnapshot)}.
     *  {@code null} (default) → use the real rank-signal logic;
     *  {@code true} → force external rendering;
     *  {@code false} → force vets rendering. Settable from
     *  {@code /wv debug anni external …}. Not persisted; resets on
     *  vetsmod reload. */
    private static volatile Boolean externalOverride = null;

    /** Debug override hook — see {@link #externalOverride}. */
    public static void setExternalOverride(Boolean override) {
        externalOverride = override;
    }

    /** Read the current debug override. {@code null} == auto. */
    public static Boolean getExternalOverride() {
        return externalOverride;
    }

    private AnniCommandRenderer() {
    }

    /** Build the full /wv anni payload as one or more chat blocks.
     *
     *  <p>Each list element is rendered by the caller as its own
     *  {@code [VETSMOD]}-prefixed block (via separate
     *  {@code sendLocalMessageNewBlock} calls). The most common case
     *  is a single-element list; the within-2h "imminent" render
     *  returns two blocks so the mode-switch UI sits in its own
     *  prefixed block for visual emphasis.</p>
     *
     *  <p>Returns {@code null} (never an empty list) when the snapshot
     *  represents an external user with an announced anni — the
     *  dispatcher then falls back to the legacy stamp text (spec §"For
     *  external users": "keep the wv anni and anni-motd as is", and
     *  anni.wynnvets.org links are reserved for vets-anni users).</p> */
    public static List<MutableComponent> render(AnniSnapshot snapshot) {
        long now = Instant.now().getEpochSecond();
        AnniSnapshot.Event event = snapshot.event();
        Long stamp = event == null ? null : event.stampEpoch();
        boolean announced = event != null && event.announced() && stamp != null && stamp > now;
        long secondsUntil = announced ? stamp - now : -1L;

        if (!announced) {
            return List.of(renderNotAnnounced(snapshot, event));
        }
        if (isExternal(snapshot)) {
            return null;
        }
        if (secondsUntil > TWO_HOURS_SECONDS) {
            return List.of(renderFarOut(snapshot, secondsUntil, stamp));
        }
        return renderImminent(snapshot, secondsUntil, stamp);
    }

    // ────────────────────────────────────────────────── not announced

    /** Spec §"If the next anni has not yet been announced" — prediction
     *  window, registration status, role chips or registration nudge.
     *
     *  External (non-vets) users get the lean form: a red "not yet
     *  announced" headline followed by the prediction line — no
     *  anni.wynnvets.org link (the dashboard is reserved for plausible
     *  vets-anni users) and no roles/board sections (they have nothing
     *  to manage there). */
    private static MutableComponent renderNotAnnounced(AnniSnapshot snapshot,
                                                       AnniSnapshot.Event event) {
        boolean external = isExternal(snapshot);
        MutableComponent out = Component.literal("");

        if (external) {
            // Lean external form: red headline only — no anni.wynnvets.org link.
            out.append(Component.literal("Next annihilation is not yet announced!")
                    .withStyle(ChatFormatting.RED));
        } else {
            // Vets users keep the linked header so they can click through
            // to the dashboard to manage RSVP / registration.
            out.append(header("anni.wynnvets.org",
                    ChatFormatting.DARK_AQUA,
                    "Next annihilation is not yet announced."));
        }

        // Prediction line (gated by vetsAnniShowPrediction).
        boolean appendedPrediction = false;
        if (VetsConfig.get(VetsConfig.VETS_ANNI_SHOW_PREDICTION)) {
            AnniSnapshot.Prediction prediction =
                    event == null ? null : event.prediction();
            if (prediction != null && prediction.earliestEpoch() != null
                    && prediction.medianEpoch() != null
                    && prediction.latestEpoch() != null) {
                out.append(Component.literal("\n"));
                out.append(predictionLine(prediction));
                appendedPrediction = true;
            } else {
                out.append(Component.literal("\n"));
                out.append(line(ChatFormatting.GRAY,
                        "Prediction window unavailable — no recent anchor."));
                appendedPrediction = true;
            }
        }

        if (!external) {
            // Vets users see prediction + a registration block whose
            // shape depends on whether they're registered, fill-only,
            // or have specific role capabilities. We deliberately do
            // NOT surface the board state here: with no announced anni
            // there's no placement to talk about.
            if (appendedPrediction) out.append(Component.literal("\n"));
            out.append(vetsNotAnnouncedRegistrationBlock(snapshot));
        }
        return out;
    }

    /** The not-announced vets-user registration block — three shapes
     *  depending on the snapshot's {@code registration} field.
     *
     *  <ul>
     *    <li><b>Unregistered</b> — they have no AnniPlayer row /
     *        no role capabilities. Red prose nudging them to open
     *        anni.wynnvets.org/me.</li>
     *    <li><b>Fill-only</b> — registered but no specific role
     *        capabilities. Multi-line gold/grey explainer about how
     *        fill slots work + a click-through to claim a role.</li>
     *    <li><b>Has roles</b> — the existing role-chip line plus a
     *        dark-green affirmation pointing at the #annihilation
     *        Discord channel.</li>
     *  </ul> */
    private static MutableComponent vetsNotAnnouncedRegistrationBlock(AnniSnapshot snapshot) {
        AnniSnapshot.Registration reg = snapshot.registration();
        if (reg == null || !reg.registered()) {
            return unregisteredNudgeBlock();
        }
        if (reg.roles().isEmpty()) {
            return fillOnlyExplainerBlock();
        }
        MutableComponent line = label("Eligible Roles", ChatFormatting.GRAY);
        boolean first = true;
        for (AnniSnapshot.RoleEntry role : reg.roles()) {
            if (!first) {
                line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            }
            line.append(AnniHoverBuilder.roleChip(role));
            first = false;
        }
        line.append(Component.literal("\n"));
        line.append(allSetAffirmation());
        return line;
    }

    /** Dark-green italic affirmation appended after the role chips
     *  when the user is registered + has roles + no announced anni.
     *  Links to the #annihilation Discord channel. */
    private static MutableComponent allSetAffirmation() {
        Style base = Style.EMPTY
                .withColor(ChatFormatting.DARK_GREEN)
                .withItalic(true);
        // Link gets the Discord-channel dark-aqua to read as a channel
        // reference rather than blending into the dark-green affirmation.
        Style link = Style.EMPTY
                .withColor(ChatFormatting.DARK_AQUA)
                .withItalic(true)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(
                        "https://discord.com/channels/1313769181321236490/1339393368672702567")))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("Open #annihilation in Discord")
                                .withStyle(ChatFormatting.GRAY)));
        return Component.literal("You're all set for the next ").withStyle(base)
                .append(Component.literal("#Annihilation").withStyle(link));
    }

    /** Four-line prose for the registered-but-fill-only case. */
    private static MutableComponent fillOnlyExplainerBlock() {
        Style yellowBoldItalic = Style.EMPTY
                .withColor(ChatFormatting.YELLOW)
                .withBold(true)
                .withItalic(true);
        // Lines 2 and 3 explicitly clear bold so they don't inherit
        // the &l from the &e&l&o root line above. Spec is &6&o and
        // &#7e7e7e&o — italic, NOT bold.
        Style goldItalic = Style.EMPTY
                .withColor(ChatFormatting.GOLD)
                .withBold(false)
                .withItalic(true);
        Style goldItalicLink = goldItalic
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(
                        "https://www.wynnvets.org/docs/guild/anni/#fill-builds")));
        Style mediumGrayItalic = Style.EMPTY
                .withColor(TextColor.fromRgb(0x7E7E7E))
                .withBold(false)
                .withItalic(true);
        Style whiteBoldItalic = Style.EMPTY
                .withColor(ChatFormatting.WHITE)
                .withBold(true)
                .withItalic(true);
        Style aquaBoldItalicLink = Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withBold(true)
                .withItalic(true)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(
                        "https://anni.wynnvets.org/me")));

        MutableComponent out = Component.literal("You have not yet indicated any role capabilities!")
                .withStyle(yellowBoldItalic);
        out.append(Component.literal("\n"));
        out.append(Component.literal("We will try our best to slot you in a ")
                .withStyle(goldItalic));
        out.append(Component.literal("fill slot").withStyle(goldItalicLink));
        out.append(Component.literal(", but our capacity to do this is unfortunately limited!")
                .withStyle(goldItalic));
        out.append(Component.literal("\n"));
        out.append(Component.literal("In the likely event we are unable to offer enough "
                        + "fill slots, we will prioritise those who have attended the fewest Annis.")
                .withStyle(mediumGrayItalic));
        out.append(Component.literal("\n"));
        out.append(Component.literal("If you believe you are able to claim a role, please do so ")
                .withStyle(whiteBoldItalic));
        out.append(Component.literal("here").withStyle(aquaBoldItalicLink));
        out.append(Component.literal("!").withStyle(whiteBoldItalic));
        return out;
    }

    /** Two-line prose for a vets-tier user who has not opened
     *  anni.wynnvets.org/me at all. */
    private static MutableComponent unregisteredNudgeBlock() {
        Style redBold = Style.EMPTY
                .withColor(ChatFormatting.RED)
                .withBold(true);
        Style yellowBoldUnderlinedLink = Style.EMPTY
                .withColor(ChatFormatting.YELLOW)
                .withBold(true)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(
                        "https://anni.wynnvets.org/me")));
        // Both styles below explicitly clear bold so they don't inherit
        // the redBold flag from the root "You have not yet opened "
        // component — the user spec is &c! and &4&o, not &c&l! / &4&l&o.
        Style redPlain = Style.EMPTY
                .withColor(ChatFormatting.RED)
                .withBold(false);
        Style darkRedItalic = Style.EMPTY
                .withColor(ChatFormatting.DARK_RED)
                .withBold(false)
                .withItalic(true);

        MutableComponent out = Component.literal("You have not yet opened ")
                .withStyle(redBold);
        out.append(Component.literal("anni.wynnvets.org/me")
                .withStyle(yellowBoldUnderlinedLink));
        out.append(Component.literal("!").withStyle(redPlain));
        out.append(Component.literal("\n"));
        out.append(Component.literal(
                        "Doing so unlocks all sorts of features, and helps us organise vets guild annis!")
                .withStyle(darkRedItalic));
        return out;
    }

    /** "Prediction: 72h from now | Jun 19 @13:52±~3h".
     *
     *  <p>q0/q4 (earliest/latest) endpoints carry no useful information
     *  beyond what the median + uncertainty already convey, so they're
     *  dropped from the line — the {@code ~} prefix on the ± already
     *  disclaims that it's a rough figure.</p>
     *
     *  <p>The ± value is the <em>standard deviation</em> of the
     *  Uniform(71.4h, 82.0h) prediction distribution, not the half-
     *  window. σ = window/√12 ≈ 3.06h on a 10.6h window — i.e. the
     *  "probably within ~Xh of q2" interpretation a reader expects from
     *  ±. Half-window (5.3h) would conflate "the rare tails" with the
     *  typical-deviation reading.</p>
     *
     *  <p>Visual structure (per latest user format):</p>
     *  <ul>
     *    <li>Label, qualifier, time → gold</li>
     *    <li>Hours value, date → yellow</li>
     *    <li>White-bold pipe separator between countdown and date</li>
     *    <li>Uncertainty in italic gray, hugging the time</li>
     *  </ul> */
    private static MutableComponent predictionLine(AnniSnapshot.Prediction prediction) {
        long median = prediction.medianEpoch();
        long now = Instant.now().getEpochSecond();
        long secondsUntil = median - now;
        double sigmaHours = prediction.windowHours() / Math.sqrt(12.0);

        String hoursStr = formatCoarseHours(Math.abs(secondsUntil));
        String qualifierStr = secondsUntil <= 0 ? " overdue " : " from now ";

        Instant medianInstant = Instant.ofEpochSecond(median);
        String dateStr = MEDIAN_DATE_FMT.format(medianInstant);
        String timeStr = MEDIAN_TIME_FMT.format(medianInstant);

        return label("Prediction", ChatFormatting.GOLD)
                .append(Component.literal(hoursStr).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(qualifierStr).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("|")
                        .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Component.literal(" " + dateStr + " ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("@" + timeStr).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("±~" + formatUncertainty(sigmaHours))
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    // ────────────────────────────────────────────────── 2+ hours out

    /** Spec §"If the next anni has been announced and is 2+ hours in the
     *  future" — long-form. Vets-only; the {@link #render} dispatcher
     *  routes external users to {@code null} so the caller falls back
     *  to the legacy stamp message.
     *
     *  <p>Header form: {@code anni.wynnvets.org returns in 71h 59m
     *  (14:00, Fri 19)} with aqua/dark-aqua/dark-gray triplet styling
     *  per latest user format. Hour and minute numerics are aqua; the
     *  "h" / "m" qualifiers and "returns in" are dark aqua; the stamp
     *  parenthetical is the only dark-gray we still allow because the
     *  primary info (countdown) is in aqua-family colors above. */
    private static MutableComponent renderFarOut(AnniSnapshot snapshot,
                                                 long secondsUntil, long stamp) {
        String stampStr = STAMP_FMT.format(Instant.ofEpochSecond(stamp));

        MutableComponent out = Component.literal("");
        out.append(AnniHoverBuilder.linkBadge("anni.wynnvets.org",
                "https://anni.wynnvets.org",
                "Open the anni dashboard",
                ChatFormatting.AQUA));
        out.append(Component.literal(" returns in ").withStyle(ChatFormatting.DARK_AQUA));
        appendCountdown(out, secondsUntil);
        out.append(Component.literal(" (" + stampStr + ")")
                .withStyle(ChatFormatting.DARK_GRAY));

        out.append(Component.literal("\n"));
        // Once a user is assigned to a party, "Eligible Roles" misleads
        // — what they care about is the specific role they're filling.
        // Surface that as "Assigned Role: …" instead. The Party
        // Assignment block below skips its redundant Role sub-field in
        // that case.
        AnniSnapshot.Board boardForRoleLine = snapshot.board();
        boolean inParty = boardForRoleLine != null
                && "party".equalsIgnoreCase(boardForRoleLine.state());
        if (inParty) {
            out.append(assignedRoleSection(snapshot)).append(Component.literal("\n"));
        } else {
            out.append(registrationSection(snapshot)).append(Component.literal("\n"));
        }
        out.append(rsvpSection(snapshot, secondsUntil)).append(Component.literal("\n"));
        MutableComponent attendance = attendanceSection(snapshot);
        if (attendance != null) {
            out.append(attendance).append(Component.literal("\n"));
        }
        out.append(boardSection(snapshot, true));
        return out;
    }

    /** Append the countdown segment ("71h 59m" / "23m 04s" / "47s") to
     *  the header. Picks resolution by magnitude so a sub-minute window
     *  isn't rounded to "0h 1m" — at this timescale the user wants
     *  seconds, same as the S3 boss-bar countdown.
     *
     *  <ul>
     *    <li>≥ 1h → {@code Xh Ym} (current long-form)</li>
     *    <li>≥ 1m → {@code Xm Ys} with zero-padded seconds</li>
     *    <li>&lt; 1m → {@code Ys} (drops the redundant {@code 0m})</li>
     *  </ul>
     *
     *  Numerics aqua, qualifiers dark-aqua — matches the existing
     *  aqua/dark-aqua header pairing. */
    private static void appendCountdown(MutableComponent out, long secondsUntil) {
        long s = Math.max(0L, secondsUntil);
        long hours = s / 3600L;
        long minutes = (s % 3600L) / 60L;
        long seconds = s % 60L;

        if (hours > 0L) {
            out.append(Component.literal(Long.toString(hours)).withStyle(ChatFormatting.AQUA));
            out.append(Component.literal("h ").withStyle(ChatFormatting.DARK_AQUA));
            out.append(Component.literal(Long.toString(minutes)).withStyle(ChatFormatting.AQUA));
            out.append(Component.literal("m").withStyle(ChatFormatting.DARK_AQUA));
            return;
        }
        if (minutes > 0L) {
            out.append(Component.literal(Long.toString(minutes)).withStyle(ChatFormatting.AQUA));
            out.append(Component.literal("m ").withStyle(ChatFormatting.DARK_AQUA));
            out.append(Component.literal(String.format(Locale.ROOT, "%02d", seconds))
                    .withStyle(ChatFormatting.AQUA));
            out.append(Component.literal("s").withStyle(ChatFormatting.DARK_AQUA));
            return;
        }
        out.append(Component.literal(Long.toString(seconds)).withStyle(ChatFormatting.AQUA));
        out.append(Component.literal("s").withStyle(ChatFormatting.DARK_AQUA));
    }

    /** "Assigned Role: TANK" — used in {@link #renderFarOut} when the
     *  user is in a party. Replaces the registration line; the same
     *  role chip rendering as elsewhere (so the style/colour/click
     *  semantics match the role chips on the dashboard). */
    private static MutableComponent assignedRoleSection(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        String roleCode = board != null ? board.role() : null;
        if (roleCode == null && board != null && board.party() != null) {
            roleCode = findOwnRole(board.party(), snapshot.mcUuid());
        }
        MutableComponent line = label("Assigned Role", ChatFormatting.GRAY);
        if (roleCode != null) {
            line.append(roleChipFor(snapshot, roleCode));
        } else {
            line.append(Component.literal("TBD").withStyle(ChatFormatting.GRAY));
        }
        return line;
    }

    // ────────────────────────────────────────────────── within 2h

    /** Spec §"If the next anni has been announced and within the next 2h"
     *  — same body as {@link #renderFarOut} (assignment, attendance,
     *  RSVP, roles all keep showing — per user feedback the compressed
     *  status row was an unnecessary divergence), plus the mode-switch
     *  UI as its own {@code [VETSMOD]}-prefixed second block. The
     *  separately-badged "Change Anni Mode?" line is the user's main
     *  cue that they're within the hot window and can opt into the
     *  passive/aggressive in-game features. Vets-only; external users
     *  are routed by the {@link #render} dispatcher to the legacy
     *  stamp fallback. */
    private static List<MutableComponent> renderImminent(AnniSnapshot snapshot,
                                                         long secondsUntil, long stamp) {
        return List.of(
                renderFarOut(snapshot, secondsUntil, stamp),
                renderModeSwitchBlock());
    }

    /** Standalone "Change Anni Mode?" block — header + three clickable
     *  buttons. Hover messages are intentionally short and audience-
     *  centric (no description of what each mode does mechanically —
     *  the user wants the chooser to read as "which audience are you?"
     *  rather than "which features do you want?"). */
    private static MutableComponent renderModeSwitchBlock() {
        MutableComponent line1 = Component.literal("Change Anni Mode?")
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                .append(Component.literal(" (Click:)")
                        .withStyle(Style.EMPTY
                                .withColor(ChatFormatting.GRAY)
                                .withBold(false)));

        MutableComponent line2 = Component.literal("");
        line2.append(modeButton("Silent",
                ChatFormatting.WHITE, ChatFormatting.GRAY,
                "/wv anni silent",
                "Anni features off"));
        line2.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
        line2.append(modeButton("Passive",
                ChatFormatting.GREEN, ChatFormatting.DARK_GREEN,
                "/wv anni passive",
                "Anni features recommended for most folks attending a vets-anni"));
        line2.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
        line2.append(modeButton("Aggressive",
                ChatFormatting.RED, ChatFormatting.DARK_RED,
                "/wv anni aggressive",
                "Anni features recommended for folks attending their first vets-anni"));

        return line1.append(Component.literal("\n")).append(line2);
    }

    /** One bracketed mode button — coloured label inside coloured
     *  brackets, click runs the command, hover shows the audience
     *  description. */
    private static MutableComponent modeButton(String label,
                                               ChatFormatting labelColor,
                                               ChatFormatting bracketColor,
                                               String command,
                                               String hover) {
        Style labelStyle = Style.EMPTY
                .withColor(labelColor)
                .withBold(false)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal(hover).withStyle(ChatFormatting.GRAY)));
        Style bracketStyle = Style.EMPTY
                .withColor(bracketColor)
                .withBold(false);
        return Component.literal("[").withStyle(bracketStyle)
                .append(Component.literal(label).withStyle(labelStyle))
                .append(Component.literal("]").withStyle(bracketStyle));
    }

    /** True when the operator is an "external" user — not in any of
     *  the vets-tier rank buckets vetsmod tracks locally.
     *
     *  <p>The signals mirror
     *  {@link org.wynnvets.chat.OutboundDisplayHandler}'s bridge gate
     *  ({@code shouldDisplayMessages}) — the same set nazbot's
     *  {@code !enable unauth} mode lets talk through the bridge
     *  without a dazebot auth key. That's the right set here for the
     *  same reason: the whole point of the registration prose is to
     *  nudge people to open anni.wynnvets.org, and gating it on
     *  having already linked via {@code ~vetsmod} on Discord would
     *  skip exactly the users we want to catch.</p>
     *
     *  <p>Three trip-wires (any one → not external):</p>
     *  <ul>
     *    <li>{@link GuildStateManager#isReturners()} — in the
     *        Returners guild.</li>
     *    <li>{@link GuildStateManager#isGuildless()} +
     *        {@link GuildStateManager#isWaitlistUnlocked()} —
     *        guildless waitlist member.</li>
     *    <li>{@link GuildStateManager#isHonouraryUnlocked()} —
     *        honourary unlocked.</li>
     *  </ul> */
    static boolean isExternal(AnniSnapshot snapshot) {
        Boolean override = externalOverride;
        if (override != null) return override;
        return !GuildStateManager.isEligibleForEnrichment();
    }

    // ────────────────────────────────────────────────── section helpers

    /** Registration roles or "register now" nudge. */
    private static MutableComponent registrationSection(AnniSnapshot snapshot) {
        AnniSnapshot.Registration reg = snapshot.registration();
        if (reg == null || !reg.registered()) {
            return label("Eligible Roles", ChatFormatting.GRAY)
                    .append(Component.literal("not registered  ").withStyle(ChatFormatting.YELLOW))
                    .append(AnniHoverBuilder.linkBadge("[register]",
                            AnniHoverBuilder.DOCS_PREPARING,
                            "Open anni.wynnvets.org/me preparation guide",
                            ChatFormatting.AQUA));
        }
        if (reg.roles().isEmpty()) {
            return label("Eligible Roles", ChatFormatting.GRAY)
                    .append(Component.literal(AnniHoverBuilder.displayRole("FILL") + " only")
                            .withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" — fill role assumed; specialise on ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(AnniHoverBuilder.linkBadge("anni.wynnvets.org/me",
                            "https://anni.wynnvets.org/me",
                            "Open your roles dashboard",
                            ChatFormatting.AQUA));
        }
        MutableComponent line = label("Eligible Roles", ChatFormatting.GRAY);
        boolean first = true;
        for (AnniSnapshot.RoleEntry role : reg.roles()) {
            if (!first) {
                line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            }
            line.append(AnniHoverBuilder.roleChip(role));
            first = false;
        }
        return line;
    }

    /** RSVP badge + (when far-out + un-RSVP'd) a parenthetical with
     *  click-to-suggest [Hard] [Soft] upgrade buttons.
     *
     *  <p>The upgrade prompt is gated on {@code secondsUntil > 2h}: once
     *  inside the imminent window, the RSVP slot cutoffs have already
     *  passed and surfacing the buttons would only be misleading. The
     *  user-facing rule: "the prompt only shows up after RSVP type if
     *  it is t-2h+".</p> */
    private static MutableComponent rsvpSection(AnniSnapshot snapshot, long secondsUntil) {
        MutableComponent line = label("RSVP Type", ChatFormatting.GRAY)
                .append(AnniHoverBuilder.rsvpBadge(snapshot.rsvp(), snapshot.attendance()));

        boolean rsvped = snapshot.rsvp() != null && snapshot.rsvp().notice() != null
                && !snapshot.rsvp().revoked();
        boolean farOut = secondsUntil > TWO_HOURS_SECONDS;
        if (!rsvped && farOut) {
            line.append(Component.literal(" "));
            line.append(rsvpUpgradePrompt());
        }
        return line;
    }

    /** Build the parenthetical upgrade prompt:
     *  {@code &7(Buttons to upgrade your \rsvp: &3[&bHard&3] &2[&aSoft&2]&7)}.
     *
     *  <p>Each button's bracket+label is its own click-to-suggest
     *  surface; the {@code \rsvp} word inside the parenthetical is
     *  hoverable (no click) to explain the Discord equivalent.</p> */
    private static MutableComponent rsvpUpgradePrompt() {
        Style gray = Style.EMPTY.withColor(ChatFormatting.GRAY);

        Component rsvpWord = Component.literal("\\rsvp")
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.GRAY)
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                "\\rsvp is a Discord command in vetsfish's"
                                        + " #bot-commands channel.\n"
                                        + "It does the same thing as /wv anni rsvp"
                                        + " — entirely optional from in-game."))));

        MutableComponent out = Component.literal("").withStyle(gray);
        out.append(Component.literal("(Buttons to upgrade your ").withStyle(gray));
        out.append(rsvpWord);
        out.append(Component.literal(": ").withStyle(gray));
        out.append(rsvpUpgradeButton(
                "Hard",
                ChatFormatting.DARK_AQUA, ChatFormatting.AQUA,
                "/wv anni rsvp hard",
                "HRSVP — confirms you WILL be there.\n"
                        + "Requirement: arrive at least 20 mins before anni"
                        + " starts, or your slot may be reassigned to a walk-in.\n"
                        + "Click to suggest /wv anni rsvp hard."));
        out.append(Component.literal(" ").withStyle(gray));
        out.append(rsvpUpgradeButton(
                "Soft",
                ChatFormatting.DARK_GREEN, ChatFormatting.GREEN,
                "/wv anni rsvp soft",
                "SRSVP — confirms you'll PROBABLY be there.\n"
                        + "Requirement: arrive at least 40 mins before anni"
                        + " starts, or your slot may be reassigned.\n"
                        + "Click to suggest /wv anni rsvp soft."));
        out.append(Component.literal(")").withStyle(gray));
        return out;
    }

    /** One {@code [Label]} button — bracket colour, label colour, shared
     *  click + hover. Built as a parent literal with the click/hover on
     *  the parent so the click fires whether the user lands on the
     *  bracket or the label. */
    private static MutableComponent rsvpUpgradeButton(String label,
                                                      ChatFormatting bracketColor,
                                                      ChatFormatting labelColor,
                                                      String command,
                                                      String hoverText) {
        Style parentStyle = Style.EMPTY
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal(hoverText).withStyle(ChatFormatting.GRAY)));
        MutableComponent btn = Component.literal("").withStyle(parentStyle);
        btn.append(Component.literal("[").withStyle(bracketColor));
        btn.append(Component.literal(label).withStyle(labelColor));
        btn.append(Component.literal("]").withStyle(bracketColor));
        return btn;
    }

    /** Attendance chance line — only meaningful when the user is in
     *  the unassigned bucket (where their attendance is genuinely
     *  uncertain). Two special cases substitute a definitive label:
     *  party → "ASSIGNED" (light purple), wont_assign → "COULD NOT
     *  ASSIGN" (red). Other states (unplaced, unknown) return
     *  {@code null} so the caller drops the line entirely. */
    private static MutableComponent attendanceSection(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        String state = board == null || board.state() == null
                ? null : board.state().toLowerCase();
        if (state == null) return null;
        switch (state) {
            case "party":
                return label("Attendance Chance", ChatFormatting.GRAY)
                        .append(Component.literal("ASSIGNED")
                                .withStyle(ChatFormatting.LIGHT_PURPLE,
                                        ChatFormatting.BOLD));
            case "wont_assign":
                return label("Attendance Chance", ChatFormatting.GRAY)
                        .append(Component.literal("COULD NOT ASSIGN")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            case "unassigned":
                return label("Attendance Chance", ChatFormatting.GRAY)
                        .append(AnniHoverBuilder.attendanceBar(snapshot.attendance()));
            default:
                return null;
        }
    }

    /** Board state — different forms for "unplaced", "unassigned",
     *  "wont_assign", "party". */
    private static MutableComponent boardSection(AnniSnapshot snapshot,
                                                 boolean partyDetails) {
        AnniSnapshot.Board board = snapshot.board();
        if (board == null || board.state() == null) {
            return Component.literal("");
        }
        String state = board.state().toLowerCase();
        switch (state) {
            case "unplaced":
                return label("Party Assignment", ChatFormatting.GRAY)
                        .append(Component.literal("unplaced")
                                .withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(
                                " — register & RSVP to join the placement queue")
                                .withStyle(ChatFormatting.GRAY));
            case "wont_assign":
                MutableComponent wont = label("Party Assignment", ChatFormatting.GRAY)
                        .append(Component.literal("won't assign")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                if (board.wontReason() != null && !board.wontReason().isEmpty()) {
                    wont.append(Component.literal(" — " + board.wontReason())
                            .withStyle(ChatFormatting.GRAY));
                }
                return wont;
            case "unassigned":
                // Don't restate the RSVP — the RSVP line right above
                // already shows HRSVP/SRSVP. Just say what board state
                // means for placement.
                return label("Party Assignment", ChatFormatting.GRAY)
                        .append(Component.literal("unassigned")
                                .withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal("Awaiting placement")
                                .withStyle(ChatFormatting.GRAY));
            case "party":
                return partyDetails ? partyAssignedBlock(snapshot)
                                    : partyOneLine(board);
            default:
                return label("Party Assignment", ChatFormatting.GRAY)
                        .append(Component.literal(state).withStyle(ChatFormatting.GRAY));
        }
    }

    /** Multi-line party assignment block. */
    private static MutableComponent partyAssignedBlock(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        AnniSnapshot.Party party = board == null ? null : board.party();
        if (party == null) {
            return label("Party Assignment", ChatFormatting.GRAY)
                    .append(Component.literal("party (details pending)")
                            .withStyle(ChatFormatting.GRAY));
        }
        // Role lives on the "Assigned Role" line above this block —
        // see renderFarOut. Don't repeat it here.
        MutableComponent line = label("Party", ChatFormatting.GRAY)
                .append(AnniHoverBuilder.partyOrdinalChip(party))
                .append(Component.literal("  "));

        line.append(label("World", ChatFormatting.GRAY))
                .append(AnniHoverBuilder.partyWorldChip(party.world()));

        if (party.host() != null && party.host().username() != null) {
            line.append(Component.literal("\n"));
            line.append(label("Host", ChatFormatting.GRAY))
                    .append(Component.literal(party.host().username())
                            .withStyle(ChatFormatting.AQUA));
        }
        return line;
    }

    /** Single-line party blurb for the not-announced renderer. */
    private static MutableComponent partyOneLine(AnniSnapshot.Board board) {
        AnniSnapshot.Party party = board == null ? null : board.party();
        if (party == null) {
            return label("Party Assignment", ChatFormatting.GRAY)
                    .append(Component.literal("party").withStyle(ChatFormatting.GREEN));
        }
        MutableComponent line = label("Party Assignment", ChatFormatting.GRAY);
        line.append(AnniHoverBuilder.partyOrdinalChip(party));
        line.append(Component.literal("  "));
        line.append(AnniHoverBuilder.partyWorldChip(party.world()));
        return line;
    }

    // ────────────────────────────────────────────────── small helpers

    /** Build the leading "anni.wynnvets.org — <subtitle>" header.
     *
     *  Wraps in an empty-styled root so the {@link AnniHoverBuilder#linkBadge}'s
     *  {@code underlined=true} doesn't bleed through to every sibling
     *  appended downstream — children of a styled parent inherit
     *  underline/bold/italic flags whenever they don't set them
     *  explicitly, and the renderer chain hangs a lot of unstyled
     *  segments off this header. */
    private static MutableComponent header(String prefix,
                                           ChatFormatting prefixColor,
                                           String subtitle) {
        MutableComponent root = Component.literal("");
        root.append(AnniHoverBuilder.linkBadge(prefix, "https://" + prefix,
                "Open " + prefix, prefixColor));
        root.append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY));
        root.append(Component.literal(subtitle).withStyle(ChatFormatting.WHITE));
        return root;
    }

    private static MutableComponent label(String text, ChatFormatting color) {
        return Component.literal(text + ": ").withStyle(color);
    }

    private static MutableComponent line(ChatFormatting color, String text) {
        return Component.literal(text).withStyle(color);
    }

    /** Resolve the role-code for the local player from the party member
     *  list when {@code board.role} is missing. */
    private static String findOwnRole(AnniSnapshot.Party party, String mcUuid) {
        if (mcUuid == null || party.members() == null) return null;
        for (AnniSnapshot.MemberRef m : party.members()) {
            if (mcUuid.equalsIgnoreCase(m.uuid())) {
                return m.role();
            }
        }
        return null;
    }

    /** Build a role chip for a given role-code, preferring the snapshot's
     *  registration entry (which carries the docs URL + hover title) when
     *  available; otherwise falls back to the role code + generic
     *  event-gameplay anchor. */
    private static MutableComponent roleChipFor(AnniSnapshot snapshot, String roleCode) {
        AnniSnapshot.Registration reg = snapshot.registration();
        if (reg != null) {
            for (AnniSnapshot.RoleEntry r : reg.roles()) {
                if (r.role() != null && r.role().equalsIgnoreCase(roleCode)) {
                    return AnniHoverBuilder.roleChip(r);
                }
            }
        }
        return AnniHoverBuilder.roleChip(roleCode, null, null);
    }

    /** Coarse "Xh" / "Xm" countdown for the prediction line — minute
     *  granularity is noise when the underlying window is ~10h wide,
     *  and the user has asked the prediction stay in hours regardless
     *  of magnitude (so a far-future stamp reads "60664h from now"
     *  rather than getting compressed into days). */
    private static String formatCoarseHours(long seconds) {
        if (seconds <= 0) return "<1m";
        if (seconds < 3600) {
            long minutes = Math.max(1, seconds / 60);
            return minutes + "m";
        }
        long hours = Math.round(seconds / 3600.0);
        return hours + "h";
    }

    /** Standard-deviation uncertainty for the prediction line. Rounded
     *  to whole hours so the typical ~3h reading drops cleanly into
     *  "±~3h" without trailing precision noise. */
    private static String formatUncertainty(double hours) {
        if (hours <= 0) return "0h";
        long rounded = Math.round(hours);
        if (rounded >= 1) {
            return rounded + "h";
        }
        long minutes = Math.max(1, Math.round(hours * 60.0));
        return minutes + "min";
    }
}
