package org.wynnvets.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.wynntils.mc.extension.EntityRenderStateExtension;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.fetcher.polling.SupportersPoller;
import org.wynnvets.mwe.anni.outline.AnniOutlineRegistry;
import org.wynnvets.mwe.anni.outline.AnniOutlineTicker;
import org.wynnvets.rendering.nametag.NametagAnimator;

/**
 * Replaces the {@code nameTag} on the per-frame {@link AvatarRenderState}
 * with either an anni-tier-coloured literal (S4) or an animated supporter
 * gradient (existing behaviour) immediately after vanilla finishes
 * populating the state.
 *
 * <p><b>Why {@code extractRenderState} TAIL instead of
 * {@code submitNameTag} HEAD.</b> Wynntils'
 * {@code CustomNametagRendererFeature.onPlayerNameTagRender} (subscribed
 * to {@code PlayerNametagRenderEvent}, dispatched from Wynntils'
 * own HEAD inject on {@code submitNameTag}) reads {@code state.nameTag}
 * directly and then <em>cancels</em> the vanilla submit when it adds
 * gear-hover lines (whenever the player is the hovered raycast target)
 * or when it adds a Wynntils account-type badge. A cancel from any
 * priority-1000 HEAD inject short-circuits every later-priority HEAD
 * inject on the same method via the mixin processor's generated
 * {@code if (ci.isCancelled()) return;} guard — which is exactly the
 * earlier {@code submitNameTag} HEAD design's failure mode (observed in
 * S4 testing 2026-06-17: party tier outlines applied, nametag colours
 * did not, because Wynntils intercepted before our inject ran).</p>
 *
 * <p>Modifying the field at {@code extractRenderState} TAIL guarantees
 * the value Wynntils' event handler reads is already ours — there is no
 * code path between extract and submit that vanilla could override
 * {@code state.nameTag} on. Per-frame, regardless of cancellation, the
 * field carries our tier colour and Wynntils' custom rendering picks it
 * up verbatim into the prefixed-name component it builds.</p>
 *
 * <p><b>Branch order:</b> anni first, then supporter — a supporter who
 * is also on a vets-anni party gets the role colour for the duration of
 * the highlight gate, then reverts to the animated glint after the gate
 * closes. Outsiders get dark-grey ({@code §8}); registry members get the
 * tier colour from {@link AnniOutlineRegistry.Entry#nametagFormatting()}.</p>
 *
 * <p><b>wynnmod interop ({@link #vetsmod$reapplyAfterWrap}).</b>
 * wynnmod's {@code PlayerNametagFeature} (config "Wynncraft Rank") wraps
 * the {@code submitNameTag(EntityRenderState→AvatarRenderState)} call
 * inside the bridge method with {@code @WrapOperation}. Its PRE listener
 * uses {@code state.nameTag} as a {@code badgeNameCache} key, and on
 * cache hit rebuilds {@code state.nameTag} from {@code playerName.toString()}
 * — discarding our per-character glint Style. We can't avoid being read
 * as the cache key, but we can re-apply after wynnmod mutates: by
 * wrapping the same call site at mixin priority 900, vetsmod becomes the
 * <em>inner</em> wrap (wynnmod default 1000 → outer), so vetsmod's wrap
 * runs after wynnmod's PRE event handler but before the real
 * {@code submitNameTag} draws. We just re-run the same anni/supporter
 * override logic on whatever Component wynnmod left behind —
 * {@link NametagAnimator#tryAnimate} locates the username substring with
 * {@code lastIndexOf} and only rewrites segments inside it, so the rank
 * icon prefix wynnmod added is preserved verbatim. Result with wynnmod's
 * Wynncraft Rank ON: {@code (rankPUALogo)(glinted)(rankColour)Username},
 * matching the pre-Stage-4 behaviour. No-op when wynnmod is absent (no
 * outer wrap → vetsmod's wrap just re-applies idempotently to its own
 * extractRenderState TAIL output).</p>
 */
@Mixin(value = AvatarRenderer.class, priority = 900)
public class NametagMixin {

    /**
     * Cached at class init — wynnmod doesn't hot-load. Drives the
     * supporter-branch skip at {@link #vetsmod$rewriteNameTag} (TAIL):
     * when wynnmod is present, pre-glinting {@code state.nameTag} at
     * TAIL would feed wynnmod's {@code badgeNameCache} a per-frame
     * varying key (every render frame produces a fresh per-character
     * animated Component, see {@link NametagAnimator#tryAnimate}), so
     * the cache occasionally misses and wynnmod's PRE handler leaves
     * the badge off for a tick — visible as a per-tick flash back to
     * {@code (glint)(partycolour)}. Leaving the Component as vanilla at
     * TAIL gives wynnmod a stable cache key (the unmodified Wynncraft
     * nametag) so it hits 100% of the time and always writes the badged
     * form; {@link #vetsmod$reapplyAfterWrap} then re-glints inside
     * wynnmod's output, restoring the intended
     * {@code (rankPUALogo)(glint)(rankColour)} appearance.
     */
    private static final boolean WYNNMOD_PRESENT =
            FabricLoader.getInstance().isModLoaded("wynnmod");

    @Inject(
            method =
                    "extractRenderState("
                            + "Lnet/minecraft/world/entity/Avatar;"
                            + "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;"
                            + "F)V",
            at = @At("TAIL"))
    private void vetsmod$rewriteNameTag(
            Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        applyOverride(state, entity, false);
    }

    @WrapOperation(
            method =
                    "submitNameTag("
                            + "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                            + "Lnet/minecraft/client/renderer/state/CameraRenderState;"
                            + ")V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/entity/player/AvatarRenderer;submitNameTag("
                                            + "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;"
                                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                                            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                                            + "Lnet/minecraft/client/renderer/state/CameraRenderState;"
                                            + ")V"))
    private void vetsmod$reapplyAfterWrap(
            AvatarRenderer<?> instance,
            AvatarRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState cameraRenderState,
            Operation<Void> original) {
        Entity entity = ((EntityRenderStateExtension) state).getEntity();
        applyOverride(state, entity, true);
        original.call(instance, state, poseStack, submitNodeCollector, cameraRenderState);
    }

    /**
     * Anni-first / supporter-second nametag override, shared between the
     * extractRenderState TAIL inject and the submitNameTag inner-wrap.
     * Both call sites pass a non-null {@code state} but {@code entity} may
     * be {@code null} from the wrap path if the render-state extension was
     * never populated.
     *
     * <p>{@code fromWrap} gates the supporter branch only: when wynnmod
     * is present we must <em>not</em> pre-glint at TAIL, or wynnmod's
     * {@code badgeNameCache} key thrashes (see {@link #WYNNMOD_PRESENT}).
     * The anni branch is unconditional — anni overrides wynnmod by design,
     * and writing the anni literal at TAIL gives wynnmod a stable key
     * for the duration of the highlight gate.</p>
     */
    private static void applyOverride(AvatarRenderState state, Entity entity, boolean fromWrap) {
        if (state.nameTag == null) return;
        if (!(entity instanceof AbstractClientPlayer player)) return;

        String username = player.getGameProfile().name();
        if (username == null) return;

        // S4 anni nametag overlay. Runs before the supporter branch so
        // an own-party supporter shows their role colour for the
        // duration of the highlight gate and the supporter glint
        // resumes afterwards.
        if (AnniOutlineTicker.isOutlineSuppressionActive()
                && VetsConfig.get(VetsConfig.VETS_ANNI_NAMETAGS_ENABLED)) {
            AnniOutlineRegistry.Entry anni = AnniOutlineRegistry.getEntry(username);
            ChatFormatting fmt =
                    (anni != null) ? anni.nametagFormatting() : ChatFormatting.DARK_GRAY;
            String original = state.nameTag.getString();
            // Wynncraft embeds the team colour as a legacy `§<code>`
            // prefix INSIDE the string content (e.g. "§awonderkas" for
            // a friend-team-coloured nametag) rather than only in the
            // Component's Style. Leaving that prefix in causes vanilla's
            // text renderer to parse it at draw time, silently
            // overriding our .withStyle(...) colour. Stripping § codes
            // first guarantees our Style is what actually paints.
            // ({@code /wv debug trigger nametagsDump} confirms the
            // registry hit; the {@code original=} field on that command
            // shows the §-prefixed string we're stripping here.)
            String stripped = ChatFormatting.stripFormatting(original);
            if (stripped == null || stripped.isEmpty()) stripped = original;
            state.nameTag = Component.literal(stripped).withStyle(fmt);
            return;
        }

        // wynnmod's badgeNameCache uses state.nameTag itself as part of
        // its key (Component#equals compares per-character Style). If we
        // pre-glint at TAIL, the cache key changes every frame; misses
        // produce a visible per-tick flash to (glint)(partycolour). Let
        // the wrap own the supporter glint when wynnmod is loaded —
        // wynnmod sees the stable vanilla Component, always badges, and
        // our wrap re-glints inside the badge.
        if (!fromWrap && WYNNMOD_PRESENT) return;

        if (!SupportersPoller.isSupporter(username)) return;
        if (!VetsConfig.get(VetsConfig.SHOW_SUPPORTER_GLINTS)) return;

        Component animated = NametagAnimator.tryAnimate(state.nameTag, username);
        if (animated != null) {
            state.nameTag = animated;
        }
    }
}
