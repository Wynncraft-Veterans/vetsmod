package org.wynnvets.mixin.client.accessors;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reach into the private {@code BossHealthOverlay#events} map so the
 * vets-anni boss-bar manager can insert and remove its synthetic
 * {@link LerpingBossEvent} without going through the vanilla packet
 * pipeline. That pipeline is deliberately left running: the earlier
 * design cancelled it and crashed the client on 2026-06-16, as
 * {@link org.wynnvets.mixin.client.BossHealthOverlayMixin} records at
 * length. Our bar is simply an extra entry vanilla never delivered.
 *
 * <p>Wynntils already {@code @Shadow @Mutable}'s the same field and
 * replaces it with a {@code ConcurrentHashMap} in its own
 * {@link org.wynnvets.mixin.client.BossHealthOverlayMixin BossHealthOverlayMixin} (see
 * {@code Wynntils/common/src/main/java/com/wynntils/mc/mixin/BossHealthOverlayMixin.java}),
 * which makes the multi-thread access pattern below safe — we write
 * only from our tick driver, the render path reads back through the
 * {@code @Redirect} in <em>our own</em> identically-named
 * {@link org.wynnvets.mixin.client.BossHealthOverlayMixin}, and
 * vanilla's packet handler goes on writing its own entries alongside
 * ours.</p>
 */
@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {

    @Accessor("events")
    Map<UUID, LerpingBossEvent> getEvents();
}
