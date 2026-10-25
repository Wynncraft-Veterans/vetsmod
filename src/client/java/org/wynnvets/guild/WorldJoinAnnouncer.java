package org.wynnvets.guild;

import com.wynntils.utils.mc.McUtils;
import net.minecraft.client.player.LocalPlayer;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.fetcher.ondemand.MotdFetcher;
import org.wynnvets.fetcher.ondemand.StampFetcher;
import org.wynnvets.logging.VetsLogger;

/**
 * The world-join MOTD and anni-stamp prints, and the state that limits their repeats.
 *
 * <p>Each print asks a fetcher for its line and prints when the returned future completes, so
 * neither print waits for the other. The MOTD is the guild MOTD for a player eligible for it,
 * falling back to the standard MOTD when that is empty, and the standard MOTD otherwise. A fetch
 * timestamp and a short cooldown space the world-join MOTD fetches. A flag for each print, set
 * when its fetch starts, keeps the later guild callbacks from starting that print again on the
 * same connection. A disconnect clears the timestamp and the flags.</p>
 */
public final class WorldJoinAnnouncer {

    // State tracking for MOTD to prevent duplicate fetches
    // Package-private for GuildLifecycle, which sets it on world join and clears it on reset.
    static volatile long lastMotdFetchTime = 0;

    // Package-private for GuildLifecycle, whose world-join cooldown check reads it.
    static final long MOTD_FETCH_COOLDOWN_MS = 1_000L;

    // Set when fetchAndDisplayMotd() starts either MOTD fetch (guild or
    // standard); blocks onGuildInfoUpdated()'s re-fetch for the session.
    // Package-private for GuildLifecycle, which reads it on guild updates and resets it.
    static volatile boolean guildMotdDisplayedThisSession = false;

    // Set when a stamp fetch starts (whether or not anything is then shown);
    // consulted only by onGuildInfoUpdated() and onGuildCheckCompleted().
    // Package-private for GuildLifecycle, which gates later stamp prints on it and resets it.
    static volatile boolean stampDisplayedThisSession = false;

    private WorldJoinAnnouncer() {}

    // Package-private for GuildLifecycle, which calls it on world join and on a guild update.
    static void fetchAndDisplayMotd() {
        // Check if auto-messages are enabled (global gate)
        if (!VetsConfig.get(VetsConfigKeys.VETS_AUTOMESSAGE)) {
            VetsLogger.debug("Auto-messages disabled, skipping MOTD");
            return;
        }

        // Check if MOTD printing is enabled (user toggle)
        if (!VetsConfig.get(VetsConfigKeys.PRINT_MOTD)) {
            VetsLogger.debug("printMOTD disabled, skipping MOTD");
            return;
        }

        VetsLogger.debug("Fetching MOTD");
        LocalPlayer player = McUtils.player();

        if (player != null) {
            // Mark MOTD as shown now — prevents onGuildInfoUpdated() from re-fetching
            // if guild info arrives after WorldStateEvent.WORLD (race condition).
            guildMotdDisplayedThisSession = true;

            // Use guild MOTD for eligible users (Returners, waitlist-unlocked, honourary-unlocked)
            boolean useGuildMotd =
                    GuildStateManager.isReturners()
                            || (GuildStateManager.isGuildless()
                                    && GuildStateManager.isWaitlistUnlocked())
                            || GuildStateManager.isHonouraryUnlocked();

            // Use NewBlock so the motd gets a fresh full [VETSMOD] badge and stays
            // visually separate from the anni-motd block rather than collapsing into
            // one compact-badged run. The two are fetched independently and print in
            // completion order (world-join-motd-and-anni-motd-print-in-completion-order).
            if (useGuildMotd) {
                MotdFetcher.fetchGuildMotd()
                        .thenAccept(
                                guildMotdComponent -> {
                                    String text = guildMotdComponent.getString();
                                    if (text != null && !text.isEmpty()) {
                                        ChatUtils.sendLocalMessageNewBlock(guildMotdComponent);
                                    } else {
                                        // Fall back to standard MOTD if guild MOTD is empty
                                        MotdFetcher.fetchMotd()
                                                .thenAccept(
                                                        motdComponent -> {
                                                            ChatUtils.sendLocalMessageNewBlock(
                                                                    motdComponent);
                                                        });
                                    }
                                });
            } else {
                MotdFetcher.fetchMotd()
                        .thenAccept(
                                motdComponent -> {
                                    ChatUtils.sendLocalMessageNewBlock(motdComponent);
                                });
            }
        }
    }

    /**
     * Fetch and display the annihilation stamp message (if applicable)
     */
    // Public for GuildDiagnostics, whose forced recheck reprints it. GuildLifecycle calls it too.
    public static void fetchAndDisplayStampMessage() {
        // Check if auto-messages are enabled (global gate)
        if (!VetsConfig.get(VetsConfigKeys.VETS_AUTOMESSAGE)) {
            VetsLogger.debug("Auto-messages disabled, skipping stamp");
            return;
        }

        // Check if annihilation printing is enabled (user toggle)
        if (!VetsConfig.get(VetsConfigKeys.PRINT_ANNI)) {
            VetsLogger.debug("printANNI disabled, skipping stamp");
            return;
        }

        VetsLogger.debug("Fetching annihilation stamp");
        LocalPlayer player = McUtils.player();

        if (player != null) {
            stampDisplayedThisSession = true;
            StampFetcher.fetchStampAndCreateMessage()
                    .thenAccept(
                            stampMessage -> {
                                if (stampMessage != null) {
                                    VetsLogger.debug("Displaying annihilation countdown");
                                    // NewBlock so the anni-motd gets a fresh full [VETSMOD] badge,
                                    // separate from the motd block.
                                    ChatUtils.sendLocalMessageNewBlock(stampMessage);
                                }
                            });
        }
    }
}
