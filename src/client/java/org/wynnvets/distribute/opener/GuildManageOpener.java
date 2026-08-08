package org.wynnvets.distribute.opener;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.text.StyledText;
import com.wynntils.mc.event.ContainerSetContentEvent;
import com.wynntils.mc.event.MenuEvent;
import com.wynntils.utils.mc.McUtils;
import com.wynntils.utils.wynn.ContainerUtils;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import org.wynnvets.distribute.command.OutboundCommand;
import org.wynnvets.distribute.distributor.GraidsDistributor;
import org.wynnvets.logging.VetsLogger;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Opens sub-views of the Guild Management GUI by sending
 * {@code /guild manage} and clicking the right tile in the resulting
 * {@code "<guild>: Manage"} menu.
 *
 * <h2>Members path</h2>
 * <p>{@link #openManageMembers()} intercepts the Manage menu in
 * {@link MenuEvent.MenuOpenedEvent.Pre} (so the Manage GUI never
 * visually flashes), creates a synthetic empty container, and sends a
 * left-click on {@link #MEMBERS_SLOT} (the top-left "Manage Members"
 * key icon). Mirrors Wynntils' {@code GuildBankHotkeyFeature} approach.</p>
 *
 * <h2>Log path</h2>
 * <p>{@link #openGuildLog()} can't use the Pre-cancel trick because the
 * Guild Log tile's slot index isn't hardcoded anywhere in Wynntils and
 * Wynncraft has been known to shift the Manage GUI layout. Instead the
 * Manage menu is allowed to render, {@link ContainerSetContentEvent.Post}
 * populates the items, the tile whose hover-name matches Wynntils'
 * {@code "§7§lGuild Log"} pattern is found dynamically, and a
 * left-click is dispatched on its slot.</p>
 *
 * <p>Why not just {@code /guild log}? Empirically Wynncraft drops
 * {@code /guild log} sent shortly after a menu close (even via the
 * chat field): the server requires the player to be in a "clean"
 * session state that {@code /guild manage} doesn't. Going through
 * Manage&rarr;click reuses the only command we know is reliable.</p>
 */
public final class GuildManageOpener {

  /** Title shown on the GUI opened by {@code /guild manage}. */
  private static final Pattern MANAGE_TITLE_PATTERN = Pattern.compile(".+: Manage");

  /** Hover-name pattern that identifies the Guild Log tile in the
   *  Manage GUI. Mirrors Wynntils'
   *  {@code CustomGuildLogScreenFeature.GUILD_LOG_ITEM_PATTERN}. */
  private static final Pattern GUILD_LOG_ITEM_PATTERN = Pattern.compile("§7§lGuild Log");

  /** Top-left slot of the Manage GUI — the "Manage Members" key icon. */
  private static final int MEMBERS_SLOT = 0;

  private enum Target { MEMBERS, LOG }

  private static final GuildManageOpener INSTANCE = new GuildManageOpener();

  /** What we're trying to navigate to. Null when idle. Single-shot:
   *  cleared the moment the target click is dispatched, so a stray
   *  Manage menu opened by the user manually is never auto-clicked. */
  private static volatile Target target = null;
  /** Container id of the Manage menu we're driving (Log path only). */
  private static volatile int manageContainerId = -1;

  private GuildManageOpener() {}

  /**
   * Registers this opener with the Wynntils event bus. Must be called after Wynntils has finished
   * its own initialisation; see {@link org.wynnvets.listeners.WynntilsEventListener#register()
   * WynntilsEventListener#register()} for the timing.
   */
  public static void register() {
    WynntilsMod.registerEventListener(INSTANCE);
    VetsLogger.debug("Registered GuildManageOpener on Wynntils event bus");
  }

  /**
   * Sends {@code /guild manage} and arms the Pre-intercept so the next
   * Manage menu auto-clicks {@link #MEMBERS_SLOT}, transitioning
   * straight to the Members GUI.
   *
   * <p>Routes through {@link OutboundCommand#queueFront(String)} so the
   * command sits at the head of Wynntils' rate-limited outbound queue.
   * It still respects the 7-tick spacing, but jumps ahead of any
   * background traffic ({@code /v} fanout etc.) that happens to be
   * queued &mdash; user-initiated distribute flows shouldn't wait
   * seconds for staff chat to drain.</p>
   */
  public static void openManageMembers() {
    target = Target.MEMBERS;
    manageContainerId = -1;
    OutboundCommand.queueFront("guild manage");
  }

  /**
   * Sends {@code /guild manage} and arms the post-render scan so the
   * next Manage menu auto-clicks the Guild Log tile (whichever slot
   * holds the {@code §7§lGuild Log} item), transitioning to the Log
   * GUI. Used by {@link GraidsDistributor}.
   *
   * <p>Same front-of-queue routing as {@link #openManageMembers()}.</p>
   */
  public static void openGuildLog() {
    target = Target.LOG;
    manageContainerId = -1;
    OutboundCommand.queueFront("guild manage");
  }

  @SubscribeEvent
  public void onMenuOpenPre(MenuEvent.MenuOpenedEvent.Pre event) {
    // Only the Members path uses the Pre intercept (hardcoded slot 0).
    // The Log path can't because the log tile's slot isn't fixed.
    if (target != Target.MEMBERS) return;
    target = null;

    StyledText title = StyledText.fromComponent(event.getTitle());
    if (!title.matches(MANAGE_TITLE_PATTERN)) {
      VetsLogger.debug("GuildManageOpener: title [{}] did not match Manage pattern",
          title.getString());
      return;
    }

    event.setCanceled(true);

    AbstractContainerMenu container =
        event.getMenuType().create(event.getContainerId(), McUtils.inventory());
    ContainerUtils.clickOnSlot(
        MEMBERS_SLOT,
        event.getContainerId(),
        GLFW.GLFW_MOUSE_BUTTON_LEFT,
        container.getItems());
  }

  @SubscribeEvent
  public void onMenuOpenPost(MenuEvent.MenuOpenedEvent.Post event) {
    if (target != Target.LOG) return;
    if (manageContainerId != -1) return;
    StyledText title = StyledText.fromComponent(event.getTitle());
    if (!title.matches(MANAGE_TITLE_PATTERN)) return;
    manageContainerId = event.getContainerId();
    VetsLogger.debug("GuildManageOpener: Manage menu open (id={}), waiting for items to find Log tile",
        manageContainerId);
  }

  @SubscribeEvent
  public void onSetContent(ContainerSetContentEvent.Post event) {
    if (target != Target.LOG) return;
    if (event.getContainerId() != manageContainerId) return;

    AbstractContainerScreen<?> screen = currentManageScreen();
    if (screen == null) return;

    List<ItemStack> items = screen.getMenu().getItems();
    for (int slot = 0; slot < items.size(); slot++) {
      ItemStack stack = items.get(slot);
      if (stack.isEmpty()) continue;
      StyledText hover = StyledText.fromComponent(stack.getHoverName());
      if (hover.matches(GUILD_LOG_ITEM_PATTERN)) {
        VetsLogger.debug("GuildManageOpener: Guild Log tile at slot {}, clicking", slot);
        ContainerUtils.clickOnSlot(
            slot,
            manageContainerId,
            GLFW.GLFW_MOUSE_BUTTON_LEFT,
            items);
        target = null;
        manageContainerId = -1;
        return;
      }
    }
    VetsLogger.debug("GuildManageOpener: Guild Log tile not found in Manage items");
    target = null;
    manageContainerId = -1;
  }

  private static AbstractContainerScreen<?> currentManageScreen() {
    if (McUtils.mc().screen instanceof AbstractContainerScreen<?> screen
        && screen.getMenu().containerId == manageContainerId) {
      return screen;
    }
    return null;
  }
}
