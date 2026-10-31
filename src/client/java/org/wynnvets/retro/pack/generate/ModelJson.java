package org.wynnvets.retro.pack.generate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The JSON files the pack writes for its stubs and generated items, as bytes, and what it reads of
 * a model's.
 */
final class ModelJson {

    private static final String ITEM_GENERATED = "minecraft:item/generated";

    /** The slot of the texture a model's particles take, which a slot or a hand does not draw. */
    private static final String PARTICLE = "particle";

    private ModelJson() {}

    /**
     * What a model's file names.
     *
     * @param slots its texture slots: each to a texture id, or to a {@code #} reference to a slot
     * @param parent its parent's model id, or {@code null}
     */
    record Named(Map<String, String> slots, String parent) {}

    /**
     * Reads a model's file. A slot in the object form names its {@code sprite}.
     *
     * @param json the file's bytes
     * @return what it names
     * @throws RuntimeException when the file is not such a model
     */
    static Named read(byte[] json) {
        JsonObject model =
                JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> slots = new HashMap<>();
        JsonObject textures = model.getAsJsonObject("textures");
        if (textures != null) {
            for (Map.Entry<String, JsonElement> slot : textures.entrySet()) {
                JsonElement value = slot.getValue();
                slots.put(
                        slot.getKey(),
                        value.isJsonObject()
                                ? value.getAsJsonObject().get("sprite").getAsString()
                                : value.getAsString());
            }
        }
        JsonElement parent = model.get("parent");
        return new Named(slots, parent == null ? null : parent.getAsString());
    }

    /**
     * The textures a model with these slots draws: each slot's value, through its {@code #}
     * references, but for {@code particle}. A reference to a slot these do not fill names nothing.
     *
     * @param slots the slots
     * @return the texture ids, as the slots spell them
     */
    static Set<String> drawn(Map<String, String> slots) {
        Set<String> textures = new HashSet<>();
        for (Map.Entry<String, String> slot : slots.entrySet()) {
            if (slot.getKey().equals(PARTICLE)) continue;
            String value = slot.getValue();
            // A loop of references runs out at the number of slots, still on a reference.
            for (int hops = 0; hops < slots.size() && isReference(value); hops++) {
                value = slots.get(value.substring(1));
            }
            if (value != null && !isReference(value)) textures.add(value);
        }
        return textures;
    }

    private static boolean isReference(String value) {
        return value != null && value.startsWith("#");
    }

    /**
     * A model that only names a parent.
     *
     * @param parent the parent's model id
     * @return the file's bytes
     */
    static byte[] parentOnly(String parent) {
        return bytes(model(parent));
    }

    /**
     * A model that names a parent and fills one of its texture slots.
     *
     * @param parent the parent's model id
     * @param slot the slot, such as {@code art}
     * @param texture the texture id that fills it
     * @return the file's bytes
     */
    static byte[] withTexture(String parent, String slot, String texture) {
        JsonObject textures = new JsonObject();
        textures.addProperty(slot, texture);
        JsonObject json = model(parent);
        json.add("textures", textures);
        return bytes(json);
    }

    /**
     * A flat item model drawing one texture.
     *
     * @param texture the texture id
     * @return the file's bytes
     */
    static byte[] generatedItem(String texture) {
        return withTexture(ITEM_GENERATED, "layer0", texture);
    }

    /**
     * An item definition that draws one model.
     *
     * @param model the model id
     * @return the file's bytes
     */
    static byte[] itemDefinition(String model) {
        JsonObject drawn = new JsonObject();
        drawn.addProperty("type", "minecraft:model");
        drawn.addProperty("model", model);
        JsonObject definition = new JsonObject();
        definition.add("model", drawn);
        return bytes(definition);
    }

    private static JsonObject model(String parent) {
        JsonObject json = new JsonObject();
        json.addProperty("parent", parent);
        return json;
    }

    private static byte[] bytes(JsonObject json) {
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }
}
