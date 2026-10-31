package org.wynnvets.retro.pack.generate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.manifest.StackSections.StackModel;
import org.wynnvets.retro.pack.PackFiles;
import org.wynnvets.retro.rules.ModelStub;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * The shared art of a generation with a part off, which
 * {@link org.wynnvets.retro.rules.PartFilter#textureOn PartFilter.textureOn} decides by: a weapon's
 * stub draws {@code item/iron_shovel}, owned by {@code items}, so with only {@code weapons} on that
 * texture must still be replaced, and Wynncraft's ingredient models parent vanilla item models, so
 * with {@code items} on and {@code crafting} off {@code item/gold_nugget} must not be.
 *
 * <p>It walks every model {@link ModelStubStep} considers. What a model on the screen draws is its
 * stub's art when its part is on and it has a stub, and otherwise the textures of the model
 * itself, as the packs have it. A model that cannot be read adds nothing, and is one skipped line
 * if it is there but broken. A generation with every part on never builds this.
 *
 * @param on the textures the on parts draw, those they draw by code among them
 * @param off the textures that models of the off parts draw, left as Wynncraft's
 */
record DrawnArt(Set<String> on, Set<String> off) {

    /** The textures of {@code gen}'s models and items, on the walk {@link #of} takes. */
    private static final class Walk {
        private final GenerationContext gen;

        /**
         * Each model's texture slots, its own over its parents'. A model on a cycle of parents
         * stops at the model that began it, which has only what it has read so far.
         */
        private final Map<String, Map<String, String>> slotsByModel = new HashMap<>();

        Walk(GenerationContext gen) {
            this.gen = gen;
        }

        /** The textures a model draws, each with its namespace. */
        Set<String> textures(String modelId) {
            Set<String> textures = new HashSet<>();
            for (String texture : ModelJson.drawn(slots(modelId))) {
                String id = inFull(texture);
                if (id != null) textures.add(id);
            }
            return textures;
        }

        private Map<String, String> slots(String modelId) {
            Map<String, String> known = slotsByModel.get(modelId);
            if (known != null) return known;
            Map<String, String> slots = new HashMap<>();
            slotsByModel.put(modelId, slots);
            Identifier file = PackFiles.modelFile(modelId);
            if (file != null) {
                gen.out.attempt(
                        "could not read the textures " + modelId + " draws",
                        () -> read(file, slots));
            }
            return slots;
        }

        private void read(Identifier file, Map<String, String> slots) throws IOException {
            byte[] json = gen.topFile(file);
            if (json == null) return;
            ModelJson.Named model = ModelJson.read(json);
            String parentId = model.parent() == null ? null : inFull(model.parent());
            if (parentId != null) slots.putAll(slots(parentId));
            slots.putAll(model.slots());
        }

        /** What a stub draws. */
        Set<String> stubArt(ModelStub stub) {
            return switch (stub) {
                case ModelStub.TintProof tintProof -> Set.of(tintProof.art());
                case ModelStub.Potion potion -> Set.of(RetroAssetIds.POTION_BOTTLE);
                case ModelStub.Ingredient ingredient -> textures(ingredient.parent());
                case ModelStub.ItemParent parent -> textures(parent.model());
            };
        }

        /**
         * What the code draws for a vanilla item, as vanilla's item definition has it: the textures
         * of every model it can pick, so all the clock's frames, and the art of a renderer of its
         * own, which no model names. Wynncraft does not redefine the items the code draws, or the
         * pack draws them from a copy of vanilla's definition.
         */
        Set<String> itemArt(String item) {
            Set<String> art = new HashSet<>();
            Identifier id = Identifier.tryParse(item);
            if (id == null) return art;
            gen.out.attempt(
                    "could not read what the item " + item + " draws",
                    () -> {
                        byte[] json =
                                gen.sources.read(RetroSource.VANILLA, PackFiles.ITEMS.idToFile(id));
                        if (json != null) {
                            definitionArt(
                                    JsonParser.parseString(
                                            new String(json, StandardCharsets.UTF_8)),
                                    art);
                        }
                    });
            return art;
        }

        /** Adds the art of one node of an item definition and of every node under it. */
        private void definitionArt(JsonElement node, Set<String> art) {
            if (node.isJsonArray()) {
                node.getAsJsonArray().forEach(element -> definitionArt(element, art));
            }
            if (!node.isJsonObject()) return;
            JsonObject fields = node.getAsJsonObject();
            for (Map.Entry<String, JsonElement> field : fields.entrySet()) {
                JsonElement value = field.getValue();
                if (value.isJsonPrimitive()) {
                    if (field.getKey().equals("model") || field.getKey().equals("base")) {
                        String model = inFull(value.getAsString());
                        if (model != null) art.addAll(textures(model));
                    } else if (field.getKey().equals("type")
                            && value.getAsString().equals(RetroAssetIds.SHIELD_RENDERER)) {
                        art.add(RetroAssetIds.SHIELD_ART);
                    }
                } else {
                    definitionArt(value, art);
                }
            }
        }

        /** An id with its namespace; {@code null} when it is no id. */
        private static String inFull(String id) {
            Identifier parsed = Identifier.tryParse(id);
            return parsed == null ? null : parsed.toString();
        }
    }

    /**
     * Works out the art of one generation.
     *
     * @param gen the generation, some part of it off
     * @return its art
     */
    static DrawnArt of(GenerationContext gen) {
        Walk walk = new Walk(gen);
        Set<String> on = new HashSet<>();
        Set<String> off = new HashSet<>();
        for (Identifier file : ModelStubStep.models(gen)) {
            String id = PackFiles.MODELS.fileToId(file).toString();
            gen.out.attempt(
                    "could not read what " + id + " draws",
                    () -> {
                        if (!gen.parts.modelOn(id)) {
                            off.addAll(walk.textures(id));
                            return;
                        }
                        Optional<ModelStub> stub = gen.modelRules.modelStub(id);
                        on.addAll(stub.isPresent() ? walk.stubArt(stub.get()) : walk.textures(id));
                    });
        }
        if (gen.parts.on(RetroPart.GEAR_BOXES)) {
            for (String item : gen.manifest.stacks().gearBoxIcons().values()) {
                on.addAll(walk.itemArt(item));
            }
            on.addAll(walk.itemArt(RetroAssetIds.GLINTING_BOOK));
        }
        for (StackModel row : gen.manifest.stacks().stackModels()) {
            if (gen.parts.on(row.part())) on.addAll(walk.itemArt(row.model()));
        }
        return new DrawnArt(on, off);
    }

    /**
     * What the code draws for one vanilla item.
     *
     * @param gen the generation
     * @param item the item's id
     * @return the textures it draws
     */
    // Package-private for unit tests. See DrawnArtTest.
    static Set<String> itemArt(GenerationContext gen, String item) {
        return new Walk(gen).itemArt(item);
    }
}
