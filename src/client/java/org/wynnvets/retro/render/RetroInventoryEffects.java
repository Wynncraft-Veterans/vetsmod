package org.wynnvets.retro.render;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.world.effect.MobEffectInstance;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.manifest.RetroPart;

/**
 * The effects the inventory's effect list leaves out while the retro pack is live on Wynncraft
 * ({@code hidden_inventory_effects} in {@code retro_textures.yml}).
 *
 * <p>Wynncraft shows a weapon's attack speed as an endless haste or mining fatigue, whose
 * up and down arrows its pack draws on the HUD; vanilla's effect list beside the inventory shows
 * it as "Attack Speed &infin;". Only an effect that lasts forever is left out, so a haste that
 * runs out still shows.
 * {@link org.wynnvets.mixin.client.retro.RetroEffectsInInventoryMixin RetroEffectsInInventoryMixin}
 * filters the list through {@link #visibleEffects}.</p>
 */
public final class RetroInventoryEffects {

    private static final RetroGuard GUARD =
            RetroGuard.retrying("filtering the inventory's effects", "the list shows them all");

    private RetroInventoryEffects() {}

    /**
     * The effects the inventory's effect list shows.
     *
     * @param effects the player's active effects
     * @return {@code effects} itself while the pack is not live on Wynncraft, its {@code effects}
     *     part is off or none is hidden; otherwise a new list without the hidden ones
     */
    public static Collection<MobEffectInstance> visibleEffects(
            Collection<MobEffectInstance> effects) {
        if (effects.isEmpty() || !RetroModeManager.isPartLiveOnWynncraft(RetroPart.EFFECTS)) {
            return effects;
        }
        return GUARD.call(
                () -> {
                    List<MobEffectInstance> shown = new ArrayList<>(effects.size());
                    for (MobEffectInstance effect : effects) {
                        String id = effect.getEffect().getRegisteredName();
                        if (!isHidden(id, effect.isInfiniteDuration())) shown.add(effect);
                    }
                    return shown.size() == effects.size() ? effects : shown;
                },
                effects);
    }

    /**
     * Whether the inventory's effect list leaves an effect out.
     *
     * @param effectId the effect's id, such as {@code minecraft:haste}
     * @param infinite whether it lasts forever
     * @return whether it lasts forever and a {@code hidden_inventory_effects} pattern matches its
     *     whole id
     */
    // Package-private for unit tests. See RetroInventoryEffectsTest.
    static boolean isHidden(String effectId, boolean infinite) {
        if (!infinite) return false;
        for (Pattern pattern : RetroModeManager.manifest().screens().hiddenInventoryEffects()) {
            if (pattern.matcher(effectId).matches()) return true;
        }
        return false;
    }
}
