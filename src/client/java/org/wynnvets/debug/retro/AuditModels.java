package org.wynnvets.debug.retro;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wynntils.utils.mc.McUtils;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import org.wynnvets.debug.dump.LiveItemModels;
import org.wynnvets.retro.RetroReport;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.pack.PackFiles;
import org.wynnvets.retro.rules.ModelRules;
import org.wynnvets.retro.rules.ModelStub;

/**
 * What {@link RetroAudit}'s report says of Wynncraft's item models: how they parent each other,
 * which reach a stub that way, and which ingredient stems need a look.
 */
final class AuditModels {

    /** How many parents a model's chain is followed through. */
    private static final int MAX_PARENTS = 16;

    /** The folder of the item models the report looks through. */
    private static final String ITEM_MODELS = "models/item";

    private AuditModels() {}

    /**
     * One of Wynncraft's item models that names a parent.
     *
     * @param parent the parent's model id
     * @param ownArt whether it sets {@code textures} or {@code elements}, which draw in place of
     *     its parent's art
     */
    record Parented(String parent, boolean ownArt) {}

    /**
     * The models of Wynncraft's pack that name a parent.
     *
     * @param wynncraft Wynncraft's pack
     * @return each such item model, by model id; a model that cannot be read is left out
     */
    static Map<String, Parented> parents(PackResources wynncraft) {
        Map<String, Parented> parents = new HashMap<>();
        wynncraft.listResources(
                PackFiles.TYPE,
                Identifier.DEFAULT_NAMESPACE,
                ITEM_MODELS,
                (file, contents) -> {
                    if (!PackFiles.isJson(file)) return;
                    try (Reader reader =
                            new InputStreamReader(contents.get(), StandardCharsets.UTF_8)) {
                        JsonElement json = JsonParser.parseReader(reader);
                        JsonObject model = json.isJsonObject() ? json.getAsJsonObject() : null;
                        JsonElement parent = model == null ? null : model.get("parent");
                        Identifier id =
                                parent != null && parent.isJsonPrimitive()
                                        ? Identifier.tryParse(parent.getAsString())
                                        : null;
                        if (id != null) {
                            boolean ownArt = model.has("textures") || model.has("elements");
                            parents.put(
                                    PackFiles.MODELS.fileToId(file).toString(),
                                    new Parented(id.toString(), ownArt));
                        }
                    } catch (IOException | RuntimeException e) {
                        // A model that cannot be read names no parent here.
                    }
                });
        return parents;
    }

    /**
     * Each model that is not stubbed but whose chain of parents reaches a stubbed model.
     *
     * @param rules the model rules, which name each stub's rule
     * @param parents {@link #parents}
     * @param seen the generation
     * @return one line per model, in id order, with that chain and the stub's rule
     */
    static List<String> derived(
            ModelRules rules, Map<String, Parented> parents, RetroReport.Seen seen) {
        return reachingAStub(rules, parents, seen, link -> false);
    }

    /**
     * Each model that draws a stub through its parent: {@link #derived}, without the models that
     * draw art of their own, or that reach the stub through a model that does. A new item of this
     * shape shows the stub's art though no rule names it.
     *
     * @param rules the model rules, which name each stub's rule
     * @param parents {@link #parents}
     * @param seen the generation
     * @return one line per model, in id order, with that chain and the stub's rule
     */
    static List<String> drawnThroughParent(
            ModelRules rules, Map<String, Parented> parents, RetroReport.Seen seen) {
        return reachingAStub(rules, parents, seen, Parented::ownArt);
    }

    /** The models whose chain reaches a stub, walking no further than a link {@code stops}. */
    private static List<String> reachingAStub(
            ModelRules rules,
            Map<String, Parented> parents,
            RetroReport.Seen seen,
            Predicate<Parented> stops) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Parented> model : new TreeMap<>(parents).entrySet()) {
            if (seen.stubs().contains(model.getKey())) continue;
            List<String> chain = new ArrayList<>();
            Parented link = model.getValue();
            while (link != null && !stops.test(link) && chain.size() < MAX_PARENTS) {
                String parent = link.parent();
                chain.add(parent);
                if (seen.stubs().contains(parent)) {
                    lines.add(
                            model.getKey()
                                    + " -> "
                                    + String.join(" -> ", chain)
                                    + "  "
                                    + rules.modelStub(parent).map(AuditModels::stub).orElse(""));
                    break;
                }
                link = parents.get(parent);
            }
        }
        return lines;
    }

    /**
     * The ingredient stems the generation saw: every one, or only those with no row whose model
     * parents no vanilla model.
     */
    static List<String> ingredients(
            RetroManifest manifest,
            RetroReport.Seen seen,
            Map<String, Parented> parents,
            PackResources vanilla,
            ResourceManager resources,
            Map<String, ItemStack> wynntilsIngredients,
            boolean all) {
        Map<String, List<String>> names =
                all ? wynntilsNames(resources, wynntilsIngredients) : Map.of();
        List<String> lines = new ArrayList<>();
        for (String model : seen.models()) {
            String stem = ModelRules.ingredientStem(model);
            if (stem == null) continue;
            String row = manifest.models().ingredients().get(stem);
            Parented parented = parents.get(model);
            String parent = parented == null ? null : parented.parent();
            Identifier parentFile = parent == null ? null : PackFiles.modelFile(parent);
            boolean vanillaParent =
                    parentFile != null && vanilla.getResource(PackFiles.TYPE, parentFile) != null;
            if (!all && (row != null || vanillaParent)) continue;
            String line =
                    stem
                            + "  "
                            + (row == null ? "(no row)" : row)
                            + "  "
                            + (parent == null ? "(no parent)" : parent);
            if (all)
                line += "  " + String.join(", ", names.getOrDefault(stem, List.of("(none found)")));
            lines.add(line);
        }
        return lines;
    }

    /**
     * Which Wynntils ingredients each stem is the icon of: the stems among the models each
     * ingredient's icon stack draws. The definitions are parsed here, which is the expensive part;
     * the walks go to the render thread and are waited for, since a property may read the
     * keyboard there ({@link LiveItemModels}).
     */
    private static Map<String, List<String>> wynntilsNames(
            ResourceManager resources, Map<String, ItemStack> wynntilsIngredients) {
        Map<Identifier, Optional<ItemModel.Unbaked>> definitions = new HashMap<>();
        for (ItemStack icon : wynntilsIngredients.values()) {
            Identifier itemModel = icon.get(DataComponents.ITEM_MODEL);
            if (itemModel != null) {
                definitions.computeIfAbsent(
                        itemModel, id -> LiveItemModels.definition(resources, id));
            }
        }
        return McUtils.mc().submit(() -> stemNames(definitions, wynntilsIngredients)).join();
    }

    /** {@link #wynntilsNames}' walks, over definitions already parsed. Render thread. */
    private static Map<String, List<String>> stemNames(
            Map<Identifier, Optional<ItemModel.Unbaked>> definitions,
            Map<String, ItemStack> wynntilsIngredients) {
        Map<String, List<String>> names = new HashMap<>();
        wynntilsIngredients.forEach(
                (name, icon) -> {
                    Optional<ItemModel.Unbaked> definition =
                            definitions.getOrDefault(
                                    icon.get(DataComponents.ITEM_MODEL), Optional.empty());
                    if (definition.isEmpty()) return;
                    try {
                        for (String model :
                                LiveItemModels.models(definition.get(), icon, null, null)) {
                            String stem = ModelRules.ingredientStem(model);
                            if (stem != null) {
                                names.computeIfAbsent(stem, s -> new ArrayList<>()).add(name);
                            }
                        }
                    } catch (RuntimeException e) {
                        // An icon the walk cannot follow names no stem.
                    }
                });
        names.values().forEach(list -> list.sort(null));
        return names;
    }

    /** A stub as the report shows it: its rule and what it draws. */
    private static String stub(ModelStub stub) {
        return "(" + stub.rule() + ": " + stub.art() + ")";
    }
}
