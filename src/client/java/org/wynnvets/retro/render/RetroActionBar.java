package org.wynnvets.retro.render;

import com.wynntils.handlers.actionbar.ActionBarSegment;
import com.wynntils.handlers.actionbar.event.ActionBarRenderEvent;
import com.wynntils.handlers.actionbar.event.ActionBarUpdatedEvent;
import com.wynntils.models.characterstats.actionbar.segments.CombatExperienceSegment;
import com.wynntils.models.characterstats.actionbar.segments.HealthBarSegment;
import com.wynntils.models.characterstats.actionbar.segments.HealthTextSegment;
import com.wynntils.models.characterstats.actionbar.segments.HotbarSegment;
import com.wynntils.models.characterstats.actionbar.segments.LevelSegment;
import com.wynntils.models.characterstats.actionbar.segments.ManaBarSegment;
import com.wynntils.models.characterstats.actionbar.segments.ManaTextSegment;
import com.wynntils.models.characterstats.actionbar.segments.MeterBarSegment;
import com.wynntils.models.characterstats.actionbar.segments.MeterEdgeAnimationSegment;
import com.wynntils.models.characterstats.actionbar.segments.MeterTransitionSegment;
import com.wynntils.models.characterstats.actionbar.segments.ProfessionExperienceSegment;
import com.wynntils.models.characterstats.actionbar.segments.UltimateReadyActivateSegment;
import com.wynntils.models.characterstats.actionbar.segments.UltimateReadyTransitionSegment;
import com.wynntils.models.spells.actionbar.segments.UltimateSegment;
import com.wynntils.models.spells.type.UltimateInfo;
import com.wynntils.utils.type.CappedValue;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.util.Util;
import org.apache.commons.lang3.reflect.FieldUtils;

/**
 * What retro's HUD does with Wynncraft's action bar, for
 * {@link org.wynnvets.listeners.RetroActionBarListener RetroActionBarListener}: takes out what
 * {@link RetroHud} draws in its place, and keeps in {@link RetroHud#READINGS} what it draws from
 * the bar itself, since Wynntils keeps neither breath nor charge. Render thread only.
 */
public final class RetroActionBar {

    /**
     * Only the bubbles depend on this read, of a field Wynntils could rename, so failing it leaves
     * the rest of the HUD drawn.
     */
    private static final RetroGuard CHARGE_GUARD =
            RetroGuard.retrying("reading the ultimate's charge", "no charge in the bubbles");

    /** As {@link #CHARGE_GUARD}: this reads a private map; the event's own method still works. */
    private static final RetroGuard ALL_SEGMENTS_GUARD =
            RetroGuard.retrying(
                    "taking every segment of a class out of the action bar",
                    "parts of Wynncraft's badge may show over retro's rows");

    private RetroActionBar() {}

    /**
     * Takes out of the action bar what Wynncraft draws where retro's HUD goes, while retro draws
     * it: its health and mana bars, the ornate frame round the hotbar, its experience bar (combat,
     * or a profession's while gathering), and the badge above the hotbar's middle, a hexagon with
     * the level in it whose rim shows sprint and breath and which holds the ultimate's charge, with
     * its animations. The health and mana numbers go too, while {@link RetroHud#numbersMoved}. The
     * spell inputs stay, and so does the ultimate's "F" prompt above them. Wynntils still reads
     * every segment; this only changes what the action bar draws.
     *
     * <p>Whatever the mode, it first notes that a bar arrived, which keeps the readings fresh.
     * {@code setSegmentEnabled} takes out the first segment of each class, so the two classes that
     * one bar can hold more than once, while the badge changes to or from the ultimate's, go
     * through {@link #hideAll}.</p>
     *
     * @param event the action bar about to be drawn
     */
    public static void hideWynncraftsBars(ActionBarRenderEvent event) {
        RetroHud.READINGS.heard(Util.getMillis());
        if (!RetroHud.drawsRetro()) return;
        RetroHud.GUARD.run(
                () -> {
                    if (RetroHud.numbersMoved()) {
                        event.setSegmentEnabled(HealthTextSegment.class, false);
                        event.setSegmentEnabled(ManaTextSegment.class, false);
                        event.setSegmentEnabled(
                                RetroNumberSegmentMatcher.HealthSegment.class, false);
                        event.setSegmentEnabled(RetroNumberSegmentMatcher.ManaSegment.class, false);
                    }
                    event.setSegmentEnabled(HealthBarSegment.class, false);
                    event.setSegmentEnabled(ManaBarSegment.class, false);
                    event.setSegmentEnabled(HotbarSegment.class, false);
                    event.setSegmentEnabled(CombatExperienceSegment.class, false);
                    event.setSegmentEnabled(ProfessionExperienceSegment.class, false);
                    event.setSegmentEnabled(LevelSegment.class, false);
                    event.setSegmentEnabled(RetroLevelSegmentMatcher.Segment.class, false);
                    hideAll(event, MeterBarSegment.class);
                    event.setSegmentEnabled(MeterEdgeAnimationSegment.class, false);
                    hideAll(event, MeterTransitionSegment.class);
                    event.setSegmentEnabled(UltimateSegment.class, false);
                    event.setSegmentEnabled(UltimateReadyTransitionSegment.class, false);
                });
    }

    /**
     * Takes every segment of a class out of the action bar, by reflection, since the event's own
     * method stops at the first. If its map cannot be read, the first still goes.
     */
    private static void hideAll(
            ActionBarRenderEvent event, Class<? extends ActionBarSegment> segment) {
        event.setSegmentEnabled(segment, false);
        ALL_SEGMENTS_GUARD.run(
                () -> {
                    @SuppressWarnings("unchecked")
                    Map<ActionBarSegment, Boolean> segments =
                            (Map<ActionBarSegment, Boolean>) privateField(event, "segments");
                    segments.replaceAll(
                            (candidate, enabled) -> enabled && !segment.isInstance(candidate));
                });
    }

    /**
     * Reads what retro draws from the action bar itself. It reads whatever the mode: Wynntils posts
     * this only when the bar changes, and a player standing still at full health and mana keeps one
     * bar for as long as they like, which retro then switched on must already have read.
     *
     * @param event the action bar, changed
     */
    public static void readBar(ActionBarUpdatedEvent event) {
        RetroHud.GUARD.run(
                () -> {
                    if (event.getSegments().isEmpty()) {
                        RetroHud.READINGS.clear();
                    } else {
                        RetroHud.READINGS.update(bar(event));
                    }
                });
    }

    private static RetroHudReadings.Bar bar(ActionBarUpdatedEvent event) {
        return new RetroHudReadings.Bar(
                RetroHudReadings.shownMeter(
                        all(event, MeterBarSegment.class)
                                .map(meter -> value(meter.getMeterBarInfo().value()))
                                .toList()),
                has(event, MeterEdgeAnimationSegment.class)
                        || has(event, MeterTransitionSegment.class),
                has(event, UltimateReadyTransitionSegment.class)
                        || has(event, UltimateReadyActivateSegment.class),
                first(event, UltimateSegment.class).map(RetroActionBar::charge).orElse(null),
                has(event, LevelSegment.class)
                        || has(event, RetroLevelSegmentMatcher.Segment.class),
                glyphs(
                        event,
                        HealthTextSegment.class,
                        RetroNumberSegmentMatcher.HealthSegment.class),
                glyphs(event, ManaTextSegment.class, RetroNumberSegmentMatcher.ManaSegment.class));
    }

    /** A number's glyphs from Wynntils' segment or else retro's, or {@code null} with neither. */
    private static String glyphs(
            ActionBarUpdatedEvent event,
            Class<? extends ActionBarSegment> wynntils,
            Class<? extends ActionBarSegment> retro) {
        return first(event, wynntils)
                .map(ActionBarSegment::getSegmentText)
                .or(() -> first(event, retro).map(ActionBarSegment::getSegmentText))
                .map(RetroHudGlyphs::numberGlyphs)
                .orElse(null);
    }

    private static <T extends ActionBarSegment> Optional<T> first(
            ActionBarUpdatedEvent event, Class<T> segment) {
        return all(event, segment).findFirst();
    }

    private static <T extends ActionBarSegment> Stream<T> all(
            ActionBarUpdatedEvent event, Class<T> segment) {
        return event.getSegments().stream().filter(segment::isInstance).map(segment::cast);
    }

    private static boolean has(
            ActionBarUpdatedEvent event, Class<? extends ActionBarSegment> segment) {
        return first(event, segment).isPresent();
    }

    private static RetroHudReadings.Value value(CappedValue value) {
        return new RetroHudReadings.Value(value.current(), value.max());
    }

    /**
     * The ultimate's charge as Wynntils read it, by reflection, since {@link UltimateSegment} keeps
     * it in a field with no getter.
     *
     * @return the charge, or one with no maximum, which draws no bubbles, if the field cannot be
     *     read
     */
    private static RetroHudReadings.Value charge(UltimateSegment segment) {
        return CHARGE_GUARD.call(
                () -> value(((UltimateInfo) privateField(segment, "ultimateInfo")).progress()),
                new RetroHudReadings.Value(0, 0));
    }

    /** A Wynntils field with no getter; what cannot be read throws, for the caller's guard. */
    private static Object privateField(Object owner, String field) {
        try {
            return FieldUtils.readField(owner, field, true);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
