package org.wynnvets.retro.pack;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * The retro pack as Minecraft's resource manager sees it: an in-memory pack of client resources
 * whose files are generated the first time anything asks for one where they can be, plus a few
 * static files answered from the rule files alone.
 *
 * <p>{@link RetroPackInjector} adds it to a reload on the render thread, where it must cost
 * nothing, so it starts out declared but empty: its namespaces and folders are fixed, and it has
 * no {@code pack.mcmeta}, metadata sections or filter. A request outside those folders is
 * answered without generating, which matters because {@code createReload} itself starts every
 * reload listener on the render thread, and Minecraft's font loader lists {@code font/} there
 * before the loading screen shows. The first request inside them comes from the reload's worker
 * threads (the atlas, model, item definition and equipment asset loaders) and runs the
 * generator inline under a lock; every later request, and any that waited on the lock, gets the
 * same files. Nothing is queued on the reload's executor, so a generation never waits on another
 * reload task, and none waits on the render thread. The render thread can still wait on the lock:
 * a texture it loads for the first time while a worker is generating, such as a skin, waits for
 * the files.</p>
 *
 * <p>The static files, the language files, are another matter: the language manager reads them
 * on the render thread (in the reload's apply step, and again whenever the player changes
 * language), so they must never wait on a generation. They come from a second supplier, memoised
 * under a lock of its own, which reads only the rule files; a request in their folder never
 * generates. A failed generation takes them with it, and a failure of their own empties the pack
 * before it ever generates, so a failed pack serves nothing.</p>
 *
 * <p><b>Nothing here throws into the reload.</b> Minecraft answers a failed reload by deselecting
 * every resource pack and saving {@code options.txt}, so each entry point catches
 * {@code Throwable} and answers as if the file were absent, logging the first failure at warn
 * and the rest at debug. A generation, or a read of the static files, that fails serves an empty
 * pack for the rest of its life and stops retro for the session
 * ({@link RetroModeManager#latch}).</p>
 *
 * <p>The files are held in memory, and {@link #close()} lets them go. The pack opens nothing
 * that outlives a generation: the generator closes the {@code programmer_art} pack it opened,
 * and never closes the packs it reads below this one or Minecraft's vanilla pack, which the
 * reload owns.</p>
 */
final class RetroPackResources implements PackResources {

    /** The pack's id and title, as Minecraft's reload log lists it among the others. */
    static final PackLocationInfo LOCATION =
            new PackLocationInfo(
                    "vetsmod/retro",
                    Component.literal("Retro textures"),
                    PackSource.BUILT_IN,
                    Optional.empty());

    /**
     * The namespaces the pack can hold: Minecraft's, Wynntils' and avomod2's textures, and
     * vetsmod's own. They are fixed because the resource manager asks before anything is
     * generated.
     */
    private static final Set<String> NAMESPACES =
            Set.of(
                    "minecraft",
                    "wynntils",
                    RetroAssetIds.AVOMOD_NAMESPACE,
                    RetroAssetIds.NAMESPACE);

    /**
     * The top-level folders the generator writes into. They are fixed for the same reason, and a
     * request anywhere else finds nothing without generating, so a reload listener that reads
     * another folder on the render thread, as the font loader does, never starts a generation
     * there. A folder joins this set only once nothing reads it on the render thread before the
     * reload's preparations finish. {@code textures} and {@code models} are read by the atlas and
     * model loaders on the reload's workers; {@code items} only by the model manager's item
     * definition loader ({@code ClientItemInfoLoader.scheduleLoad}), which lists it on the
     * reload's executor ({@code supplyAsync}); {@code equipment} only by the equipment asset
     * loader ({@code EquipmentAssetManager}, a {@code SimpleJsonResourceReloadListener}), whose
     * preparation lists it on a worker too.
     */
    private static final Set<String> FOLDERS = Set.of("textures", "models", "items", "equipment");

    /**
     * The top-level folders of the static files, which never generate: {@code lang}, which the
     * language manager reads on the render thread.
     */
    private static final Set<String> STATIC_FOLDERS = Set.of("lang");

    private final Callable<Map<Identifier, byte[]>> generator;
    private final Callable<Map<Identifier, byte[]>> statics;
    private final Object lock = new Object();
    private final Object staticLock = new Object();
    private final AtomicBoolean warned = new AtomicBoolean();

    /** The generated files, or {@code null} before the first request. */
    private volatile Map<Identifier, byte[]> files;

    /** The static files, or {@code null} before the first request for one. */
    private volatile Map<Identifier, byte[]> staticFiles;

    /** Whether the generation or the static files failed, which empties the pack for good. */
    private volatile boolean broken;

    private volatile boolean closed;

    /** Whether the generation produced files; kept after {@link #close()} lets them go. */
    private volatile boolean generatedFiles;

    /**
     * A pack whose files {@code generator} makes on the first request inside {@link #FOLDERS},
     * and {@code statics} on the first inside {@link #STATIC_FOLDERS}.
     *
     * @param generator makes the files, each id a full file path such as
     *     {@code minecraft:textures/block/stone.png}; called at most once, and may throw
     * @param statics reads the static files, such as {@code minecraft:lang/en_us.json}, without
     *     generating; called at most once, on any thread, and may throw
     */
    RetroPackResources(
            Callable<Map<Identifier, byte[]>> generator,
            Callable<Map<Identifier, byte[]>> statics) {
        this.generator = generator;
        this.statics = statics;
    }

    /**
     * Whether a generation has produced at least one file, even if the pack has since been
     * closed. Never generates.
     *
     * @return {@code false} before generation, after one that failed or found nothing to do, and
     *     once the static files have failed
     */
    boolean generatedFiles() {
        return generatedFiles && !broken;
    }

    /** The files, generating them on the first call; empty after a failure or close. */
    private Map<Identifier, byte[]> files() {
        Map<Identifier, byte[]> generated = files;
        if (generated != null) return generated;
        synchronized (lock) {
            if (files == null) {
                files = closed ? Map.of() : generate();
                // close() sets closed before it empties files, so either it empties them after
                // this assignment or this check sees it: a closed pack keeps no files.
                if (closed) files = Map.of();
            }
            return files;
        }
    }

    /** The static files, read on the first call; empty after a failure or close. */
    private Map<Identifier, byte[]> staticFiles() {
        Map<Identifier, byte[]> read = staticFiles;
        if (read == null) {
            synchronized (staticLock) {
                if (staticFiles == null) {
                    staticFiles =
                            closed ? Map.of() : call(statics, "the retro pack's language files");
                    if (closed) staticFiles = Map.of();
                }
                read = staticFiles;
            }
        }
        return broken ? Map.of() : read;
    }

    /**
     * The files of the folder {@code path}, a file or a folder to list, starts with: the
     * generated files for one of {@link #FOLDERS}, the static files for one of
     * {@link #STATIC_FOLDERS}, and none anywhere else, the root included, which nothing in
     * Minecraft lists.
     */
    private Map<Identifier, byte[]> filesFor(String path) {
        int slash = path.indexOf('/');
        String folder = slash < 0 ? path : path.substring(0, slash);
        if (FOLDERS.contains(folder)) return broken ? Map.of() : files();
        if (STATIC_FOLDERS.contains(folder)) return staticFiles();
        return Map.of();
    }

    private Map<Identifier, byte[]> generate() {
        Map<Identifier, byte[]> generated = call(generator, "the retro pack");
        generatedFiles = !generated.isEmpty();
        return generated;
    }

    /**
     * Calls {@code supplier} for a copy of its files. A failure empties the pack for good and
     * stops retro for the session; the files come back empty.
     */
    private Map<Identifier, byte[]> call(Callable<Map<Identifier, byte[]>> supplier, String what) {
        try {
            Map<Identifier, byte[]> made = supplier.call();
            return made == null ? Map.of() : Map.copyOf(made);
        } catch (Throwable t) {
            broken = true;
            // The latch logs the one warning, with the cause, and tells the player.
            warned.set(true);
            try {
                RetroModeManager.latch(what + " failed to build", t);
            } catch (Throwable u) {
                VetsLogger.warn("Retro pack: could not build {}", what, t);
            }
            return Map.of();
        }
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... path) {
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        try {
            if (closed || type != PackFiles.TYPE) return null;
            byte[] bytes = filesFor(id.getPath()).get(id);
            return bytes == null ? null : supplier(bytes);
        } catch (Throwable t) {
            failed("serve " + id, t);
            return null;
        }
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        try {
            if (closed || type != PackFiles.TYPE) return;
            // As a folder pack lists it: every file under <namespace>/<path>/, at any depth.
            String folder = path.isEmpty() ? "" : path + "/";
            for (Map.Entry<Identifier, byte[]> file : filesFor(path).entrySet()) {
                Identifier id = file.getKey();
                if (id.getNamespace().equals(namespace) && id.getPath().startsWith(folder)) {
                    output.accept(id, supplier(file.getValue()));
                }
            }
        } catch (Throwable t) {
            failed("list " + namespace + ":" + path, t);
        }
    }

    /** A supplier that cannot fail: each call opens a fresh stream over the same bytes. */
    private static IoSupplier<InputStream> supplier(byte[] bytes) {
        return () -> new ByteArrayInputStream(bytes);
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackFiles.TYPE ? NAMESPACES : Set.of();
    }

    /** No metadata sections, so no {@code pack.mcmeta} filter either. */
    @Override
    public <T> T getMetadataSection(MetadataSectionType<T> type) {
        return null;
    }

    @Override
    public PackLocationInfo location() {
        return LOCATION;
    }

    /** Lets the files go; later requests find nothing. */
    @Override
    public void close() {
        closed = true;
        files = Map.of();
        staticFiles = Map.of();
    }

    /** Logs a failure: the first at warn with its stack, the rest at debug. */
    private void failed(String action, Throwable t) {
        if (warned.compareAndSet(false, true)) {
            VetsLogger.warn("Retro pack: could not {}", action, t);
        } else {
            VetsLogger.debug("Retro pack: could not {}", action, t);
        }
    }
}
