package org.wynnvets.retro.pack.generate;

import java.nio.file.Path;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import org.wynnvets.Vetsmod;
import org.wynnvets.logging.VetsLogger;

/**
 * The Vanilla Tweaks art the jar bundles for
 * {@link org.wynnvets.retro.manifest.RetroSource#VANILLA_TWEAKS}, credited in {@code credits.txt}.
 *
 * <p>It lives under {@code retro/vanilla_tweaks}, outside the mod's {@code assets}: Fabric loads
 * those as a resource pack whether retro is on or not, and this art must show only through the
 * retro pack, by the rules of {@code retro_textures.yml}.</p>
 */
final class VanillaTweaksPack {

    /** The art's folder in the jar, which holds an {@code assets} folder as a pack does. */
    // Package-private for unit tests. See VanillaTweaksPackTest.
    static final String ROOT = "retro/vanilla_tweaks";

    private VanillaTweaksPack() {}

    /**
     * Opens the art in the jar.
     *
     * @return the pack, for the caller to close, or {@code null} when it cannot be found, which
     *     leaves the source with no textures
     */
    static PackResources open() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer(Vetsmod.MOD_ID)
                    .flatMap(mod -> mod.findPath(ROOT))
                    .map(VanillaTweaksPack::open)
                    .orElse(null);
        } catch (RuntimeException e) {
            VetsLogger.warn("Retro pack: could not find the Vanilla Tweaks art", e);
            return null;
        }
    }

    /**
     * Opens the art at {@code root}.
     *
     * @param root the folder holding the {@code assets} folder, in the jar's file system or not
     * @return the pack, for the caller to close
     */
    // Package-private for unit tests. See VanillaTweaksPackTest.
    static PackResources open(Path root) {
        return new PathPackResources(
                new PackLocationInfo(
                        "vetsmod_vanilla_tweaks",
                        Component.literal("Vanilla Tweaks"),
                        PackSource.BUILT_IN,
                        Optional.empty()),
                root);
    }
}
