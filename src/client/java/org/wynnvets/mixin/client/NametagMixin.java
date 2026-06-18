package org.wynnvets.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Avatar;
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
 */
@Mixin(AvatarRenderer.class)
public class NametagMixin {

    @Inject(
            method = "extractRenderState("
                    + "Lnet/minecraft/world/entity/Avatar;"
                    + "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;"
                    + "F)V",
            at = @At("TAIL"))
    private void vetsmod$rewriteNameTag(
            Avatar entity,
            AvatarRenderState state,
            float partialTick,
            CallbackInfo ci) {
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
            ChatFormatting fmt = (anni != null)
                    ? anni.nametagFormatting()
                    : ChatFormatting.DARK_GRAY;
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

        if (!SupportersPoller.isSupporter(username)) return;
        if (!VetsConfig.get(VetsConfig.SHOW_SUPPORTER_GLINTS)) return;

        Component animated = NametagAnimator.tryAnimate(state.nameTag, username);
        if (animated != null) {
            state.nameTag = animated;
        }
    }
}
