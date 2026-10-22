package org.wynnvets.mwe.anni.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wynnvets.mwe.anni.state.AnniSnapshot;

/**
 * {@link AnniCommandRenderer#render}'s {@code null} contract, and goldens for the parts of the
 * render a test can reach: the mode-switch block, the prediction line, the countdown and the
 * hour formatters.
 *
 * <p>The contract: for an external user with an announced anni, {@code render} returns
 * {@code null}, not an empty list, so its caller falls back to the legacy stamp text. Each of
 * those tests forces the external override to {@code TRUE} through
 * {@link AnniCommandRenderer#setExternalOverride}, so {@code isExternal} never reaches
 * {@code GuildStateManager}, and the external arm returns before anything that reads
 * {@code VetsConfig}, which cannot initialise in a test. {@link #restoreOverride} puts the
 * default back for the classes that share this JVM. The override has no getter, and its one
 * reader, {@code isExternal}, reaches {@code VetsConfig} through {@code GuildStateManager} when
 * the override is {@code null}, so the restore is not asserted.</p>
 *
 * <p>Each golden is a {@code Component.toString()}, which spells out every segment's text,
 * colour, flags (an explicit {@code false} included), click and hover. That departs from
 * {@code HelpCommandsTest}'s walk over sibling text, because here the click and hover targets are
 * part of what the render produces. The string form is Minecraft 1.21.11's, so a Minecraft update
 * that changes {@code Component.toString()} will need the goldens regenerated.</p>
 *
 * <p>The prediction line reads the clock and formats the median in the JVM's default zone and
 * format locale, so its tests compute the expected date and time with the same patterns, around
 * the call. Nothing here changes the default locale or zone.</p>
 *
 * <p>Not covered here, because each reaches {@code VetsConfig}: the far-out and within-two-hours
 * bodies ({@code renderFarOut} builds its header link through {@code AnniHoverBuilder.linkBadge},
 * which asks whether hover details are on), and the not-announced body ({@code renderNotAnnounced}
 * reads whether to show the prediction, and its vets form's {@code header} also builds its link
 * through {@code linkBadge}). The bytecode proof and the in-game pass cover them.</p>
 */
class AnniCommandRendererTest {

    @AfterEach
    void restoreOverride() {
        AnniCommandRenderer.setExternalOverride(null);
    }

    private static AnniSnapshot snapshot(String json) {
        return AnniSnapshot.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    /** An announced anni {@code seconds} from now, and nothing else. */
    private static AnniSnapshot announcedIn(long seconds) {
        long stamp = Instant.now().getEpochSecond() + seconds;
        return AnniSnapshot.fromJson(
                JsonParser.parseString(
                                "{\"event\": {\"stamp_epoch\": "
                                        + stamp
                                        + ", \"announced\": true}}")
                        .getAsJsonObject());
    }

    @Test
    void render_returnsNullForAnExternalUserWithAnAnniMoreThanTwoHoursOut() {
        AnniCommandRenderer.setExternalOverride(Boolean.TRUE);

        assertNull(AnniCommandRenderer.render(announcedIn(8L * 3600L)));
    }

    @Test
    void render_returnsNullForAnExternalUserWithAnAnniWithinTwoHours() {
        AnniCommandRenderer.setExternalOverride(Boolean.TRUE);

        assertNull(AnniCommandRenderer.render(announcedIn(600L)));
    }

    // ── the mode-switch block ─────────────────────────────────────────

    @Test
    void modeSwitchBlock_offersTheThreeModes() {
        assertEquals(
                "literal{Change Anni Mode?}[style={color=yellow,bold}, "
                        + "siblings=[literal{ (Click:)}[style={color=gray,!bold}], literal{\n}, "
                        + "empty[siblings=[literal{[}[style={color=gray,!bold}, "
                        + "siblings=[literal{Silent}[style={color=white,!bold,"
                        + "clickEvent=RunCommand[command=/wv anni silent],"
                        + "hoverEvent=ShowText[value=literal{Anni features "
                        + "off}[style={color=gray}]]}], literal{]}[style={color=gray,!bold}]]], "
                        + "literal{ | }[style={color=dark_gray}], "
                        + "literal{[}[style={color=dark_green,!bold}, "
                        + "siblings=[literal{Passive}[style={color=green,!bold,"
                        + "clickEvent=RunCommand[command=/wv anni passive],"
                        + "hoverEvent=ShowText[value=literal{Anni features recommended for most "
                        + "folks experienced at attending vets-annis}[style={color=gray}]]}], "
                        + "literal{]}[style={color=dark_green,!bold}]]], "
                        + "literal{ | }[style={color=dark_gray}], "
                        + "literal{[}[style={color=dark_red,!bold}, "
                        + "siblings=[literal{Aggressive}[style={color=red,!bold,"
                        + "clickEvent=RunCommand[command=/wv anni aggressive],"
                        + "hoverEvent=ShowText[value=literal{Anni features recommended for folks "
                        + "attending their first vets-anni}[style={color=gray}]]}], "
                        + "literal{]}[style={color=dark_red,!bold}]]]]]]]",
                AnniCommandRenderer.renderModeSwitchBlock().toString());
    }

    // ── the prediction line ───────────────────────────────────────────

    private static AnniSnapshot.Prediction prediction(long medianEpoch, double windowHours) {
        return snapshot(
                        "{\"event\": {\"prediction\": {\"median_epoch\": "
                                + medianEpoch
                                + ", \"window_hours\": "
                                + windowHours
                                + "}}}")
                .event()
                .prediction();
    }

    private static String dateOf(long epoch) {
        return DateTimeFormatter.ofPattern("MMM d")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(epoch));
    }

    private static String timeOf(long epoch) {
        return DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(epoch));
    }

    /** The line as rendered for a median 72 h out, with its date and time left as tokens. */
    private static final String PREDICTION_72H =
            "literal{Prediction: }[style={color=gold}, "
                    + "siblings=[literal{72h}[style={color=yellow}], "
                    + "literal{ from now }[style={color=gold}], "
                    + "literal{|}[style={color=white,bold}], "
                    + "literal{ {DATE} }[style={color=yellow}], "
                    + "literal{@{TIME}}[style={color=gold}], "
                    + "literal{±~3h}[style={color=gray,italic}]]]";

    @Test
    void predictionLine_saysHowFarOffTheMedianIsAndWhen() {
        long before = Instant.now().getEpochSecond();
        long median = before + 72L * 3600L;

        String line = AnniCommandRenderer.predictionLine(prediction(median, 10.6)).toString();

        // "72h" holds while the clock moves less than half an hour during the call
        assumeTrue(Math.abs(Instant.now().getEpochSecond() - before) < 1800L);
        assertEquals(
                PREDICTION_72H.replace("{DATE}", dateOf(median)).replace("{TIME}", timeOf(median)),
                line);
    }

    @Test
    void predictionLine_saysOverdueOnceTheMedianHasPassed() {
        long before = Instant.now().getEpochSecond();
        long median = before - 3L * 3600L;

        String line = AnniCommandRenderer.predictionLine(prediction(median, 10.6)).getString();

        // "3h" holds while the clock moves less than half an hour during the call
        assumeTrue(Math.abs(Instant.now().getEpochSecond() - before) < 1800L);
        assertEquals(
                "Prediction: 3h overdue | " + dateOf(median) + " @" + timeOf(median) + "±~3h",
                line);
    }

    // ── the countdown ─────────────────────────────────────────────────

    private static MutableComponent countdown(long seconds) {
        MutableComponent root = Component.literal("");
        AnniCommandRenderer.appendCountdown(root, seconds);
        return root;
    }

    @Test
    void countdown_picksItsResolutionByMagnitude() {
        assertEquals(
                "empty[siblings=[literal{59}[style={color=aqua}], "
                        + "literal{s}[style={color=dark_aqua}]]]",
                countdown(59L).toString());
        assertEquals(
                "empty[siblings=[literal{1}[style={color=aqua}], "
                        + "literal{m }[style={color=dark_aqua}], literal{00}[style={color=aqua}], "
                        + "literal{s}[style={color=dark_aqua}]]]",
                countdown(60L).toString());
        assertEquals(
                "empty[siblings=[literal{1}[style={color=aqua}], "
                        + "literal{h }[style={color=dark_aqua}], literal{0}[style={color=aqua}], "
                        + "literal{m}[style={color=dark_aqua}]]]",
                countdown(3600L).toString());
    }

    @Test
    void countdown_atEachBoundary() {
        assertEquals("0s", countdown(-5L).getString());
        assertEquals("0s", countdown(0L).getString());
        assertEquals("59s", countdown(59L).getString());
        assertEquals("1m 00s", countdown(60L).getString());
        assertEquals("1m 01s", countdown(61L).getString());
        assertEquals("59m 59s", countdown(3599L).getString());
        assertEquals("1h 0m", countdown(3600L).getString());
        assertEquals("2h 1m", countdown(7261L).getString());
    }

    // ── the hour formatters ───────────────────────────────────────────

    @Test
    void coarseHours_atEachBoundary() {
        assertEquals("<1m", AnniCommandRenderer.formatCoarseHours(-5L));
        assertEquals("<1m", AnniCommandRenderer.formatCoarseHours(0L));
        assertEquals("1m", AnniCommandRenderer.formatCoarseHours(1L));
        assertEquals("1m", AnniCommandRenderer.formatCoarseHours(119L));
        assertEquals("2m", AnniCommandRenderer.formatCoarseHours(120L));
        assertEquals("59m", AnniCommandRenderer.formatCoarseHours(3599L));
        assertEquals("1h", AnniCommandRenderer.formatCoarseHours(3600L));
        assertEquals("1h", AnniCommandRenderer.formatCoarseHours(5399L));
        assertEquals("2h", AnniCommandRenderer.formatCoarseHours(5400L));
        assertEquals("60664h", AnniCommandRenderer.formatCoarseHours(60664L * 3600L));
    }

    @Test
    void uncertainty_atEachBoundary() {
        assertEquals("0h", AnniCommandRenderer.formatUncertainty(-1.0));
        assertEquals("0h", AnniCommandRenderer.formatUncertainty(0.0));
        assertEquals("1min", AnniCommandRenderer.formatUncertainty(0.001));
        assertEquals("29min", AnniCommandRenderer.formatUncertainty(0.49));
        assertEquals("1h", AnniCommandRenderer.formatUncertainty(0.5));
        assertEquals("1h", AnniCommandRenderer.formatUncertainty(1.49));
        assertEquals("2h", AnniCommandRenderer.formatUncertainty(1.5));
        assertEquals("3h", AnniCommandRenderer.formatUncertainty(3.06));
    }
}
