package org.wynnvets.retro.pack.generate;

import java.nio.file.Path;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.packs.PackResources;
import org.wynnvets.Vetsmod;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.pack.PackFiles;

/**
 * The art the jar bundles: the Vanilla Tweaks art of {@link RetroSource#VANILLA_TWEAKS}, credited
 * in {@code credits.txt}, in a folder that holds an {@code assets} folder as a pack does.
 *
 * <p>The folder lives under {@code retro/}, outside the mod's {@code assets}: Fabric loads those
 * as a resource pack whether retro is on or not, and this art must show only through the retro
 * pack, by the rules of {@code retro.yml}.</p>
 */
final class BundledArt {

    /** The art's folder in the jar. */
    // Package-private for unit tests. See BundledArtTest.
    static final String ROOT = "retro/vanilla_tweaks";

    private BundledArt() {}

    /**
     * Opens the art in the jar.
     *
     * @return its pack by its source, for the caller to close; none when its folder cannot be
     *     found, which leaves the source with no textures
     */
    static Map<RetroSource, PackResources> open() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer(Vetsmod.MOD_ID)
                    .flatMap(mod -> mod.findPath(ROOT))
                    .map(root -> Map.of(RetroSource.VANILLA_TWEAKS, open(root)))
                    .orElse(Map.of());
        } catch (RuntimeException e) {
            VetsLogger.warn("Retro pack: could not find the Vanilla Tweaks art the jar bundles", e);
            return Map.of();
        }
    }

    /**
     * Opens the art at {@code root}.
     *
     * @param root the folder holding the {@code assets} folder, in the jar's file system or not
     * @return the pack, for the caller to close
     */
    // Package-private for unit tests. See BundledArtTest.
    static PackResources open(Path root) {
        return PackFiles.folder(root, RetroSource.VANILLA_TWEAKS, "Vanilla Tweaks");
    }
}
