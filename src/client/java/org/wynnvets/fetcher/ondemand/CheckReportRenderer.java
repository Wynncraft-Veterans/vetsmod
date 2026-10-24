package org.wynnvets.fetcher.ondemand;

import com.wynntils.utils.mc.McUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.Prepend;
import org.wynnvets.datamodels.Guild;
import org.wynnvets.datamodels.MembershipSnapshot;
import org.wynnvets.datamodels.User;
import org.wynnvets.datamodels.UserUUID;
import org.wynnvets.guild.GuildStateManager;

/**
 * Renders the {@code /wv check} readout as a multi-line block in the local chat HUD. Each
 * logical line is dispatched via its own {@link ChatUtils#sendLocalMessage} call so the
 * {@link Prepend#DEFAULT} dedup auto-demotes the badge to the compact
 * block-indicator on continuation lines.
 *
 * <p>No network: besides its arguments it reads the tab list, the local player, guild state and
 * the clock, and it writes chat and {@link Prepend}'s dedup state.</p>
 */
final class CheckReportRenderer {
    private static final Style LABEL_STYLE = Style.EMPTY.withColor(ChatFormatting.GRAY);
    private static final Style VALUE_STYLE = Style.EMPTY.withColor(ChatFormatting.AQUA);
    private static final Style MUTED_STYLE = Style.EMPTY.withColor(ChatFormatting.DARK_GRAY);
    private static final Style HIDDEN_STYLE = Style.EMPTY.withColor(ChatFormatting.YELLOW);

    private CheckReportRenderer() {}

    // ── Rendering ──────────────────────────────────────────────────────

    static void renderError(String message) {
        McUtils.mc()
                .execute(
                        () ->
                                ChatUtils.sendLocalMessage(
                                        Component.literal(message).withStyle(ChatFormatting.RED)));
    }

    static void renderCheck(
            String requestedName,
            UserUUID uuid,
            User user,
            ReturnersRosterProbe.ReturnersMembership returners,
            // The roster answer for the guild the payload names when that is not Returners;
            // null when none was asked.
            ReturnersRosterProbe.ReturnersMembership otherGuild,
            MembershipSnapshot snapshot) {
        // Mark the start of a new render block so the first line re-extends
        // the badge — this is what gives staff a visual divider between two
        // back-to-back /wv check responses (and between this snapshot block
        // and the preceding caution-history block, which arrive on separate
        // WS frames).
        Prepend.resetDedup();

        String displayName =
                (user != null && user.getUsername() != null) ? user.getUsername() : requestedName;

        // Tab list is our freshest signal — local Minecraft state, no HTTP
        // round-trip, no CDN cache. It only covers the local player's guild, and
        // only as many online members as the tab list's guild column holds (see
        // TabListGuildParser), so a miss is normal — for cross-guild targets and
        // in a busy guild alike; a hit is authoritative. Cheap O(N) scan over
        // <100 entries, always run.
        String tabListServer = OnlineMemberService.getCurrentServer(displayName);

        // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
        // Reason/Remove: see the first workaround block in this class.
        // Same-tick evidence the target is in Returners: they appear in the
        // local player's guild tab bucket AND the local player is in
        // Returners. Mirrors the guard OnlineMemberService.gatherOnlinePlayers
        // uses before forwarding the tab list (avoids treating a non-Returners
        // local guild's tab hit as Returners confirmation).
        boolean tabListConfirmsReturners = tabListServer != null && GuildStateManager.isReturners();
        // <<< PATCH END: WYNN-STALE-WORKAROUND

        // Self-check: the local player is treated as online, since they are
        // the one running /wv check (normally from Wynncraft; the command does
        // not check which server the client is on). Avoids the "Wynn's cached
        // online=false for the local player" trap.
        String localName = (McUtils.player() != null) ? McUtils.playerName() : null;
        boolean isSelf = localName != null && localName.equalsIgnoreCase(displayName);

        // Item 0: username + UUID
        ChatUtils.sendLocalMessage(
                Component.literal(displayName)
                        .withStyle(VALUE_STYLE)
                        .append(Component.literal("  (" + uuid.id + ")").setStyle(MUTED_STYLE)));

        // Item 0a: legacy name (only if Returners roster gave us one)
        if (returners != null
                && returners.legacyName() != null
                && !returners.legacyName().equalsIgnoreCase(displayName)) {
            ChatUtils.sendLocalMessage(
                    label("Legacy name: ")
                            .append(
                                    Component.literal(returners.legacyName())
                                            .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))));
        }

        if (user == null) {
            // The profile items (first seen, last seen, playtime, online) collapse
            // into this one line when there's no Wynn profile; the guild and
            // veteran lines below still render, each with its own placeholder.
            ChatUtils.sendLocalMessage(
                    label("Wynncraft profile: ")
                            .append(
                                    Component.literal("(no Wynncraft data)")
                                            .setStyle(HIDDEN_STYLE)));
        } else {
            renderWynnLines(user, snapshot, tabListServer, isSelf);
        }

        GuildEvidence evidence = new GuildEvidence(returners, otherGuild, tabListConfirmsReturners);
        ChatUtils.sendLocalMessage(guildLine(user, evidence, snapshot));
        renderVeteranLine(user);
        renderBlocklistLine(snapshot);
        ChatUtils.sendLocalMessage(discordLinkLine(snapshot, user, evidence));
        renderStage2Line(snapshot);
        renderCultLine(snapshot);
    }

    private static void renderWynnLines(
            User user, MembershipSnapshot snapshot, String tabListServer, boolean isSelf) {
        // Both join times are cut to the viewer's local date and labelled against the viewer's
        // own today, so the label and the parenthetical agree with the viewer's calendar.
        // A time that does not parse as an instant falls back to the API's own date text,
        // labelled against today.
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);

        // Item 1: first seen. Show coarse "N years ago" as the headline value
        // (raw days are unreadable for old accounts) with the ISO date as the
        // muted parenthetical.
        String firstJoin = user.getFirstJoinDate();
        if (firstJoin == null) {
            ChatUtils.sendLocalMessage(label("First seen on Wynncraft: ").append(hidden()));
        } else {
            Instant firstInstant = user.getFirstJoinInstant();
            LocalDate firstDate =
                    firstInstant == null ? null : TimeLabels.toLocalDate(firstInstant, zone);
            String firstLabel =
                    firstDate == null
                            ? TimeLabels.toCoarseRelativeDateLabel(firstJoin)
                            : TimeLabels.toCoarseRelativeDateLabel(firstDate, today);
            String firstShown = firstDate == null ? firstJoin : firstDate.toString();
            ChatUtils.sendLocalMessage(
                    label("First seen on Wynncraft: ")
                            .append(Component.literal(firstLabel).setStyle(VALUE_STYLE))
                            .append(
                                    Component.literal("  (" + firstShown + ")")
                                            .setStyle(MUTED_STYLE)));
        }

        // Item 2: last seen. When the Wynn API hides lastJoin (privacy on), fall
        // back to self, then the tab list, then dazebot's last_seen_observed
        // best guess (several dazebot paths keep it current); `(hidden)` only
        // when we have nothing at all. Today the dazebot fallback never fires
        // because temporary-server drops last_seen_observed (see
        // check-membership-ack-drops-last-seen-observed).
        String lastJoin = user.getLastJoinDate();
        if (lastJoin != null) {
            Instant lastInstant = user.getLastJoinInstant();
            LocalDate lastDate =
                    lastInstant == null ? null : TimeLabels.toLocalDate(lastInstant, zone);
            String lastLabel =
                    lastDate == null
                            ? TimeLabels.toRelativeDateLabel(lastJoin)
                            : TimeLabels.toRelativeDateLabel(lastDate, today);
            String lastShown = lastDate == null ? lastJoin : lastDate.toString();
            ChatUtils.sendLocalMessage(
                    label("Last seen on Wynncraft: ")
                            .append(Component.literal(lastLabel).setStyle(VALUE_STYLE))
                            .append(
                                    Component.literal("  (" + lastShown + ")")
                                            .setStyle(MUTED_STYLE)));
        } else {
            renderLastSeenFallback(snapshot, tabListServer, isSelf);
        }

        // Item 3: playtime
        Float playtime = user.getPlaytime();
        if (playtime == null) {
            ChatUtils.sendLocalMessage(label("Playtime: ").append(hidden()));
        } else {
            ChatUtils.sendLocalMessage(
                    label("Playtime: ")
                            .append(
                                    Component.literal(formatHours(playtime) + " hours")
                                            .setStyle(VALUE_STYLE)));
        }

        // Item 3b: currently online. Surfaced between playtime and guild because
        // it's the most actionable signal for a staff vetting decision.
        renderOnlineLine(user, snapshot, tabListServer, isSelf);
    }

    private static void renderLastSeenFallback(
            MembershipSnapshot snapshot, String tabListServer, boolean isSelf) {
        MutableComponent line = label("Last seen on Wynncraft: ");
        if (isSelf) {
            line.append(Component.literal("now").setStyle(HIDDEN_STYLE))
                    .append(Component.literal("  (self)").setStyle(MUTED_STYLE));
            ChatUtils.sendLocalMessage(line);
            return;
        }
        if (tabListServer != null) {
            // Tab list is "right now" by construction.
            line.append(Component.literal("now").setStyle(HIDDEN_STYLE))
                    .append(
                            Component.literal("  (tab-list: " + tabListServer + ")")
                                    .setStyle(MUTED_STYLE));
            ChatUtils.sendLocalMessage(line);
            return;
        }
        MembershipSnapshot.LastSeenObserved obs =
                snapshot != null ? snapshot.getLastSeenObserved() : null;
        if (obs != null && obs.getTs() > 0) {
            long ageSec = Instant.now().getEpochSecond() - obs.getTs();
            line.append(
                            Component.literal(TimeLabels.humanizeAge(ageSec) + " ago")
                                    .setStyle(HIDDEN_STYLE))
                    .append(
                            Component.literal("  (best guess - server-change observed")
                                    .setStyle(MUTED_STYLE));
            if (obs.getServerAtObservation() != null && !obs.getServerAtObservation().isEmpty()) {
                line.append(
                        Component.literal(" on " + obs.getServerAtObservation())
                                .setStyle(MUTED_STYLE));
            }
            line.append(Component.literal(")").setStyle(MUTED_STYLE));
        } else {
            line.append(hidden());
        }
        ChatUtils.sendLocalMessage(line);
    }

    private static void renderOnlineLine(
            User user, MembershipSnapshot snapshot, String tabListServer, boolean isSelf) {
        MutableComponent line = label("Currently online: ");

        // Self is treated as online: the local player is the one running /wv
        // check, normally from Wynncraft (the command does not check which
        // server the client is on).
        if (isSelf) {
            line.append(
                    Component.literal("yes").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
            if (tabListServer != null) {
                line.append(Component.literal("  (" + tabListServer + ")").setStyle(MUTED_STYLE));
            } else {
                line.append(Component.literal("  (self)").setStyle(MUTED_STYLE));
            }
            ChatUtils.sendLocalMessage(line);
            return;
        }

        // Tab list beats Wynn's /v3/player.online (the latter is CDN-cached
        // for up to 2 min and routinely says "false" for a player who joined
        // seconds ago — matches the /wv list behaviour staff are used to).
        if (tabListServer != null) {
            line.append(
                            Component.literal("yes")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)))
                    .append(
                            Component.literal("  (" + tabListServer + ", tab-list)")
                                    .setStyle(MUTED_STYLE));
            ChatUtils.sendLocalMessage(line);
            return;
        }

        Boolean wynnOnline = user != null ? user.getOnline() : null;
        String wynnServer = user != null ? user.getServer() : null;

        if (wynnOnline != null) {
            if (wynnOnline) {
                line.append(
                        Component.literal("yes")
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
                if (wynnServer != null && !wynnServer.isEmpty()) {
                    line.append(Component.literal("  (" + wynnServer + ")").setStyle(MUTED_STYLE));
                }
            } else {
                line.append(Component.literal("no").setStyle(LABEL_STYLE));
            }
            ChatUtils.sendLocalMessage(line);
            return;
        }

        // Wynn hidden — try the last_seen_observed best guess (today never
        // present; see check-membership-ack-drops-last-seen-observed).
        MembershipSnapshot.LastSeenObserved obs =
                snapshot != null ? snapshot.getLastSeenObserved() : null;
        if (obs != null && obs.getTs() > 0) {
            long ageSec = Instant.now().getEpochSecond() - obs.getTs();
            if (ageSec >= 0 && ageSec < 600) {
                line.append(
                        Component.literal("~" + TimeLabels.humanizeAge(ageSec) + " ago")
                                .setStyle(HIDDEN_STYLE));
                if (obs.getServerAtObservation() != null
                        && !obs.getServerAtObservation().isEmpty()) {
                    line.append(
                            Component.literal(
                                            "  (" + obs.getServerAtObservation() + ", best guess)")
                                    .setStyle(MUTED_STYLE));
                }
                ChatUtils.sendLocalMessage(line);
                return;
            }
        }
        line.append(Component.literal("(unknown)").setStyle(HIDDEN_STYLE));
        ChatUtils.sendLocalMessage(line);
    }

    private static String formatHours(float hours) {
        // Show one decimal place; users care about the order of magnitude.
        return String.format(Locale.ROOT, "%,.1f", hours);
    }

    /**
     * The Guild line. A guild named by Wynncraft's payload is shown only as far as a roster
     * backs it: a roster that lists the target gives the rank (the roster's, never the payload's,
     * which can lag); one that answered "not a member" gives "guildless" with a catching-up note;
     * one that could not be read leaves the payload's guild with a yellow "(cached)" tail and no
     * rank. The tab list also vouches for Returners. See {@link GuildEvidence}.
     */
    // Package-private for unit tests. See CheckReportRendererTest.
    static MutableComponent guildLine(
            User user, GuildEvidence evidence, MembershipSnapshot snapshot) {
        MutableComponent line = label("Guild: ");

        if (user == null) {
            return line.append(Component.literal("(no Wynncraft profile)").setStyle(HIDDEN_STYLE));
        }

        Guild guild = user.getGuild();
        ReturnersRosterProbe.ReturnersMembership returners = evidence.returners();
        ReturnersRosterProbe.ReturnersMembership otherGuild = evidence.otherGuild();

        // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
        // Reason/Remove: see the first workaround block in this class.
        // Any single fresh signal is enough to render Returners: the roster
        // (invalidated on officer-write) OR the local tab list (Minecraft
        // client state, same-tick). Catches the rejoin case where the payload
        // briefly names a non-Returners guild but the target is provably back
        // in Returners.
        if (evidence.confirmsReturners()) {
            line.append(Component.literal("Returners").setStyle(VALUE_STYLE));
            String rank = returners.rank();
            // Suppressed original fallback: `if (rank == null && guild != null) rank =
            // guild.getRank();`
            // — guild.getRank() from the stale payload can silently re-introduce
            // staleness (payload can claim RECRUITER for a former member who was
            // already kicked). Roster-only until upstream fixes invalidation.
            if (rank != null && !rank.isEmpty()) {
                line.append(rankTail(rank));
            }
            return line;
        }

        // The payload names a guild and nothing above confirmed Returners. Every branch is
        // roster-rank-only: a roster that lists the target supplies the rank; one that answered
        // "not a member" means the payload is behind, so "guildless" plus a hint rather than the
        // misleading stale value; one that failed to answer leaves the payload's guild as the
        // best available fact, marked "(cached)", with no rank.
        if (user.isInVets()) {
            if (returners.verdict() == ReturnersRosterProbe.RosterVerdict.NOT_IN_ROSTER) {
                return line.append(catchingUpTail());
            }
            return line.append(Component.literal("Returners").setStyle(VALUE_STYLE))
                    .append(cachedTail());
        }

        if (guild != null && guild.getName() != null) {
            // A non-Returners guild. otherGuild is null only for a payload guild named
            // Returners without Returners' UUID, which no roster was asked about.
            if (otherGuild != null
                    && otherGuild.verdict() == ReturnersRosterProbe.RosterVerdict.NOT_IN_ROSTER) {
                return line.append(catchingUpTail());
            }
            line.append(Component.literal(guild.getName()).setStyle(VALUE_STYLE));
            if (guild.getPrefix() != null && !guild.getPrefix().isEmpty()) {
                line.append(Component.literal("  [").setStyle(LABEL_STYLE))
                        .append(
                                Component.literal(guild.getPrefix())
                                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)))
                        .append(Component.literal("]").setStyle(LABEL_STYLE));
            }
            if (otherGuild != null) {
                if (otherGuild.verdict() == ReturnersRosterProbe.RosterVerdict.IN_ROSTER) {
                    String rank = otherGuild.rank();
                    if (rank != null && !rank.isEmpty()) {
                        line.append(rankTail(rank));
                    }
                } else {
                    line.append(cachedTail());
                }
            }
            return line;
        }
        // <<< PATCH END: WYNN-STALE-WORKAROUND

        // Player is guildless according to Wynn — annotate with dazebot's
        // membership state so we surface "guildless but on the vets roster".
        MembershipSnapshot.Discord disc = snapshot != null ? snapshot.getDiscord() : null;
        String tier = disc != null ? disc.getTier() : null;
        boolean hiatus = disc != null && disc.isHiatus();
        if ("member".equals(tier)) {
            line.append(Component.literal("guildless").setStyle(HIDDEN_STYLE))
                    .append(
                            Component.literal("  -  appears in vets roster (cached)")
                                    .setStyle(HIDDEN_STYLE));
        } else if ("waitlist".equals(tier)) {
            line.append(Component.literal("guildless").setStyle(VALUE_STYLE))
                    .append(Component.literal("  -  on waitlist").setStyle(VALUE_STYLE));
        } else if ("honourary".equals(tier)) {
            line.append(Component.literal("guildless").setStyle(VALUE_STYLE))
                    .append(Component.literal("  -  honourary").setStyle(VALUE_STYLE));
        } else if (hiatus) {
            line.append(
                            Component.literal("guildless")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)))
                    .append(
                            Component.literal("  -  hiatus")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)));
        } else {
            line.append(Component.literal("guildless").setStyle(LABEL_STYLE));
        }
        return line;
    }

    private static MutableComponent rankTail(String rank) {
        return Component.literal("  -  ")
                .setStyle(LABEL_STYLE)
                .append(
                        Component.literal(rank)
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)));
    }

    private static MutableComponent catchingUpTail() {
        return Component.literal("guildless")
                .setStyle(HIDDEN_STYLE)
                .append(
                        Component.literal("  -  Wynncraft profile still catching up")
                                .setStyle(HIDDEN_STYLE));
    }

    /** The roster call failed, so the payload's guild stands unverified. */
    private static MutableComponent cachedTail() {
        return Component.literal("  (cached)").setStyle(HIDDEN_STYLE);
    }

    private static void renderVeteranLine(User user) {
        // Only an explicit `veteran: true` counts. What Wynncraft sends for
        // non-vets isn't pinned here — absent, null and false are all possible
        // shapes (Wynntils' own parser allows for null) — and absent and null
        // both arrive as null. Treat anything but TRUE as "no"; the
        // privacy-hidden interpretation is wrong here.
        MutableComponent line = label("Veteran tag: ");
        if (user == null) {
            line.append(Component.literal("(no Wynncraft data)").setStyle(HIDDEN_STYLE));
        } else if (Boolean.TRUE.equals(user.getVeteran())) {
            line.append(
                    Component.literal("yes").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
        } else {
            line.append(Component.literal("no").setStyle(LABEL_STYLE));
        }
        ChatUtils.sendLocalMessage(line);
    }

    private static void renderBlocklistLine(MembershipSnapshot snapshot) {
        MutableComponent line = label("Blocklist: ");
        if (snapshot == null || snapshot.getDiscord() == null) {
            line.append(Component.literal("(snapshot unavailable").setStyle(MUTED_STYLE));
            String detail = snapshot != null ? snapshot.getBlocklistReason() : null;
            if (detail != null && !detail.isEmpty()) {
                line.append(Component.literal(": " + detail).setStyle(MUTED_STYLE));
            }
            line.append(Component.literal(")").setStyle(MUTED_STYLE));
        } else if (snapshot.isBlocklisted()) {
            line.append(
                    Component.literal("BLOCKED")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            String reason = snapshot.getBlocklistReason();
            if (reason != null && !reason.isEmpty()) {
                line.append(Component.literal("  -  \"").setStyle(LABEL_STYLE))
                        .append(
                                Component.literal(reason)
                                        .setStyle(
                                                Style.EMPTY
                                                        .withColor(ChatFormatting.RED)
                                                        .withItalic(true)))
                        .append(Component.literal("\"").setStyle(LABEL_STYLE));
            }
        } else {
            line.append(
                    Component.literal("clean")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
        }
        ChatUtils.sendLocalMessage(line);
    }

    /**
     * The Discord link line. A {@code (hiatus)} role is annotated "role may be stale" only when
     * the guild evidence puts the target back in a guild: Returners (roster or tab list) or, by
     * its own roster, the guild the payload names. dazebot's HIATUS means a guildless ex-member,
     * so being online says nothing about the role, and a roster that did not answer gives no hint.
     */
    // Package-private for unit tests. See CheckReportRendererTest.
    static MutableComponent discordLinkLine(
            MembershipSnapshot snapshot, User user, GuildEvidence evidence) {
        MutableComponent line = label("Discord link: ");
        MembershipSnapshot.Discord disc = snapshot != null ? snapshot.getDiscord() : null;
        if (disc == null) {
            return line.append(Component.literal("(snapshot unavailable)").setStyle(MUTED_STYLE));
        }
        if (!disc.isLinked()) {
            return line.append(Component.literal("not linked").setStyle(LABEL_STYLE));
        }

        String display = disc.getDiscDisplay();
        String discUuid = disc.getDiscUuid();
        if (display == null || display.isEmpty()) {
            display = discUuid != null ? discUuid : "<unknown>";
        }
        String tier = disc.getTier();

        Style tierStyle;
        String tierLabel;
        if (!disc.isInGuild()) {
            // Linked but the Discord user has left vetscord.
            tierStyle = Style.EMPTY.withColor(ChatFormatting.YELLOW);
            tierLabel = "(left server)";
        } else {
            switch (tier == null ? "" : tier) {
                case "member" -> {
                    tierStyle = Style.EMPTY.withColor(ChatFormatting.GREEN);
                    tierLabel = "member";
                }
                case "honourary" -> {
                    tierStyle = Style.EMPTY.withColor(ChatFormatting.GOLD);
                    tierLabel = "honourary";
                }
                case "waitlist" -> {
                    tierStyle = Style.EMPTY.withColor(ChatFormatting.AQUA);
                    tierLabel = "waitlist";
                }
                default -> {
                    tierStyle = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA);
                    tierLabel = "registered";
                }
            }
        }

        line.append(Component.literal("@" + display).setStyle(VALUE_STYLE));
        if (discUuid != null && !discUuid.isEmpty()) {
            line.append(Component.literal("  (" + discUuid + ")").setStyle(MUTED_STYLE));
        }
        line.append(Component.literal("  -  ").setStyle(LABEL_STYLE))
                .append(Component.literal(tierLabel).setStyle(tierStyle));

        if (disc.isWaitlistedModifier() && !"waitlist".equals(tier)) {
            line.append(
                    Component.literal("  +waitlist")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
        }
        if (disc.isHiatus()) {
            // dazebot attempts (best-effort) a guild re-read and role
            // reconciliation during the snapshot fetch, before it reports
            // HIATUS, so a stale HIATUS role is one the target has since
            // outgrown by rejoining a guild; the evidence is what says so.
            Style hiatusStyle = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA);
            line.append(Component.literal("  (hiatus").setStyle(hiatusStyle));
            String nowIn = guildNowIn(user, evidence);
            if (nowIn != null) {
                line.append(
                        Component.literal(" - now in " + nowIn + "; role may be stale")
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
            }
            line.append(Component.literal(")").setStyle(hiatusStyle));
        }
        return line;
    }

    /**
     * The guild the evidence puts the target in, or {@code null} when it puts them in none:
     * {@code "Returners"} when the roster or the tab list confirms it, else the payload's guild
     * name when that guild's own roster lists the target.
     */
    private static String guildNowIn(User user, GuildEvidence evidence) {
        if (evidence.confirmsReturners()) {
            return "Returners";
        }
        Guild guild = user != null ? user.getGuild() : null;
        if (guild != null
                && guild.getName() != null
                && evidence.otherGuild() != null
                && evidence.otherGuild().inGuild()) {
            return guild.getName();
        }
        return null;
    }

    private static void renderStage2Line(MembershipSnapshot snapshot) {
        MutableComponent line = label("Vetsmod client key: ");
        if (snapshot == null || snapshot.getDiscord() == null) {
            line.append(Component.literal("(snapshot unavailable)").setStyle(MUTED_STYLE));
        } else if (snapshot.isStage2Active()) {
            line.append(
                    Component.literal("active")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
        } else {
            line.append(Component.literal("inactive").setStyle(LABEL_STYLE));
        }
        ChatUtils.sendLocalMessage(line);
    }

    private static void renderCultLine(MembershipSnapshot snapshot) {
        // Suppressed when the snapshot names no cult, including when the
        // snapshot is unavailable.
        if (snapshot == null
                || snapshot.getCult() == null
                || snapshot.getCult().getName() == null) {
            return;
        }
        MembershipSnapshot.Cult cult = snapshot.getCult();
        MutableComponent line =
                label("Cult: ").append(Component.literal(cult.getName()).setStyle(VALUE_STYLE));
        if (cult.isFigurehead()) {
            line.append(
                    Component.literal("  (figurehead)")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)));
        }
        ChatUtils.sendLocalMessage(line);
    }

    private static MutableComponent label(String text) {
        return Component.literal(text).setStyle(LABEL_STYLE);
    }

    private static MutableComponent hidden() {
        return Component.literal("(hidden)").setStyle(HIDDEN_STYLE);
    }
}
