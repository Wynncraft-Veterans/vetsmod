package org.wynnvets.extras.panorama;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.wynnvets.Vetsmod;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.rendering.panorama.HostedCubeMapTexture;
import org.wynnvets.rendering.panorama.HostedPanorama;
import org.wynnvets.rendering.panorama.PanoramaDownloads;
import org.wynnvets.retro.RetroModeManager;

/**
 * The panorama easter egg: while {@code /wv extras panorama} is on and retro is off, the panorama
 * behind the main menu is Vetlas, six faces downloaded from i.wynnvets.org
 * ({@link HostedPanorama#VETLAS}), in place of the one the loaded packs give (Wynncraft's Detlas
 * with Wynntils' preloaded copy of its pack, else vanilla's). The gate is retro's setting, not its pack: retro draws its own panorama through its
 * pack, so while retro is on the egg gives way, even where retro's panorama does not show. See
 * {@code vetsmod_extras.md}.
 *
 * <p>Its faces are downloaded the first time they are wanted, when the egg is turned on or drawn,
 * so a player who never turns it on downloads nothing; until they are here, and if their download
 * fails, the stock panorama shows ({@link PanoramaDownloads}).</p>
 *
 * <p>{@link org.wynnvets.mixin.client.extras.PanoramaRendererMixin PanoramaRendererMixin} hands
 * every panorama draw to {@link #draw}, on the render thread. The setting is read at each draw,
 * so turning it on or off needs no reload. Once the faces are here, the first draw that would show
 * Vetlas has them read and checked on Minecraft's I/O threads, so the render thread never waits on
 * the disk; a later draw then makes the cube map, and registers and loads its texture from the
 * faces held in memory ({@link HostedCubeMapTexture}), on the render thread. Both are let go the
 * first time the panorama is drawn without Vetlas, so the egg costs nothing while it is off. While
 * registered, the texture reloads with every resource reload, on the reload's workers, as
 * Minecraft's own does. It is never let go while a reload runs (while the loading overlay is up):
 * that reload took the texture into its list as it started and would upload it again at its end,
 * into a texture nothing holds any more, which would keep its memory until the game exits.</p>
 *
 * <p>Nothing here throws into the draw: any failure to choose, load, let go or draw Vetlas logs
 * one warning, lets it go (after the reload, if one is running), and leaves the stock panorama
 * until the game restarts. The faces are
 * checked against their pins before the texture is made, and a reload builds it again from the
 * same bytes in memory, never from the disk, so no reload can meet a face it cannot read, where a
 * cube map that fails to load fails the whole reload.</p>
 */
public final class ExtrasPanorama {

    /** The id Vetlas's cube map texture is registered under. */
    // Package-private for unit tests. See ExtrasPanoramaTest.
    static final Identifier LOCATION =
            Identifier.fromNamespaceAndPath(Vetsmod.MOD_ID, "textures/gui/title/background/vetlas");

    /** Vetlas's cube map while it is loaded, else {@code null}. Render thread only. */
    private static CubeMap cubeMap;

    /** Whether Vetlas failed to load or draw, which leaves the stock panorama until restart. */
    private static boolean failed;

    /**
     * The downloaded faces being read and checked off the render thread, else {@code null}.
     * Render thread only.
     */
    private static CompletableFuture<List<byte[]>> reading;

    /**
     * Whether a failure while a reload ran left Vetlas's texture registered, for the next draw
     * after the reload to let go. Render thread only.
     */
    private static boolean releasePending;

    private ExtrasPanorama() {}

    /**
     * Whether the egg is on, whatever retro is.
     *
     * @return the stored setting
     */
    public static boolean isOn() {
        return VetsConfig.get(VetsConfigKeys.EXTRAS_PANORAMA);
    }

    /**
     * Turns the egg on or off, from the next draw on.
     *
     * @param on whether Vetlas shows while retro is off
     */
    public static void setOn(boolean on) {
        VetsConfig.set(VetsConfigKeys.EXTRAS_PANORAMA, on);
        if (on
                && PanoramaDownloads.state(HostedPanorama.VETLAS)
                        == PanoramaDownloads.State.MISSING) {
            PanoramaDownloads.start(HostedPanorama.VETLAS, wrote -> {});
        }
    }

    /**
     * Where Vetlas's download stands.
     *
     * @return its state
     */
    public static PanoramaDownloads.State download() {
        return PanoramaDownloads.state(HostedPanorama.VETLAS);
    }

    /**
     * Whether Vetlas failed this session, which leaves the stock panorama until restart.
     *
     * @return {@code true} once loading, letting go or drawing Vetlas has failed
     */
    public static boolean hasFailed() {
        return failed;
    }

    /**
     * Whether Vetlas is drawn.
     *
     * @param on whether the egg is on
     * @param retro whether retro is on, which draws its own panorama
     * @param failed whether Vetlas failed this session
     * @return {@code true} only when the egg is on, retro is off and Vetlas has not failed
     */
    // Package-private for unit tests. See ExtrasPanoramaTest.
    static boolean shows(boolean on, boolean retro, boolean failed) {
        return on && !retro && !failed;
    }

    /**
     * Draws the panorama: Vetlas while it shows, else {@code stock}. Render thread only. Throws
     * only what drawing {@code stock} throws.
     *
     * @param minecraft the client
     * @param stock the cube map the panorama renderer holds
     * @param render draws a cube map as the renderer would have drawn {@code stock}
     */
    public static void draw(Minecraft minecraft, CubeMap stock, Consumer<CubeMap> render) {
        CubeMap vetlas = null;
        try {
            if (shows(isOn(), RetroModeManager.isEnabled(), failed)) {
                if (cubeMap == null) cubeMap = load(minecraft.getTextureManager());
                vetlas = cubeMap;
            } else if ((cubeMap != null || releasePending)
                    && !(minecraft.getOverlay() instanceof LoadingOverlay)) {
                releasePending = false;
                release(minecraft.getTextureManager());
            }
        } catch (Throwable t) {
            fail(minecraft, t);
        }
        if (vetlas == null) {
            render.accept(stock);
            return;
        }
        try {
            render.accept(vetlas);
        } catch (Throwable t) {
            fail(minecraft, t);
            render.accept(stock);
        }
    }

    /**
     * Registers and loads Vetlas's texture from its downloaded faces, then makes its cube map. The
     * faces are read and checked on Minecraft's I/O threads, not here; while they are being read,
     * or are not here, it gives {@code null}, so the stock panorama shows meanwhile, and when they
     * are not here it starts their download, unless one is running or has failed.
     */
    private static CubeMap load(TextureManager textures) {
        if (reading == null) {
            PanoramaDownloads.State state = PanoramaDownloads.state(HostedPanorama.VETLAS);
            if (state == PanoramaDownloads.State.HERE) {
                reading =
                        CompletableFuture.supplyAsync(
                                () -> PanoramaDownloads.read(HostedPanorama.VETLAS), Util.ioPool());
            } else if (state == PanoramaDownloads.State.MISSING) {
                PanoramaDownloads.start(HostedPanorama.VETLAS, wrote -> {});
            }
            return null;
        }
        if (!reading.isDone()) return null;
        // read never throws; a set it could not read is deleted or marked failed.
        List<byte[]> faces = reading.join();
        reading = null;
        if (faces == null) return null;
        textures.registerAndLoad(LOCATION, new HostedCubeMapTexture(LOCATION, faces));
        return new CubeMap(LOCATION);
    }

    /** Lets the cube map and its texture go; the next draw that shows Vetlas loads them again. */
    private static void release(TextureManager textures) {
        CubeMap held = cubeMap;
        cubeMap = null;
        if (held != null) held.close();
        textures.release(LOCATION);
    }

    /** Stops Vetlas until restart, with one warning, and lets whatever it holds go. */
    private static void fail(Minecraft minecraft, Throwable t) {
        failed = true;
        VetsLogger.warn(
                "Panorama: could not draw Vetlas; the main menu keeps its own panorama until the"
                        + " game restarts",
                t);
        try {
            if (minecraft.getOverlay() instanceof LoadingOverlay) {
                // Never while a reload runs: the next draw after it lets the texture go.
                releasePending = true;
            } else {
                release(minecraft.getTextureManager());
            }
        } catch (Throwable u) {
            VetsLogger.debug("Panorama: could not let Vetlas go", u);
        }
    }
}
