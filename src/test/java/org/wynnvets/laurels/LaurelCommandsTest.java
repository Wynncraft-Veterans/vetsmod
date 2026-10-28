package org.wynnvets.laurels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/**
 * The pure parts of {@link LaurelCommands}: which steps {@code update} runs for the server's
 * {@code collect} list, how it chains them, the fields of the frames it sends, and the chat lines
 * an ack turns into.
 *
 * <p>The command tree, the submission to {@code DistributeRuns} and the network send are not
 * exercised. {@code runSteps} ends in {@code DistributeRuns.finished()}, which returns at once
 * when no run is active, so the chain is run on its own. An ack is built with {@code ack(json)},
 * and a lines list is read with {@code texts(lines)}; the colour of a line is checked where it
 * carries meaning.</p>
 */
class LaurelCommandsTest {

    private static final LaurelCommands.StepBody NO_OP = next -> {};
    private static final LaurelCommands.Step XP_STEP =
            new LaurelCommands.Step(LaurelMetric.XP, NO_OP);
    private static final LaurelCommands.Step STREAK_STEP =
            new LaurelCommands.Step(LaurelMetric.STREAK, NO_OP);

    private static LaurelCommands.Step recordingStep(
            List<String> ran, String name, boolean handsBack) {
        return new LaurelCommands.Step(
                LaurelMetric.STREAK,
                next -> {
                    ran.add(name);
                    if (handsBack) next.run();
                });
    }

    private static JsonObject ack(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static List<String> texts(List<Component> lines) {
        return lines.stream().map(Component::getString).toList();
    }

    private static void assertColour(ChatFormatting expected, Component line) {
        assertEquals(expected.getColor().intValue(), line.getStyle().getColor().getValue());
    }

    // ----- selectSteps -----

    @Test
    void beforeTheFirstFetchEveryStepRuns() {
        List<LaurelCommands.Step> steps = List.of(XP_STEP, STREAK_STEP);

        assertSame(steps, LaurelCommands.selectSteps(steps, null));
    }

    @Test
    void anEmptyCollectListSelectsNothing() {
        assertEquals(
                List.of(), LaurelCommands.selectSteps(List.of(XP_STEP, STREAK_STEP), Set.of()));
    }

    @Test
    void onlyTheStepsTheServerAsksForRunInTheirOwnOrder() {
        List<LaurelCommands.Step> steps = List.of(XP_STEP, STREAK_STEP);

        assertEquals(
                List.of(STREAK_STEP),
                LaurelCommands.selectSteps(steps, Set.of(LaurelMetric.STREAK)));
        assertEquals(
                List.of(XP_STEP, STREAK_STEP),
                LaurelCommands.selectSteps(
                        steps, Set.of(LaurelMetric.STREAK, LaurelMetric.XP, LaurelMetric.RETURN)));
    }

    // ----- runSteps -----

    @Test
    void stepsRunInOrderEachHandingBackToTheNext() {
        List<String> ran = new ArrayList<>();
        LaurelCommands.Step first = recordingStep(ran, "first", true);
        LaurelCommands.Step second = recordingStep(ran, "second", true);

        LaurelCommands.runSteps(List.of(first, second), 0);

        assertEquals(List.of("first", "second"), ran);
    }

    @Test
    void aStepThatNeverHandsBackStopsTheStepsAfterIt() {
        List<String> ran = new ArrayList<>();
        LaurelCommands.Step first = recordingStep(ran, "first", false);
        LaurelCommands.Step second = recordingStep(ran, "second", true);

        LaurelCommands.runSteps(List.of(first, second), 0);

        assertEquals(List.of("first"), ran);
    }

    // ----- frame fields -----

    @Test
    void aReturnFrameNamesThePlaceAndThePlayer() {
        assertEquals(
                "{\"metric\":\"return\",\"entries\":[{\"place\":2,\"name\":\"Steve\"}]}",
                LaurelCommands.returnFields(2, "Steve").toString());
    }

    @Test
    void aClearFrameCarriesOnlyTheMetricAndTheFlag() {
        assertEquals(
                "{\"metric\":\"return\",\"clear\":true}",
                LaurelCommands.returnClearFields().toString());
    }

    @Test
    void aStreakFrameCarriesTheEntriesItWasGiven() {
        assertEquals(
                "{\"metric\":\"streak\",\"entries\":[]}",
                LaurelCommands.streakFields(new JsonArray()).toString());
    }

    // ----- ackLines -----

    @Test
    void anAckWithAnUnknownOutcomeIsRedAndSaysItIsSafeToRunAgain() {
        List<Component> lines =
                LaurelCommands.ackLines(
                        ack("{\"outcome_unknown\":\"no reply\",\"status\":\"error\"}"),
                        okAck -> {
                            throw new AssertionError("an unknown outcome is not an ok ack");
                        });

        assertEquals(
                List.of("[laurels] Outcome unknown (no reply) - it is safe to run it again."),
                texts(lines));
        assertColour(ChatFormatting.RED, lines.get(0));
    }

    @Test
    void anErrorAckIsRedWithTheServersDetail() {
        List<Component> lines =
                LaurelCommands.ackLines(
                        ack("{\"status\":\"error\",\"detail\":\"laurels store write failed\"}"),
                        okAck -> {
                            throw new AssertionError("an error is not an ok ack");
                        });

        assertEquals(List.of("[laurels] failed: laurels store write failed"), texts(lines));
        assertColour(ChatFormatting.RED, lines.get(0));
    }

    @Test
    void anAckWithNoStatusIsAnUnknownError() {
        assertEquals(
                List.of("[laurels] failed: unknown error"),
                texts(LaurelCommands.ackLines(ack("{}"), okAck -> List.of())));
    }

    @Test
    void anOkAckIsLeftToTheCallersLines() {
        JsonObject okAck = ack("{\"status\":\"ok\",\"accepted\":1}");

        List<Component> lines =
                LaurelCommands.ackLines(
                        okAck,
                        received -> List.of(Component.literal("seen " + (received == okAck))));

        assertEquals(List.of("seen true"), texts(lines));
    }

    @Test
    void aPlacedPlayerIsGreen() {
        List<Component> lines =
                LaurelCommands.returnSetLines(
                        1, "Steve", ack("{\"status\":\"ok\",\"accepted\":1,\"unresolved\":[]}"));

        assertEquals(List.of("Steve is #1 on the return podium."), texts(lines));
        assertColour(ChatFormatting.GREEN, lines.get(0));
    }

    @Test
    void aNameTheServerCouldNotResolveIsRedNotFound() {
        List<Component> lines =
                LaurelCommands.returnSetLines(
                        3,
                        "Nobody",
                        ack("{\"status\":\"ok\",\"accepted\":0,\"unresolved\":[\"Nobody\"]}"));

        assertEquals(List.of("No player named Nobody was found."), texts(lines));
        assertColour(ChatFormatting.RED, lines.get(0));
    }

    @Test
    void aClearedPodiumIsGreen() {
        List<Component> lines = LaurelCommands.returnClearLines(ack("{\"status\":\"ok\"}"));

        assertEquals(List.of("Return podium cleared."), texts(lines));
        assertColour(ChatFormatting.GREEN, lines.get(0));
    }
}
