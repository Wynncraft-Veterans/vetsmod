package org.wynnvets.retro.pack.generate;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.wynnvets.rendering.image.Pixels;
import org.wynnvets.rendering.image.PngCodec;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.pack.PackFiles;
import org.wynnvets.retro.rules.ModelRules;
import org.wynnvets.retro.rules.PackFacts;
import org.wynnvets.retro.rules.PartFilter;
import org.wynnvets.retro.rules.TextureRules;

/**
 * One generation, as its steps share it: the rules, the packs it reads, what it has written so far,
 * and the reads more than one step makes.
 */
final class GenerationContext {

    final RetroManifest manifest;
    final TextureRules textureRules;
    final ModelRules modelRules;

    /** Which parts of retro this generation leaves out. */
    final PartFilter parts;

    /** The packs the reload loads, bottom first, without the retro pack. */
    final List<PackResources> stack;

    final PackResources wynncraft;

    /** The Wynncraft pack and those below it. */
    final List<PackResources> lowerPacks;

    /** The Wynncraft pack and those below it, as the reload would see them without retro. */
    final ResourceManager lower;

    final ProgrammerArtSources sources;
    final PackOutput out;

    /** The textures the Wynncraft pack ships. */
    final Set<String> shipped;

    final PackFacts facts;

    /** The stubs' writer, one per generation because it remembers what resolves. */
    final StubWriter stubs;

    /**
     * A texture's art as a source holds it.
     *
     * @param source the source it was read from
     * @param file the texture's file
     * @param png the file's bytes
     */
    record SourceArt(RetroSource source, Identifier file, byte[] png) {}

    /**
     * A generation over one reload's packs, with every part on.
     *
     * @param manifest the rule files
     * @param stack the packs the reload loads, bottom first, without the retro pack
     * @param wynncraftIndex the index in {@code stack} of the Wynncraft pack
     * @param sources the sources, open for as long as this generation runs
     * @param out where the files and the report's facts go
     */
    // Package-private for unit tests. See GenerationStepsTest.
    GenerationContext(
            RetroManifest manifest,
            List<PackResources> stack,
            int wynncraftIndex,
            ProgrammerArtSources sources,
            PackOutput out) {
        this(manifest, stack, wynncraftIndex, sources, out, RetroPart.all());
    }

    /**
     * A generation over one reload's packs.
     *
     * @param manifest the rule files
     * @param stack the packs the reload loads, bottom first, without the retro pack
     * @param wynncraftIndex the index in {@code stack} of the Wynncraft pack
     * @param sources the sources, open for as long as this generation runs
     * @param out where the files and the report's facts go
     * @param partsOn the parts that are on
     */
    GenerationContext(
            RetroManifest manifest,
            List<PackResources> stack,
            int wynncraftIndex,
            ProgrammerArtSources sources,
            PackOutput out,
            Set<RetroPart> partsOn) {
        this.manifest = manifest;
        this.textureRules = new TextureRules(manifest.textures());
        this.modelRules = new ModelRules(manifest.models());
        this.parts = new PartFilter(manifest.parts(), partsOn);
        this.stack = stack;
        this.wynncraft = stack.get(wynncraftIndex);
        this.lowerPacks = stack.subList(0, wynncraftIndex + 1);
        this.lower = new MultiPackResourceManager(PackFiles.TYPE, lowerPacks);
        this.sources = sources;
        this.out = out;
        this.shipped = PackFiles.textureIds(wynncraft);
        this.facts =
                new PackFacts() {
                    @Override
                    public boolean wynncraftShips(String textureId) {
                        return shipped.contains(textureId);
                    }

                    /** Never decoded: either answer keeps the texture (see the generator). */
                    @Override
                    public boolean wynncraftTransparent(String textureId) {
                        return false;
                    }

                    @Override
                    public boolean has(RetroSource source, String textureId) {
                        return sources.has(source, textureId);
                    }
                };
        this.stubs = new StubWriter(this);
    }

    /**
     * {@code textureId}'s art, from the first of its sources that has it.
     *
     * @return the art; {@code null} when none has it, or for a text that is not a texture id
     * @throws IOException when the file is there but cannot be read
     */
    SourceArt sourceArt(String textureId) throws IOException {
        RetroSource source = textureRules.firstSourceWith(textureId, facts);
        Identifier file = PackFiles.textureFile(textureId);
        byte[] png = source == null || file == null ? null : sources.read(source, file);
        return png == null ? null : new SourceArt(source, file, png);
    }

    /**
     * The {@code .mcmeta} beside {@code art} in its source.
     *
     * @return its bytes, or {@code null} when it has none
     * @throws IOException when the file is there but cannot be read
     */
    byte[] mcmeta(SourceArt art) throws IOException {
        return sources.read(art.source(), PackFiles.mcmeta(art.file()));
    }

    /**
     * {@code textureId} decoded, from the first of its sources that has it, or {@code null} when
     * none has it.
     */
    Pixels sourceImage(String textureId) throws IOException {
        SourceArt art = sourceArt(textureId);
        return art == null ? null : PngCodec.decode(art.png());
    }

    /**
     * A file's bytes as the reload would show it without the retro pack: from the Wynncraft pack,
     * or a pack below it when Wynncraft ships none.
     *
     * @return the bytes; {@code null} when no pack there has it
     * @throws IOException when the file is there but cannot be read
     */
    byte[] liveFile(Identifier file) throws IOException {
        Optional<Resource> live = lower.getResource(file);
        return live.isEmpty() ? null : PackFiles.read(live.get()::open);
    }

    /**
     * A file's bytes from the reload's packs, top first.
     *
     * @return the bytes; {@code null} when no pack has it
     * @throws IOException when the file is there but cannot be read
     */
    byte[] topFile(Identifier file) throws IOException {
        for (int i = stack.size() - 1; i >= 0; i--) {
            byte[] bytes = PackFiles.read(stack.get(i).getResource(PackFiles.TYPE, file));
            if (bytes != null) return bytes;
        }
        return null;
    }

    /**
     * {@code textureId} decoded as the reload would show it without the retro pack, or {@code null}
     * when no pack there has it.
     */
    Pixels liveImage(String textureId) throws IOException {
        Identifier file = PackFiles.textureFile(textureId);
        byte[] png = file == null ? null : liveFile(file);
        return png == null ? null : PngCodec.decode(png);
    }
}
