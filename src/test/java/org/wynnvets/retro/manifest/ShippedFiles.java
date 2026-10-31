package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.wynnvets.retro.rules.ModelRules;
import org.wynnvets.retro.rules.PackFacts;
import org.wynnvets.retro.rules.TextureRules;

/**
 * The shipped rule files, read as the production code reads them, and what the suites that check
 * them share.
 *
 * <p>The files are the contract, not stubs: {@code src/client/resources} is on the test runtime
 * classpath, so {@code RetroManifest.load()} reads exactly what production reads. So is the client
 * jar (Loom's {@code minecraft-clientOnly}, which carries the vanilla assets), so the shipped
 * suites check that every vanilla texture, model and equipment asset the files name exists in
 * 1.21.11, and that the image edits fit the jar's textures. So are vetsmod's own assets, so they
 * check that the parent models the stubs name ship, each with a blank from its art's atlas. The
 * Wynncraft pack is not on the classpath, so the suites pin model, texture and equipment asset ids
 * taken from Wynncraft pack {@code a43ae4e6} (2026-10-28) and check that the rows cover them and
 * send them to the art each row names.</p>
 *
 * <p>The shipped suites are this package's {@code Shipped*Test} classes, one per family of rows.
 * The decision rules themselves are
 * {@link org.wynnvets.retro.rules.TextureRulesTest TextureRulesTest}'s and
 * {@link org.wynnvets.retro.rules.ModelRulesTest ModelRulesTest}'s; the shipped suites run them
 * only to ask what the real files decide.</p>
 */
final class ShippedFiles {

    private ShippedFiles() {}

    static TextureRules realTextureRules() throws IOException {
        return new TextureRules(RetroManifest.load().textures());
    }

    static ModelRules realModelRules() throws IOException {
        return new ModelRules(RetroManifest.load().models());
    }

    /** Whether the test classpath has {@code assets/<namespace>/<folder>/<path><suffix>}. */
    static boolean onClasspath(String id, String folder, String suffix) {
        int colon = id.indexOf(':');
        String file =
                "/assets/"
                        + id.substring(0, colon)
                        + "/"
                        + folder
                        + "/"
                        + id.substring(colon + 1)
                        + suffix;
        return ShippedFiles.class.getResource(file) != null;
    }

    static boolean textureInClientJar(String textureId) {
        return onClasspath(textureId, "textures", ".png");
    }

    /** Whether the Vanilla Tweaks art the jar bundles has {@code textureId}. */
    static boolean inVanillaTweaks(String textureId) {
        return ShippedFiles.class.getResource(vanillaTweaksFile(textureId)) != null;
    }

    /** The source {@code CLIENT_JAR} reads {@code textureId} from, by the real {@code .*} row. */
    static RetroSource clientJarSource(String textureId) {
        return inVanillaTweaks(textureId) ? RetroSource.VANILLA_TWEAKS : RetroSource.VANILLA;
    }

    /** The classpath file of {@code textureId} in the Vanilla Tweaks art. */
    static String vanillaTweaksFile(String textureId) {
        int colon = textureId.indexOf(':');
        return "/retro/vanilla_tweaks/assets/"
                + textureId.substring(0, colon)
                + "/textures/"
                + textureId.substring(colon + 1)
                + ".png";
    }

    /** The texture {@code textureId} names, read from the test classpath. */
    static BufferedImage texture(String textureId) throws IOException {
        int colon = textureId.indexOf(':');
        String file =
                "/assets/"
                        + textureId.substring(0, colon)
                        + "/textures/"
                        + textureId.substring(colon + 1)
                        + ".png";
        try (InputStream in = ShippedFiles.class.getResourceAsStream(file)) {
            assertNotNull(in, file);
            return ImageIO.read(in);
        }
    }

    /**
     * The packs as the client jar has them: vanilla holds what the jar holds, Vanilla Tweaks what
     * vetsmod bundles, the Programmer Art packs nothing, and Wynncraft ships every texture asked
     * about, none of them blank.
     */
    static final PackFacts CLIENT_JAR =
            new PackFacts() {
                @Override
                public boolean wynncraftShips(String textureId) {
                    return true;
                }

                @Override
                public boolean wynncraftTransparent(String textureId) {
                    return false;
                }

                @Override
                public boolean has(RetroSource source, String textureId) {
                    return switch (source) {
                        case VANILLA -> textureInClientJar(textureId);
                        case VANILLA_TWEAKS -> inVanillaTweaks(textureId);
                        case PROGRAMMER_ART -> false;
                    };
                }
            };

    /**
     * Every source has every texture, but for Vanilla Tweaks, which has what vetsmod bundles, and
     * Wynncraft ships each one blank.
     */
    static final PackFacts EVERYTHING_BLANK =
            new PackFacts() {
                @Override
                public boolean wynncraftShips(String textureId) {
                    return true;
                }

                @Override
                public boolean wynncraftTransparent(String textureId) {
                    return true;
                }

                @Override
                public boolean has(RetroSource source, String textureId) {
                    return source != RetroSource.VANILLA_TWEAKS || inVanillaTweaks(textureId);
                }
            };

    /**
     * A vanilla model's or texture's id in full: {@code item/bow} as {@code minecraft:item/bow}.
     */
    static String inFull(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /** The model {@code modelId} names, read from the test classpath. */
    static JsonObject model(String modelId) throws IOException {
        int colon = modelId.indexOf(':');
        String file =
                "/assets/"
                        + modelId.substring(0, colon)
                        + "/models/"
                        + modelId.substring(colon + 1)
                        + ".json";
        try (InputStream in = ShippedFiles.class.getResourceAsStream(file)) {
            assertNotNull(in, file);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
