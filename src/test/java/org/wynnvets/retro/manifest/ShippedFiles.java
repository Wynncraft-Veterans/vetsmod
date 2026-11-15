package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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

    /** The classpath file of {@code id}: {@code /assets/<namespace>/<folder>/<path><suffix>}. */
    static String assetFile(String id, String folder, String suffix) {
        int colon = id.indexOf(':');
        return "/assets/"
                + id.substring(0, colon)
                + "/"
                + folder
                + "/"
                + id.substring(colon + 1)
                + suffix;
    }

    /** Whether the test classpath has {@code id}'s {@link #assetFile}. */
    static boolean onClasspath(String id, String folder, String suffix) {
        return ShippedFiles.class.getResource(assetFile(id, folder, suffix)) != null;
    }

    static boolean textureInClientJar(String textureId) {
        return onClasspath(textureId, "textures", ".png");
    }

    /** Each source whose art the jar bundles, and that art's folder on the classpath. */
    static final Map<RetroSource, String> BUNDLES =
            Map.of(RetroSource.VANILLA_TWEAKS, "/retro/vanilla_tweaks");

    /**
     * Whether a game downloads {@code source}'s art rather than having it: the old clients, and
     * vetsmod's own panorama.
     */
    static boolean downloaded(RetroSource source) {
        return source.oldClient() || source == RetroSource.VETSMOD;
    }

    /** The classpath file of {@code textureId} in a bundled source's art. */
    static String bundledFile(RetroSource source, String textureId) {
        return BUNDLES.get(source) + assetFile(textureId, "textures", ".png");
    }

    /** Whether {@code source} is bundled and its art has {@code textureId}. */
    static boolean inBundle(RetroSource source, String textureId) {
        return BUNDLES.containsKey(source)
                && ShippedFiles.class.getResource(bundledFile(source, textureId)) != null;
    }

    /** Whether the Vanilla Tweaks art the jar bundles has {@code textureId}. */
    static boolean inVanillaTweaks(String textureId) {
        return inBundle(RetroSource.VANILLA_TWEAKS, textureId);
    }

    /**
     * Every texture a bundled source's art holds: {@code assets/<namespace>/textures/<path>.png}
     * as {@code <namespace>:<path>}.
     *
     * @return the ids, sorted
     */
    static List<String> bundledTextureIds(RetroSource source) throws IOException {
        Path assets;
        try {
            assets =
                    Path.of(
                            ShippedFiles.class
                                    .getResource(BUNDLES.get(source) + "/assets")
                                    .toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        try (Stream<Path> files = Files.walk(assets)) {
            return files.map(file -> assets.relativize(file).toString().replace('\\', '/'))
                    .filter(file -> file.endsWith(".png"))
                    .map(file -> file.split("/", 3))
                    .filter(parts -> parts.length == 3 && parts[1].equals("textures"))
                    .map(
                            parts ->
                                    parts[0]
                                            + ":"
                                            + parts[2].substring(
                                                    0, parts[2].length() - ".png".length()))
                    .sorted()
                    .toList();
        }
    }

    /** The source {@code CLIENT_JAR} reads {@code textureId} from, by the real {@code .*} row. */
    static RetroSource clientJarSource(String textureId) {
        return inVanillaTweaks(textureId) ? RetroSource.VANILLA_TWEAKS : RetroSource.VANILLA;
    }

    /** The texture {@code textureId} names, read from the test classpath. */
    static BufferedImage texture(String textureId) throws IOException {
        String file = assetFile(textureId, "textures", ".png");
        try (InputStream in = ShippedFiles.class.getResourceAsStream(file)) {
            assertNotNull(in, file);
            return ImageIO.read(in);
        }
    }

    /**
     * The packs as the client jar has them: vanilla holds what the jar holds, each bundled source
     * what vetsmod bundles of it, the Programmer Art pack and the downloaded sources nothing (a game
     * that has not fetched them yet, or is offline), and Wynncraft ships every texture asked about,
     * none of them blank.
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
                        case VANILLA_TWEAKS -> inBundle(source, textureId);
                        case VETSMOD,
                                PROGRAMMER_ART,
                                MINECRAFT_1_7_10,
                                MINECRAFT_1_8_9,
                                MINECRAFT_1_9_4 ->
                                false;
                    };
                }
            };

    /**
     * Every source has every texture, but for the bundled sources, which have what vetsmod
     * bundles of them, and the downloaded sources, which have nothing yet, and Wynncraft ships each
     * one blank.
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
                    return !downloaded(source)
                            && (!BUNDLES.containsKey(source) || inBundle(source, textureId));
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
        return json(modelId, "models");
    }

    /** The JSON file {@code id} names under {@code folder}, read from the test classpath. */
    static JsonObject json(String id, String folder) throws IOException {
        String file = assetFile(id, folder, ".json");
        try (InputStream in = ShippedFiles.class.getResourceAsStream(file)) {
            assertNotNull(in, file);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
