package org.wynnvets.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.debug.DebugCommands;
import org.wynnvets.distribute.DistributeCommands;
import org.wynnvets.fetcher.ondemand.ListFetcher;
import org.wynnvets.fetcher.ondemand.MotdFetcher;
import org.wynnvets.fetcher.ondemand.ReturnFetcher;
import org.wynnvets.fetcher.ondemand.StaffFetcher;
import org.wynnvets.fetcher.ondemand.StampFetcher;
import org.wynnvets.fetcher.ondemand.UserInfoFetcher;
import org.wynnvets.fetcher.ondemand.WorldListFetcher;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.mwe.anni.command.AnniRsvpCommand;
import org.wynnvets.mwe.anni.mode.AnniMode;
import org.wynnvets.mwe.anni.mode.AnniModeManager;
import org.wynnvets.rendering.territory.TerritoryLineManager;

/**
 * Builds and registers the {@code /wv} client command tree and its handlers.
 *
 * <p>All command handlers are static â€” they read guild/config state through
 * the existing singletons ({@link GuildStateManager}, {@link VetsConfig})
 * and delegate API calls to the appropriate fetcher classes.</p>
 *
 * <p>Called from {@link org.wynnvets.VetsmodClient#onInitializeClient()} via
 * {@code ClientCommandRegistrationCallback}.</p>
 */
public final class CommandRegistry {

  private CommandRegistry() {}

  // â”€â”€ Registration â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  /**
   * Builds and registers all client commands with the Brigadier dispatcher.
   *
   * @param dispatcher     the Brigadier command dispatcher
   * @param registryAccess the registry access context
   */
  public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher,
                              CommandBuildContext registryAccess) {
    dispatcher.register(ClientCommandManager.literal("motd").executes(CommandRegistry::motd));

    dispatcher.register(
        ClientCommandManager.literal("wv")
            // /wv help
            .then(ClientCommandManager.literal("help")
                .executes(HelpCommands::help)
                .then(ClientCommandManager.literal("config")
                    .executes(HelpCommands::helpConfig))
                .then(ClientCommandManager.literal("check")
                    .executes(HelpCommands::helpCheck))
                .then(ClientCommandManager.literal("return")
                    .executes(HelpCommands::helpReturn))
                .then(ClientCommandManager.literal("staff")
                    .executes(HelpCommands::helpStaff))
                .then(ClientCommandManager.literal("list")
                    .executes(HelpCommands::helpList))
                .then(ClientCommandManager.literal("motd")
                    .executes(HelpCommands::helpMotd))
                .then(ClientCommandManager.literal("anni")
                    .executes(HelpCommands::helpAnni))
                .then(ClientCommandManager.literal("line")
                    .executes(HelpCommands::helpLine))
                .then(ClientCommandManager.literal("debug")
                    .executes(HelpCommands::helpDebug)
                    .then(ClientCommandManager.literal("set")
                        .executes(HelpCommands::helpDebugSet))
                    .then(ClientCommandManager.literal("trigger")
                        .executes(HelpCommands::helpDebugTrigger))))

            // /wv check <playerName>
            .then(ClientCommandManager.literal("check")
                .requires(src -> GuildStateManager.isConfirmedStaff())
                .then(ClientCommandManager.argument("playerName", StringArgumentType.string())
                    .executes(CommandRegistry::check)
                )
            )

            // /wv invite-force <playerName> — staff-only bypass of the
            // /gu invite gate. Wired up to the [Invite anyway] click in
            // InviteGate's warning UI; also typeable directly.
            .then(ClientCommandManager.literal("invite-force")
                .requires(src -> GuildStateManager.isConfirmedStaff())
                .then(ClientCommandManager.argument("playerName", StringArgumentType.string())
                    .executes(CommandRegistry::inviteForce)
                )
            )

            // /wv return
            .then(ClientCommandManager.literal("return")
                .requires(CommandRegistry::userIsVet)
                .executes(CommandRegistry::returnInfo)
            )

            // /wv list
            .then(ClientCommandManager.literal("list")
                .requires(CommandRegistry::userIsVet)
                .executes(CommandRegistry::list)
                .then(ClientCommandManager.literal("world")
                    .requires(CommandRegistry::userIsCaptain)
                    .executes(CommandRegistry::listWorld)
                )
            )

            // /wv staff
            .then(ClientCommandManager.literal("staff")
                .requires(CommandRegistry::userIsVet)
                .executes(CommandRegistry::staff)
            )

            // /wv motd
            .then(ClientCommandManager.literal("motd")
                .requires(CommandRegistry::userIsVet)
                .executes(CommandRegistry::motd))

            // /wv anni — base form runs the dispatcher; mode subcommands
            // route through AnniModeManager.transitionTo so the /stream
            // mutex is enforced and the boss-bar / outline subsystems
            // pick up the change on the next tick.
            .then(ClientCommandManager.literal("anni")
                .executes(CommandRegistry::anni)
                .then(ClientCommandManager.literal("silent")
                    .executes(ctx -> anniMode(ctx, AnniMode.SILENT)))
                .then(ClientCommandManager.literal("passive")
                    .executes(ctx -> anniMode(ctx, AnniMode.PASSIVE)))
                .then(ClientCommandManager.literal("aggressive")
                    .executes(ctx -> anniMode(ctx, AnniMode.AGGRESSIVE)))
                .then(ClientCommandManager.literal("rsvp")
                    .then(ClientCommandManager.literal("hard")
                        .executes(AnniRsvpCommand::hard))
                    .then(ClientCommandManager.literal("soft")
                        .executes(AnniRsvpCommand::soft))
                    .then(ClientCommandManager.literal("revoke")
                        .executes(AnniRsvpCommand::revoke))))
                // Note: scrollspot host-write lives under
                // /wv debug tree anni scrollspot (see AnniDebugCommands).
                // Hidden from the main /wv anni tree because it's used by
                // a small set of staff hosts only — surfacing it via tab-
                // complete would clutter the suggestion list for everyone
                // else.

            // /wv config
            .then(ClientCommandManager.literal("config")
                .executes(ConfigCommands::configList)
                .then(ClientCommandManager.argument("key", StringArgumentType.word())
                    .suggests(ConfigCommands.SUGGEST_CONFIG_KEYS)
                    .executes(ConfigCommands::configGet)
                    .then(ClientCommandManager.argument("value", StringArgumentType.word())
                        .suggests(ConfigCommands.SUGGEST_CONFIG_VALUES)
                        .executes(ConfigCommands::configSet)
                    )
                )
            )

            // /wv line church|scrap|bat|hegea|lighthouse
            .then(ClientCommandManager.literal("line")
                .then(ClientCommandManager.literal("church")
                    .executes(ctx -> lineToggle(ctx, "church"))
                )
                .then(ClientCommandManager.literal("scrap")
                    .executes(ctx -> lineToggle(ctx, "scrap"))
                )
                .then(ClientCommandManager.literal("bat")
                    .executes(ctx -> lineToggle(ctx, "bat"))
                )
                .then(ClientCommandManager.literal("hegea")
                    .executes(ctx -> lineToggle(ctx, "hegea"))
                )
                .then(ClientCommandManager.literal("lighthouse")
                    .executes(ctx -> lineToggle(ctx, "lighthouse"))
                )
            )

            // /wv debug â€” full sub-tree built by DebugCommands
            .then(DebugCommands.buildCommandTree())

            // /wv distribute â€” full sub-tree built by DistributeCommands
            .then(DistributeCommands.buildCommandTree())
    );
  }

  // â”€â”€ Permission predicates â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  // TODO: Replace with correct permission checks once available.
  private static boolean userIsCaptain(FabricClientCommandSource src) {
    return true;
  }

  // TODO: Replace with correct permission checks once available.
  private static boolean userIsVet(FabricClientCommandSource src) {
    return true;
  }

  // â”€â”€ Command handlers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  private static int check(CommandContext<FabricClientCommandSource> ctx) {
    if (!GuildStateManager.isConfirmedStaff()) {
      ChatUtils.sendLocalMessage(
          Component.literal("You must be confirmed staff (vetsmod authenticated) "
                  + "to use /wv check.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }

    String playerName = StringArgumentType.getString(ctx, "playerName");
    UserInfoFetcher.checkUser(playerName);
    CautionCommands.runCheckCautions(playerName);
    return 1;
  }

  private static int inviteForce(CommandContext<FabricClientCommandSource> ctx) {
    if (!GuildStateManager.isConfirmedStaff()) {
      ChatUtils.sendLocalMessage(
          Component.literal("You must be confirmed staff to use /wv invite-force.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }
    String playerName = StringArgumentType.getString(ctx, "playerName");
    InviteGate.forceDispatch(playerName);
    ChatUtils.sendLocalMessage(
        Component.literal("Dispatching /gu invite " + playerName
                + " (vetsmod gate bypassed).")
            .withStyle(ChatFormatting.YELLOW)
    );
    return 1;
  }

  private static int motd(CommandContext<FabricClientCommandSource> ctx) {
    boolean useGuildMotd = GuildStateManager.isReturners()
        || (GuildStateManager.isGuildless() && GuildStateManager.isWaitlistUnlocked())
        || GuildStateManager.isHonouraryUnlocked();

    if (useGuildMotd) {
      MotdFetcher.fetchGuildMotd().thenAccept(guildMotd -> {
        String text = guildMotd.getString();
        if (text != null && !text.isEmpty()) {
          ChatUtils.sendLocalMessageNewBlock(guildMotd);
        } else {
          MotdFetcher.fetchMotd().thenAccept(motd -> ChatUtils.sendLocalMessageNewBlock(motd));
        }
      });
    } else {
      MotdFetcher.fetchMotd().thenAccept(motd -> ChatUtils.sendLocalMessageNewBlock(motd));
    }
    return 1;
  }

  private static int returnInfo(CommandContext<FabricClientCommandSource> ctx) {
    ReturnFetcher.fetchReturn().thenAccept(returnInfo -> ChatUtils.sendLocalMessageNewBlock(returnInfo));
    return 1;
  }

  private static int staff(CommandContext<FabricClientCommandSource> ctx) {
    if (!GuildStateManager.isUnlocked()) {
      ChatUtils.sendLocalMessage(
          Component.literal("You must be unlocked to use /wv staff.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }

    StaffFetcher.fetchOnlineStaff().thenAccept(staffInfo -> ChatUtils.sendLocalMessageNewBlock(staffInfo));
    return 1;
  }

  private static int list(CommandContext<FabricClientCommandSource> ctx) {
    if (!GuildStateManager.isUnlocked()) {
      ChatUtils.sendLocalMessage(
          Component.literal("You must be unlocked to use /wv list.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }

    // "Looking up..." is a transient progress line, not the real response —
    // let it dedup to the compact indicator. The new-block separator is
    // reserved for the actual "Online Members" header that follows.
    ChatUtils.sendLocalMessage(
        Component.literal("Looking up online members...")
            .withStyle(ChatFormatting.GREEN)
    );

    ListFetcher.fetchList().thenAccept(listInfo -> ChatUtils.sendLocalMessageNewBlock(listInfo));
    return 1;
  }

  private static int listWorld(CommandContext<FabricClientCommandSource> ctx) {
    boolean isCurrentlyStaff = GuildStateManager.isStaff();
    boolean refreshStarted = GuildStateManager.refreshStaffStatusIfNeeded(!isCurrentlyStaff);

    if (refreshStarted || GuildStateManager.isCheckingStaffStatus()) {
      ChatUtils.sendLocalMessage(
          Component.literal("Checking staff permissions, please retry in a moment.")
              .withStyle(ChatFormatting.YELLOW)
      );
      return 0;
    }

    if (!GuildStateManager.isStaff()) {
      ChatUtils.sendLocalMessage(
          Component.literal("You must be staff to use /wv list world.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }

    WorldListFetcher.fetchWorldList();
    return 1;
  }

  private static int anni(CommandContext<FabricClientCommandSource> ctx) {
    StampFetcher.fetchStampAndCreateAnniCommandMessage().thenAccept(blocks -> {
      if (blocks == null || blocks.isEmpty()) {
        ChatUtils.sendLocalMessageNewBlock(
            Component.literal("Annihilation timer is currently unavailable.")
                .withStyle(ChatFormatting.YELLOW)
        );
        return;
      }
      // Each block gets its own [VETSMOD] prefix — the imminent render
      // uses this to break out the "Change Anni Mode?" UI visually.
      for (net.minecraft.network.chat.MutableComponent block : blocks) {
        ChatUtils.sendLocalMessageNewBlock(block);
      }
    });
    return 1;
  }

  /** {@code /wv anni silent|passive|aggressive} — request a mode
   *  transition. The actual config write, /stream mutex check, and
   *  chat feedback live in {@link AnniModeManager#transitionTo}; this
   *  is just the brigadier shim. */
  private static int anniMode(
      CommandContext<FabricClientCommandSource> ctx, AnniMode target) {
    AnniModeManager.transitionTo(target, AnniModeManager.Source.USER_COMMAND);
    return 1;
  }

  private static int lineToggle(CommandContext<FabricClientCommandSource> ctx, String alias) {
    if (!GuildStateManager.areFeaturesEnabled()) {
      ChatUtils.sendLocalMessage(
          Component.literal("You must be a Returners guild member to use /wv line.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }
    TerritoryLineManager.toggle(alias);
    return 1;
  }

}
