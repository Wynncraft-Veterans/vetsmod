package org.wynnvets.retro.pack.generate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.RetroReport;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.pack.PackFiles;
import org.wynnvets.retro.rules.TextureDecision;

/**
 * What one generation has made so far: its files, its counts, its skipped rules and the facts its
 * report states. It also holds the shape every row of a step shares: check the row's ids, try the
 * row, and turn anything that goes wrong into one skipped line.
 */
final class PackOutput {

    final Map<Identifier, byte[]> files = new HashMap<>();
    final Map<Count, Integer> counts = new EnumMap<>(Count.class);
    final Set<String> wynncraftCopies = new HashSet<>();
    final Set<String> copiedItems = new HashSet<>();
    final Set<String> generatedItems = new HashSet<>();
    final Map<String, List<Rect>> imageEdits = new HashMap<>();
    final List<String> skipped = new ArrayList<>();

    /** Every texture's decision. */
    final Map<String, TextureDecision> decisions = new LinkedHashMap<>();

    /** The factors each tinted texture was multiplied by, by its id. */
    final Map<String, List<Double>> tintFactors = new HashMap<>();

    /** The item models the stubs were decided for. */
    final List<String> modelIds = new ArrayList<>();

    /** The models among {@link #modelIds} that a stub replaced. */
    final Set<String> stubbed = new HashSet<>();

    /** The equipment assets the Wynncraft pack ships. */
    final List<String> equipmentIds = new ArrayList<>();

    /** A row's work, which may fail by throwing. */
    @FunctionalInterface
    interface IoAction {
        void run() throws IOException;
    }

    /**
     * The files of a texture copied or derived from another.
     *
     * @param from the file it is read from
     * @param to the file it is written to
     */
    record CopyFiles(Identifier from, Identifier to) {}

    /**
     * Runs one row's work. Whatever it throws becomes one skipped line and costs no other row. An
     * early {@code return} from the action ends just that row.
     *
     * @param failure the line to report when it throws
     * @param action the work
     */
    void attempt(String failure, IoAction action) {
        try {
            action.run();
        } catch (IOException | RuntimeException e) {
            skip(failure, e);
        }
    }

    /**
     * Runs one row's work that answers something, as {@link #attempt(String, IoAction)} does.
     *
     * @param failure the line to report when it throws
     * @param onFailure the answer when it throws
     * @param action the work
     * @return the action's answer, or {@code onFailure}
     */
    <T> T attempt(String failure, T onFailure, IoSupplier<T> action) {
        try {
            return action.get();
        } catch (IOException | RuntimeException e) {
            skip(failure, e);
            return onFailure;
        }
    }

    void count(Count count) {
        counts.merge(count, 1, Integer::sum);
    }

    void skip(String line) {
        skipped.add(line);
        VetsLogger.debug("Retro pack skipped: {}", line);
    }

    void skip(String line, Exception cause) {
        skipped.add(line + " (" + cause + ")");
        VetsLogger.debug("Retro pack skipped: {}", line, cause);
    }

    /**
     * A row's texture file.
     *
     * @param textureId the row's id
     * @return its file; {@code null}, reported, when the row's id is not a texture id
     */
    Identifier textureFile(String textureId) {
        Identifier file = PackFiles.textureFile(textureId);
        if (file == null) skip("not a texture id: " + textureId);
        return file;
    }

    /**
     * The files of a texture read from another.
     *
     * @param from the id it is read from
     * @param to the id it is written to
     * @return the files; {@code null}, reported, when either is not a texture id
     */
    CopyFiles copyFiles(String from, String to) {
        Identifier fromFile = PackFiles.textureFile(from);
        Identifier toFile = PackFiles.textureFile(to);
        if (fromFile == null || toFile == null) {
            skip("not a texture id: " + from + " for " + to);
            return null;
        }
        return new CopyFiles(fromFile, toFile);
    }

    /**
     * Writes a texture and counts it, with the {@code .mcmeta} beside it when it has one. A caller
     * reads the {@code .mcmeta} first, so a failed read writes nothing.
     *
     * @param to the texture's file
     * @param png its bytes
     * @param mcmeta the {@code .mcmeta}'s bytes, or {@code null} for none
     * @param count what the texture counts as
     */
    void writeTexture(Identifier to, byte[] png, byte[] mcmeta, Count count) {
        files.put(to, png);
        count(count);
        if (mcmeta != null) {
            files.put(PackFiles.mcmeta(to), mcmeta);
            count(Count.MCMETA);
        }
    }

    /**
     * What this generation looked at and decided, for its report.
     *
     * @param shipped the textures the Wynncraft pack ships
     * @return the facts
     */
    RetroReport.Seen seen(Set<String> shipped) {
        return new RetroReport.Seen(
                decisions, shipped, modelIds, stubbed, equipmentIds, tintFactors);
    }
}
