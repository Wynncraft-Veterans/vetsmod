package org.wynnvets.items;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.wynntils.mc.extension.MinecraftExtension;
import com.wynntils.utils.SystemUtils;
import com.wynntils.utils.mc.McUtils;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Vector2i;
import org.wynnvets.logging.VetsLogger;

/**
 * Takes legacy-aware item screenshots that include VetsMod's tooltip
 * modifications, overwriting Wynntils' pre-modification clipboard image.
 *
 * <p>Wynntils' {@code ItemScreenshotFeature} captures the tooltip at event
 * level (before our mixin runs), so its screenshot lacks our gold names and
 * LEGACY boxes.  This handler re-renders the final tooltip after all
 * modifications and copies the result to the clipboard, silently replacing
 * Wynntils' version.  It acts only on a key press latched over a legacy item
 * (see {@link ScreenshotLatch}).</p>
 */
final class LegacyScreenshotHandler {

    /**
     * Draws the tooltip 10 units in, where Wynntils' own capture draws it. Wynntils 4.2.13 and
     * later forces that position onto every tooltip drawn until its capture's readback
     * completes, a frame or more after the press, and this capture always runs in that window, so
     * any other position here would be overridden anyway; the capture size computed in
     * {@code takeScreenshot} assumes this position.
     */
    private static final ClientTooltipPositioner SCREENSHOT_POSITIONER =
            (screenWidth, screenHeight, mouseX, mouseY, tooltipWidth, tooltipHeight) ->
                    new Vector2i(10, 10);

    /** Delay before clipboard write so we overwrite Wynntils' async copy. */
    private static final Executor DELAYED_EXECUTOR =
            CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS);

    /**
     * Armed by {@link #notifyKeyPressed()} when Wynntils' screenshot keybind is pressed over a
     * legacy item. Consumed by the next call to {@link #isScreenshotRequested()}, or dropped if
     * no tooltip pass consumes it within {@link ScreenshotLatch#LIFETIME_MS}.
     */
    private static final ScreenshotLatch LATCH = new ScreenshotLatch();

    private LegacyScreenshotHandler() {}

    /** Arms the latch for one screenshot, valid for about a second. */
    static void notifyKeyPressed() {
        LATCH.press(nowMs());
    }

    /**
     * Returns {@code true} exactly once per press, and only if the press is under a second old.
     * Always disarms the latch, so subsequent tooltip renders on the same frame don't re-trigger
     * and a stale press never fires on a later tooltip.
     */
    static boolean isScreenshotRequested() {
        return LATCH.consume(nowMs());
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    /**
     * Renders the given tooltip to an offscreen framebuffer and copies the
     * resulting image to the system clipboard.
     */
    static void takeScreenshot(Font font, List<Component> tooltip) {
        Screen screen = McUtils.screen();
        if (screen == null) return;

        int width = 0;
        for (Component c : tooltip) {
            int w = font.width(c);
            if (w > width) width = w;
        }
        width += 8;

        int height = 16;
        if (tooltip.size() > 1) {
            height += 2 + (tooltip.size() - 1) * 10;
        }

        // The background sprite reaches 12 units past the text on each side (3 padding plus a
        // 9 margin, TooltipRenderUtil), so pad both axes by 12, as Wynntils' capture does.
        width += 12;
        height += 12;

        // The framebuffer is twice this size; the screen is what the render is scaled from.
        VetsLogger.debug(
                "Legacy screenshot: captured {}x{}, screen {}x{}, window {}x{}, gui scale {}",
                width,
                height,
                screen.width,
                screen.height,
                McUtils.mc().getWindow().getWidth(),
                McUtils.mc().getWindow().getHeight(),
                McUtils.mc().getWindow().getGuiScale());

        List<ClientTooltipComponent> rendered =
                tooltip.stream()
                        .map(Component::getVisualOrderText)
                        .map(ClientTooltipComponent::create)
                        .toList();

        renderToFramebuffer(screen, rendered, width, height)
                .thenApplyAsync(
                        nativeImage -> {
                            if (nativeImage == null) return null;
                            return SystemUtils.createScreenshot(nativeImage);
                        })
                .thenAcceptAsync(
                        bi -> {
                            if (bi == null) return;
                            try {
                                SystemUtils.copyImageToClipboard(bi);
                                VetsLogger.info("Legacy tooltip screenshot copied to clipboard");
                            } catch (Exception ex) {
                                VetsLogger.error(
                                        "Failed to copy legacy screenshot to clipboard", ex);
                            }
                        },
                        DELAYED_EXECUTOR)
                .exceptionally(
                        err -> {
                            VetsLogger.error("Legacy tooltip screenshot failed", err);
                            return null;
                        });
    }

    private static CompletableFuture<NativeImage> renderToFramebuffer(
            Screen screen, List<ClientTooltipComponent> tooltip, int width, int height) {
        TextureTarget framebuffer =
                new TextureTarget("VetsMod Legacy Screenshot", width * 2, height * 2, true);
        RenderSystem.getDevice()
                .createCommandEncoder()
                .clearColorAndDepthTextures(
                        framebuffer.getColorTexture(), 0, framebuffer.getDepthTexture(), 1.0);

        ((MinecraftExtension) McUtils.mc()).setOverridenRenderTarget(framebuffer);
        RenderSystem.outputColorTextureOverride = framebuffer.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = framebuffer.getDepthTextureView();

        Minecraft mc = McUtils.mc();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        GuiRenderState guiRenderState = new GuiRenderState();
        GuiRenderer guiRenderer =
                new GuiRenderer(
                        guiRenderState,
                        bufferSource,
                        mc.gameRenderer.getSubmitNodeStorage(),
                        mc.gameRenderer.getFeatureRenderDispatcher(),
                        List.of());
        GuiGraphics guiGraphics = new GuiGraphics(mc, guiRenderState, 0, 0);

        float scaleh = (float) screen.height / height;
        float scalew = (float) screen.width / width;

        guiGraphics.pose().pushMatrix();
        guiGraphics.pose().scale(scalew, scaleh);
        guiGraphics.renderTooltip(mc.font, tooltip, 0, 0, SCREENSHOT_POSITIONER, null);
        guiGraphics.pose().popMatrix();

        bufferSource.endBatch();
        guiRenderer.render(mc.gameRenderer.fogRenderer.getBuffer(FogRenderer.FogMode.NONE));
        guiRenderer.close();

        RenderSystem.outputColorTextureOverride = null;
        RenderSystem.outputDepthTextureOverride = null;
        ((MinecraftExtension) McUtils.mc()).setOverridenRenderTarget(null);
        GpuTexture texture = cloneColorAttachment(framebuffer);
        framebuffer.destroyBuffers();

        return SystemUtils.createImage(texture);
    }

    private static GpuTexture cloneColorAttachment(RenderTarget framebuffer) {
        GpuTexture original = framebuffer.getColorTexture();
        GpuDevice gpuDevice = RenderSystem.getDevice();
        GpuTexture copy =
                gpuDevice.createTexture(
                        () -> "Copy of: " + original.getLabel(),
                        GpuTexture.USAGE_COPY_DST
                                | GpuTexture.USAGE_COPY_SRC
                                | GpuTexture.USAGE_TEXTURE_BINDING
                                | GpuTexture.USAGE_RENDER_ATTACHMENT,
                        TextureFormat.RGBA8,
                        framebuffer.width,
                        framebuffer.height,
                        1,
                        1);
        gpuDevice
                .createCommandEncoder()
                .copyTextureToTexture(
                        original, copy, 0, 0, 0, 0, 0, framebuffer.width, framebuffer.height);
        return copy;
    }
}
