package org.wynnvets.mixin.client.accessors;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/**
 * Reach into the private {@code BossHealthOverlay#events} map so the
 * vets-anni boss-bar manager can insert and remove its synthetic
 * {@link LerpingBossEvent} without going through the vanilla packet
 * pipeline (which we cancel in
 * {@link org.wynnvets.mixin.client.BossHealthOverlayMixin}).
 *
 * <p>Wynntils already {@code @Shadow @Mutable}'s the same field and
 * replaces it with a {@code ConcurrentHashMap} in its own
 * {@code BossHealthOverlayMixin} (see
 * {@code Wynntils/common/src/main/java/com/wynntils/mc/mixin/BossHealthOverlayMixin.java}),
 * which makes the multi-thread access pattern below safe — we read and
 * write from both the render thread (vanilla packet path) and our tick
 * driver, but both go through that ConcurrentHashMap.</p>
 */
@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {

    @Accessor("events")
    Map<UUID, LerpingBossEvent> getEvents();
}
