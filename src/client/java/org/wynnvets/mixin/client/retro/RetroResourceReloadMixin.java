package org.wynnvets.mixin.client.retro;

import java.util.List;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.wynnvets.retro.pack.RetroPackInjector;

/**
 * Adds the retro pack to every resource reload, above Wynncraft's pack.
 *
 * <p>{@code createReload} is where a reload's pack list becomes the resource manager, so it is
 * the one place a pack can be put above a server pack, which Minecraft fixes at the top of the
 * list the player sees. Both of Minecraft's reloads pass through it (the first at start-up, and
 * {@code reloadResourcePacks}), and in 1.21.11 Minecraft's client resource manager is the only
 * {@code ReloadableResourceManager}. It runs on the render thread; both handlers are one call into
 * {@link RetroPackInjector}, which does no work there and never throws.</p>
 */
@Mixin(ReloadableResourceManager.class)
public abstract class RetroResourceReloadMixin {

    /** Hands the reload's pack list to the injector, which may add the retro pack. */
    @ModifyVariable(method = "createReload", at = @At("HEAD"), argsOnly = true)
    private List<PackResources> vetsmod$addRetroPack(List<PackResources> packs) {
        return RetroPackInjector.inject(packs);
    }

    /** Hands the new reload to the injector, which follows it to its end. */
    @Inject(method = "createReload", at = @At("RETURN"))
    private void vetsmod$watchReload(CallbackInfoReturnable<ReloadInstance> cir) {
        RetroPackInjector.watch(cir.getReturnValue());
    }
}
