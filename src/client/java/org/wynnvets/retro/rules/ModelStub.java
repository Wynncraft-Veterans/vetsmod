package org.wynnvets.retro.rules;

import org.wynnvets.retro.manifest.ParentKind;

/**
 * The stub that replaces one of Wynncraft's or Avomod's item models, by the rule it comes from;
 * {@link ModelRules#modelStub} decides it.
 */
public sealed interface ModelStub {

    /**
     * The rule the stub comes from: its section of the rule files, and for an {@code item_models}
     * stub its kind.
     *
     * @return {@code ingredients}, {@code potions}, {@code item_models (tint-proof)} or
     *     {@code item_models (model)}
     */
    String rule();

    /**
     * What the stub draws: the texture of a tint-proof stub, a potion's tinted liquid, or the model
     * another kind of stub parents.
     *
     * @return the texture or model id
     */
    String art();

    /**
     * An {@code item_models} row with a texture: a stub that draws it on a tint-proof parent, so
     * the stack's colour does not tint it,
     * {@code {"parent": <parentModel()>, "textures": {"art": <art>}}}.
     *
     * @param art the texture id the stub draws
     * @param parent how the stub is held
     * @param atlas the atlas {@code art} lives in
     */
    record TintProof(String art, ParentKind parent, Atlas atlas) implements ModelStub {
        @Override
        public String rule() {
            return "item_models (tint-proof)";
        }

        /**
         * The vetsmod model the stub parents.
         *
         * @return {@link RetroAssetIds#tintProofParent} of its kind and atlas
         */
        public String parentModel() {
            return RetroAssetIds.tintProofParent(parent, atlas);
        }

        /**
         * The fully transparent texture {@link #parentModel()} puts on the tint layer.
         *
         * @return {@link RetroAssetIds#blank} of its atlas
         */
        public String blank() {
            return RetroAssetIds.blank(atlas);
        }
    }

    /**
     * An {@code item_models} row with a model: a stub that only names it as its parent,
     * {@code {"parent": <model>}}, as an ingredient's does. The stack's colour tints whatever that
     * model's tint layer is. A vanilla item model draws from one atlas, and so does each of
     * vetsmod's own under {@code vetsmod:item/retro/} (the gear boxes' chest), so the stub does
     * too.
     *
     * @param model the model id the stub parents
     */
    record ItemParent(String model) implements ModelStub {
        @Override
        public String rule() {
            return "item_models (model)";
        }

        @Override
        public String art() {
            return model;
        }
    }

    /**
     * An ingredient's row in {@code retro_ingredients.yml}: a stub that only names {@code parent}
     * as its parent.
     *
     * @param stem the ingredient's model stem
     * @param parent the vanilla item model the stub parents
     */
    record Ingredient(String stem, String parent) implements ModelStub {
        @Override
        public String rule() {
            return "ingredients";
        }

        @Override
        public String art() {
            return parent;
        }
    }

    /**
     * A {@code potions} row: the vanilla potion with its liquid pre-tinted,
     * {@code {"parent": <TINTED_POTION>, "textures": {"liquid": <liquid's id>}}}. It draws from the
     * items atlas only, and no tint reaches its art.
     *
     * @param liquid the texture id of the potion's liquid, a {@code source_copies} copy in its
     *     family's colour
     */
    record Potion(String liquid) implements ModelStub {
        @Override
        public String rule() {
            return "potions";
        }

        @Override
        public String art() {
            return liquid;
        }
    }
}
