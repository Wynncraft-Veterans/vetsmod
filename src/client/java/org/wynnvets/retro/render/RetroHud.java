package org.wynnvets.retro.render;

import com.wynntils.core.components.Models;
import com.wynntils.handlers.actionbar.event.ActionBarRenderEvent;
import com.wynntils.models.characterstats.actionbar.segments.CombatExperienceSegment;
import com.wynntils.models.characterstats.actionbar.segments.HealthBarSegment;
import com.wynntils.models.characterstats.actionbar.segments.HotbarSegment;
import com.wynntils.models.characterstats.actionbar.segments.LevelSegment;
import com.wynntils.models.characterstats.actionbar.segments.ManaBarSegment;
import com.wynntils.models.characterstats.actionbar.segments.MeterBarSegment;
import com.wynntils.models.characterstats.actionbar.segments.MeterEdgeAnimationSegment;
import com.wynntils.models.characterstats.actionbar.segments.MeterTransitionSegment;
import com.wynntils.models.characterstats.actionbar.segments.ProfessionExperienceSegment;
import com.wynntils.models.characterstats.actionbar.segments.UltimateReadyTransitionSegment;
import com.wynntils.models.spells.actionbar.segments.UltimateSegment;
import com.wynntils.utils.mc.McUtils;
import com.wynntils.utils.type.CappedValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.contextualbar.ContextualBarRenderer;
import net.minecraft.client.gui.contextualbar.ExperienceBarRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
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
 *       maximum, in the {@code source_copies} hearts.</li>
 *   <li>{@link VanillaHudElements#FOOD_BAR}: ten shanks from its mana ({@code getMana()}).</li>
 *   <li>{@link VanillaHudElements#MOUNT_HEALTH}: the same shanks, while the player rides a mount
 *       that has health, when vanilla asks for this in place of food.</li>
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
 * <p>{@link #hideWynncraftsBars}, for
 * {@link org.wynnvets.listeners.RetroActionBarListener RetroActionBarListener}, takes out of the
 * action bar what Wynncraft draws where these go and the ornate frame round the hotbar, which
 * the vanilla hotbar the pack restores replaces; what it writes there as text, its health and
 * mana numbers and the spell inputs, stays.</p>
 *
 * <p>Retro draws the HUD while the pack is live, its {@code hud} part is on and the game is on
 * Wynncraft ({@link ServerConnectionListener#onWynncraft()}); elsewhere, such as singleplayer with
 * Wynntils' copy of Wynncraft's pack still loaded, vanilla's elements draw vanilla's values with
 * the sprites the pack restored, and with the part off the action bar stays whole and the sprites
 * stay Wynncraft's. A failure, drawing or trimming the action bar, is logged once
 * and stops retro's HUD for the session: from then on the replaced elements draw nothing and the
 * action bar is left whole, so Wynncraft's HUD returns, drawn over the vanilla hotbar the pack
 * restored. Render thread only; the Wynntils calls run only while drawing, after
 * {@code CLIENT_STARTED}. The values' maths is on plain numbers and is unit-tested; nothing
 * static here touches Wynntils.</p>
 */
public final class RetroHud {

    private static final Identifier HEART_CONTAINER =
            Identifier.withDefaultNamespace("hud/heart/container");
    private static final Identifier HEART_HALF = Identifier.withDefaultNamespace("hud/heart/half");
    private static final Identifier HEART_FULL = Identifier.withDefaultNamespace("hud/heart/full");
    private static final Identifier HEART_POISONED_HALF =
            Identifier.withDefaultNamespace("hud/heart/poisoned_half");
    private static final Identifier HEART_POISONED_FULL =
            Identifier.withDefaultNamespace("hud/heart/poisoned_full");
    private static final List<Identifier> OVERHEALTH_HALF =
            overhealthSprites(RetroAssetIds.HEART_HALF);
    private static final List<Identifier> OVERHEALTH_FULL =
            overhealthSprites(RetroAssetIds.HEART_FULL);
    private static final Identifier FOOD_EMPTY = Identifier.withDefaultNamespace("hud/food_empty");
    private static final Identifier FOOD_HALF = Identifier.withDefaultNamespace("hud/food_half");
    private static final Identifier FOOD_FULL = Identifier.withDefaultNamespace("hud/food_full");
    private static final Identifier EXPERIENCE_BACKGROUND =
            Identifier.withDefaultNamespace("hud/experience_bar_background");
    private static final Identifier EXPERIENCE_PROGRESS =
            Identifier.withDefaultNamespace("hud/experience_bar_progress");

    /** The icons in a row of hearts or shanks. */
    private static final int ICONS = 10;

    /** The halves of a full row: vanilla's twenty health or food points. */
    // Package-private for unit tests. See RetroHudTest.
    static final int HALVES = 2 * ICONS;

    /** The side of a heart or shank. */
    private static final int ICON_SIZE = 9;

    /** From one icon to the next: they overlap by a pixel. */
    private static final int ICON_STEP = 8;

    /** Half the hotbar's width: the hearts start level with its left edge, food its right. */
    private static final int HALF_HOTBAR = 91;

    /** How far above the screen's bottom vanilla draws the hearts and food. */
    private static final int ROWS_ABOVE_BOTTOM = 39;

    /** Vanilla's experience fill is its progress times this, one more than the bar's width. */
    private static final int PROGRESS_SCALE = ContextualBarRenderer.WIDTH + 1;

    private static final RetroGuard GUARD =
            RetroGuard.stopping("the HUD", "Wynncraft's HUD shows until the game restarts");

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

    /** How full one icon of a row is. */
    // Package-private for unit tests. See RetroHudTest.
    enum Fill {
        EMPTY,
        HALF,
        FULL
    }

    /**
     * Replaces vanilla's health, food, mount health, info bar and experience level elements. Call
     * once, while the client initialises: the replacements touch Wynntils only while drawing.
     */
    public static void register() {
        HudElementRegistry.replaceElement(
                VanillaHudElements.HEALTH_BAR,
                vanilla ->
                        (graphics, delta) ->
                                draw(graphics, delta, vanilla, () -> hearts(graphics)));
        HudElementRegistry.replaceElement(
                VanillaHudElements.FOOD_BAR,
                vanilla ->
                        (graphics, delta) ->
                                draw(graphics, delta, vanilla, () -> shanks(graphics)));
        HudElementRegistry.replaceElement(
                VanillaHudElements.MOUNT_HEALTH,
                vanilla ->
                        (graphics, delta) ->
                                draw(graphics, delta, vanilla, () -> mountShanks(graphics)));
        HudElementRegistry.replaceElement(
                VanillaHudElements.INFO_BAR,
                vanilla -> (graphics, delta) -> infoBar(graphics, delta, vanilla));
        HudElementRegistry.replaceElement(
                VanillaHudElements.EXPERIENCE_LEVEL,
                vanilla -> (graphics, delta) -> experienceLevel(graphics, delta, vanilla));
    }

    /**
     * Takes out of the action bar what Wynncraft draws where retro's HUD goes, while retro draws
     * it: its health and mana bars, the ornate frame round the hotbar, its experience bar (combat,
     * or a profession's while gathering), and the badge above the hotbar's middle, a hexagon with
     * the level in it whose rim shows sprint and breath and which holds the ultimate's charge,
     * with its animations: the vanilla experience bar and level number go there. The health and
     * mana numbers and the spell inputs stay, and so does the ultimate's "F" prompt above them.
     * Wynntils still reads every segment; this only changes what the action bar draws.
     * {@code setSegmentEnabled} takes out the first segment of each class.
     *
     * @param event the action bar about to be drawn
     */
    public static void hideWynncraftsBars(ActionBarRenderEvent event) {
        if (mode() != Mode.RETRO) return;
        GUARD.run(
                () -> {
                    event.setSegmentEnabled(HealthBarSegment.class, false);
                    event.setSegmentEnabled(ManaBarSegment.class, false);
                    event.setSegmentEnabled(HotbarSegment.class, false);
                    event.setSegmentEnabled(CombatExperienceSegment.class, false);
                    event.setSegmentEnabled(ProfessionExperienceSegment.class, false);
                    event.setSegmentEnabled(LevelSegment.class, false);
                    event.setSegmentEnabled(MeterBarSegment.class, false);
                    event.setSegmentEnabled(MeterEdgeAnimationSegment.class, false);
                    event.setSegmentEnabled(MeterTransitionSegment.class, false);
                    event.setSegmentEnabled(UltimateSegment.class, false);
                    event.setSegmentEnabled(UltimateReadyTransitionSegment.class, false);
                });
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
            GuiGraphics graphics, DeltaTracker delta, HudElement vanilla, Runnable retro) {
        switch (mode()) {
            case VANILLA -> vanilla.render(graphics, delta);
            case RETRO -> GUARD.run(retro);
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
     * Wynntils' health as hearts, filled from the left, poisoned while the player has vanilla's
     * poison effect, which Wynncraft sends, or Wynntils reads Wynncraft's corrupted bar from the
     * boss bars. Overhealth colours over them, whether or not they are poisoned, as vanilla's
     * absorption hearts stay gold ({@link #overhealthHalves}).
     */
    private static void hearts(GuiGraphics graphics) {
        if (!Models.WorldState.onWorld()) return;
        Optional<CappedValue> health = Models.CharacterStats.getHealth();
        if (health.isEmpty()) return;
        int current = health.get().current();
        int max = health.get().max();
        boolean poisoned =
                McUtils.player().hasEffect(MobEffects.POISON)
                        || Models.Ability.corruptedBar.isActive();
        List<Layer> layers = new ArrayList<>();
        layers.add(
                new Layer(
                        poisoned ? HEART_POISONED_HALF : HEART_HALF,
                        poisoned ? HEART_POISONED_FULL : HEART_FULL,
                        halves(current, max)));
        int over = overhealthHalves(current, max);
        for (int colour = 0; colour < OVERHEALTH_HALF.size(); colour++) {
            layers.add(
                    new Layer(
                            OVERHEALTH_HALF.get(colour),
                            OVERHEALTH_FULL.get(colour),
                            overhealthColourHalves(colour, over)));
        }
        row(graphics, graphics.guiWidth() / 2 - HALF_HOTBAR, ICON_STEP, HEART_CONTAINER, layers);
    }

    /** Wynntils' mana as shanks, filled from the right as vanilla's food is. */
    private static void shanks(GuiGraphics graphics) {
        if (!Models.WorldState.onWorld()) return;
        Optional<CappedValue> mana = Models.CharacterStats.getMana();
        if (mana.isEmpty()) return;
        int rightmost = graphics.guiWidth() / 2 + HALF_HOTBAR - ICON_SIZE;
        int halves = halves(mana.get().current(), mana.get().max());
        row(
                graphics,
                rightmost,
                -ICON_STEP,
                FOOD_EMPTY,
                List.of(new Layer(FOOD_HALF, FOOD_FULL, halves)));
    }

    /**
     * Draws a row of icons as vanilla draws its hearts and food: each icon's empty sprite, then
     * each layer's half or whole fill over it, the icons from right to left, so each icon's left
     * edge lies over the pixel it shares with its right-hand neighbour.
     *
     * @param first the left edge of the icon filled first
     * @param step from that icon to the next: positive for a row filled from the left, negative
     *     for one filled from the right
     * @param layers what fills the row, the first drawn first
     */
    private static void row(
            GuiGraphics graphics, int first, int step, Identifier empty, List<Layer> layers) {
        int y = graphics.guiHeight() - ROWS_ABOVE_BOTTOM;
        for (int drawn = 0; drawn < ICONS; drawn++) {
            int icon = step > 0 ? ICONS - 1 - drawn : drawn;
            int x = first + icon * step;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, empty, x, y, ICON_SIZE, ICON_SIZE);
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
