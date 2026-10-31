package org.wynnvets.debug.retro;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.RetroReport;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.manifest.TextureSections;
import org.wynnvets.retro.manifest.TintFactor;
import org.wynnvets.retro.pack.RetroPackInjector;
import org.wynnvets.retro.rules.ModelRules;
import org.wynnvets.retro.rules.RuleUsage;
import org.wynnvets.retro.rules.TextureDecision;

/**
 * The retro report behind {@code /wv debug tree retro report}: what the last generation did, in
 * full, and what no generation measures because only someone editing the rule files needs it.
 * It starts from that generation's {@link RetroReport} and reads the packs loaded now again only
 * for what the report says about them, so a generation pays nothing for it.
 *
 * <p>The report is plain text, a section each:</p>
 * <ul>
 *   <li>the summary, the parts the generation left out, the counts, and the rules it skipped;</li>
 *   <li>every pattern row of the rule files with how many ids it decided
 *       ({@link RuleUsage#of}), the rows that decided none first;</li>
 *   <li>the ingredient stems without a row whose model parents no vanilla model, the ones that
 *       need a look; asked for {@code ingredients}, every stem instead, with its row, its parent
 *       and the Wynntils ingredients whose icon it is;</li>
 *   <li>the models whose chain of parents reaches a stubbed model ({@link AuditModels#derived}),
 *       and among them those that draw the stub, with no art of their own on the way
 *       ({@link AuditModels#drawnThroughParent});</li>
 *   <li>each {@code tints} row, with the factors it multiplied each of its textures by;</li>
 *   <li>the equipment assets Wynncraft ships that no {@code equipment} row covers;</li>
 *   <li>the block textures Wynncraft ships that {@code same_name} replaced while Wynncraft's art
 *       barely resembles vanilla's: their brightness correlation
 *       ({@link TextureDivergence#brightnessCorrelation}) below
 *       {@value TextureDivergence#DIVERGENT}, for a {@code keep_wynncraft} row where Wynncraft
 *       repurposed the texture. Vanilla's is the art Wynncraft repainted, so a repaint keeps its
 *       shapes and scores high; the 2013 art of most blocks is another picture altogether, so
 *       measuring against it would list nearly every block. A texture vanilla lacks is measured
 *       against the copy the retro pack drew.</li>
 * </ul>
 *
 * <p>It reads Wynncraft's item models (about four thousand small files) and decodes two images
 * per texture compared (several hundred), and with {@code ingredients} parses the item
 * definitions behind the Wynntils icons ({@code items/potion.json}, about 1.5 MB), so the caller
 * runs it off the render thread; only the walks of those icons go to the render thread, which it
 * waits for. It reads the Wynncraft pack and Minecraft's vanilla pack as they are loaded now; a
 * resource reload in the middle can close them under it, and that run fails.</p>
 */
public final class RetroAudit {

    private RetroAudit() {}

    /**
     * A finished report.
     *
     * @param text the report itself
     * @param summary its counts on one line, for chat, such as {@code unused_rows=1
     *     ingredient_gaps=0 derived_models=5 drawn_through_parent=2 equipment_without_row=0
     *     divergent_textures=3}, ending {@code parts_off=<names>} when a part was off
     */
    public record Result(String text, String summary) {}

    /**
     * Builds the report. Off the render thread.
     *
     * @param report the generation it is about: the retro pack drawn now
     * @param resources the resources loaded now, the retro pack among them
     * @param vanilla Minecraft's vanilla pack
     * @param wynntilsIngredients Wynntils' ingredients, each name with the stack of its icon; read
     *     only with {@code allIngredients}
     * @param allIngredients whether to list every ingredient stem rather than the ones that need
     *     a look
     * @return the report
     * @throws IOException when the Wynncraft pack the generation read is no longer loaded
     */
    public static Result run(
            RetroReport report,
            ResourceManager resources,
            PackResources vanilla,
            Map<String, ItemStack> wynntilsIngredients,
            boolean allIngredients)
            throws IOException {
        List<PackResources> packs = resources.listPacks().toList();
        PackResources wynncraft =
                packs.stream()
                        .filter(pack -> pack.packId().equals(report.wynncraftPack()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IOException(
                                                report.wynncraftPack() + " is not loaded now"));
        PackResources retro =
                packs.stream().filter(RetroPackInjector::isRetroPack).findFirst().orElse(null);
        RetroManifest manifest = RetroModeManager.manifest();
        ModelRules rules = new ModelRules(manifest.models());
        RetroReport.Seen seen = report.seen();
        Map<String, AuditModels.Parented> parents = AuditModels.parents(wynncraft);

        Text out = new Text();
        out.line("Retro report, " + LocalDateTime.now().withNano(0));
        out.line(
                "Built over "
                        + report.wynncraftPack()
                        + " from "
                        + String.join(
                                ", ", report.sources().stream().map(RetroSource::yamlName).toList())
                        + " in "
                        + report.millis()
                        + " ms.");
        out.line(report.summary());
        if (!report.partsOff().isEmpty()) {
            out.line(
                    "Parts off: "
                            + report.partsOffNames()
                            + ". Their rows still count below, but their models have no stubs and"
                            + " their textures stay Wynncraft's, so low counts are expected.");
        }

        List<String> counts = new ArrayList<>();
        report.counts().forEach((count, n) -> counts.add(name(count) + " " + n));
        out.section("Counts", counts);
        out.section("Skipped", report.skipped());

        RuleUsage usage =
                RuleUsage.of(
                        manifest.textures(),
                        manifest.models(),
                        seen.textures().keySet(),
                        seen.models(),
                        seen.equipment());
        List<String> unused = new ArrayList<>();
        List<String> used = new ArrayList<>();
        for (RuleUsage.RowUse row : usage.rows()) {
            String line = row.section() + ": '" + row.pattern() + "'";
            if (row.ids() == 0) {
                unused.add(line);
            } else {
                used.add(line + "  " + row.ids());
            }
        }
        out.section(
                "Rows that decided nothing",
                "The image_edits, wynncraft_copies, wynntils_textures and ingredient rows that"
                        + " found nothing are among the skipped lines; the screen sections are"
                        + " read while drawing, and are not checked.",
                unused);
        out.section("Rows and how many ids each decided", used);

        List<String> ingredients =
                AuditModels.ingredients(
                        manifest,
                        seen,
                        parents,
                        vanilla,
                        resources,
                        wynntilsIngredients,
                        allIngredients);
        if (allIngredients) {
            out.section("Ingredient stems", "stem  row  parent  Wynntils ingredients", ingredients);
        } else {
            out.section(
                    "Ingredient stems with no row whose model parents no vanilla model",
                    usage.stemsWithoutRow().size()
                            + " stems have no row; those whose model parents a vanilla model"
                            + " already draw it in 2013 art and are left out.",
                    ingredients);
        }

        List<String> derived = AuditModels.derived(rules, parents, seen);
        out.section("Models that parent a stubbed model", derived);
        List<String> drawnThrough = AuditModels.drawnThroughParent(rules, parents, seen);
        out.section(
                "Models that draw a stub through their parent",
                "No rule of their own and no art of their own, so they show the stub's art and the"
                        + " item dump says they have no rule. Each wants a row, or is fine as it"
                        + " is.",
                drawnThrough);
        out.section("Tints", tints(manifest, seen));
        out.section("Equipment assets with no row", usage.equipmentWithoutRow());

        TextureDivergence.Found divergence =
                TextureDivergence.divergent(seen, wynncraft, vanilla, retro);
        out.section(
                "Block textures same_name replaced whose Wynncraft art differs from vanilla's",
                String.format(
                        Locale.ROOT,
                        "Brightness correlation below %.2f, lowest first, of %d compared"
                                + " (%d could not be read).",
                        TextureDivergence.DIVERGENT,
                        divergence.compared(),
                        divergence.unreadable()),
                divergence.lines());

        String summary =
                "unused_rows="
                        + unused.size()
                        + (allIngredients ? " stems_without_row=" : " ingredient_gaps=")
                        + (allIngredients ? usage.stemsWithoutRow().size() : ingredients.size())
                        + " derived_models="
                        + derived.size()
                        + " drawn_through_parent="
                        + drawnThrough.size()
                        + " equipment_without_row="
                        + usage.equipmentWithoutRow().size()
                        + " divergent_textures="
                        + divergence.lines().size()
                        + (report.partsOff().isEmpty()
                                ? ""
                                : " parts_off=" + report.partsOffNames());
        return new Result(out.toString(), summary);
    }

    // ── Tints ───────────────────────────────────────────────────────────

    /**
     * Each {@code tints} row with the textures it tinted: the source each was read from and the
     * factors it was multiplied by.
     */
    private static List<String> tints(RetroManifest manifest, RetroReport.Seen seen) {
        Map<TextureSections.Tint, List<String>> byRow = new LinkedHashMap<>();
        for (TextureSections.Tint row : manifest.textures().tints())
            byRow.put(row, new ArrayList<>());
        for (Map.Entry<String, TextureDecision> texture : seen.textures().entrySet()) {
            if (!(texture.getValue() instanceof TextureDecision.Tint tint)) continue;
            String id = texture.getKey();
            List<Double> factors = seen.tintFactors().get(id);
            String line =
                    "    "
                            + id
                            + " from "
                            + tint.from()
                            + " ("
                            + tint.source().yamlName()
                            + "): "
                            + (factors == null
                                    ? "not written, see the skipped lines"
                                    : String.format(
                                            Locale.ROOT,
                                            "x %.2f %.2f %.2f",
                                            factors.get(0),
                                            factors.get(1),
                                            factors.get(2)));
            for (TextureSections.Tint row : manifest.textures().tints()) {
                if (row.textures().matcher(id).matches()) {
                    byRow.get(row).add(line);
                    break;
                }
            }
        }
        List<String> lines = new ArrayList<>();
        byRow.forEach(
                (row, textures) -> {
                    lines.add(
                            "'"
                                    + row.textures().pattern()
                                    + "'  "
                                    + (row.source() == null ? "" : "source " + row.source() + "  ")
                                    + "tint "
                                    + factor(row.factor())
                                    + "  "
                                    + (row.strength() == 1
                                            ? ""
                                            : String.format(
                                                    Locale.ROOT, "strength %.2f  ", row.strength()))
                                    + (row.contrast() == 1
                                            ? ""
                                            : String.format(
                                                    Locale.ROOT, "contrast %.2f  ", row.contrast()))
                                    + textures.size()
                                    + " tinted");
                    lines.addAll(textures);
                });
        return lines;
    }

    /** A tint as the rule files write it; {@code none} for a row without one. */
    private static String factor(TintFactor factor) {
        return switch (factor) {
            case TintFactor.Colour colour -> String.format(Locale.ROOT, "'#%06X'", colour.rgb());
            case TintFactor.Match match -> "{match: '" + match.texture() + "'}";
            case TintFactor.RelativeTo relative -> "{relative_to: '" + relative.texture() + "'}";
            case TintFactor.Auto auto -> TintFactor.AUTO;
            case TintFactor.Unchanged unchanged -> "none";
            case TintFactor.Like like -> "{like: '" + like.texture() + "'}";
        };
    }

    // ── Text ────────────────────────────────────────────────────────────

    /** A count as the summary names it. */
    private static String name(Count count) {
        return count.name().toLowerCase(Locale.ROOT);
    }

    /** The report's text, built a line and a section at a time. */
    private static final class Text {
        private final StringBuilder text = new StringBuilder();

        void line(String line) {
            text.append(line).append('\n');
        }

        void section(String title, Collection<String> lines) {
            section(title, null, lines);
        }

        /** A blank line, the title with its line count, an optional note, then the lines. */
        void section(String title, String note, Collection<String> lines) {
            text.append('\n').append("## ").append(title).append(" (").append(lines.size());
            text.append(")\n");
            if (note != null) line(note);
            lines.forEach(this::line);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
