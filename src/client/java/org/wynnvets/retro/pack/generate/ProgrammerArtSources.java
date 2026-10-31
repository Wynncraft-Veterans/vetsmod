package org.wynnvets.retro.pack.generate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.pack.PackFiles;

/**
 * The packs the retro pack copies art from, one per {@link RetroSource}: the Vanilla Tweaks art
 * the jar bundles ({@link VanillaTweaksPack}), and from the player's own game files the client's
 * built-in {@code programmer_art} pack, which the generator opens, and the client's own textures,
 * Minecraft's shared vanilla pack, which it never closes.
 *
 * <p>A missing source simply has no textures: the {@code programmer_art} pack can be absent from
 * the client's pack list, or fail to open, and generation goes on with what is left. Each
 * source's texture ids are listed once, when it opens. {@link #close()} closes every source but
 * the vanilla pack.</p>
 */
public final class ProgrammerArtSources implements AutoCloseable {

    /** The id of the client's built-in Programmer Art pack. */
    public static final String PROGRAMMER_ART_PACK = "programmer_art";

    private final Map<RetroSource, PackResources> packs = new EnumMap<>(RetroSource.class);
    private final Map<RetroSource, Set<String>> textures = new EnumMap<>(RetroSource.class);

    /** The packs this class closes. */
    private final List<PackResources> owned = new ArrayList<>();

    private ProgrammerArtSources() {}

    /**
     * Opens the sources and lists their textures. A source other than vanilla that cannot be
     * opened or listed is closed again and left out.
     *
     * @param programmerArt the client's {@code programmer_art} pack, or {@code null} when its
     *     pack list has none
     * @param vanillaTweaks the art the jar bundles, which this now closes, or {@code null} when
     *     it could not be found
     * @param vanilla Minecraft's vanilla pack, which stays open
     * @return the sources
     */
    static ProgrammerArtSources open(
            Pack programmerArt, PackResources vanillaTweaks, PackResources vanilla) {
        ProgrammerArtSources sources = new ProgrammerArtSources();
        sources.add(RetroSource.VANILLA, vanilla);
        if (vanillaTweaks != null) sources.addOwned(RetroSource.VANILLA_TWEAKS, vanillaTweaks);
        if (programmerArt != null) {
            try {
                sources.addOwned(RetroSource.PROGRAMMER_ART, programmerArt.open());
            } catch (RuntimeException e) {
                VetsLogger.warn("Retro pack: could not read the programmer_art pack", e);
            }
        }
        return sources;
    }

    /** Adds a pack to close later; one that cannot be listed is closed now and left out. */
    private void addOwned(RetroSource source, PackResources pack) {
        try {
            add(source, pack);
            owned.add(pack);
        } catch (RuntimeException e) {
            pack.close();
            VetsLogger.warn("Retro pack: could not read the {} pack", source.yamlName(), e);
        }
    }

    /** Lists {@code pack}'s textures, then adds it; a pack that cannot be listed is not added. */
    private void add(RetroSource source, PackResources pack) {
        Set<String> ids = PackFiles.textureIds(pack);
        packs.put(source, pack);
        textures.put(source, ids);
    }

    /**
     * The sources that are present, in {@link RetroSource} order.
     *
     * @return the sources, at least {@link RetroSource#VANILLA}
     */
    List<RetroSource> present() {
        return new ArrayList<>(packs.keySet());
    }

    /**
     * Whether {@code source} has {@code textureId}.
     *
     * @param source the source
     * @param textureId a full texture id such as {@code minecraft:block/stone}
     * @return {@code false} for a source that is missing
     */
    boolean has(RetroSource source, String textureId) {
        return textures.getOrDefault(source, Set.of()).contains(textureId);
    }

    /**
     * Every texture id any present source has.
     *
     * @return the ids; a new, modifiable set
     */
    Set<String> textureIds() {
        Set<String> all = new HashSet<>();
        textures.values().forEach(all::addAll);
        return all;
    }

    /**
     * Reads one file from a source.
     *
     * @param source the source
     * @param file the file, such as {@code minecraft:textures/block/stone.png}
     * @return its bytes, or {@code null} when the source is missing or has no such file
     * @throws IOException when the file is there but cannot be read
     */
    byte[] read(RetroSource source, Identifier file) throws IOException {
        PackResources pack = packs.get(source);
        return pack == null ? null : PackFiles.read(pack.getResource(PackFiles.TYPE, file));
    }

    /** Closes every source but the vanilla pack. */
    @Override
    public void close() {
        owned.forEach(PackResources::close);
    }
}
