package org.wynnvets.retro.pack.generate;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.packs.PackResources;
import org.wynnvets.Vetsmod;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.pack.PackFiles;

/**
 * The art the jar bundles for retro's sources, each source's in a folder that holds an
 * {@code assets} folder as a pack does: the Vanilla Tweaks art for
 * {@link RetroSource#VANILLA_TWEAKS}, credited in {@code credits.txt}. vetsmod's own art, the main
 * menu's panorama, is downloaded instead ({@code PanoramaDownloads}), since few players use it.
 *
 * <p>The folders live under {@code retro/}, outside the mod's {@code assets}: Fabric loads those
 * as a resource pack whether retro is on or not, and this art must show only through the retro
 * pack, by the rules of {@code retro.yml}.</p>
 */
final class BundledArt {

    /** A bundled source's folder in the jar, and the id and title of the pack read over it. */
    // Package-private for unit tests. See BundledArtTest.
    record Folder(String root, String packId, String title) {}

    /** Each bundled source's folder. */
    // Package-private for unit tests. See BundledArtTest.
    static final Map<RetroSource, Folder> FOLDERS = folders();

    private BundledArt() {}

    private static Map<RetroSource, Folder> folders() {
        Map<RetroSource, Folder> folders = new EnumMap<>(RetroSource.class);
        folders.put(
                RetroSource.VANILLA_TWEAKS,
                new Folder("retro/vanilla_tweaks", "vetsmod_vanilla_tweaks", "Vanilla Tweaks"));
        return folders;
    }

    /**
     * Opens each bundled source's art in the jar.
     *
     * @return the packs, for the caller to close; a source whose folder cannot be found is left
     *     out, which leaves it with no textures
     */
    static Map<RetroSource, PackResources> open() {
        Map<RetroSource, PackResources> packs = new EnumMap<>(RetroSource.class);
        ModContainer mod;
        try {
            mod = FabricLoader.getInstance().getModContainer(Vetsmod.MOD_ID).orElse(null);
        } catch (RuntimeException e) {
            VetsLogger.warn("Retro pack: could not find the art the jar bundles", e);
            return packs;
        }
        if (mod == null) return packs;
        FOLDERS.forEach(
                (source, folder) -> {
                    try {
                        mod.findPath(folder.root())
                                .ifPresent(root -> packs.put(source, open(source, root)));
                    } catch (RuntimeException e) {
                        VetsLogger.warn("Retro pack: could not find the {} art", folder.title(), e);
                    }
                });
        return packs;
    }

    /**
     * Opens one bundled source's art at {@code root}.
     *
     * @param source a source {@link #FOLDERS} has
     * @param root the folder holding the {@code assets} folder, in the jar's file system or not
     * @return the pack, for the caller to close
     */
    // Package-private for unit tests. See BundledArtTest.
    static PackResources open(RetroSource source, Path root) {
        Folder folder = FOLDERS.get(source);
        return PackFiles.folder(root, folder.packId(), folder.title());
    }
}
