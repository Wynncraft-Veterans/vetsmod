package org.wynnvets.util;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Whether each mod vetsmod works around, without depending on it, is loaded. vetsmod's only mod
 * dependency is Wynntils. wynnmod and WynnExtras are optional, but enough players run them beside
 * vetsmod that it avoids conflicting with either, and code that must behave differently when one
 * is loaded tests its flag here. A flag for another such mod belongs here too.
 *
 * <p>Each flag is read once, when this class loads: Fabric loads every mod at startup and none
 * later, so the answer cannot change while the game runs.</p>
 */
public final class ModCompat {

    /**
     * Whether wynnmod (mod id {@code wynnmod}) is loaded.
     * {@link org.wynnvets.mixin.client.NametagMixin NametagMixin} reads it to work around the
     * rank badge wynnmod adds to player nametags.
     */
    public static final boolean WYNNMOD_PRESENT = FabricLoader.getInstance().isModLoaded("wynnmod");

    /**
     * Whether WynnExtras (mod id {@code wynnextras}) is loaded. Nothing reads it yet. The likely
     * first reader is {@link org.wynnvets.mixin.client.NametagMixin NametagMixin}: WynnExtras
     * appends its own badge to a player's nametag inside the {@code AvatarRenderer.submitNameTag}
     * that mixin wraps, with a {@code @ModifyArg} on the label of each
     * {@code SubmitNodeCollector.submitNameTag} call (in its Yarn names,
     * {@code PlayerEntityRenderer.renderLabelIfPresent} and
     * {@code OrderedRenderCommandQueue.submitLabel}).
     */
    public static final boolean WYNNEXTRAS_PRESENT =
            FabricLoader.getInstance().isModLoaded("wynnextras");

    private ModCompat() {}
}
