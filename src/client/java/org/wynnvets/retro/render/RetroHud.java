package org.wynnvets.retro.render;

import com.wynntils.core.components.Models;
import com.wynntils.models.worlds.type.WorldState;
import com.wynntils.utils.mc.McUtils;
import com.wynntils.utils.type.CappedValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.contextualbar.ContextualBarRenderer;
import net.minecraft.client.gui.contextualbar.ExperienceBarRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.wynnvets.listeners.ServerConnectionListener;
import org.wynnvets.mixin.client.accessors.GuiAccessor;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * Retro's HUD: while the retro pack is live on Wynncraft, vanilla's hearts show Wynncraft's
 * health, its food shows mana, and its experience bar and level number show combat experience.
 *
 * <p>Wynncraft draws its own HUD as glyphs in the action bar and blanks vanilla's HUD sprites,
 * which the retro pack restores ({@code same_name} in {@code retro_textures.yml}). Vanilla's own
 * health, food and experience never move with Wynncraft's, so the elements that draw them are
 * replaced through Fabric's {@link HudElementRegistry}, at vanilla's positions, each drawing
 * vanilla's element instead while retro does not draw the HUD:</p>
 * <ul>
 *   <li>{@link VanillaHudElements#HEALTH_BAR}: ten hearts from Wynntils' health
 *       ({@code Models.CharacterStats.getHealth()}). Health above its maximum is overhealth: the
 *       same row fills again from the left in orange, then yellow, then green, each a full row per
 *       maximum, in the {@code source_copies} hearts. They flash when the row falls
 *       ({@link RetroHeartFlash}) and shake while it is low, as vanilla's do.</li>
 *   <li>{@link VanillaHudElements#FOOD_BAR}: ten shanks from its mana ({@code getMana()}).</li>
 *   <li>{@link VanillaHudElements#MOUNT_HEALTH}: the same shanks, while the player rides a mount
 *       that has health, when vanilla asks for this in place of food.</li>
 *   <li>{@link VanillaHudElements#ARMOR_BAR}: ten armour icons above the hearts from Wynncraft's
 *       sprint or breath meter, filled from the left, while it is not full.</li>
 *   <li>{@link VanillaHudElements#AIR_BAR}: ten bubbles above the food from the ultimate's charge,
 *       filled from the right, while Wynncraft shows the ultimate: the last only once it is
 *       ready.</li>
 *   <li>{@link VanillaHudElements#INFO_BAR}: the experience bar from Wynntils' combat experience
 *       ({@code Models.CombatXp}), when vanilla draws its experience bar this frame
 *       ({@link GuiAccessor}); a mount's jump bar and the locator bar stay vanilla's. Then the
 *       level number, wherever vanilla would draw its own.</li>
 *   <li>{@link VanillaHudElements#EXPERIENCE_LEVEL}: nothing. Vanilla asks for it only while the
 *       player's vanilla level is above 0, which says nothing of Wynncraft's, so the info bar
 *       draws the level instead.</li>
 * </ul>
 *
 * <p>The values are drawn only in a world ({@code Models.WorldState.onWorld()}) and once Wynntils
 * has read them from the action bar; until then the rows are left out. Hearts and food are a row
 * of twenty halves, as vanilla's twenty points are ({@link #halves}, {@link #fill}): always one
 * row, which vanilla's status-bar heights already allow for; no height is registered with Fabric's
 * {@code HudStatusBarHeightRegistry}, since a registered one makes Fabric move the action bar,
 * where Wynncraft draws the rest of its HUD.</p>
 *
 * <p>{@link RetroActionBar} takes what these replace out of the action bar, and keeps in
 * {@link RetroHudReadings} what the armour, the bubbles and the numbers draw from it. The health
 * and mana numbers would lie over the armour and bubbles, so they are drawn outside the hotbar on
 * each side, by the experience bar, in Wynncraft's own glyphs ({@link #numbersMoved}).</p>
 *
 * <p>Retro draws the HUD while the pack is live, its {@code hud} part is on and the game is on
 * Wynncraft ({@link ServerConnectionListener#onWynncraft()}); elsewhere, such as singleplayer with
 * Wynntils' copy of Wynncraft's pack still loaded, vanilla's elements draw vanilla's values with
 * the sprites the pack restored, and with the part off the action bar stays whole and the sprites
 * stay Wynncraft's. A failure, reading, drawing or trimming the action bar, is logged once
 * and stops retro's HUD for the session: from then on the replaced elements draw nothing and the
 * action bar is left whole, so Wynncraft's HUD returns, drawn over the vanilla hotbar the pack
 * restored. Render thread only; the Wynntils calls run only while drawing and from the action bar
 * and world state events, after {@code CLIENT_STARTED}. The values' maths is on plain numbers and
 * is unit-tested; nothing static here touches Wynntils.</p>
 */
public final class RetroHud {

    private static final Identifier HEART_CONTAINER =
            Identifier.withDefaultNamespace("hud/heart/container");
    private static final Identifier HEART_CONTAINER_BLINKING =
            Identifier.withDefaultNamespace("hud/heart/container_blinking");
    private static final List<Identifier> OVERHEALTH_HALF =
            overhealthSprites(RetroAssetIds.HEART_HALF);
    private static final List<Identifier> OVERHEALTH_FULL =
            overhealthSprites(RetroAssetIds.HEART_FULL);
    private static final Identifier FOOD_EMPTY = Identifier.withDefaultNamespace("hud/food_empty");
    private static final Identifier FOOD_HALF = Identifier.withDefaultNamespace("hud/food_half");
    private static final Identifier FOOD_FULL = Identifier.withDefaultNamespace("hud/food_full");
    private static final Identifier ARMOUR_EMPTY =
            Identifier.withDefaultNamespace("hud/armor_empty");
    private static final Identifier ARMOUR_HALF = Identifier.withDefaultNamespace("hud/armor_half");
    private static final Identifier ARMOUR_FULL = Identifier.withDefaultNamespace("hud/armor_full");
    private static final Identifier AIR_EMPTY = Identifier.withDefaultNamespace("hud/air_empty");
    private static final Identifier AIR = Identifier.withDefaultNamespace("hud/air");
    private static final Identifier EXPERIENCE_BACKGROUND =
            Identifier.withDefaultNamespace("hud/experience_bar_background");
    private static final Identifier EXPERIENCE_PROGRESS =
            Identifier.withDefaultNamespace("hud/experience_bar_progress");

    /** The icons in a row. */
    static final int ICONS = 10;

    /** The halves of a full row: vanilla's twenty health or food points. */
    // Package-private for unit tests. See RetroHudTest.
    static final int HALVES = 2 * ICONS;

    /** At or below this many halves the hearts shake, as vanilla's do at four points. */
    private static final int SHAKE_HALVES = 4;

    /** Vanilla's seed for each tick's shake, per tick. */
    private static final long SHAKE_SEED = 312871L;

    /** A row that stays still. */
    private static final IntSupplier NO_SHAKE = () -> 0;

    /** The side of an icon. */
    private static final int ICON_SIZE = 9;

    /** From one icon to the next: they overlap by a pixel. */
    private static final int ICON_STEP = 8;

    /** Half the hotbar's width: the hearts start level with its left edge, food its right. */
    private static final int HALF_HOTBAR = 91;

    /** How far above the screen's bottom vanilla draws the hearts and food. */
    private static final int ROWS_ABOVE_BOTTOM = 39;

    /** How far above the screen's bottom vanilla draws the armour and air, over the first rows. */
    private static final int UPPER_ROWS_ABOVE_BOTTOM = 49;

    /**
     * How far above the screen's bottom the numbers' tops go: by the experience bar, under
     * Wynntils' overlays beside the hearts and food, and over the offhand slot and attack
     * indicator.
     */
    private static final int NUMBERS_ABOVE_BOTTOM = 31;

    /**
     * How far below where a string is drawn the numbers' glyphs start: 7 less the ascent, -19, of
     * their provider in Wynncraft's {@code bottom_middle} font ({@link RetroHudGlyphs#FONT}), so a
     * change to that provider in Wynncraft's pack moves the numbers.
     */
    private static final int NUMBER_GLYPH_DROP = 26;

    /** Between the hotbar's edge and a number. */
    private static final int NUMBER_GAP = 2;

    private static final int OPAQUE_WHITE = 0xFFFFFFFF;

    /** Vanilla's experience fill is its progress times this, one more than the bar's width. */
    private static final int PROGRESS_SCALE = ContextualBarRenderer.WIDTH + 1;

    /** Package-private for {@link RetroActionBar}, whose failures stop the HUD too. */
    static final RetroGuard GUARD =
            RetroGuard.stopping("the HUD", "Wynncraft's HUD shows until the game restarts");

    /** What {@link RetroActionBar} last read from the action bar. */
    static final RetroHudReadings READINGS = new RetroHudReadings();

    private static final RetroHeartFlash HEART_FLASH = new RetroHeartFlash();

    /** The corrupted bar that no longer counts ({@link #worldStateChanged}). */
    private static Object staleCorruptedBar;

    private RetroHud() {}

    private static List<Identifier> overhealthSprites(String fill) {
        return RetroAssetIds.OVERHEALTH_COLOURS.stream()
                .map(colour -> Identifier.parse(RetroAssetIds.overhealthSprite(colour, fill)))
                .toList();
    }

    /** What the replaced elements draw. */
    private enum Mode {
        /** Vanilla's elements: no {@code hud} part live, or the game is not on Wynncraft. */
        VANILLA,
        /** Retro's HUD. */
        RETRO,
        /** Nothing: retro's HUD failed, and vanilla's values would not move either. */
        NONE
    }

    /**
     * One fill of a row of icons.
     *
     * @param half the sprite of an icon's left half
     * @param full the sprite of a whole icon
     * @param halves how many of the row's halves it fills ({@link #halves})
     */
    private record Layer(Identifier half, Identifier full, int halves) {}

    /** Vanilla's kinds of heart that retro's draw as. */
    private enum HeartType {
        NORMAL(""),
        POISONED("poisoned_"),
        WITHERED("withered_");

        private final Identifier half;
        private final Identifier full;
        private final Identifier halfBlinking;
        private final Identifier fullBlinking;

        HeartType(String prefix) {
            String path = "hud/heart/" + prefix;
            half = Identifier.withDefaultNamespace(path + "half");
            full = Identifier.withDefaultNamespace(path + "full");
            halfBlinking = Identifier.withDefaultNamespace(path + "half_blinking");
            fullBlinking = Identifier.withDefaultNamespace(path + "full_blinking");
        }
    }

    /** How full one icon of a row is. */
    // Package-private for unit tests. See RetroHudTest.
    enum Fill {
        EMPTY,
        HALF,
        FULL
    }

    /**
     * Replaces vanilla's health, food, mount health, armour, air, info bar and experience level
     * elements. Call once, while the client initialises: the replacements touch Wynntils only while
     * drawing.
     */
    public static void register() {
        replace(VanillaHudElements.ARMOR_BAR, RetroHud::armour);
        replace(VanillaHudElements.AIR_BAR, RetroHud::bubbles);
        replace(VanillaHudElements.HEALTH_BAR, RetroHud::hearts);
        replace(VanillaHudElements.FOOD_BAR, RetroHud::shanks);
        replace(VanillaHudElements.MOUNT_HEALTH, RetroHud::mountShanks);
        HudElementRegistry.replaceElement(
                VanillaHudElements.INFO_BAR,
                vanilla -> (graphics, delta) -> infoBar(graphics, delta, vanilla));
        HudElementRegistry.replaceElement(
                VanillaHudElements.EXPERIENCE_LEVEL,
                vanilla -> (graphics, delta) -> experienceLevel(graphics, delta, vanilla));
    }

    /** Replaces one element with what {@link #draw} picks: vanilla's, {@code retro} or nothing. */
    private static void replace(Identifier element, Consumer<GuiGraphics> retro) {
        HudElementRegistry.replaceElement(
                element, vanilla -> (graphics, delta) -> draw(graphics, delta, vanilla, retro));
    }

    /**
     * Whether retro draws the health and mana numbers, and {@link RetroActionBar} takes them out:
     * in a world, and while vanilla asks for the hearts and food, which it does only while the
     * player can be hurt and the camera is a player's.
     *
     * @return whether they are moved
     */
    static boolean numbersMoved() {
        Minecraft mc = McUtils.mc();
        return Models.WorldState.onWorld()
                && mc.gameMode.canHurtPlayer()
                && mc.getCameraEntity() instanceof Player;
    }

    /**
     * Whether retro's HUD is what the replaced elements draw now.
     *
     * @return whether it is
     */
    static boolean drawsRetro() {
        return mode() == Mode.RETRO;
    }

    private static Mode mode() {
        if (!RetroModeManager.isPartLive(RetroPart.HUD)) return Mode.VANILLA;
        // Asked even once stopped: off Wynncraft, vanilla's elements still draw.
        if (!GUARD.callEvenIfStopped(ServerConnectionListener::onWynncraft, true)) {
            return Mode.VANILLA;
        }
        return GUARD.stopped() ? Mode.NONE : Mode.RETRO;
    }

    /** One replaced element: vanilla's, retro's, or nothing, as {@link #mode()} says. */
    private static void draw(
            GuiGraphics graphics,
            DeltaTracker delta,
            HudElement vanilla,
            Consumer<GuiGraphics> retro) {
        switch (mode()) {
            case VANILLA -> vanilla.render(graphics, delta);
            case RETRO -> GUARD.run(() -> retro.accept(graphics));
            case NONE -> {}
        }
    }

    /** Vanilla swaps food for the mount's hearts while riding; mana stays where the food was. */
    private static void mountShanks(GuiGraphics graphics) {
        if (McUtils.player().getVehicle() instanceof LivingEntity mount
                && mount.showVehicleHealth()) {
            shanks(graphics);
        }
    }

    private static void infoBar(GuiGraphics graphics, DeltaTracker delta, HudElement vanilla) {
        Mode mode = mode();
        if (mode == Mode.VANILLA) {
            vanilla.render(graphics, delta);
            return;
        }
        // Read even once stopped: only the experience bar gives way to nothing.
        ContextualBarRenderer bar =
                GUARD.callEvenIfStopped(
                        () ->
                                ((GuiAccessor) McUtils.mc().gui)
                                        .vetsmod$getContextualInfoBar()
                                        .getValue(),
                        null);
        if (bar == null) {
            vanilla.render(graphics, delta);
            return;
        }
        if (!(bar instanceof ExperienceBarRenderer)) {
            vanilla.render(graphics, delta);
        } else if (mode == Mode.RETRO) {
            GUARD.run(() -> experienceBar(graphics, bar));
        }
        if (mode == Mode.RETRO) GUARD.run(() -> level(graphics));
    }

    private static void experienceLevel(
            GuiGraphics graphics, DeltaTracker delta, HudElement vanilla) {
        if (mode() == Mode.VANILLA) vanilla.render(graphics, delta);
    }

    /**
     * Wynntils' health as hearts, filled from the left: poisoned while the player has vanilla's
     * poison effect, which Wynncraft sends, and otherwise withered while Wynntils reads a current
     * corrupted bar from the boss bars ({@link #worldStateChanged}), poison first as in vanilla.
     * Overhealth colours over any of them ({@link #overhealthHalves}), so its amount always shows,
     * though vanilla's absorption hearts wither. They flash as vanilla's do when the row falls
     * ({@link RetroHeartFlash}), and shake while it is low.
     */
    private static void hearts(GuiGraphics graphics) {
        healthNumber(graphics);
        Optional<CappedValue> health =
                Models.WorldState.onWorld() ? Models.CharacterStats.getHealth() : Optional.empty();
        if (health.isEmpty()) {
            HEART_FLASH.reset();
            return;
        }
        int current = health.get().current();
        int max = health.get().max();
        int halves = halves(current, max);
        int tick = McUtils.mc().gui.getGuiTicks();
        HEART_FLASH.update(current, halves, tick, Util.getMillis());
        boolean flashing = HEART_FLASH.flashing(tick);
        HeartType type =
                McUtils.player().hasEffect(MobEffects.POISON)
                        ? HeartType.POISONED
                        : isCurrentBar(Models.Ability.corruptedBar.getEvent(), staleCorruptedBar)
                                ? HeartType.WITHERED
                                : HeartType.NORMAL;
        List<Layer> layers = new ArrayList<>();
        if (flashing) {
            layers.add(new Layer(type.halfBlinking, type.fullBlinking, HEART_FLASH.shownHalves()));
        }
        layers.add(new Layer(type.half, type.full, halves));
        int over = overhealthHalves(current, max);
        for (int colour = 0; colour < OVERHEALTH_HALF.size(); colour++) {
            layers.add(
                    new Layer(
                            OVERHEALTH_HALF.get(colour),
                            OVERHEALTH_FULL.get(colour),
                            overhealthColourHalves(colour, over)));
        }
        row(
                graphics,
                graphics.guiWidth() / 2 - HALF_HOTBAR,
                ROWS_ABOVE_BOTTOM,
                ICON_STEP,
                flashing ? HEART_CONTAINER_BLINKING : HEART_CONTAINER,
                true,
                layers,
                halves <= SHAKE_HALVES ? shake(tick) : NO_SHAKE);
    }

    /** The health number, outside the hotbar on the hearts' side. */
    private static void healthNumber(GuiGraphics graphics) {
        Component number = number(READINGS.healthNumber(Util.getMillis()));
        if (number == null) return;
        Font font = McUtils.mc().font;
        int x = graphics.guiWidth() / 2 - HALF_HOTBAR - NUMBER_GAP - font.width(number);
        drawNumber(graphics, font, number, x);
    }

    /** The mana number, outside the hotbar on the food's side. */
    private static void manaNumber(GuiGraphics graphics) {
        Component number = number(READINGS.manaNumber(Util.getMillis()));
        if (number == null) return;
        int x = graphics.guiWidth() / 2 + HALF_HOTBAR + NUMBER_GAP;
        drawNumber(graphics, McUtils.mc().font, number, x);
    }

    /** A number's glyphs in Wynncraft's font, or {@code null} where none is to be drawn. */
    private static Component number(String glyphs) {
        if (glyphs == null || !numbersMoved()) return null;
        return Component.literal(glyphs).withStyle(style -> style.withFont(RetroHudGlyphs.FONT));
    }

    /** With no shadow: the glyphs carry their own grey edge. */
    private static void drawNumber(GuiGraphics graphics, Font font, Component number, int x) {
        int y = graphics.guiHeight() - NUMBERS_ABOVE_BOTTOM - NUMBER_GLYPH_DROP;
        graphics.drawString(font, number, x, y, OPAQUE_WHITE, false);
    }

    /**
     * Forgets what was read from the action bar, and notes the corrupted bar Wynntils holds
     * whenever the player is not in a world. Wynntils forgets a bar only when the server removes
     * it, which a dropped connection never does, so {@link #hearts} counts only a bar Wynncraft has
     * sent since ({@link #isCurrentBar}). Wynntils posts this on a disconnect too.
     *
     * @param newState the state Wynntils' world state just became
     */
    public static void worldStateChanged(WorldState newState) {
        READINGS.clear();
        if (newState == WorldState.WORLD) return;
        GUARD.run(() -> staleCorruptedBar = Models.Ability.corruptedBar.getEvent());
    }

    /**
     * Whether Wynntils' corrupted bar counts: it holds one, and not the one
     * {@link #worldStateChanged} noted. A new bar is always a new object.
     *
     * @param bar the bar's event, {@code null} while Wynntils holds none
     * @param stale the event it held when the player last left a world
     * @return whether the bar counts
     */
    // Package-private for unit tests. See RetroHudTest.
    static boolean isCurrentBar(Object bar, Object stale) {
        return bar != null && bar != stale;
    }

    /** Each icon a pixel down or not, the same for a whole tick, as vanilla shakes its hearts. */
    private static IntSupplier shake(int tick) {
        Random random = new Random(tick * SHAKE_SEED);
        return () -> random.nextInt(2);
    }

    /** Wynntils' mana as shanks, filled from the right as vanilla's food is. */
    private static void shanks(GuiGraphics graphics) {
        manaNumber(graphics);
        if (!Models.WorldState.onWorld()) return;
        Optional<CappedValue> mana = Models.CharacterStats.getMana();
        if (mana.isEmpty()) return;
        int halves = halves(mana.get().current(), mana.get().max());
        row(
                graphics,
                rightmostIcon(graphics),
                ROWS_ABOVE_BOTTOM,
                -ICON_STEP,
                FOOD_EMPTY,
                true,
                List.of(new Layer(FOOD_HALF, FOOD_FULL, halves)),
                NO_SHAKE);
    }

    /** Wynncraft's sprint or breath meter as armour, filled from the left. */
    private static void armour(GuiGraphics graphics) {
        if (!Models.WorldState.onWorld()) return;
        int halves = READINGS.armourHalves(Util.getMillis());
        if (halves == RetroHudReadings.NO_ROW) return;
        row(
                graphics,
                graphics.guiWidth() / 2 - HALF_HOTBAR,
                UPPER_ROWS_ABOVE_BOTTOM,
                ICON_STEP,
                ARMOUR_EMPTY,
                true,
                List.of(new Layer(ARMOUR_HALF, ARMOUR_FULL, halves)),
                NO_SHAKE);
    }

    /**
     * The ultimate's charge as bubbles, filled from the right as vanilla's air is. A bubble is
     * whole or empty, and its hollow middle would show an empty one's dot, as vanilla's never does.
     */
    private static void bubbles(GuiGraphics graphics) {
        if (!Models.WorldState.onWorld()) return;
        int bubbles = READINGS.bubbles(Util.getMillis());
        if (bubbles == RetroHudReadings.NO_ROW) return;
        row(
                graphics,
                rightmostIcon(graphics),
                UPPER_ROWS_ABOVE_BOTTOM,
                -ICON_STEP,
                AIR_EMPTY,
                false,
                List.of(new Layer(AIR, AIR, 2 * bubbles)),
                NO_SHAKE);
    }

    /** The left edge of the icon that ends level with the hotbar's right edge. */
    private static int rightmostIcon(GuiGraphics graphics) {
        return graphics.guiWidth() / 2 + HALF_HOTBAR - ICON_SIZE;
    }

    /**
     * Draws a row of icons as vanilla draws its hearts and food: each icon's empty sprite, then
     * each layer's half or whole fill over it, the icons from right to left, so each icon's left
     * edge lies over the pixel it shares with its right-hand neighbour.
     *
     * @param first the left edge of the icon filled first
     * @param aboveBottom how far above the screen's bottom the row's top is
     * @param step from that icon to the next: positive for a row filled from the left, negative
     *     for one filled from the right
     * @param emptyUnderFill whether a filled icon has the empty sprite under it too
     * @param layers what fills the row, the first drawn first
     * @param shake how far down the next icon drawn goes
     */
    private static void row(
            GuiGraphics graphics,
            int first,
            int aboveBottom,
            int step,
            Identifier empty,
            boolean emptyUnderFill,
            List<Layer> layers,
            IntSupplier shake) {
        int top = graphics.guiHeight() - aboveBottom;
        for (int drawn = 0; drawn < ICONS; drawn++) {
            int icon = step > 0 ? ICONS - 1 - drawn : drawn;
            int x = first + icon * step;
            int y = top + shake.getAsInt();
            if (emptyUnderFill || unfilled(icon, layers)) {
                graphics.blitSprite(
                        RenderPipelines.GUI_TEXTURED, empty, x, y, ICON_SIZE, ICON_SIZE);
            }
            for (Layer layer : layers) {
                Identifier sprite =
                        switch (fill(icon, layer.halves())) {
                            case EMPTY -> null;
                            case HALF -> layer.half();
                            case FULL -> layer.full();
                        };
                if (sprite != null) {
                    graphics.blitSprite(
                            RenderPipelines.GUI_TEXTURED, sprite, x, y, ICON_SIZE, ICON_SIZE);
                }
            }
        }
    }

    private static boolean unfilled(int icon, List<Layer> layers) {
        return layers.stream().allMatch(layer -> fill(icon, layer.halves()) == Fill.EMPTY);
    }

    /** Wynntils' combat experience on vanilla's bar, where {@code bar} would draw vanilla's. */
    private static void experienceBar(GuiGraphics graphics, ContextualBarRenderer bar) {
        if (!Models.WorldState.onWorld() || Models.CombatXp.getCombatLevel().current() <= 0) {
            return;
        }
        CappedValue xp = Models.CombatXp.getXp();
        int left = bar.left(McUtils.window());
        int top = bar.top(McUtils.window());
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                EXPERIENCE_BACKGROUND,
                left,
                top,
                ContextualBarRenderer.WIDTH,
                ContextualBarRenderer.HEIGHT);
        int width = progressWidth(xp.current(), xp.max());
        if (width > 0) {
            graphics.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    EXPERIENCE_PROGRESS,
                    ContextualBarRenderer.WIDTH,
                    ContextualBarRenderer.HEIGHT,
                    0,
                    0,
                    left,
                    top,
                    width,
                    ContextualBarRenderer.HEIGHT);
        }
    }

    /** Wynntils' combat level as vanilla's green number, under vanilla's own conditions. */
    private static void level(GuiGraphics graphics) {
        Minecraft mc = McUtils.mc();
        if (!mc.gameMode.hasExperience() || !Models.WorldState.onWorld()) return;
        int level = Models.CombatXp.getCombatLevel().current();
        if (level > 0) ContextualBarRenderer.renderExperienceLevel(graphics, mc.font, level);
    }

    /**
     * How many of a row's {@link #HALVES} halves a value fills: none at 0 or below, all of them
     * only at its maximum or above, and otherwise the halves it covers in full, but at least one.
     * So a row shows any loss, unlike vanilla's (which rounds up), and is never empty while
     * anything is left.
     *
     * @param current the value
     * @param max its maximum; none is filled when it is 0 or below
     * @return the filled halves, from 0 to {@link #HALVES}
     */
    // Package-private for unit tests. See RetroHudTest.
    static int halves(int current, int max) {
        if (current <= 0 || max <= 0) return 0;
        if (current >= max) return HALVES;
        return Math.max(1, (int) ((long) HALVES * current / max));
    }

    /**
     * How many halves overhealth fills, counted through its colours: a full row of them for each
     * time the maximum is exceeded over again, rounded down but at least one while there is any,
     * and no more than every colour's row.
     *
     * @param current the health
     * @param max its maximum; none is filled when it is 0 or below
     * @return the filled halves, from 0 to {@link #HALVES} times the colours
     */
    // Package-private for unit tests. See RetroHudTest.
    static int overhealthHalves(int current, int max) {
        if (max <= 0 || current <= max) return 0;
        long halves = Math.max(1, (long) HALVES * (current - max) / max);
        return (int) Math.min(RetroAssetIds.OVERHEALTH_COLOURS.size() * HALVES, halves);
    }

    /**
     * How many of a row's halves one overhealth colour fills: each starts from the left over the
     * one before it, once that has filled the row.
     *
     * @param colour the colour, from 0
     * @param over {@link #overhealthHalves}
     * @return the filled halves, from 0 to {@link #HALVES}
     */
    // Package-private for unit tests. See RetroHudTest.
    static int overhealthColourHalves(int colour, int over) {
        return Math.max(0, Math.min(HALVES, over - colour * HALVES));
    }

    /**
     * How full one icon of a row is, as vanilla fills its hearts and food.
     *
     * @param icon the icon, from 0, the one filled first
     * @param halves the row's filled halves
     * @return {@link Fill#FULL} when both its halves are filled, {@link Fill#HALF} when only the
     *     first is
     */
    // Package-private for unit tests. See RetroHudTest.
    static Fill fill(int icon, int halves) {
        int filled = halves - 2 * icon;
        if (filled >= 2) return Fill.FULL;
        return filled == 1 ? Fill.HALF : Fill.EMPTY;
    }

    /**
     * How wide the experience bar's fill is, as vanilla works it out
     * ({@code ExperienceBarRenderer}: the progress times {@link #PROGRESS_SCALE}, in whole pixels),
     * never wider than the bar.
     *
     * @param current the experience gained towards the next level
     * @param max the experience the level needs; no fill when it is 0 or below
     * @return the fill's width in pixels, from 0 to {@code ContextualBarRenderer.WIDTH}
     */
    // Package-private for unit tests. See RetroHudTest.
    static int progressWidth(int current, int max) {
        if (current <= 0 || max <= 0) return 0;
        return (int) Math.min(ContextualBarRenderer.WIDTH, (long) PROGRESS_SCALE * current / max);
    }
}
