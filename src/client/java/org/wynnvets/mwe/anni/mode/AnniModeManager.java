package org.wynnvets.mwe.anni.mode;

import com.wynntils.core.components.Models;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

/**
 * Source-of-truth for mode transitions: the single chokepoint that
 * enforces the {@code /stream} mutex (spec §3.1) and owns the user-facing
 * feedback around a {@link VetsConfig#VETS_ANNI_MODE} write.
 *
 * <p>⚠️ That feedback does <b>not</b> precede the write.
 * {@link #transitionTo(AnniMode, Source)} calls {@link VetsConfig#setString}
 * first and prints its confirmation afterwards. The only message that precedes
 * a write is the refusal — and on that path there is no write at all.</p>
 *
 * <p>The mode is read on two routes, and per-class they are almost disjoint
 * populations — only this class uses both.</p>
 *
 * <ul>
 *   <li>Through {@link #current()}: the three subsystems —
 *       {@link org.wynnvets.mwe.anni.bossbar.VetsBossBarManager VetsBossBarManager},
 *       {@link org.wynnvets.mwe.anni.outline.AnniOutlineTicker AnniOutlineTicker}
 *       and
 *       {@link org.wynnvets.mwe.anni.aggressive.AnniAggressiveTicker AnniAggressiveTicker}
 *       — plus this class's own {@link #transitionTo(AnniMode, Source)}, which
 *       reads the previous mode for its "(was Y)" confirmation and its logs.
 *       The three subsystems are the readers outside this package, and
 *       {@link VetsConfig#VETS_ANNI_MODE}'s own Javadoc names them with that
 *       qualifier: "Outside the mode package it is read through
 *       {@code AnniModeManager.current()}".</li>
 *   <li>Through {@link AnniMode#fromConfig()} directly: {@link AnniWindowWatcher},
 *       {@link StreamerModeChatDetector}, and <b>this class</b> — at
 *       {@link #current()} itself, which is the shim, and again in
 *       {@link #applyStartupDefaultIfNeeded()}, which is not.</li>
 * </ul>
 *
 * <p>So "three classes read the mode" is true only of the readers outside
 * this package. Counting every reader gives six. ⚠️ Dropping the qualifier is an easy
 * mistake to make from inside this file, because the obvious grep for
 * {@code fromConfig()} excludes the file you are editing.</p>
 *
 * <p>Nothing writes {@link VetsConfig#VETS_ANNI_MODE} directly. The key has a
 * single write site, inside {@link #transitionTo(AnniMode, Source)}, which is
 * what keeps the mutex honest.</p>
 *
 * <p>Consumers that move the mode without user input all route through
 * {@link #transitionTo}:</p>
 * <ul>
 *   <li>{@link AnniWindowWatcher} at T+30m via
 *       {@link Source#AUTO_WINDOW_CLOSE} — no longer writes the config
 *       directly; delegates to {@link #preferredMode()} so a user's
 *       explicit pick is preserved across the window boundary.</li>
 *   <li>{@link StreamerModeChatDetector} auto-flips to silent on a
 *       detected stream-on via {@link Source#AUTO_STREAM_ACTIVATED},
 *       and auto-restores {@link #preferredMode()} on stream-off via
 *       {@link Source#AUTO_STREAM_DEACTIVATED}.</li>
 *   <li>{@link #applyStartupDefaultIfNeeded()} at world-join /
 *       guild-info-updated via {@link Source#AUTO_STARTUP_DEFAULT}
 *       promotes still-default users from SILENT to PASSIVE once
 *       enrichment eligibility is confirmed.</li>
 * </ul>
 */
public final class AnniModeManager {

    /** Why a transition was requested — controls feedback wording and
     *  the DEBUG bypass. */
    public enum Source {
        /** A {@code /wv anni <mode>} command from the user. Only this
         *  source flips {@link VetsConfig#VETS_ANNI_MODE_USER_SET} and
         *  snapshots the pick into {@link VetsConfig#VETS_ANNI_USER_MODE}. */
        USER_COMMAND,
        /** {@link AnniWindowWatcher} closing the hot window. Routes
         *  through {@link #preferredMode()} — preserves the user's
         *  explicit pick if set, else applies the eligibility default. */
        AUTO_WINDOW_CLOSE,
        /** {@link StreamerModeChatDetector} observed a stream-on line. */
        AUTO_STREAM_ACTIVATED,
        /** {@link StreamerModeChatDetector} observed a stream-off line —
         *  auto-restores the user's preferred mode via
         *  {@link #preferredMode()}. */
        AUTO_STREAM_DEACTIVATED,
        /** Fired once at world-join / on guild-info update to apply the
         *  eligibility-based default (PASSIVE for enrichment-eligible
         *  users, SILENT otherwise) — but only while
         *  {@link VetsConfig#VETS_ANNI_MODE_USER_SET} is {@code false}. */
        AUTO_STARTUP_DEFAULT,
        /** {@code /wv debug tree anni mode set …} — bypasses the
         *  {@code /stream} mutex so we can test passive/aggressive
         *  rendering even while screen-recording a debug session. Does
         *  NOT set the user-explicit flag (debug is not user intent). */
        DEBUG_BYPASS_MUTEX,
    }

    private AnniModeManager() {}

    /** The current mode, read fresh from {@link VetsConfig}. */
    public static AnniMode current() {
        return AnniMode.fromConfig();
    }

    /**
     * Attempt a mode transition.
     *
     * <p>Refused when the target is PASSIVE/AGGRESSIVE and either of the
     * stream detectors says we're streaming, unless the source is
     * {@link Source#DEBUG_BYPASS_MUTEX}. A refused transition leaves the config
     * untouched.</p>
     *
     * <p><b>Whether anything is printed depends entirely on the source, in both
     * directions.</b> Only {@link Source#USER_COMMAND} sees the spec's "stream is
     * suboptimal — try /toggle ghosts NONE" guidance on a refusal; the four
     * {@code AUTO_*} sources decline with a debug log and nothing user-visible.
     * Symmetrically, only {@code USER_COMMAND} and {@link Source#DEBUG_BYPASS_MUTEX}
     * print the generic "Anni mode: X (was Y)" confirmation on success — the four
     * {@code AUTO_*} sources return early, after the write, with a debug log
     * instead, because two of them print their own contextual message at the call
     * site and the other two should stay silent.</p>
     *
     * <p>Successful transitions persist via {@link VetsConfig#setString}
     * <em>before</em> any confirmation is printed. No-op transitions (target
     * equals current mode) still take that path, so a user sees their action
     * acknowledged.</p>
     *
     * @return {@code true} if the config was written; {@code false} if the
     *         transition was refused by the mutex <em>or</em> if {@code target}
     *         was {@code null} (so the caller can branch on outcome — e.g.
     *         {@code /wv debug} prefers a quieter log line on success).
     */
    public static boolean transitionTo(AnniMode target, Source source) {
        if (target == null) return false;
        AnniMode previous = current();
        boolean wantsActive = target != AnniMode.SILENT;
        boolean streamActive = wantsActive && source != Source.DEBUG_BYPASS_MUTEX && isInStream();
        if (streamActive) {
            // Only USER_COMMAND deserves the "stream is suboptimal, try
            // /toggle ghosts" guidance chat spam — auto-sources (startup
            // default, window-close, stream-deactivated race) silently
            // decline. Debug bypasses the mutex above so never reaches here.
            if (source == Source.USER_COMMAND) {
                ChatUtils.sendLocalMessage(
                        Component.literal("Anni mode change refused: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(
                                        Component.literal("/stream is active")
                                                .withStyle(ChatFormatting.RED))
                                .append(
                                        Component.literal(". Stream is suboptimal for anni — try ")
                                                .withStyle(ChatFormatting.GRAY))
                                .append(
                                        Component.literal("/toggle ghosts NONE")
                                                .withStyle(ChatFormatting.AQUA))
                                .append(
                                        Component.literal(" instead.")
                                                .withStyle(ChatFormatting.GRAY)));
            } else {
                VetsLogger.debug(
                        "Anni mode transition refused ({} -> {}, source={}): /stream active",
                        previous.toConfigValue(),
                        target.toConfigValue(),
                        source);
            }
            return false;
        }

        VetsConfig.setString(VetsConfig.VETS_ANNI_MODE, target.toConfigValue());

        // A USER_COMMAND transition is the *only* thing that flips the
        // explicit-choice flag. This is what makes the pick survive
        // internal transitions (stream-on forcing SILENT, T+30m
        // window-close reset) — those overwrite VETS_ANNI_MODE but leave
        // VETS_ANNI_USER_MODE intact, so preferredMode() can restore it.
        if (source == Source.USER_COMMAND) {
            VetsConfig.set(VetsConfig.VETS_ANNI_MODE_USER_SET, true);
            VetsConfig.setString(VetsConfig.VETS_ANNI_USER_MODE, target.toConfigValue());
        }

        // Auto-sources either print their own contextual message at the
        // call site (AUTO_STREAM_*) or should stay quiet entirely
        // (AUTO_WINDOW_CLOSE, AUTO_STARTUP_DEFAULT). Only USER_COMMAND
        // and DEBUG_BYPASS_MUTEX print the generic "Anni mode: X (was Y)".
        switch (source) {
            case AUTO_STREAM_ACTIVATED:
            case AUTO_STREAM_DEACTIVATED:
            case AUTO_WINDOW_CLOSE:
            case AUTO_STARTUP_DEFAULT:
                VetsLogger.debug(
                        "Anni mode auto-changed {} -> {} (source={})",
                        previous.toConfigValue(),
                        target.toConfigValue(),
                        source);
                return true;
            default:
                break;
        }
        ChatUtils.sendLocalMessage(
                Component.literal("Anni mode: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(
                                Component.literal(target.toConfigValue())
                                        .withStyle(modeColor(target)))
                        .append(Component.literal(" (was ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(
                                Component.literal(previous.toConfigValue())
                                        .withStyle(modeColor(previous)))
                        .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY)));
        return true;
    }

    /**
     * The mode the user should be running in right now, absent any
     * transient overrides. Consulted by exactly two callers:
     * {@link AnniWindowWatcher}, and {@link StreamerModeChatDetector#observe}
     * on stream-off (the read itself is in that class's private
     * {@code handleStreamOff()}).
     *
     * <p>{@link #applyStartupDefaultIfNeeded()} does <em>not</em> consult this
     * method. It re-derives the eligibility default inline, which it can because
     * it has already returned early on {@link VetsConfig#VETS_ANNI_MODE_USER_SET}
     * — the one branch this method adds over that default.</p>
     *
     * <p>If the user has ever explicitly picked a mode
     * ({@link VetsConfig#VETS_ANNI_MODE_USER_SET} is {@code true}),
     * their remembered pick from {@link VetsConfig#VETS_ANNI_USER_MODE}
     * wins. Otherwise the eligibility-based default applies: PASSIVE
     * for enrichment-eligible users, SILENT for external users.</p>
     */
    public static AnniMode preferredMode() {
        if (VetsConfig.get(VetsConfig.VETS_ANNI_MODE_USER_SET)) {
            return AnniMode.fromString(VetsConfig.getString(VetsConfig.VETS_ANNI_USER_MODE));
        }
        return GuildStateManager.isEligibleForEnrichment() ? AnniMode.PASSIVE : AnniMode.SILENT;
    }

    /**
     * Idempotent — becomes a no-op forever once the user picks a mode.
     *
     * <p>Called from {@link GuildStateManager#onEnteredWorld} and
     * {@code onGuildInfoUpdated}, so that an eligibility change re-derives the default for a
     * still-unset user: PASSIVE if enrichment-eligible, SILENT otherwise. Today only a change
     * that reaches one of them (a world join, a Wynntils guild join or leave, or Wynntils'
     * guild data turning up after a join) does; one from an auth ack or failure, a newly
     * stored key, or a {@code /gu stats} result waits for the next world join
     * ({@code anni-default-not-reapplied-on-auth-tier-flip}). Routes
     * through {@link #transitionTo} so the
     * {@code /stream} mutex is honoured — if streaming, the transition
     * is silently refused and {@link StreamerModeChatDetector} will
     * apply it on stream-off instead.</p>
     */
    public static void applyStartupDefaultIfNeeded() {
        if (VetsConfig.get(VetsConfig.VETS_ANNI_MODE_USER_SET)) return;
        AnniMode target =
                GuildStateManager.isEligibleForEnrichment() ? AnniMode.PASSIVE : AnniMode.SILENT;
        if (AnniMode.fromConfig() == target) return;
        transitionTo(target, Source.AUTO_STARTUP_DEFAULT);
    }

    /** {@code true} if either Wynntils' streamer-mode signal OR our
     *  chat-line backup detector indicates the user is streaming. */
    private static boolean isInStream() {
        return Models.StreamerMode.isInStream() || StreamerModeChatDetector.lastSeenInStream();
    }

    private static ChatFormatting modeColor(AnniMode mode) {
        switch (mode) {
            case PASSIVE:
                return ChatFormatting.GREEN;
            case AGGRESSIVE:
                return ChatFormatting.RED;
            case SILENT:
            default:
                return ChatFormatting.WHITE;
        }
    }
}
