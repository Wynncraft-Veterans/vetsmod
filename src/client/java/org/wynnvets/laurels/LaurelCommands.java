package org.wynnvets.laurels;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.wynntils.utils.mc.McUtils;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.commands.CommandUsage;
import org.wynnvets.distribute.DistributeRuns;
import org.wynnvets.fetcher.polling.LaurelPoller;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.Json;

/**
 * Builds the {@code /wv laurels} client command, which feeds the standings the server cannot
 * compute: {@code update} collects what only a game client can see, and {@code return} sets the
 * weekly-return podium by hand. See {@code vetsmod_laurels.md}.
 *
 * <p>Both subcommands send a {@code laurels_set} staff-action frame, which the server refuses
 * from a session it does not treat as staff, so the node requires
 * {@link GuildStateManager#isConfirmedStaff()} and every handler re-checks it, the way
 * {@code /wv check} does. {@code update} also needs {@link GuildStateManager#isReturners()}:
 * it reads the Members menu of whatever guild the player is in, and the server would store that
 * guild's streaks as Returners'.</p>
 *
 * <p>{@code update} is a {@link DistributeRuns run}, because it drives the same Members menu as
 * {@code /wv distribute} and must not overlap one. It runs the {@link #STEPS} the server's
 * {@code collect} list asks for, one after another, and the run ends when the last one hands
 * back to {@link #runSteps}. A step that loses its menu or stalls ends the run through
 * {@link DistributeRuns#stop} instead, and the steps after it never start. {@code return} is a
 * single frame and takes no part in a run.</p>
 */
public final class LaurelCommands {

    /** The staff-action frame type both subcommands send. */
    private static final String FRAME_TYPE = "laurels_set";

    /** What a run of {@code update} is called in {@link DistributeRuns}' chat lines. */
    private static final String RUN_NOUN = "laurels update";

    private static final String USAGE = "/wv laurels <update|return>";

    private static final String USAGE_RETURN =
            "/wv laurels return <1|2|3> <playerName>, or /wv laurels return clear";

    /** How an in-game collection step runs. */
    // Package-private for unit tests. See LaurelCommandsTest.
    @FunctionalInterface
    interface StepBody {
        /** Runs the step, and calls {@code next} when it ends normally. */
        void run(Runnable next);
    }

    /** One in-game collection step: the metric it feeds, and how it runs. */
    // Package-private for unit tests. See LaurelCommandsTest.
    record Step(LaurelMetric metric, StepBody body) {}

    /**
     * The steps {@code update} can run, in order. Temporary: the streak crawl stands in for the
     * Wynncraft API's {@code weekly.streak}, which is empty. When it is retired this list is
     * empty and {@code update} goes with it (see {@code vetsmod_laurels.md}).
     */
    private static final List<Step> STEPS =
            List.of(new Step(LaurelMetric.STREAK, StreakCrawl::run));

    private LaurelCommands() {}

    /**
     * Builds the {@code laurels} literal. Each node short of a finished command prints a red
     * usage line when typed bare ({@link CommandUsage}), so it stays on the client instead of
     * going to the server.
     */
    public static LiteralArgumentBuilder<FabricClientCommandSource> buildCommandTree() {
        LiteralArgumentBuilder<FabricClientCommandSource> returnNode =
                ClientCommandManager.literal("return")
                        .executes(CommandUsage.usage(USAGE_RETURN))
                        .then(
                                ClientCommandManager.literal("clear")
                                        .executes(LaurelCommands::clear));
        for (int place = 1; place <= Laurel.PLACES; place++) {
            returnNode.then(placeLeaf(place));
        }
        return ClientCommandManager.literal("laurels")
                .requires(src -> GuildStateManager.isConfirmedStaff())
                .executes(CommandUsage.usage(USAGE))
                .then(ClientCommandManager.literal("update").executes(LaurelCommands::update))
                .then(returnNode);
    }

    /** Builds the {@code <place> <playerName>} branch under {@code return}. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> placeLeaf(int place) {
        return ClientCommandManager.literal(Integer.toString(place))
                .executes(CommandUsage.usage("/wv laurels return " + place + " <playerName>"))
                .then(
                        ClientCommandManager.argument("playerName", StringArgumentType.string())
                                .executes(ctx -> setPlace(ctx, place)));
    }

    // ── Handlers ─────────────────────────────────────────────────────────

    private static int update(CommandContext<FabricClientCommandSource> ctx) {
        if (!ensureStaff()) return 0;
        if (!GuildStateManager.isReturners()) {
            ChatUtils.sendLocalMessage(
                    line(
                            ChatFormatting.RED,
                            "/wv laurels update is restricted to the Returners guild."));
            return 0;
        }
        LaurelStandings standings = LaurelPoller.standings();
        Set<LaurelMetric> collect = standings == null ? null : standings.collect();
        List<Step> steps = selectSteps(STEPS, collect);
        if (steps.isEmpty()) {
            VetsLogger.debug("laurels update: no step for the server's collect list {}", collect);
            ChatUtils.sendLocalMessage(
                    line(ChatFormatting.GRAY, "Nothing for this client to collect in-game."));
            return 1;
        }
        DistributeRuns.submit(RUN_NOUN, () -> runSteps(steps, 0));
        return 1;
    }

    private static int setPlace(CommandContext<FabricClientCommandSource> ctx, int place) {
        if (!ensureStaff()) return 0;
        String playerName = StringArgumentType.getString(ctx, "playerName");
        upload(returnFields(place, playerName), ack -> returnSetLines(place, playerName, ack));
        return 1;
    }

    private static int clear(CommandContext<FabricClientCommandSource> ctx) {
        if (!ensureStaff()) return 0;
        upload(returnClearFields(), LaurelCommands::returnClearLines);
        return 1;
    }

    /**
     * The staff gate itself, checked at executor time like {@code /wv check}'s: the node's
     * {@code .requires} uses the same predicate, so this red line fires only if the state
     * changes between parse and execute.
     */
    private static boolean ensureStaff() {
        if (GuildStateManager.isConfirmedStaff()) return true;
        ChatUtils.sendLocalMessage(
                line(ChatFormatting.RED, "/wv laurels is restricted to authenticated stewards."));
        return false;
    }

    // ── Steps ────────────────────────────────────────────────────────────

    /**
     * The steps whose metric the server still wants collected.
     *
     * @param steps the steps this client has, in run order
     * @param collect the server's {@code collect} list, or {@code null} before the first fetch,
     *     when every step is kept
     * @return the kept steps, in their original order
     */
    // Package-private for unit tests. See LaurelCommandsTest.
    static List<Step> selectSteps(List<Step> steps, Set<LaurelMetric> collect) {
        if (collect == null) return steps;
        return steps.stream().filter(step -> collect.contains(step.metric())).toList();
    }

    /**
     * Runs {@code steps} from {@code index}, each one handing back to the next, and ends the
     * run with {@link DistributeRuns#finished()} after the last.
     */
    private static void runSteps(List<Step> steps, int index) {
        if (index == steps.size()) {
            DistributeRuns.finished();
            return;
        }
        steps.get(index).body().run(() -> runSteps(steps, index + 1));
    }

    // ── Frames ───────────────────────────────────────────────────────────

    /** The fields of a {@code return} frame that puts {@code playerName} on {@code place}. */
    // Package-private for unit tests. See LaurelCommandsTest.
    static JsonObject returnFields(int place, String playerName) {
        JsonObject entry = new JsonObject();
        entry.addProperty("place", place);
        entry.addProperty("name", playerName);
        JsonArray entries = new JsonArray();
        entries.add(entry);
        JsonObject fields = fields(LaurelMetric.RETURN);
        fields.add("entries", entries);
        return fields;
    }

    /** The fields of a {@code return} frame that empties the podium. */
    // Package-private for unit tests. See LaurelCommandsTest.
    static JsonObject returnClearFields() {
        JsonObject fields = fields(LaurelMetric.RETURN);
        fields.addProperty("clear", true);
        return fields;
    }

    /** The fields of a {@code streak} frame carrying {@code entries}. */
    // Package-private for unit tests. See LaurelCommandsTest.
    static JsonObject streakFields(JsonArray entries) {
        JsonObject fields = fields(LaurelMetric.STREAK);
        fields.add("entries", entries);
        return fields;
    }

    private static JsonObject fields(LaurelMetric metric) {
        JsonObject fields = new JsonObject();
        fields.addProperty("metric", metric.wireName());
        return fields;
    }

    /**
     * Sends a {@code laurels_set} frame and prints its ack. The ack arrives off the client
     * thread, so the printing is handed to it. The default commit wait applies (45 s): the
     * server may resolve a name against Mojang before it writes.
     *
     * @param fields the frame's fields, without {@code type}
     * @param okLines the lines for an ack with {@code status: ok}
     */
    static void upload(JsonObject fields, Function<JsonObject, List<Component>> okLines) {
        V1ApiManager.sendStaffActionFrame(
                FRAME_TYPE,
                fields,
                ack ->
                        McUtils.mc()
                                .execute(
                                        () ->
                                                ackLines(ack, okLines)
                                                        .forEach(ChatUtils::sendLocalMessage)));
    }

    // ── Ack lines ────────────────────────────────────────────────────────

    /**
     * The chat lines for a {@code laurels_set} ack: red for an outcome that is unknown (the
     * socket was lost, or no reply came in time) and for an error, and {@code okLines} otherwise.
     * Both writes replace what they set, so the unknown outcome is safe to run again.
     */
    // Package-private for unit tests. See LaurelCommandsTest.
    static List<Component> ackLines(JsonObject ack, Function<JsonObject, List<Component>> okLines) {
        String unknownCause = Json.stringOrNull(ack, "outcome_unknown");
        if (unknownCause != null) {
            return List.of(
                    line(
                            ChatFormatting.RED,
                            "[laurels] Outcome unknown ("
                                    + unknownCause
                                    + ") - it is safe to run it again."));
        }
        if (!"ok".equals(Json.optString(ack, "status", "error"))) {
            return List.of(
                    line(
                            ChatFormatting.RED,
                            "[laurels] failed: " + Json.optString(ack, "detail", "unknown error")));
        }
        return okLines.apply(ack);
    }

    /**
     * The lines for an ok ack to {@link #returnFields}. The server answers an unknown name with
     * {@code accepted: 0}, not an error.
     */
    // Package-private for unit tests. See LaurelCommandsTest.
    static List<Component> returnSetLines(int place, String playerName, JsonObject ack) {
        if (Json.optInt(ack, "accepted", 0) == 0) {
            return List.of(
                    line(ChatFormatting.RED, "No player named " + playerName + " was found."));
        }
        return List.of(
                line(
                        ChatFormatting.GREEN,
                        playerName + " is #" + place + " on the return podium."));
    }

    /**
     * The lines for an ok ack to {@link #returnClearFields()}.
     *
     * @param ack unused: a clear has nothing to report
     */
    // Package-private for unit tests. See LaurelCommandsTest.
    static List<Component> returnClearLines(JsonObject ack) {
        return List.of(line(ChatFormatting.GREEN, "Return podium cleared."));
    }

    /** A chat line in one colour. */
    // Package-private for StreakCrawl, which prints its own lines.
    static Component line(ChatFormatting colour, String text) {
        return Component.literal(text).withStyle(colour);
    }
}
