package org.wynnvets.distribute;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Handlers;
import com.wynntils.core.text.StyledText;
import com.wynntils.mc.event.MenuEvent;
import com.wynntils.utils.mc.McUtils;
import com.wynntils.utils.wynn.ContainerUtils;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.bus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import org.wynnvets.logging.VetsLogger;

import java.util.regex.Pattern;

/**
 * Opens the guild members list by sending {@code /guild manage} and
 * intercepting the resulting {@code "<guild>: Manage"} menu before it
 * renders, then synthesising a click on the top-left slot
 * ({@value #MEMBERS_SLOT}, "Manage Members").
 *
 * <p>Mirrors Wynntils' own {@code GuildBankHotkeyFeature} approach &mdash;
 * the only differences are the slot index (members vs. bank) and the
 * trigger (a vetsmod command vs. a key bind).</p>
 */
public final class GuildManageOpener {

  /** Title shown on the GUI opened by {@code /guild manage}. */
  private static final Pattern MANAGE_TITLE_PATTERN = Pattern.compile(".+: Manage");

  /** Top-left slot of the Manage GUI — the "Manage Members" key icon. */
  private static final int MEMBERS_SLOT = 0;

  private static final GuildManageOpener INSTANCE = new GuildManageOpener();

  /** True between {@link #openManageMembers()} and the next matching
   *  {@link MenuEvent.MenuOpenedEvent.Pre}.  Single-shot so a stray
   *  Manage menu opened by the user manually is never auto-clicked. */
  private static volatile boolean armed = false;

  private GuildManageOpener() {}

  /**
   * Registers this opener with the Wynntils event bus. Must be called
   * after Wynntils has finished its own initialisation; see
   * {@code WynntilsEventListener.register()} for the timing.
   */
  public static void register() {
    WynntilsMod.registerEventListener(INSTANCE);
    VetsLogger.debug("Registered GuildManageOpener on Wynntils event bus");
  }

  /**
   * Sends {@code /guild manage} and arms the one-shot intercept so the
   * next matching menu auto-clicks {@link #MEMBERS_SLOT}.
   */
  public static void openManageMembers() {
    armed = true;
    Handlers.Command.sendCommandImmediately("guild manage");
  }

  @SubscribeEvent
  public void onMenuOpenPre(MenuEvent.MenuOpenedEvent.Pre event) {
    if (!armed) return;
    armed = false;

    // ContainerModel can't be used here — too early in the event chain.
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
}
