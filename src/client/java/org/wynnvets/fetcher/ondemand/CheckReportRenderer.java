package org.wynnvets.fetcher.ondemand;

import com.wynntils.utils.mc.McUtils;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
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
            MembershipSnapshot snapshot,
            // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
            // Reason: /v3/player/{uuid}.guild ~12h stale for offline players.
            // Remove: when Wynncraft invalidates the player endpoint on
            //   officer-write events, or exposes a webhook, or an offline-player
            //   spot-check shows <10min staleness.
            // Imports used only by this workaround: java.util.Optional and
            // org.wynnvets.guild.GuildStateManager.
            Optional<String> confirmedNonReturnersRank
            // <<< PATCH END: WYNN-STALE-WORKAROUND
            ) {
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
        // not check which server the client is on). Skips the whole
        // signal-aggregation dance below and avoids the "Wynn's cached
        // online=false for the local player" trap.
        String localName = (McUtils.player() != null) ? McUtils.playerName() : null;
        boolean isSelf = localName != null && localName.equalsIgnoreCase(displayName);

        boolean currentlyOnline = isCurrentlyOnline(user, snapshot, tabListServer, isSelf);

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

        renderGuildLine(
                user,
                returners,
                snapshot,
                // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
                confirmedNonReturnersRank,
                tabListConfirmsReturners
                // <<< PATCH END: WYNN-STALE-WORKAROUND
                );
        renderVeteranLine(user);
        renderBlocklistLine(snapshot);
        renderDiscordLinkLine(snapshot, currentlyOnline);
        renderStage2Line(snapshot);
        renderCultLine(snapshot);
    }

    /**
     * Aggregates "is this player on Wynncraft right now?" across four
     * signals, in order of authoritativeness: true for self (the local
     * player is treated as online), then local tab list (live), then Wynn's
     * {@code online} field (2-min CDN cache, can lag), then a recent
     * ({@literal <}10 min) {@code last_seen_observed.ts}: dazebot's
     * best-guess last-online time, which several dazebot paths keep
     * current, its {@code server_watcher} among them. Today this fourth
     * signal cannot fire, because temporary-server's ack does not carry
     * {@code last_seen_observed} (see
     * {@code check-membership-ack-drops-last-seen-observed}). Used to
     * qualify the {@code (hiatus)} discord-line annotation: a positive
     * signal is meant to tell staff the role state may be stale. Today
     * being online is not evidence of that, since dazebot's HIATUS means a
     * guildless ex-member (see
     * {@code wv-check-hiatus-stale-hint-keys-off-online}).
     */
    private static boolean isCurrentlyOnline(
            User user, MembershipSnapshot snapshot, String tabListServer, boolean isSelf) {
        if (isSelf) {
            return true;
        }
        if (tabListServer != null) {
            return true;
        }
        if (user != null && Boolean.TRUE.equals(user.getOnline())) {
            return true;
        }
        MembershipSnapshot.LastSeenObserved obs =
                snapshot != null ? snapshot.getLastSeenObserved() : null;
        if (obs != null && obs.getTs() > 0) {
            long ageSec = Instant.now().getEpochSecond() - obs.getTs();
            return ageSec >= 0 && ageSec < 600;
        }
        return false;
    }

    private static void renderWynnLines(
            User user, MembershipSnapshot snapshot, String tabListServer, boolean isSelf) {
        // Item 1: first seen. Show coarse "N years ago" as the headline value
        // (raw days are unreadable for old accounts) with the ISO date as the
        // muted parenthetical.
        String firstJoin = user.getFirstJoinDate();
        if (firstJoin == null) {
            ChatUtils.sendLocalMessage(label("First seen on Wynncraft: ").append(hidden()));
        } else {
            ChatUtils.sendLocalMessage(
                    label("First seen on Wynncraft: ")
                            .append(
                                    Component.literal(
                                                    TimeLabels.toCoarseRelativeDateLabel(firstJoin))
                                            .setStyle(VALUE_STYLE))
                            .append(
                                    Component.literal("  (" + firstJoin + ")")
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
            ChatUtils.sendLocalMessage(
                    label("Last seen on Wynncraft: ")
                            .append(
                                    Component.literal(TimeLabels.toRelativeDateLabel(lastJoin))
                                            .setStyle(VALUE_STYLE))
                            .append(
                                    Component.literal("  (" + lastJoin + ")")
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
        // it's the most actionable signal for a staff vetting decision. The
        // Discord line's `(hiatus)` qualifier asks a related question through
        // isCurrentlyOnline, which differs only in letting a recent
        // last_seen_observed outrank Wynncraft's online=false. Today that field
        // never arrives, so that case cannot occur (see
        // check-membership-ack-drops-last-seen-observed).
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

    private static void renderGuildLine(
            User user,
            ReturnersRosterProbe.ReturnersMembership returners,
            MembershipSnapshot snapshot,
            // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
            // Reason/Remove: see the first workaround block in this class.
            Optional<String> confirmedNonReturnersRank,
            boolean tabListConfirmsReturners
            // <<< PATCH END: WYNN-STALE-WORKAROUND
            ) {
        MutableComponent line = label("Guild: ");

        if (user == null) {
            line.append(Component.literal("(no Wynncraft profile)").setStyle(HIDDEN_STYLE));
            ChatUtils.sendLocalMessage(line);
            return;
        }

        Guild guild = user.getGuild();
        boolean inVets = user.isInVets() && (returners == null || returners.inGuild());
        // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
        // Reason/Remove: see the first workaround block in this class.
        // Any single fresh signal is enough to render Returners: the roster
        // (invalidated on officer-write) OR the local tab list (Minecraft
        // client state, same-tick). Catches the rejoin case where the payload
        // briefly names a non-Returners guild but the target is provably back
        // in Returners.
        boolean rosterConfirmsReturners = returners != null && returners.inGuild();
        boolean confirmedReturners = rosterConfirmsReturners || tabListConfirmsReturners;
        // <<< PATCH END: WYNN-STALE-WORKAROUND

        if (inVets
                // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
                || confirmedReturners
        // <<< PATCH END: WYNN-STALE-WORKAROUND
        ) {
            line.append(Component.literal("Returners").setStyle(VALUE_STYLE));
            String rank = returners != null ? returners.rank() : null;
            // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
            // Reason/Remove: see the first workaround block in this class.
            // Suppressed original fallback: `if (rank == null && guild != null) rank =
            // guild.getRank();`
            // — guild.getRank() from the stale payload can silently re-introduce
            // staleness (payload can claim RECRUITER for a former member who was
            // already kicked). Roster-only until upstream fixes invalidation.
            // <<< PATCH END: WYNN-STALE-WORKAROUND
            if (rank != null && !rank.isEmpty()) {
                line.append(Component.literal("  -  ").setStyle(LABEL_STYLE))
                        .append(
                                Component.literal(rank)
                                        .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)));
            }
            ChatUtils.sendLocalMessage(line);
            return;
        }

        // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
        // Reason/Remove: see the first workaround block in this class.
        // Cross-signal mismatch: either (a) Wynncraft's payload says Returners
        // but the roster disagrees (falls out of the inVets branch above with
        // guild.name == "Returners"), or (b) Wynncraft names some non-Returners
        // guild but that guild's own roster doesn't list the UUID. Both mean
        // the payload is behind — render "guildless" + a hint rather than the
        // misleading stale value.
        boolean returnersMismatch = user.isInVets() && returners != null && !returners.inGuild();
        boolean otherGuildMismatch =
                guild != null
                        && guild.getName() != null
                        && !"Returners".equals(guild.getName())
                        && confirmedNonReturnersRank.isEmpty();
        if (returnersMismatch || otherGuildMismatch) {
            line.append(Component.literal("guildless").setStyle(HIDDEN_STYLE))
                    .append(
                            Component.literal("  -  Wynncraft profile still catching up")
                                    .setStyle(HIDDEN_STYLE));
            ChatUtils.sendLocalMessage(line);
            return;
        }
        // <<< PATCH END: WYNN-STALE-WORKAROUND

        if (guild != null && guild.getName() != null) {
            // Player is in a non-Returners guild.
            line.append(Component.literal(guild.getName()).setStyle(VALUE_STYLE));
            if (guild.getPrefix() != null && !guild.getPrefix().isEmpty()) {
                line.append(Component.literal("  [").setStyle(LABEL_STYLE))
                        .append(
                                Component.literal(guild.getPrefix())
                                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)))
                        .append(Component.literal("]").setStyle(LABEL_STYLE));
            }
            // >>> PATCH BEGIN: WYNN-STALE-WORKAROUND (2026-07-11)
            // Reason/Remove: see the first workaround block in this class.
            // Original block used guild.getRank() directly; now prefer the
            // confirming roster's rank when present (the payload rank can lag
            // even for a still-in-guild member after a promotion/demotion).
            // Today the payload fallback is effectively unreachable: for any
            // guild not named Returners, the mismatch block above has already
            // returned when that rank is absent (see
            // wv-check-guild-line-conflates-roster-failure-with-absence).
            String rankText = confirmedNonReturnersRank.orElse(guild.getRank());
            if (rankText != null && !rankText.isEmpty()) {
                line.append(Component.literal("  -  ").setStyle(LABEL_STYLE))
                        .append(
                                Component.literal(rankText)
                                        .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)));
            }
            // <<< PATCH END: WYNN-STALE-WORKAROUND
            ChatUtils.sendLocalMessage(line);
            return;
        }

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
        ChatUtils.sendLocalMessage(line);
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

    private static void renderDiscordLinkLine(
            MembershipSnapshot snapshot, boolean currentlyOnline) {
        MutableComponent line = label("Discord link: ");
        MembershipSnapshot.Discord disc = snapshot != null ? snapshot.getDiscord() : null;
        if (disc == null) {
            line.append(Component.literal("(snapshot unavailable)").setStyle(MUTED_STYLE));
            ChatUtils.sendLocalMessage(line);
            return;
        }
        if (!disc.isLinked()) {
            line.append(Component.literal("not linked").setStyle(LABEL_STYLE));
            ChatUtils.sendLocalMessage(line);
            return;
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
            // HIATUS. The qualifier below is meant to flag a HIATUS role that
            // is stale; today it keys off being online, which dazebot's HIATUS
            // (a guildless ex-member) does not rule out, so it can flag a correct
            // role and miss a stale one (see wv-check-hiatus-stale-hint-keys-off-online).
            Style hiatusStyle = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA);
            line.append(Component.literal("  (hiatus").setStyle(hiatusStyle));
            if (currentlyOnline) {
                line.append(
                        Component.literal(" - currently online; may be stale")
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
            }
            line.append(Component.literal(")").setStyle(hiatusStyle));
        }
        ChatUtils.sendLocalMessage(line);
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
