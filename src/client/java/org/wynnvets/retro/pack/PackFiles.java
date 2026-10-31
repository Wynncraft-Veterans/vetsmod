package org.wynnvets.retro.pack;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * Where a resource pack keeps what retro reads and writes: the pack type, the file of each kind of
 * id and back, and reading a file's bytes. It holds nothing but the converters, and touches no
 * pack until a method is handed one.
 */
public final class PackFiles {

    /** The side of a pack everything retro touches is on. */
    public static final PackType TYPE = PackType.CLIENT_RESOURCES;

    /** The folder of the models. */
    public static final String MODELS_FOLDER = "models";

    /** The folder of the equipment assets: how armour and other worn items are drawn. */
    public static final String EQUIPMENT_FOLDER = "equipment";

    private static final String TEXTURES_FOLDER = "textures";

    private static final String TEXTURE_EXTENSION = ".png";

    private static final String JSON_EXTENSION = ".json";

    /** Texture files, {@code textures/<path>.png}, and the texture ids they hold. */
    public static final FileToIdConverter TEXTURES =
            new FileToIdConverter(TEXTURES_FOLDER, TEXTURE_EXTENSION);

    /** Model files, {@code models/<path>.json}, and the model ids they hold. */
    public static final FileToIdConverter MODELS = FileToIdConverter.json(MODELS_FOLDER);

    /** Item definition files, {@code items/<path>.json}, and the item model ids they hold. */
    public static final FileToIdConverter ITEMS = FileToIdConverter.json("items");

    /** Equipment asset files, {@code equipment/<path>.json}, and the asset ids they hold. */
    public static final FileToIdConverter EQUIPMENT = FileToIdConverter.json(EQUIPMENT_FOLDER);

    /** Language files, {@code lang/<code>.json}, and the language codes they hold. */
    public static final FileToIdConverter LANG = FileToIdConverter.json("lang");

    private PackFiles() {}

    /**
     * The file of a texture.
     *
     * @param textureId the texture id, such as {@code minecraft:block/stone}
     * @return its file, such as {@code minecraft:textures/block/stone.png}; {@code null} when
     *     {@code textureId} is not an id
     */
    public static Identifier textureFile(String textureId) {
        Identifier id = Identifier.tryParse(textureId);
        return id == null ? null : TEXTURES.idToFile(id);
    }

    /**
     * The file of a model.
     *
     * @param modelId the model id, such as {@code minecraft:item/stick}
     * @return its file, such as {@code minecraft:models/item/stick.json}; {@code null} when
     *     {@code modelId} is not an id
     */
    public static Identifier modelFile(String modelId) {
        Identifier id = Identifier.tryParse(modelId);
        return id == null ? null : MODELS.idToFile(id);
    }

    /**
     * The file of an equipment asset.
     *
     * @param assetId the asset id, such as {@code minecraft:iron}
     * @return its file, such as {@code minecraft:equipment/iron.json}; {@code null} when
     *     {@code assetId} is not an id
     */
    public static Identifier equipmentFile(String assetId) {
        Identifier id = Identifier.tryParse(assetId);
        return id == null ? null : EQUIPMENT.idToFile(id);
    }

    /**
     * The {@code .mcmeta} beside a file.
     *
     * @param file the file, such as {@code minecraft:textures/block/stone.png}
     * @return such as {@code minecraft:textures/block/stone.png.mcmeta}
     */
    public static Identifier mcmeta(Identifier file) {
        return file.withSuffix(PackResources.METADATA_EXTENSION);
    }

    /**
     * Whether a file is a JSON file, as a folder's listing can hold others.
     *
     * @param file the file
     * @return whether its path ends in {@code .json}
     */
    public static boolean isJson(Identifier file) {
        return file.getPath().endsWith(JSON_EXTENSION);
    }

    /**
     * A file's bytes.
     *
     * @param contents the file as a pack gives it, which is {@code null} for a file it lacks
     * @return the bytes, or {@code null} for no supplier
     * @throws IOException when the file cannot be read
     */
    public static byte[] read(IoSupplier<InputStream> contents) throws IOException {
        if (contents == null) return null;
        try (InputStream in = contents.get()) {
            return in.readAllBytes();
        }
    }

    /**
     * The JSON files {@code pack} itself holds under a folder.
     *
     * @param pack the pack, listed directly rather than through a resource manager
     * @param namespace the namespace
     * @param folder the folder, such as {@code models/item/wynn}
     * @return the files, each by its full path, in the pack's order; a new, modifiable list
     */
    public static List<Identifier> jsonFiles(PackResources pack, String namespace, String folder) {
        List<Identifier> files = new ArrayList<>();
        pack.listResources(
                TYPE,
                namespace,
                folder,
                (file, contents) -> {
                    if (isJson(file)) files.add(file);
                });
        return files;
    }

    /**
     * Every texture id {@code pack} itself holds, in any namespace.
     *
     * @param pack the pack, listed directly rather than through a resource manager
     * @return the ids, such as {@code minecraft:block/stone}; a new, modifiable set
     */
    public static Set<String> textureIds(PackResources pack) {
        Set<String> ids = new HashSet<>();
        for (String namespace : pack.getNamespaces(TYPE)) {
            pack.listResources(
                    TYPE,
                    namespace,
                    TEXTURES_FOLDER,
                    (file, contents) -> {
                        if (file.getPath().endsWith(TEXTURE_EXTENSION)) {
                            ids.add(TEXTURES.fileToId(file).toString());
                        }
                    });
        }
        return ids;
    }
}
