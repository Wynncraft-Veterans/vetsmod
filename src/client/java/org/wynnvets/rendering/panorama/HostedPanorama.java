package org.wynnvets.rendering.panorama;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.IntFunction;
import org.wynnvets.util.Digests;

/**
 * vetsmod's own main-menu panoramas: screenshots, six cube faces each, about 5 MB a set, hosted on
 * i.wynnvets.org rather than bundled, since few players turn either on. Each face is pinned by its
 * address, size and SHA-256, so a download is the file the mod was built with or nothing. An object
 * on i.wynnvets.org is never replaced (it is served as immutable for a year): a new picture is a
 * new upload and new pins here.
 */
public enum HostedPanorama {

    /** Old Detlas by day in retro's art: retro's main menu, its {@code vetsmod} source. */
    OLD_DETLAS(
            "old_detlas",
            "assets/minecraft/textures/gui/title/background/panorama_",
            List.of(
                    face(
                            "dzuALy7J",
                            1107162,
                            "d5af3b77decbb7c60143c655617313279ee19e5a69a8c1f43efdf2b49bb1e867"),
                    face(
                            "wjmymZ7q",
                            822833,
                            "163035a6e570d286b7db3f935c43b63da964252ca027cb610c74d8d6474d4124"),
                    face(
                            "rbEJwZD6",
                            793651,
                            "99abb9ec5a78a5ed4353ae5468d2972a1c808e9b58f5d3d91c7d34977cea75a5"),
                    face(
                            "8TTosB7j",
                            1076405,
                            "47510f154065412edd770e0bbf150a5524c912020bec5f6e0a3adc53e8f50d03"),
                    face(
                            "DLZ2H0db",
                            195036,
                            "d37d82364a5f7b691d9e8d0826072919714f095e395fd1edb4fa9ed00fca82e5"),
                    face(
                            "EiH2wdv9",
                            1181625,
                            "cb275bc5cd28a6078d5c2a3f022275fd103b47a7114a1c1e998bc64392a9371a"))),

    /** Vetlas in Wynncraft's art: the {@code /wv extras panorama} easter egg. */
    VETLAS(
            "vetlas",
            "assets/vetsmod/textures/gui/title/background/vetlas_",
            List.of(
                    face(
                            "6Ccez2gc",
                            1193073,
                            "1eec391746d5113816e8da7eae2447a001502081bc4458bceb50fa54056f8e78"),
                    face(
                            "laWuXNmP",
                            1049047,
                            "b66a15fa39176c79b015e95eba77db197ff64e429f5ef4d03823e9c8791975c9"),
                    face(
                            "jYN1Vx7g",
                            947415,
                            "38755566244d404d3b9eb05f8a178b344494a36eab3e28d8726caf97886401ab"),
                    face(
                            "aNcw8doK",
                            939222,
                            "bbef15a2f30ac67b70fcfa06a674d098a15eff2e4b8373427d835c06bea92f00"),
                    face(
                            "dRA3KWtZ",
                            120271,
                            "9989473be621c3cd7f36d140b98bdba13034fcc403e70a308ef37cf585cedfe4"),
                    face(
                            "EcvyglWM",
                            900778,
                            "abb278d2a5f80da8637a5a5e8ed8a4312f58d6d088b5228d4ed17d78282c6a64")));

    /** Where the panoramas are hosted. */
    private static final String HOST = "https://i.wynnvets.org/";

    /** Changes every set's folder when what a folder holds for the same pins changes. */
    private static final int FORMAT = 1;

    /**
     * One face of a panorama, as i.wynnvets.org serves it.
     *
     * @param uri where it is
     * @param size its length in bytes
     * @param sha256 its SHA-256, 64 lower-case hex digits
     */
    public record Face(URI uri, long size, String sha256) {}

    private final String folderName;
    private final String pathPrefix;
    private final List<Face> faces;

    HostedPanorama(String folderName, String pathPrefix, List<Face> faces) {
        this.folderName = folderName;
        this.pathPrefix = pathPrefix;
        this.faces = List.copyOf(faces);
    }

    private static Face face(String name, long size, String sha256) {
        return new Face(URI.create(HOST + name + ".png"), size, sha256);
    }

    /**
     * @return the name of the folder its downloads are kept in
     */
    public String folderName() {
        return folderName;
    }

    /**
     * @return its six faces, face 0 first
     */
    public List<Face> faces() {
        return faces;
    }

    /**
     * Where a face is kept in the set's folder: at its texture's place in a resource pack, so retro
     * can open Old Detlas's folder as a pack.
     *
     * @param face 0 to 5
     * @return such as {@code assets/minecraft/textures/gui/title/background/panorama_0.png}
     */
    public String path(int face) {
        return pathPrefix + face + ".png";
    }

    /**
     * What the set's folder holds, as a name: this format and every face's pin, so new pins make a
     * new folder.
     *
     * @return 16 lower-case hex digits of a SHA-256
     */
    public String hash() {
        return hash(faces, this::path);
    }

    /**
     * {@link #hash()} of any faces.
     *
     * @param faces the faces, face 0 first
     * @param path where each face is kept, by its number
     * @return 16 lower-case hex digits of a SHA-256
     */
    // Package-private for unit tests. See HostedPanoramaTest.
    static String hash(List<Face> faces, IntFunction<String> path) {
        StringBuilder text = new StringBuilder().append(FORMAT).append('\n');
        for (int i = 0; i < faces.size(); i++) {
            text.append(path.apply(i)).append('\t').append(faces.get(i).sha256()).append('\n');
        }
        return Digests.sha256(text.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }
}
