package org.wynnvets.mwe.anni.aggressive;

import com.wynntils.core.components.Models;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;
import org.wynnvets.mwe.anni.zone.AnniZone;

/**
 * S5 — clickable "Suggest /toggle ghosts none" prompt on first zone entry
 * per stamp_epoch.
 *
 * <p>Per the parent plan §S5: ghost players (other-world phased players)
 * are a major source of visual noise during anni. Wynncraft's
 * {@code /toggle ghosts off|none} hides them. We nag the user to set it
 * once per anni, suppressed when we can prove they've already set it.</p>
 *
 * <p><b>Detection (decision-locked this session):</b> walk
 * {@link Minecraft#level}.players(); if ANY player returns
 * {@link com.wynntils.models.players.PlayerModel#isPlayerGhost(net.minecraft.world.entity.player.Player)
 * true}, ghosts must be ON (otherwise Wynncraft wouldn't have sent the
 * client those players in the first place). Show the prompt
 * unconditionally in that case — it's useful info. If zero visible
 * players are ghosts, it's ambiguous (could be off, could just be an
 * empty area); fall back to a per-stamp_epoch sentinel
 * ({@link VetsConfig#VETS_ANNI_GHOSTS_PROMPT_SHOWN_FOR_STAMP}) so we
 * prompt at most once per anni in that branch.</p>
 *
 * <p><b>Trigger:</b> rising edge of {@link AnniZone#isInZone(double, double)}
 * while aggressive mode is active and the prompt toggle is on.</p>
 *
 * <p>Click action is a {@code suggestCommand} (not run-direct) so the
 * user has to actively confirm — avoids accidental toggle on stray
 * clicks.</p>
 */
public final class GhostsPromptHandler {

    private static volatile boolean registered = false;

    /** Last-observed zone state for rising-edge detection. */
    private static volatile boolean lastInZone = false;

    private GhostsPromptHandler() {
    }

    public static void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        VetsLogger.debug("GhostsPromptHandler registered");
    }

    private static void tick() {
        try {
            tickInner();
        } catch (Exception e) {
            VetsLogger.debug("GhostsPromptHandler.tick failed: {}", e.getMessage());
        }
    }

    private static void tickInner() {
        if (!AnniAggressiveTicker.isAggressiveActive()) {
            // Reset the rising-edge tracker when aggressive is off so the
            // next activation can re-trigger if the user is already in
            // the zone at activation time.
            lastInZone = false;
            return;
        }
        if (!VetsConfig.get(VetsConfig.VETS_ANNI_GHOSTS_PROMPT)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return;

        boolean inZone = AnniZone.isInZone(player.getX(), player.getZ());
        boolean rising = inZone && !lastInZone;
        lastInZone = inZone;

        if (!rising) return;

        boolean anyGhost = anyVisibleGhost(level, player);
        if (anyGhost) {
            // Ghosts confirmed on → prompt unconditionally.
            firePrompt();
            return;
        }

        // Ambiguous: could be ghosts off, could be empty area. Fall back
        // to the per-stamp_epoch sentinel.
        long stamp = currentStampEpoch();
        if (stamp <= 0L) return;
        String shownFor = VetsConfig.getString(
                VetsConfig.VETS_ANNI_GHOSTS_PROMPT_SHOWN_FOR_STAMP);
        if (shownFor == null) shownFor = "";
        String stampStr = Long.toString(stamp);
        if (stampStr.equals(shownFor)) return;
        firePrompt();
        VetsConfig.setString(
                VetsConfig.VETS_ANNI_GHOSTS_PROMPT_SHOWN_FOR_STAMP, stampStr);
    }

    private static boolean anyVisibleGhost(ClientLevel level, LocalPlayer self) {
        for (AbstractClientPlayer other : level.players()) {
            if (other == self) continue;
            try {
                if (Models.Player.isPlayerGhost(other)) return true;
            } catch (Exception e) {
                // Wynntils not initialised or transient model failure —
                // treat as "no info"; fall through.
                return false;
            }
        }
        return false;
    }

    private static long currentStampEpoch() {
        AnniSnapshot snapshot = AnniSnapshotCache.latest();
        if (snapshot == null) return 0L;
        AnniSnapshot.Event event = snapshot.event();
        if (event == null) return 0L;
        Long stamp = event.stampEpoch();
        return stamp != null ? stamp : 0L;
    }

    private static void firePrompt() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            MutableComponent suggestion = Component.literal("[Suggest: /toggle ghosts none]")
                    .withStyle(s -> s
                            .withColor(ChatFormatting.GOLD)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent.SuggestCommand(
                                    "/toggle ghosts none"))
                            .withHoverEvent(new HoverEvent.ShowText(
                                    Component.literal(
                                            "Hides phased players from other worlds. "
                                                    + "Less visual noise during anni."))));
            MutableComponent msg = Component.literal("Anni starting soon — ")
                    .withStyle(ChatFormatting.AQUA)
                    .append(suggestion);
            ChatUtils.sendLocalMessage(msg);
        });
    }

    /** Debug entry — report whether the prompt would fire right now.
     *  Walks the player list and dumps the per-player ghost flag. */
    public static void debugDump() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            VetsLogger.info("ghostsPromptDump: no Minecraft");
            return;
        }
        LocalPlayer self = mc.player;
        ClientLevel level = mc.level;
        if (self == null || level == null) {
            VetsLogger.info("ghostsPromptDump: not in-world");
            return;
        }
        boolean inZone = AnniZone.isInZone(self.getX(), self.getZ());
        boolean aggro = AnniAggressiveTicker.isAggressiveActive();
        boolean toggleOn = VetsConfig.get(VetsConfig.VETS_ANNI_GHOSTS_PROMPT);
        long stamp = currentStampEpoch();
        String shownFor = VetsConfig.getString(
                VetsConfig.VETS_ANNI_GHOSTS_PROMPT_SHOWN_FOR_STAMP);

        VetsLogger.info("--- ghostsPromptDump ---");
        VetsLogger.info("aggressive_active={} toggle_on={} in_zone={} stamp={} shown_for={}",
                aggro, toggleOn, inZone, stamp, shownFor);

        int total = 0;
        int ghosts = 0;
        for (AbstractClientPlayer other : level.players()) {
            if (other == self) continue;
            total++;
            boolean isGhost;
            try {
                isGhost = Models.Player.isPlayerGhost(other);
            } catch (Exception e) {
                isGhost = false;
            }
            if (isGhost) ghosts++;
            String name = other.getGameProfile() != null
                    ? other.getGameProfile().name() : "?";
            VetsLogger.info("  player={} ghost={}", name, isGhost);
        }
        boolean wouldFire = aggro && toggleOn && inZone && (
                ghosts > 0
                || (stamp > 0 && !Long.toString(stamp).equals(shownFor)));
        VetsLogger.info("visible_players={} ghosts={} would_fire_on_rising_edge={}",
                total, ghosts, wouldFire);
    }
}
