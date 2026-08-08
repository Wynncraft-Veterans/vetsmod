package org.wynnvets.debug.diagnostics;

import com.wynntils.core.components.Models;
import com.wynntils.models.worlds.type.WorldState;
import java.util.Collection;
import java.util.StringJoiner;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.fetcher.polling.SupportersPoller;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

/**
 * Handles the {@code /wv debug} diagnostics dump and the
 * {@code /wv debug true|false} logging toggle.
 *
 * <p>With no arguments, dumps diagnostic information to chat and a more
 * detailed report to the log file.  With {@code true}/{@code false}, toggles
 * the debug logging level on or off.</p>
 *
 * <p>This is a <b>debug-only</b> utility — invoked from the
 * {@link org.wynnvets.debug.DebugCommands} command tree.</p>
 */
public final class DiagnosticsHandler {

    private DiagnosticsHandler() {}

    /**
     * Executes the debug command with the given argument.
     *
     * @param arg {@code null} for no-arg info dump, or "true"/"false" to toggle
     */
    public static void execute(String arg) {
        if (arg == null) {
            dumpDiagnostics();
        } else if (arg.equalsIgnoreCase("true")) {
            VetsLogger.setDebugEnabled(true);
            VetsConfig.setLong(VetsConfig.VETS_DEBUG_ENABLED_AT, System.currentTimeMillis());
            ChatUtils.sendLocalMessage(
                    Component.literal("Debug logging ")
                            .withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal("enabled").withStyle(ChatFormatting.GREEN))
                            .append(
                                    Component.literal(
                                                    ". Verbose output is now being written to your log file. (persists for 3 days)")
                                            .withStyle(ChatFormatting.YELLOW)));
        } else if (arg.equalsIgnoreCase("false")) {
            VetsLogger.setDebugEnabled(false);
            VetsConfig.setLong(VetsConfig.VETS_DEBUG_ENABLED_AT, 0L);
            ChatUtils.sendLocalMessage(
                    Component.literal("Debug logging ")
                            .withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal("disabled").withStyle(ChatFormatting.RED))
                            .append(Component.literal(".").withStyle(ChatFormatting.YELLOW)));
        } else {
            ChatUtils.sendLocalMessage(
                    Component.literal("Usage: /wv debug [true|false]")
                            .withStyle(ChatFormatting.RED));
        }
    }

    // ── Diagnostics dump ─────────────────────────────────────────────

    private static void dumpDiagnostics() {
        String vetsmodVersion = getModVersion("vetsmod");
        String mcVersion = getModVersion("minecraft");
        String fabricLoaderVersion = getModVersion("fabricloader");
        String wynntilsVersion = getModVersion("wynntils");
        String fabricApiVersion = getModVersion("fabric-api");

        // Guild / user state
        boolean isReturners = GuildStateManager.isReturners();
        boolean isGuildless = GuildStateManager.isGuildless();
        boolean isUnlocked = GuildStateManager.isUnlocked();
        boolean isStaff = GuildStateManager.isStaff();
        String selfRank = GuildStateManager.selfStaffRank();
        boolean canExecute = GuildStateManager.canExecuteCommands();
        boolean debugOverride = GuildStateManager.isDebugForceGuildlessUnlocked();
        boolean automessageEnabled = VetsConfig.get(VetsConfig.VETS_AUTOMESSAGE);
        boolean debugLogging = VetsLogger.isDebugEnabled();
        String playerName = GuildStateManager.playerName();
        boolean isSupporter = playerName != null && SupportersPoller.isSupporter(playerName);

        // Bearer-key auth state (new /unlock <key> flow)
        boolean hasAuthKey = GuildStateManager.hasStoredAuthKey();
        boolean authVerified = GuildStateManager.isAuthenticatedThisSession();
        String authTier = GuildStateManager.currentAuthTier();
        String authFailureReason = GuildStateManager.lastAuthFailureReason();
        boolean hasLegacyUnlock = GuildStateManager.hasLegacyPasswordUnlock();
        long authVerifiedAt = VetsConfig.getLong(VetsConfig.VETS_AUTH_VERIFIED_AT);
        String persistedTier = VetsConfig.getString(VetsConfig.VETS_AUTH_TIER);

        // Server info
        String serverAddress = "unknown";
        String serverBrand = "unknown";
        Minecraft minecraft = Minecraft.getInstance();
        ServerData serverData = minecraft.getCurrentServer();
        if (serverData != null) {
            serverAddress = serverData.ip;
        }
        LocalPlayer player = minecraft.player;
        if (player != null && player.connection != null) {
            String brand = player.connection.serverBrand();
            if (brand != null && !brand.isEmpty()) {
                serverBrand = brand;
            }
        }

        // Wynntils world state
        String worldState = "unknown";
        String worldName = "unknown";
        try {
            WorldState state = Models.WorldState.getCurrentState();
            worldState = state.name();
            worldName = Models.WorldState.getCurrentWorldName();
            if (worldName == null || worldName.isEmpty()) {
                worldName = "n/a";
            }
        } catch (Exception ignored) {
            // Wynntils model may not be available
        }

        // ── Chat output (concise) ──────────────────────────────────
        MutableComponent chatMsg = Component.empty();
        chatMsg.append(
                Component.literal("Diagnostics\n")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        chatMsg.append(Component.literal("Version: ").withStyle(ChatFormatting.GRAY));
        chatMsg.append(Component.literal(vetsmodVersion + "\n").withStyle(ChatFormatting.WHITE));
        chatMsg.append(Component.literal("MC: ").withStyle(ChatFormatting.GRAY));
        chatMsg.append(Component.literal(mcVersion).withStyle(ChatFormatting.WHITE));
        chatMsg.append(Component.literal(" | Fabric: ").withStyle(ChatFormatting.GRAY));
        chatMsg.append(Component.literal(fabricLoaderVersion).withStyle(ChatFormatting.WHITE));
        chatMsg.append(Component.literal(" | Fabric API: ").withStyle(ChatFormatting.GRAY));
        chatMsg.append(Component.literal(fabricApiVersion).withStyle(ChatFormatting.WHITE));
        chatMsg.append(Component.literal(" | Wynntils: ").withStyle(ChatFormatting.GRAY));
        chatMsg.append(Component.literal(wynntilsVersion + "\n").withStyle(ChatFormatting.WHITE));
        chatMsg.append(Component.literal("Server: ").withStyle(ChatFormatting.GRAY));
        chatMsg.append(Component.literal(serverAddress).withStyle(ChatFormatting.WHITE));
        chatMsg.append(Component.literal(" (").withStyle(ChatFormatting.GRAY));
        chatMsg.append(Component.literal(serverBrand).withStyle(ChatFormatting.WHITE));
        chatMsg.append(Component.literal(")\n").withStyle(ChatFormatting.GRAY));
        chatMsg.append(Component.literal("World: ").withStyle(ChatFormatting.GRAY));
        chatMsg.append(
                Component.literal(worldState + " / " + worldName + "\n")
                        .withStyle(ChatFormatting.WHITE));
        chatMsg.append(Component.literal("Guild: ").withStyle(ChatFormatting.GRAY));
        chatMsg.append(guildSummary(isReturners, isGuildless));
        chatMsg.append(Component.literal(" | Staff: ").withStyle(ChatFormatting.GRAY));
        chatMsg.append(boolComponent(isStaff));
        if (!selfRank.isEmpty()) {
            chatMsg.append(
                    Component.literal(" (" + selfRank + ")").withStyle(ChatFormatting.WHITE));
        }
        chatMsg.append(Component.literal(" | Unlocked: ").withStyle(ChatFormatting.GRAY));
        chatMsg.append(boolComponent(isUnlocked));
        chatMsg.append(Component.literal("\n").withStyle(ChatFormatting.GRAY));
        chatMsg.append(Component.literal("Debug logging: ").withStyle(ChatFormatting.GRAY));
        chatMsg.append(boolComponent(debugLogging));
        chatMsg.append(Component.literal(" | Automessage: ").withStyle(ChatFormatting.GRAY));
        chatMsg.append(boolComponent(automessageEnabled));
        chatMsg.append(Component.literal(" | Supporter: ").withStyle(ChatFormatting.GRAY));
        chatMsg.append(boolComponent(isSupporter));
        chatMsg.append(Component.literal("\n").withStyle(ChatFormatting.GRAY));
        chatMsg.append(Component.literal("Auth: ").withStyle(ChatFormatting.GRAY));
        chatMsg.append(authStatusComponent(hasAuthKey, authVerified, authFailureReason));
        if (authVerified || !authTier.isEmpty()) {
            chatMsg.append(Component.literal(" | Tier: ").withStyle(ChatFormatting.GRAY));
            chatMsg.append(
                    Component.literal(authTier.isEmpty() ? "?" : authTier)
                            .withStyle(ChatFormatting.WHITE));
        }
        if (authVerifiedAt > 0L) {
            chatMsg.append(Component.literal(" | Verified: ").withStyle(ChatFormatting.GRAY));
            chatMsg.append(
                    Component.literal(formatRelative(authVerifiedAt) + " ago")
                            .withStyle(ChatFormatting.WHITE));
        }
        if (hasLegacyUnlock) {
            chatMsg.append(Component.literal(" | Legacy unlock: ").withStyle(ChatFormatting.GRAY));
            chatMsg.append(Component.literal("yes").withStyle(ChatFormatting.YELLOW));
        }

        ChatUtils.sendLocalMessageNewBlock(chatMsg);

        // ── Detailed log dump ───────────────────────────────────────
        VetsLogger.info("=== VetsMod Diagnostics Dump ===");
        VetsLogger.info("VetsMod version: {}", vetsmodVersion);
        VetsLogger.info(
                "Minecraft: {} | Fabric Loader: {} | Fabric API: {}",
                mcVersion,
                fabricLoaderVersion,
                fabricApiVersion);
        VetsLogger.info("Wynntils: {}", wynntilsVersion);
        VetsLogger.info("Server: {} | Brand: {}", serverAddress, serverBrand);
        VetsLogger.info("World state: {} | World name: {}", worldState, worldName);
        VetsLogger.info("Player: {}", playerName);
        VetsLogger.info(
                "Guild state: returners={}, guildless={}, unlocked={}, canExecute={}",
                isReturners,
                isGuildless,
                isUnlocked,
                canExecute);
        VetsLogger.info("Staff: {}, rank={}", isStaff, selfRank.isEmpty() ? "none" : selfRank);
        VetsLogger.info(
                "Config: automessage={}, debugLogging={}, debugGuildlessOverride={}, supporter={}",
                automessageEnabled,
                debugLogging,
                debugOverride,
                isSupporter);
        VetsLogger.info(
                "Auth: hasKey={}, verifiedThisSession={}, tier={}, persistedTier={}, verifiedAt={}, legacyUnlock={}, lastFailure={}",
                hasAuthKey,
                authVerified,
                authTier.isEmpty() ? "none" : authTier,
                persistedTier == null || persistedTier.isEmpty() ? "none" : persistedTier,
                authVerifiedAt == 0L ? "never" : Long.toString(authVerifiedAt),
                hasLegacyUnlock,
                authFailureReason.isEmpty() ? "none" : authFailureReason);

        // Mod list
        Collection<ModContainer> mods = FabricLoader.getInstance().getAllMods();
        VetsLogger.info("Loaded mods ({}):", mods.size());
        StringJoiner modSummary = new StringJoiner(", ");
        for (ModContainer mod : mods) {
            String entry =
                    mod.getMetadata().getId()
                            + " "
                            + mod.getMetadata().getVersion().getFriendlyString();
            modSummary.add(entry);
        }
        VetsLogger.info("  {}", modSummary.toString());
        VetsLogger.info("=== End Diagnostics Dump ===");
    }

    private static MutableComponent guildSummary(boolean isReturners, boolean isGuildless) {
        if (isReturners) {
            return Component.literal("Returners").withStyle(ChatFormatting.GREEN);
        } else if (isGuildless) {
            return Component.literal("Guildless").withStyle(ChatFormatting.YELLOW);
        } else {
            return Component.literal("Other").withStyle(ChatFormatting.RED);
        }
    }

    private static MutableComponent boolComponent(boolean value) {
        return Component.literal(value ? "true" : "false")
                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static MutableComponent authStatusComponent(
            boolean hasKey, boolean verified, String failureReason) {
        if (verified) {
            return Component.literal("verified").withStyle(ChatFormatting.GREEN);
        }
        if (hasKey) {
            MutableComponent c =
                    Component.literal("stored, unverified").withStyle(ChatFormatting.YELLOW);
            if (!failureReason.isEmpty()) {
                c.append(
                        Component.literal(" (" + failureReason + ")")
                                .withStyle(ChatFormatting.RED));
            }
            return c;
        }
        return Component.literal("none").withStyle(ChatFormatting.RED);
    }

    private static String formatRelative(long epochMillis) {
        long delta = System.currentTimeMillis() - epochMillis;
        if (delta < 0L) return "0s";
        long seconds = delta / 1000L;
        if (seconds < 60L) return seconds + "s";
        long minutes = seconds / 60L;
        if (minutes < 60L) return minutes + "m";
        long hours = minutes / 60L;
        if (hours < 24L) return hours + "h";
        long days = hours / 24L;
        return days + "d";
    }

    private static String getModVersion(String modId) {
        return FabricLoader.getInstance()
                .getModContainer(modId)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("not found");
    }
}
