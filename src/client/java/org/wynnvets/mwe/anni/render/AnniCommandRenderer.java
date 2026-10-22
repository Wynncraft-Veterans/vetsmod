package org.wynnvets.mwe.anni.render;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.mwe.anni.state.AnniSnapshot;

/**
 * Renderer for {@code /wv anni} (no args) when an
 * {@link AnniSnapshot} is available.
 *
 * <p>Implements the three branches from the integration spec:</p>
 * <ul>
 *   <li>§"If the next anni has not yet been announced" — show the
 *       {@code \guess}-style prediction line and, for a vets user, the
 *       registration block: the listed roles, a fill-only explainer, or
 *       a register-now nudge ({@link AnniOnboardingBlocks}). An external
 *       user gets the lean form: a red headline and the prediction line,
 *       with no anni.wynnvets.org link and no registration block.</li>
 *   <li>§"If the next anni has been announced and is 2+ hours in the
 *       future" — relative timestamp, then the status lines of
 *       {@link AnniStatusSections}: registration (or, in a party, the
 *       assigned role), RSVP widget, attendance chance (the attendance
 *       bar while unassigned) and party assignment; expands with party
 *       details when assigned.</li>
 *   <li>§"If the next anni has been announced and within the next 2h" —
 *       the 2h+ body again (minus the RSVP upgrade prompt, which
 *       {@code AnniStatusSections.rsvpSection} shows only more than two
 *       hours out), plus a [silent|passive|aggressive] mode-switch widget
 *       as its own second block.</li>
 * </ul>
 *
 * <p>While an announced anni's stamp is still ahead, an external user gets no render at all:
 * {@link #render} returns {@code null}, and its caller falls back to the legacy stamp text. Once
 * the stamp has passed, the snapshot takes the not-announced branch, external user or not. See
 * {@link #render} for the list-and-{@code null} contract.</p>
 *
 * <p><b>Not</b> a pure function, so one snapshot can render more than one way:</p>
 * <ul>
 *   <li>It reads the wall clock, in {@link #render} (whether the stamp is still ahead, the
 *       countdown and the two-hour split) and in {@code predictionLine} (how far off the median
 *       is).</li>
 *   <li>Whether the user is external comes from {@link #isExternal}. That reads the mutable
 *       {@link #externalOverride}, which the debug commands set and clear
 *       ({@code /wv debug tree anni external}, and each successful preset inject). With no
 *       override it asks
 *       {@link GuildStateManager#isEligibleForEnrichment}, which fans out to the guild check, the
 *       waitlist unlock and the honourary unlock; the guild check reads the clock too, since
 *       {@code GuildChecker}'s cached result expires. {@link AnniMotdRenderer} also calls
 *       {@code isExternal}, so the override steers the world-join anni-motd as well.</li>
 *   <li>It reads {@link VetsConfig} flags that {@code /wv config} can change mid-session.</li>
 * </ul>
 *
 * <p>The direct caller is {@code StampFetcher.fetchStampAndCreateAnniCommandMessage}, which
 * calls {@link #render} and hands the result back up to {@code CommandRegistry}; it is
 * <em>that</em> frame which dispatches each block through
 * {@link org.wynnvets.chat.ChatUtils ChatUtils}. {@code StampFetcher} does not reference
 * {@code ChatUtils} at all — naming it as this class's caller conflates the route with the
 * caller, which is the shape an earlier sweep corrected in five other facades. {@code render}
 * runs on the client thread when a snapshot is cached. On a cold cache it runs only if the
 * on-demand pull brings a snapshot back, and then on the thread that completes the pull
 * (normally the WebSocket reader).</p>
 */
public final class AnniCommandRenderer {

    /** Compact stamp used in the announced header — "14:00, Fri 19". */
    private static final DateTimeFormatter STAMP_FMT =
            DateTimeFormatter.ofPattern("HH:mm, EEE d").withZone(ZoneId.systemDefault());

    /** Day portion of the prediction date — "Jun 19". Abbreviated
     *  month (per latest user format). */
    private static final DateTimeFormatter MEDIAN_DATE_FMT =
            DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault());

    /** Time portion of the prediction date — "05:51". Split from the
     *  date so the renderer can punctuate them with a darker '@' chip. */
    private static final DateTimeFormatter MEDIAN_TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    // Package-private for AnniStatusSections, which gates the RSVP upgrade prompt on it.
    static final long TWO_HOURS_SECONDS = 2L * 60L * 60L;

    /** Debug-only override for {@link #isExternal(AnniSnapshot)}.
     *  {@code null} (default) → use the real rank-signal logic;
     *  {@code true} → force external rendering;
     *  {@code false} → force vets rendering. Settable from
     *  {@code /wv debug tree anni external …} — the {@code tree} literal
     *  is load-bearing, since {@code DebugCommands.buildCommandTree}
     *  mounts the whole anni subtree under it — and set again by each
     *  successful {@code /wv debug tree anni snapshot inject preset}:
     *  {@code true} for a preset whose name starts with
     *  {@code external}, {@code false} for {@code member}, and
     *  {@code null} for any other.
     *  Not persisted; resets on vetsmod reload. */
    private static volatile Boolean externalOverride = null;

    /** Debug override hook — see {@link #externalOverride}. */
    public static void setExternalOverride(Boolean override) {
        externalOverride = override;
    }

    private AnniCommandRenderer() {}

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
     *  line, then, for a vets user, the registration block: role chips,
     *  the fill-only explainer or the registration nudge.
     *
     *  External (non-vets) users get the lean form: a red "not yet
     *  announced" headline followed by the prediction line — no
     *  anni.wynnvets.org link (the dashboard is reserved for plausible
     *  vets-anni users) and no roles/board sections (they have nothing
     *  to manage there). */
    private static MutableComponent renderNotAnnounced(
            AnniSnapshot snapshot, AnniSnapshot.Event event) {
        boolean external = isExternal(snapshot);
        MutableComponent out = Component.literal("");

        if (external) {
            // Lean external form: red headline only — no anni.wynnvets.org link.
            out.append(
                    Component.literal("The next annihilation has not yet been announced!")
                            .withStyle(ChatFormatting.RED));
        } else {
            // Vets users keep the linked header so they can click through
            // to the dashboard to manage RSVP / registration.
            out.append(
                    header(
                            "anni.wynnvets.org",
                            ChatFormatting.DARK_AQUA,
                            "Next annihilation not yet announced."));
        }

        // Prediction line (gated by vetsAnniShowPrediction).
        boolean appendedPrediction = false;
        if (VetsConfig.get(VetsConfigKeys.VETS_ANNI_SHOW_PREDICTION)) {
            AnniSnapshot.Prediction prediction = event == null ? null : event.prediction();
            if (prediction != null
                    && prediction.earliestEpoch() != null
                    && prediction.medianEpoch() != null
                    && prediction.latestEpoch() != null) {
                out.append(Component.literal("\n"));
                out.append(predictionLine(prediction));
                appendedPrediction = true;
            } else {
                out.append(Component.literal("\n"));
                out.append(
                        line(
                                ChatFormatting.GRAY,
                                "Prediction window unavailable (probably a vetsmod API error)."));
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
            out.append(AnniOnboardingBlocks.vetsNotAnnouncedRegistrationBlock(snapshot));
        }
        return out;
    }

    /** "Prediction: 72h from now | Jun 19 @13:52±~3h".
     *
     *  <p>q0/q4 (earliest/latest) endpoints carry no useful information
     *  beyond what the median + uncertainty already convey, so they're
     *  dropped from the line — the {@code ~} prefix on the ± already
     *  disclaims that it's a rough figure.</p>
     *
     *  <p>The ± value is the <em>standard deviation</em> of the uniform
     *  prediction distribution, not the half-window:
     *  {@code σ = prediction.windowHours() / √12}. That is the
     *  "probably within ~Xh of q2" interpretation a reader expects from
     *  ±; the half-window would conflate "the rare tails" with the
     *  typical-deviation reading.</p>
     *
     *  <p>⚠️ The window is <b>read from the snapshot at render time</b>,
     *  so none of these figures is a constant. Worked example, from one
     *  observed Uniform(71.4h, 82.0h): window 10.6h → σ ≈ 3.06h, against
     *  a half-window of 5.3h. Treat it as an illustration of the
     *  arithmetic, not as the distribution — nothing pins these four
     *  numbers, and a different anchor moves all of them together.
     *  {@code AnniSnapshotDebugCommands} carries a matching σ comment that
     *  points back here.</p>
     *
     *  <p>Visual structure (per latest user format):</p>
     *  <ul>
     *    <li>Label, qualifier, time → gold</li>
     *    <li>Hours value, date → yellow</li>
     *    <li>White-bold pipe separator between countdown and date</li>
     *    <li>Uncertainty in italic gray, hugging the time</li>
     *  </ul> */
    // Package-private for unit tests. See AnniCommandRendererTest.
    static MutableComponent predictionLine(AnniSnapshot.Prediction prediction) {
        long median = prediction.medianEpoch();
        long now = Instant.now().getEpochSecond();
        long secondsUntil = median - now;
        double sigmaHours = prediction.windowHours() / Math.sqrt(12.0);

        String hoursStr = formatCoarseHours(Math.abs(secondsUntil));
        String qualifierStr = secondsUntil <= 0 ? " overdue " : " from now ";

        Instant medianInstant = Instant.ofEpochSecond(median);
        String dateStr = MEDIAN_DATE_FMT.format(medianInstant);
        String timeStr = MEDIAN_TIME_FMT.format(medianInstant);

        return AnniHoverBuilder.label("Prediction", ChatFormatting.GOLD)
                .append(Component.literal(hoursStr).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(qualifierStr).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("|").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Component.literal(" " + dateStr + " ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("@" + timeStr).withStyle(ChatFormatting.GOLD))
                .append(
                        Component.literal("±~" + formatUncertainty(sigmaHours))
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
     *  parenthetical is the only dark-gray <em>carrying content</em> we
     *  still allow, because the primary info (countdown) is in
     *  aqua-family colors above. The render's other {@code DARK_GRAY}
     *  text carries no content: the separators {@code " · "},
     *  {@code " | "} and {@code " — "}, and the attendance bar's
     *  brackets, which the rule was never about. */
    private static MutableComponent renderFarOut(
            AnniSnapshot snapshot, long secondsUntil, long stamp) {
        String stampStr = STAMP_FMT.format(Instant.ofEpochSecond(stamp));

        MutableComponent out = Component.literal("");
        out.append(
                AnniHoverBuilder.linkBadge(
                        "anni.wynnvets.org",
                        "https://anni.wynnvets.org",
                        "Open the anni dashboard",
                        ChatFormatting.AQUA));
        out.append(Component.literal(" returns in ").withStyle(ChatFormatting.DARK_AQUA));
        appendCountdown(out, secondsUntil);
        out.append(Component.literal(" (" + stampStr + ")").withStyle(ChatFormatting.DARK_GRAY));

        out.append(Component.literal("\n"));
        // Once a user is assigned to a party, "Eligible Roles" misleads
        // — what they care about is the specific role they're filling.
        // Surface that as "Assigned Role: …" instead. The Party
        // Assignment block below (AnniStatusSections.partyAssignedBlock)
        // skips its redundant Role sub-field in that case.
        AnniSnapshot.Board boardForRoleLine = snapshot.board();
        boolean inParty =
                boardForRoleLine != null && "party".equalsIgnoreCase(boardForRoleLine.state());
        if (inParty) {
            out.append(AnniStatusSections.assignedRoleSection(snapshot))
                    .append(Component.literal("\n"));
        } else {
            out.append(AnniStatusSections.registrationSection(snapshot))
                    .append(Component.literal("\n"));
        }
        out.append(AnniStatusSections.rsvpSection(snapshot, secondsUntil))
                .append(Component.literal("\n"));
        MutableComponent attendance = AnniStatusSections.attendanceSection(snapshot);
        if (attendance != null) {
            out.append(attendance).append(Component.literal("\n"));
        }
        out.append(AnniStatusSections.boardSection(snapshot));
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
    // Package-private for unit tests. See AnniCommandRendererTest.
    static void appendCountdown(MutableComponent out, long secondsUntil) {
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
            out.append(
                    Component.literal(String.format(Locale.ROOT, "%02d", seconds))
                            .withStyle(ChatFormatting.AQUA));
            out.append(Component.literal("s").withStyle(ChatFormatting.DARK_AQUA));
            return;
        }
        out.append(Component.literal(Long.toString(seconds)).withStyle(ChatFormatting.AQUA));
        out.append(Component.literal("s").withStyle(ChatFormatting.DARK_AQUA));
    }

    // ────────────────────────────────────────────────── within 2h

    /** Spec §"If the next anni has been announced and within the next 2h"
     *  — same body as {@link #renderFarOut} (assignment, attendance,
     *  RSVP, roles all keep showing — per user feedback the compressed
     *  status row was an unnecessary divergence), except that the RSVP
     *  line drops its upgrade prompt ({@code AnniStatusSections.rsvpSection}
     *  shows it only while {@code secondsUntil} is over two hours, though
     *  vets-anni still takes a hard or soft RSVP until T-90m), plus the
     *  mode-switch UI as its own {@code [VETSMOD]}-prefixed second block. The
     *  separately-badged "Change Anni Mode?" line is the user's main
     *  cue that they're within the hot window and can opt into the
     *  passive/aggressive in-game features. Vets-only; external users
     *  are routed by the {@link #render} dispatcher to the legacy
     *  stamp fallback. */
    private static List<MutableComponent> renderImminent(
            AnniSnapshot snapshot, long secondsUntil, long stamp) {
        return List.of(renderFarOut(snapshot, secondsUntil, stamp), renderModeSwitchBlock());
    }

    /** Standalone "Change Anni Mode?" block — header + three clickable
     *  buttons. Hover messages are intentionally short and audience-
     *  centric (no description of what each mode does mechanically —
     *  the user wants the chooser to read as "which audience are you?"
     *  rather than "which features do you want?"). */
    // Package-private for unit tests. See AnniCommandRendererTest.
    static MutableComponent renderModeSwitchBlock() {
        MutableComponent line1 =
                Component.literal("Change Anni Mode?")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                        .append(
                                Component.literal(" (Click:)")
                                        .withStyle(
                                                Style.EMPTY
                                                        .withColor(ChatFormatting.GRAY)
                                                        .withBold(false)));

        MutableComponent line2 = Component.literal("");
        line2.append(
                modeButton(
                        "Silent",
                        ChatFormatting.WHITE,
                        ChatFormatting.GRAY,
                        "/wv anni silent",
                        "Anni features off"));
        line2.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
        line2.append(
                modeButton(
                        "Passive",
                        ChatFormatting.GREEN,
                        ChatFormatting.DARK_GREEN,
                        "/wv anni passive",
                        "Anni features recommended for most folks experienced at attending vets-annis"));
        line2.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
        line2.append(
                modeButton(
                        "Aggressive",
                        ChatFormatting.RED,
                        ChatFormatting.DARK_RED,
                        "/wv anni aggressive",
                        "Anni features recommended for folks attending their first vets-anni"));

        return line1.append(Component.literal("\n")).append(line2);
    }

    /** One bracketed mode button — coloured label inside coloured
     *  brackets, click runs the command, hover shows the audience
     *  description. */
    private static MutableComponent modeButton(
            String label,
            ChatFormatting labelColor,
            ChatFormatting bracketColor,
            String command,
            String hover) {
        Style labelStyle =
                Style.EMPTY
                        .withColor(labelColor)
                        .withBold(false)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(
                                new HoverEvent.ShowText(
                                        Component.literal(hover).withStyle(ChatFormatting.GRAY)));
        Style bracketStyle = Style.EMPTY.withColor(bracketColor).withBold(false);
        return Component.literal("[")
                .withStyle(bracketStyle)
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

    // ────────────────────────────────────────────────── small helpers

    /** Build the leading "anni.wynnvets.org — <subtitle>" header.
     *
     *  Wraps in an empty-styled root so the {@link AnniHoverBuilder#linkBadge}'s
     *  {@code underlined=true} doesn't bleed through to every sibling
     *  appended downstream — children of a styled parent inherit
     *  underline/bold/italic flags whenever they don't set them
     *  explicitly, and the renderer chain hangs a lot of unstyled
     *  segments off this header. */
    private static MutableComponent header(
            String prefix, ChatFormatting prefixColor, String subtitle) {
        MutableComponent root = Component.literal("");
        root.append(
                AnniHoverBuilder.linkBadge(
                        prefix, "https://" + prefix, "Open " + prefix, prefixColor));
        root.append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY));
        root.append(Component.literal(subtitle).withStyle(ChatFormatting.WHITE));
        return root;
    }

    private static MutableComponent line(ChatFormatting color, String text) {
        return Component.literal(text).withStyle(color);
    }

    /** Coarse "Xh" / "Xm" countdown for the prediction line — minute
     *  granularity is noise when the underlying window is hours wide
     *  (10.6h in the worked example on {@link #predictionLine}),
     *  and the user has asked the prediction stay in hours regardless
     *  of magnitude (so a far-future stamp reads "60664h from now"
     *  rather than getting compressed into days). */
    // Package-private for unit tests. See AnniCommandRendererTest.
    static String formatCoarseHours(long seconds) {
        if (seconds <= 0) return "<1m";
        if (seconds < 3600) {
            long minutes = Math.max(1, seconds / 60);
            return minutes + "m";
        }
        long hours = Math.round(seconds / 3600.0);
        return hours + "h";
    }

    /** Standard-deviation uncertainty for the prediction line. Rounded
     *  to the nearest hour so a reading such as the worked example's
     *  σ ≈ 3.06h (see {@link #predictionLine}) drops cleanly into
     *  "±~3h" without trailing precision noise. Below half an hour it
     *  prints whole minutes instead (at least {@code 1min}), and zero
     *  or less prints {@code 0h}. */
    // Package-private for unit tests. See AnniCommandRendererTest.
    static String formatUncertainty(double hours) {
        if (hours <= 0) return "0h";
        long rounded = Math.round(hours);
        if (rounded >= 1) {
            return rounded + "h";
        }
        long minutes = Math.max(1, Math.round(hours * 60.0));
        return minutes + "min";
    }
}
