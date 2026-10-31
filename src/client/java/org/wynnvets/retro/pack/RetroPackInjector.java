package org.wynnvets.retro.pack;

import com.wynntils.utils.mc.McUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.pack.generate.ProgrammerArtSources;
import org.wynnvets.retro.pack.generate.RetroPackGenerator;

/**
 * Adds the retro pack to Minecraft's resource reloads.
 * {@link org.wynnvets.mixin.client.retro.RetroResourceReloadMixin RetroResourceReloadMixin} hands
 * every reload's pack list to {@link #inject} as the reload is created, and the reload itself to
 * {@link #watch} once it has started.
 *
 * <p>The pack goes directly above the highest Wynncraft pack, so it outranks Wynncraft's art and
 * nothing else of the player's: a resource pack the player put above Wynncraft's still wins. In
 * the first session after a Wynncraft pack update, Wynncraft's pack arrives as a server pack,
 * which Minecraft always places on top, so the retro pack then sits above the player's packs
 * too. That is accepted rather than worked around.</p>
 *
 * <p>A reload gets no retro pack when retro is off or stopped for the session, when it has no
 * Wynncraft pack, or when it is Minecraft's recovery reload, the one that follows a failed reload
 * with every resource pack deselected.</p>
 *
 * <p>Both entry points run on the render thread, inside {@code createReload}. They do no I/O
 * beyond one lookup per candidate pack, and they never throw: a failure only leaves the reload
 * without a retro pack.</p>
 */
public final class RetroPackInjector {

    /** The id prefix Minecraft's {@code DownloadedPackSource} gives a pack a server sent. */
    private static final String SERVER_PACK_PREFIX = "server/";

    /** The id prefix of Wynntils' preloaded copy of Wynncraft's pack; its SHA-1 follows. */
    private static final String PRELOADED_PACK_PREFIX = "wynntils_preloaded/";

    /** A file only Wynncraft's pack has: the font of its chat banners. */
    private static final Identifier WYNNCRAFT_PROBE =
            Identifier.withDefaultNamespace("font/banner/box.json");

    /**
     * The latest reload's completion. When it failed, the next reload is Minecraft's recovery
     * reload. Written on the render thread.
     */
    private static volatile CompletableFuture<?> lastReload;

    /**
     * A retro pack {@link #inject} added, and the number {@link RetroModeManager#reloadStarted()}
     * gave its reload.
     */
    private record Injected(RetroPackResources pack, int reload) {}

    /** What {@link #inject} added to the reload being created, for {@link #watch}; or none. */
    private static Injected injected;

    private RetroPackInjector() {}

    /**
     * Adds a retro pack to a reload's packs, directly above the highest Wynncraft pack. Clears
     * {@link RetroModeManager#isPackLive()} for the reload that is starting.
     *
     * @param packs the packs the reload will load, bottom first
     * @return the packs with the retro pack added, or {@code packs} itself when this reload gets
     *     none
     */
    public static List<PackResources> inject(List<PackResources> packs) {
        int reload = RetroModeManager.reloadStarted();
        injected = null;
        try {
            if (!RetroModeManager.isActive()) return packs;
            CompletableFuture<?> last = lastReload;
            if (last != null && last.isCompletedExceptionally()) {
                VetsLogger.debug("Retro pack: left out of Minecraft's recovery reload");
                return packs;
            }
            int wynncraft = highestWynncraftPack(packs);
            if (wynncraft < 0) return packs;
            Minecraft mc = McUtils.mc();
            RetroPackGenerator generator =
                    new RetroPackGenerator(
                            packs,
                            wynncraft,
                            mc.getResourcePackRepository()
                                    .getPack(ProgrammerArtSources.PROGRAMMER_ART_PACK),
                            mc.getVanillaPackResources());
            RetroPackResources retro =
                    new RetroPackResources(generator::generate, RetroPackGenerator::staticFiles);
            List<PackResources> withRetro = new ArrayList<>(packs);
            withRetro.add(wynncraft + 1, retro);
            injected = new Injected(retro, reload);
            VetsLogger.debug("Retro pack: added above {}", packs.get(wynncraft).packId());
            return withRetro;
        } catch (Throwable t) {
            VetsLogger.warn("Retro pack: could not add it to this reload", t);
            return packs;
        }
    }

    /**
     * Follows a reload that {@link #inject} has seen. When it finishes, a retro pack in it with
     * files makes {@link RetroModeManager#isPackLive()} true. When it fails with such a pack in
     * it, retro stops for the session: the retro pack may be what failed it, and Minecraft's
     * answer, deselecting every resource pack, must not happen twice.
     *
     * @param reload the reload {@code createReload} just created
     */
    public static void watch(ReloadInstance reload) {
        Injected pending = injected;
        injected = null;
        try {
            CompletableFuture<?> done = reload.done();
            lastReload = done;
            if (pending == null) return;
            done.whenComplete((result, failure) -> finished(pending, failure));
        } catch (Throwable t) {
            VetsLogger.warn("Retro pack: could not follow this reload", t);
        }
    }

    private static void finished(Injected pending, Throwable failure) {
        try {
            if (!pending.pack().generatedFiles()) return;
            if (failure != null) {
                RetroModeManager.latch(
                        "a resource reload with the retro pack in it failed", failure);
            } else {
                RetroModeManager.packWentLive(pending.reload());
            }
        } catch (Throwable t) {
            VetsLogger.warn("Retro pack: could not record the end of a reload", t);
        }
    }

    /**
     * Whether the loaded resources include a Wynncraft pack. Render thread only.
     *
     * @return {@code true} when one of the current packs is Wynncraft's
     */
    public static boolean isWynncraftLoaded() {
        return McUtils.mc()
                .getResourceManager()
                .listPacks()
                .anyMatch(RetroPackInjector::isWynncraftPack);
    }

    /**
     * Whether the loaded resources include a retro pack, empty or not. Render thread only.
     *
     * @return {@code true} when the last reload got a retro pack
     */
    public static boolean isRetroLoaded() {
        return McUtils.mc()
                .getResourceManager()
                .listPacks()
                .anyMatch(RetroPackInjector::isRetroPack);
    }

    /**
     * Whether {@code pack} is a retro pack.
     *
     * @param pack a loaded pack
     * @return {@code true} for the pack {@link #inject} adds, empty or not
     */
    public static boolean isRetroPack(PackResources pack) {
        return pack instanceof RetroPackResources;
    }

    /** The index of the highest Wynncraft pack in {@code packs}, or {@code -1}. */
    // Package-private for unit tests. See RetroPackInjectorTest.
    static int highestWynncraftPack(List<PackResources> packs) {
        for (int i = packs.size() - 1; i >= 0; i--) {
            if (isWynncraftPack(packs.get(i))) return i;
        }
        return -1;
    }

    /**
     * Whether {@code pack} is Wynncraft's: a server pack, or Wynntils' preloaded copy, that has
     * {@link #WYNNCRAFT_PROBE}. The id alone would also take another server's pack.
     */
    // Package-private for unit tests. See RetroPackInjectorTest.
    static boolean isWynncraftPack(PackResources pack) {
        String id = pack.packId();
        if (!id.startsWith(SERVER_PACK_PREFIX) && !id.startsWith(PRELOADED_PACK_PREFIX)) {
            return false;
        }
        try {
            return pack.getResource(PackFiles.TYPE, WYNNCRAFT_PROBE) != null;
        } catch (RuntimeException e) {
            VetsLogger.debug("Retro pack: could not look inside {}", id, e);
            return false;
        }
    }
}
