package org.wynnvets.extras.panorama;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.texture.TextureContents;
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
 * behind the main menu is Vetlas ({@link HostedPanorama#VETLAS}), in place of the one the loaded
 * packs give. The gate is retro's setting, not its pack: retro draws its own panorama through its
 * pack, so while retro is on the egg gives way, even where retro's panorama does not show. See
 * {@code vetsmod_extras.md}.
 *
 * <p>Its faces are downloaded the first time they are wanted, when the egg is turned on or drawn,
 * so a player who never turns it on downloads nothing; until they are here, and if their download
 * fails, the stock panorama shows ({@link PanoramaDownloads}).</p>
 *
 * <p>{@link org.wynnvets.mixin.client.extras.PanoramaRendererMixin PanoramaRendererMixin} hands
 * every panorama draw to {@link #draw}, on the render thread, which reads the setting each time,
 * so turning the egg on or off needs no reload. The faces are read, checked and decoded on
 * Minecraft's I/O threads; the render thread only uploads them ({@link HostedCubeMapTexture}).
 * All of it is let go the first time the panorama is drawn without Vetlas, so the egg costs
 * nothing while it is off. Nothing here throws into the draw: a failure logs one warning and
 * leaves the stock panorama until the game restarts.</p>
 */
public final class ExtrasPanorama {

    /** The id Vetlas's cube map texture is registered under. */
    private static final Identifier LOCATION =
            Identifier.fromNamespaceAndPath(Vetsmod.MOD_ID, "textures/gui/title/background/vetlas");

    /**
     * Vetlas's faces as downloaded, and the cube map's layers decoded from them.
     *
     * @param faces the faces' PNG bytes, which the texture keeps for each resource reload
     * @param layers the layers, for the render thread to upload
     */
    private record Decoded(List<byte[]> faces, TextureContents layers) {}

    /** Vetlas's cube map while it is loaded, else {@code null}. Render thread only. */
    private static CubeMap cubeMap;

    /** Whether Vetlas's texture is registered. Render thread only. */
    private static boolean registered;

    /**
     * The faces being read, checked and decoded off the render thread, else {@code null}; it
     * gives {@code null} when they are not here whole. Render thread only.
     */
    private static CompletableFuture<Decoded> decoding;

    /** Whether Vetlas failed to load or draw, which leaves the stock panorama until restart. */
    private static boolean failed;

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
     * Turns the egg on or off, from the next draw on; turning it on starts Vetlas's download if
     * it is missing.
     *
     * @param on whether Vetlas shows while retro is off
     */
    public static void setOn(boolean on) {
        VetsConfig.set(VetsConfigKeys.EXTRAS_PANORAMA, on);
        if (on) PanoramaDownloads.start(HostedPanorama.VETLAS);
    }

    /**
     * Where Vetlas's download stands.
     *
     * @return its state
     */
    public static PanoramaDownloads.State downloadState() {
        return PanoramaDownloads.state(HostedPanorama.VETLAS);
    }

    /**
     * Whether Vetlas failed this session, which leaves the stock panorama until restart.
     *
     * @return {@code true} once loading or drawing Vetlas has failed
     */
    public static boolean hasFailed() {
        return failed;
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
            if (!failed && isOn() && !RetroModeManager.isEnabled()) {
                if (cubeMap == null) cubeMap = load(minecraft.getTextureManager());
                vetlas = cubeMap;
            } else if ((cubeMap != null || registered || decoding != null)
                    && !reloadRunning(minecraft)) {
                release(minecraft.getTextureManager());
            }
        } catch (Throwable t) {
            fail(t);
        }
        if (vetlas == null) {
            render.accept(stock);
            return;
        }
        try {
            render.accept(vetlas);
        } catch (Throwable t) {
            fail(t);
            render.accept(stock);
        }
    }

    /**
     * Vetlas's cube map once its faces are decoded, else {@code null}, so the stock panorama
     * shows meanwhile. The first call has the faces read, checked and decoded on Minecraft's I/O
     * threads, or, when they are not here, starts their download (unless one is running or has
     * failed); a later call uploads them and registers the texture.
     */
    private static CubeMap load(TextureManager textures) {
        if (decoding == null) {
            if (downloadState() == PanoramaDownloads.State.HERE) {
                decoding = CompletableFuture.supplyAsync(ExtrasPanorama::decode, Util.ioPool());
            } else {
                PanoramaDownloads.start(HostedPanorama.VETLAS);
            }
            return null;
        }
        if (!decoding.isDone()) return null;
        CompletableFuture<Decoded> done = decoding;
        decoding = null;
        // A failed decode throws here, into draw's catch; a set that is not here whole gives
        // null (read deletes it or marks it failed).
        Decoded decoded = done.join();
        if (decoded == null) return null;
        HostedCubeMapTexture texture = null;
        try {
            texture = new HostedCubeMapTexture(LOCATION, decoded.faces());
            texture.apply(decoded.layers());
        } catch (RuntimeException e) {
            if (texture != null) texture.close();
            // apply closes the layers itself unless it throws before it reaches them.
            decoded.layers().close();
            throw e;
        }
        textures.register(LOCATION, texture);
        registered = true;
        return new CubeMap(LOCATION);
    }

    /** Reads, checks and decodes Vetlas's faces; {@code null} when they are not here whole. */
    private static Decoded decode() {
        List<byte[]> faces = PanoramaDownloads.read(HostedPanorama.VETLAS);
        if (faces == null) return null;
        try {
            return new Decoded(faces, HostedCubeMapTexture.decode(LOCATION, faces));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Whether a resource reload is running. Nothing is let go meanwhile: the reload took Vetlas's
     * texture into its list as it started and would upload it again at its end, into a texture
     * nothing holds any more, which would keep its memory until the game exits.
     */
    private static boolean reloadRunning(Minecraft minecraft) {
        return minecraft.getOverlay() instanceof LoadingOverlay;
    }

    /**
     * Lets the cube map, its texture and any decode in flight go; the next draw that shows Vetlas
     * loads them again. The state is cleared first, so a failure here is not met again.
     */
    private static void release(TextureManager textures) {
        CubeMap held = cubeMap;
        boolean release = registered;
        CompletableFuture<Decoded> pending = decoding;
        cubeMap = null;
        registered = false;
        decoding = null;
        if (pending != null) {
            pending.thenAccept(
                    decoded -> {
                        if (decoded != null) decoded.layers().close();
                    });
        }
        try {
            if (held != null) held.close();
        } finally {
            if (release) textures.release(LOCATION);
        }
    }

    /**
     * Stops Vetlas until restart, with one warning; the next draw outside a reload lets go what
     * it holds. Once stopped, a draw only lets go, so a failure after that is logged quietly.
     */
    private static void fail(Throwable t) {
        if (failed) {
            VetsLogger.debug("Panorama: could not let Vetlas go", t);
            return;
        }
        failed = true;
        VetsLogger.warn(
                "Panorama: could not draw Vetlas; the main menu keeps its own panorama until the"
                        + " game restarts",
                t);
    }
}
