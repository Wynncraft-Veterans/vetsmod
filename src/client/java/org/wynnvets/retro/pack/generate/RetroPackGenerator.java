package org.wynnvets.retro.pack.generate;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.RetroReport;
import org.wynnvets.retro.manifest.AssetSections;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.pack.PackFiles;

/**
 * Builds the retro pack's files for one reload, from the rule files and the packs that reload
 * loads: each {@link #STEPS step} turns its rules into plain files that Minecraft reads as it would
 * any pack's.
 *
 * <p>The language files are not generated: {@link #staticFiles} answers them from the rule files
 * alone, so {@link org.wynnvets.retro.pack.RetroPackResources RetroPackResources} can serve them on
 * any thread.
 *
 * <p>Only Wynncraft's item models under {@code item/wynn/}, Avomod's that a {@code potions} row
 * names, and the block models {@code block_models} names are ever replaced, so
 * {@code oak_boat.json} and the {@code w<N>} models, which Wynntils reads, are never touched. An
 * item model cannot draw from two atlases, and when one tries, Minecraft fails the whole item
 * definition that names it. A texture that does not resolve becomes the blocks atlas' missing
 * texture, so a stub is emitted only when every texture and model it names resolves, in this pack
 * or the reload's others.
 *
 * <p>A generator reads the packs of the reload it was made for and never closes them; they belong
 * to the reload's resource manager. It reads the Wynncraft pack and those below it through a
 * {@link net.minecraft.server.packs.resources.MultiPackResourceManager MultiPackResourceManager} of
 * its own, which it never closes either, since that would close those packs. It decodes an image
 * only to edit, tint or compose one: the question whether Wynncraft ships a texture fully
 * transparent changes no file (such a texture stays either way), so the generator answers it
 * {@code false} and its report counts Wynncraft's deliberate blanks among the textures no rule
 * replaced.
 */
public final class RetroPackGenerator {

    /**
     * The steps, in the order they run. The order is behaviour, and this list is the one place it
     * is stated. A stub resolves the files it names against what earlier steps wrote, so every step
     * that writes a texture or a model a stub can name comes before the stubs: the copies a stub's
     * art reads, and the accessories that ingredient rows parent. A later step's file also replaces
     * an earlier one's at the same path, so a texture drawn for {@code blank_textures} or
     * {@code wynntils_textures} outranks the texture rules' file.
     */
    static final List<GenerationStep> STEPS =
            List.of(
                    new TextureStep(),
                    new WynncraftCopyStep(),
                    new SourceCopyStep(),
                    new BlankTextureStep(),
                    new WynntilsTextureStep(),
                    new AccessoryTierStep(),
                    new ModelStubStep(),
                    new BlockModelStep(),
                    new EquipmentStep(),
                    new ItemDefinitionStep(),
                    new OutlinedIconStep());

    private final List<PackResources> stack;
    private final int wynncraftIndex;
    private final Pack programmerArt;
    private final PackResources vanilla;

    /**
     * A generator for one reload.
     *
     * @param stack the packs the reload loads, bottom first, without the retro pack
     * @param wynncraftIndex the index in {@code stack} of the Wynncraft pack the retro pack sits
     *     directly above
     * @param programmerArt the client's {@code programmer_art} pack, or {@code null} when it has
     *     none
     * @param vanilla Minecraft's vanilla pack
     */
    public RetroPackGenerator(
            List<PackResources> stack,
            int wynncraftIndex,
            Pack programmerArt,
            PackResources vanilla) {
        this.stack = List.copyOf(stack);
        this.wynncraftIndex = wynncraftIndex;
        this.programmerArt = programmerArt;
        this.vanilla = vanilla;
    }

    /**
     * Generates the files, records the report with
     * {@link RetroModeManager#generated(RetroReport)} and logs its summary. A file that cannot be
     * read, or a rule that cannot be applied, is skipped and listed in the report; anything else
     * that goes wrong is thrown, for {@link org.wynnvets.retro.pack.RetroPackResources
     * RetroPackResources} to turn into an empty pack.
     *
     * @return the files, each keyed by its full path, such as
     *     {@code minecraft:textures/block/stone.png}
     * @throws IOException when a rule file cannot be read
     */
    public Map<Identifier, byte[]> generate() throws IOException {
        long start = System.nanoTime();
        RetroManifest manifest = RetroManifest.load();
        Set<RetroPart> partsOn = RetroModeManager.partsOn();
        PackOutput out = new PackOutput();
        List<RetroSource> present;
        RetroReport.Seen seen;
        try (ProgrammerArtSources sources =
                ProgrammerArtSources.open(programmerArt, VanillaTweaksPack.open(), vanilla)) {
            present = sources.present();
            GenerationContext gen =
                    new GenerationContext(manifest, stack, wynncraftIndex, sources, out, partsOn);
            for (GenerationStep step : STEPS) step.run(gen);
            seen = out.seen(gen.shipped);
        }
        RetroReport report =
                new RetroReport(
                        stack.get(wynncraftIndex).packId(),
                        present,
                        out.counts,
                        out.wynncraftCopies,
                        out.copiedItems,
                        out.generatedItems,
                        out.imageEdits,
                        out.skipped,
                        List.copyOf(RetroPart.allBut(partsOn)),
                        (System.nanoTime() - start) / 1_000_000,
                        seen);
        RetroModeManager.generated(report);
        VetsLogger.info("Retro pack: {}", report.summary());
        return out.files;
    }

    /**
     * The files the pack serves without generating, answered from the rule files alone: one
     * language file per {@code lang} locale, while {@code screens} is on. Reading them costs one
     * parse of the rule files and no pack, so it is safe on any thread, the render thread
     * included.
     *
     * @return the files, each keyed by its full path
     * @throws IOException when a rule file cannot be read
     */
    public static Map<Identifier, byte[]> staticFiles() throws IOException {
        return staticFiles(
                RetroManifest.load().assets().lang(), RetroModeManager.isPartOn(RetroPart.SCREENS));
    }

    /**
     * The static files of one {@code lang} section.
     *
     * @param lang the {@code lang} section
     * @param screens whether {@code screens} is on
     * @return {@link #langFiles}; none with {@code screens} off
     */
    // Package-private for unit tests. See RetroPackGeneratorTest.
    static Map<Identifier, byte[]> staticFiles(AssetSections.Lang lang, boolean screens) {
        return screens ? langFiles(lang) : Map.of();
    }

    /**
     * One language file per locale, each holding every string.
     *
     * @param lang the {@code lang} section
     * @return {@code minecraft:lang/<locale>.json} for each locale, in order; none when there are
     *     no strings
     */
    // Package-private for unit tests. See RetroPackGeneratorTest.
    static Map<Identifier, byte[]> langFiles(AssetSections.Lang lang) {
        Map<Identifier, byte[]> files = new LinkedHashMap<>();
        if (lang.strings().isEmpty()) return files;
        JsonObject json = new JsonObject();
        lang.strings().forEach(json::addProperty);
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        for (String locale : lang.locales()) {
            files.put(PackFiles.LANG.idToFile(Identifier.withDefaultNamespace(locale)), bytes);
        }
        return files;
    }
}
