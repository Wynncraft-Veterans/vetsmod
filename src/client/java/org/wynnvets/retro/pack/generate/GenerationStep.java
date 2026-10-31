package org.wynnvets.retro.pack.generate;

/**
 * One kind of file the retro pack generates. A step holds no state of its own, so one instance
 * serves every generation; what a generation builds up lives in its {@link GenerationContext}.
 *
 * <p>A step walks its rows and wraps each in {@link PackOutput#attempt}, so one bad row is one line
 * in the report and the rest still run. Adding a step means one class and one entry in
 * {@link RetroPackGenerator#STEPS}, whose order is behaviour. A step writing into a new top-level
 * folder must also declare it in {@link org.wynnvets.retro.pack.RetroPackResources
 * RetroPackResources}, whose folders are fixed.
 */
interface GenerationStep {

    /**
     * Writes this step's files.
     *
     * @param gen the generation
     */
    void run(GenerationContext gen);
}
