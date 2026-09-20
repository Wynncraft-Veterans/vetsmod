package org.wynnvets.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.Test;

/**
 * Golden pins for the twelve {@code /wv help} pages.
 *
 * <p>These exist to make the consolidation that follows reviewable. Each page is
 * pinned as a <em>tree</em> — sibling count, each sibling's exact string, each
 * sibling's colour and bold flag — not as rendered text. Two adjacent siblings
 * sharing a style render identically to one merged sibling but are a different
 * component, so a {@code getString()} comparison would wave through exactly the
 * collapse mistakes these tests are here to catch.</p>
 *
 * <p>The trailing newlines in the expectations below are data, not decoration:
 * they are what separates one row from the next, and two pages deliberately end
 * without one.</p>
 *
 * <p>{@link HelpCommands} declares no static fields, so nothing loads at
 * class-init and the build halves can be called directly. Those halves take
 * their gates as parameters, so nothing here reaches {@code GuildStateManager},
 * which would fall through to Wynntils — absent at test runtime.</p>
 */
class HelpCommandsTest {

    /** One expected sibling: its colour, whether it is bold, and its exact text. */
    private record Sibling(ChatFormatting colour, boolean bold, String text) {}

    private static Sibling sib(ChatFormatting colour, String text) {
        return new Sibling(colour, false, text);
    }

    private static Sibling boldSib(ChatFormatting colour, String text) {
        return new Sibling(colour, true, text);
    }

    private static void assertPage(MutableComponent page, Sibling... expected) {
        List<Component> actual = page.getSiblings();
        assertEquals(
                expected.length,
                actual.size(),
                "sibling count — a mismatch means appends were merged or split, which"
                        + " changes the tree even when the rendered text is unchanged");
        for (int i = 0; i < expected.length; i++) {
            Component sibling = actual.get(i);
            assertEquals(expected[i].text(), sibling.getString(), "sibling " + i + " text");
            assertEquals(
                    expected[i].colour().getColor().intValue(),
                    sibling.getStyle().getColor().getValue(),
                    "sibling " + i + " colour");
            assertEquals(expected[i].bold(), sibling.getStyle().isBold(), "sibling " + i + " bold");
        }
    }

    /** The command names on an index page — every YELLOW sibling, in order. */
    private static List<String> rowsOf(MutableComponent page) {
        List<String> rows = new ArrayList<>();
        for (Component sibling : page.getSiblings()) {
            if (sibling.getStyle().getColor().getValue()
                    == ChatFormatting.YELLOW.getColor().intValue()) {
                rows.add(sibling.getString());
            }
        }
        return rows;
    }

    /**
     * The rows bare {@code /wv help} should advertise, restated independently of
     * the production order so that regrouping the table by gate fails here.
     */
    private static List<String> expectedRows(
            boolean vet, boolean unlocked, boolean featuresEnabled, boolean staff) {
        List<String> rows = new ArrayList<>();
        rows.add("/wv help");
        rows.add("/wv anni");
        rows.add("/wv config [<key> [<value>]]");
        if (vet) {
            rows.add("/wv motd");
        }
        if (unlocked) {
            rows.add("/wv list");
            rows.add("/wv staff");
        }
        if (featuresEnabled) {
            rows.add("/wv return");
            rows.add("/wv line <church|scrap|bat|hegea|lighthouse>");
        }
        if (staff) {
            rows.add("/wv check <player>");
        }
        rows.add("/wv debug");
        return rows;
    }

    private static String describe(
            boolean vet, boolean unlocked, boolean featuresEnabled, boolean staff) {
        return "vet="
                + vet
                + " unlocked="
                + unlocked
                + " featuresEnabled="
                + featuresEnabled
                + " staff="
                + staff;
    }

    // ----- help(): the index page and its four gates -----

    /** Every gate true — the widest the page ever gets. */
    @Test
    void helpPage_withEveryGateTrue_pinsItsTwentyTwoSiblings() {
        assertPage(
                HelpCommands.buildHelp(true, true, true, true),
                boldSib(ChatFormatting.GOLD, "Commands\n"),
                sib(
                        ChatFormatting.GRAY,
                        "Use /wv help <command> for details on a specific command.\n\n"),
                sib(ChatFormatting.YELLOW, "/wv help"),
                sib(ChatFormatting.GRAY, " — Show this help message\n"),
                sib(ChatFormatting.YELLOW, "/wv anni"),
                sib(ChatFormatting.GRAY, " — Show annihilation timer\n"),
                sib(ChatFormatting.YELLOW, "/wv config [<key> [<value>]]"),
                sib(ChatFormatting.GRAY, " — View or change mod settings\n"),
                sib(ChatFormatting.YELLOW, "/wv motd"),
                sib(ChatFormatting.GRAY, " — Show the message of the day\n"),
                sib(ChatFormatting.YELLOW, "/wv list"),
                sib(ChatFormatting.GRAY, " — Show online members and VetsMod status\n"),
                sib(ChatFormatting.YELLOW, "/wv staff"),
                sib(ChatFormatting.GRAY, " — Show online staff members\n"),
                sib(ChatFormatting.YELLOW, "/wv return"),
                sib(ChatFormatting.GRAY, " — Show info about this week's event\n"),
                sib(ChatFormatting.YELLOW, "/wv line <church|scrap|bat|hegea|lighthouse>"),
                sib(ChatFormatting.GRAY, " — Toggle gxp boundary lines\n"),
                sib(ChatFormatting.YELLOW, "/wv check <player>"),
                sib(ChatFormatting.GRAY, " — Look up a player's eligibility\n"),
                sib(ChatFormatting.YELLOW, "/wv debug"),
                sib(ChatFormatting.GRAY, " — Diagnostics & debug tools"));
    }

    /** Every gate false — only the four unconditional rows survive. */
    @Test
    void helpPage_withNoGate_pinsItsTenSiblings() {
        assertPage(
                HelpCommands.buildHelp(false, false, false, false),
                boldSib(ChatFormatting.GOLD, "Commands\n"),
                sib(
                        ChatFormatting.GRAY,
                        "Use /wv help <command> for details on a specific command.\n\n"),
                sib(ChatFormatting.YELLOW, "/wv help"),
                sib(ChatFormatting.GRAY, " — Show this help message\n"),
                sib(ChatFormatting.YELLOW, "/wv anni"),
                sib(ChatFormatting.GRAY, " — Show annihilation timer\n"),
                sib(ChatFormatting.YELLOW, "/wv config [<key> [<value>]]"),
                sib(ChatFormatting.GRAY, " — View or change mod settings\n"),
                sib(ChatFormatting.YELLOW, "/wv debug"),
                sib(ChatFormatting.GRAY, " — Diagnostics & debug tools"));
    }

    /**
     * All sixteen gate combinations, each against its exact ordered row set. Row
     * order is gate-interleaved — three unconditional rows, then the gated ones,
     * then {@code /wv debug} — so grouping a table by gate would reorder the page
     * and fail here rather than in review.
     */
    @Test
    void gateMatrix_allSixteenCombinationsProduceTheirExactRowSet() {
        for (int mask = 0; mask < 16; mask++) {
            boolean vet = (mask & 1) != 0;
            boolean unlocked = (mask & 2) != 0;
            boolean featuresEnabled = (mask & 4) != 0;
            boolean staff = (mask & 8) != 0;
            assertEquals(
                    expectedRows(vet, unlocked, featuresEnabled, staff),
                    rowsOf(HelpCommands.buildHelp(vet, unlocked, featuresEnabled, staff)),
                    describe(vet, unlocked, featuresEnabled, staff));
        }
    }

    /**
     * Phase 2's {@code be482b1} is the fix this protects: {@code /wv list} was
     * printed twice, and advertised on the vet predicate rather than on
     * {@code isUnlocked()}. Across all sixteen combinations it must appear exactly
     * once when unlocked and never otherwise.
     */
    @Test
    void wvListIsAdvertisedExactlyOnceWhenUnlockedAndNeverOtherwise() {
        for (int mask = 0; mask < 16; mask++) {
            boolean vet = (mask & 1) != 0;
            boolean unlocked = (mask & 2) != 0;
            boolean featuresEnabled = (mask & 4) != 0;
            boolean staff = (mask & 8) != 0;
            long advertised =
                    rowsOf(HelpCommands.buildHelp(vet, unlocked, featuresEnabled, staff)).stream()
                            .filter("/wv list"::equals)
                            .count();
            assertEquals(
                    unlocked ? 1L : 0L,
                    advertised,
                    "/wv list advert count for " + describe(vet, unlocked, featuresEnabled, staff));
        }
    }

    // ----- the eleven remaining pages -----

    /**
     * The second index page. Its last row is three siblings, not two — a YELLOW
     * key and <em>two</em> separate GRAY appends — and neither of them ends in a
     * newline. Joining the two renders identically and is a different tree.
     */
    @Test
    void helpConfigPage_pinsItsNineteenSiblings() {
        assertPage(
                HelpCommands.buildHelpConfig(),
                boldSib(ChatFormatting.GOLD, "Config Options\n"),
                sib(ChatFormatting.GRAY, "Toggle with: /wv config <key> <true|false>\n\n"),
                sib(ChatFormatting.YELLOW, "legacyItemHighlighting\n"),
                sib(
                        ChatFormatting.GRAY,
                        "  Show legacy item highlighting in tooltips and inventory\n"),
                sib(ChatFormatting.YELLOW, "printMOTD\n"),
                sib(ChatFormatting.GRAY, "  Auto-print the message of the day on world join\n"),
                sib(ChatFormatting.YELLOW, "printANNI\n"),
                sib(ChatFormatting.GRAY, "  Auto-print the annihilation timer on world join\n"),
                sib(ChatFormatting.YELLOW, "printBridgeMessages\n"),
                sib(ChatFormatting.GRAY, "  Show bridge (guild chat relay) messages in chat\n"),
                sib(ChatFormatting.YELLOW, "showSupporterGlints\n"),
                sib(ChatFormatting.GRAY, "  Show supporter animated gradient glints on nametags\n"),
                sib(ChatFormatting.YELLOW, "colorBlindMode\n"),
                sib(
                        ChatFormatting.GRAY,
                        "  Use a higher-contrast colour pair for supporter glints "
                                + "so the shimmer is visible to red/green colour-blind users\n"),
                sib(ChatFormatting.YELLOW, "handleSpoilers\n"),
                sib(
                        ChatFormatting.GRAY,
                        "  Render ||spoiler|| markers as hoverable spoiler labels\n"),
                sib(ChatFormatting.YELLOW, "moreReliableGuildCheck\n"),
                sib(
                        ChatFormatting.GRAY,
                        "  Run /gu stats on world join to detect guild membership,"),
                sib(
                        ChatFormatting.GRAY,
                        " instead of relying solely on Wynntils (which can stay null)"));
    }

    @Test
    void helpCheckPage_pinsItsFourSiblings() {
        assertPage(
                HelpCommands.buildHelpCheck(),
                sib(ChatFormatting.YELLOW, "/wv check <player>\n"),
                sib(
                        ChatFormatting.GRAY,
                        "Look up a player's guild membership and unlock status.\n"),
                sib(ChatFormatting.GRAY, "Requires: "),
                sib(ChatFormatting.RED, "Staff"));
    }

    @Test
    void helpReturnPage_pinsItsFourSiblings() {
        assertPage(
                HelpCommands.buildHelpReturn(),
                sib(ChatFormatting.YELLOW, "/wv return\n"),
                sib(
                        ChatFormatting.GRAY,
                        "Display information about this week's scheduled event, "
                                + "as fetched from the guild-announcements channel.\n"),
                sib(ChatFormatting.GRAY, "Requires: "),
                sib(ChatFormatting.GREEN, "Returners guild member"));
    }

    @Test
    void helpStaffPage_pinsItsFourSiblings() {
        assertPage(
                HelpCommands.buildHelpStaff(),
                sib(ChatFormatting.YELLOW, "/wv staff\n"),
                sib(ChatFormatting.GRAY, "Show a list of currently online staff members.\n"),
                sib(ChatFormatting.GRAY, "Requires: "),
                sib(ChatFormatting.GREEN, "Unlocked"));
    }

    /**
     * The {@code /wv list} <em>detail</em> page, whose heading is {@code "/wv list\n"}.
     * It is a different page from the {@code "/wv list"} row on the index, and the
     * regression pin above does not count it.
     */
    @Test
    void helpListPage_pinsItsFourSiblings() {
        assertPage(
                HelpCommands.buildHelpList(),
                sib(ChatFormatting.YELLOW, "/wv list\n"),
                sib(
                        ChatFormatting.GRAY,
                        "Show online Returners members, grouped by VetsMod usage, "
                                + "honourary, and waitlist status.\n"),
                sib(ChatFormatting.GRAY, "Requires: "),
                sib(ChatFormatting.GREEN, "Veteran (Returners, waitlist, or honourary)"));
    }

    @Test
    void helpMotdPage_pinsItsFourSiblings() {
        assertPage(
                HelpCommands.buildHelpMotd(),
                sib(ChatFormatting.YELLOW, "/wv motd\n"),
                sib(
                        ChatFormatting.GRAY,
                        "Show the guild message of the day. Also available as "
                                + "a standalone /motd command.\n"),
                sib(ChatFormatting.GRAY, "Requires: "),
                sib(ChatFormatting.GREEN, "Veteran (Returners, waitlist, or honourary)"));
    }

    @Test
    void helpAnniPage_pinsItsFourSiblings() {
        assertPage(
                HelpCommands.buildHelpAnni(),
                sib(ChatFormatting.YELLOW, "/wv anni\n"),
                sib(
                        ChatFormatting.GRAY,
                        "Show how long until the next annihilation event, "
                                + "if one has been announced.\n"),
                sib(ChatFormatting.GRAY, "Requires: "),
                sib(ChatFormatting.WHITE, "None (public)"));
    }

    @Test
    void helpLinePage_pinsItsFourSiblings() {
        assertPage(
                HelpCommands.buildHelpLine(),
                sib(ChatFormatting.YELLOW, "/wv line <church|scrap|bat|hegea|lighthouse>\n"),
                sib(
                        ChatFormatting.GRAY,
                        "Toggle the rendering of territory boundary lines for the "
                                + "specified territory. Use \"church\" (Witness Church - Forest of Eyes), \"scrap\" (Scrapyard - Corkus Sea Cove), "
                                + "\"bat\" (Batcave - Royal Barracks), \"hegea\" (Training Grounds - Fort Hegea), or \"lighthouse\" (Lighthouse - Contested District) "
                                + "to pick which boundaries to show.\n"),
                sib(ChatFormatting.GRAY, "Requires: "),
                sib(ChatFormatting.GREEN, "Returners guild member"));
    }

    /**
     * Carries a lone DARK_GRAY line that belongs to no row.
     */
    @Test
    void helpDebugPage_pinsItsFourteenSiblings() {
        assertPage(
                HelpCommands.buildHelpDebug(),
                sib(ChatFormatting.YELLOW, "/wv debug\n"),
                sib(ChatFormatting.GRAY, "Run diagnostics and dump mod state to chat and log.\n\n"),
                sib(ChatFormatting.GOLD, "Subcommands:\n"),
                sib(ChatFormatting.YELLOW, "/wv debug <boolean>"),
                sib(ChatFormatting.GRAY, " — Toggle debug logging\n"),
                sib(ChatFormatting.YELLOW, "/wv debug set <key> <value>"),
                sib(ChatFormatting.GRAY, " — Manage debug config keys\n"),
                sib(ChatFormatting.YELLOW, "/wv debug trigger <action>"),
                sib(ChatFormatting.GRAY, " — Run a one-shot debug action\n"),
                sib(ChatFormatting.YELLOW, "/wv debug tree <subsystem>"),
                sib(ChatFormatting.GRAY, " — Open a subsystem-specific debug tree (e.g. anni)\n\n"),
                sib(ChatFormatting.DARK_GRAY, "Use /wv help debug <set|trigger> for details.\n"),
                sib(ChatFormatting.GRAY, "Requires: "),
                sib(ChatFormatting.WHITE, "None (public)"));
    }

    /**
     * The {@code itemDump} row is three siblings: a YELLOW head, a GRAY line, then a
     * DARK_GRAY continuation. Flattening the continuation to GRAY changes what
     * the player sees.
     */
    @Test
    void helpDebugSetPage_pinsItsFifteenSiblings() {
        assertPage(
                HelpCommands.buildHelpDebugSet(),
                sib(ChatFormatting.YELLOW, "/wv debug set [<key> [<value>]]\n"),
                sib(ChatFormatting.GRAY, "Manage debug-only configuration keys.\n\n"),
                sib(ChatFormatting.GOLD, "Usage:\n"),
                sib(ChatFormatting.YELLOW, "/wv debug set"),
                sib(ChatFormatting.GRAY, " — List all debug config keys and their values\n"),
                sib(ChatFormatting.YELLOW, "/wv debug set <key>"),
                sib(ChatFormatting.GRAY, " — Show the current value of a key\n"),
                sib(ChatFormatting.YELLOW, "/wv debug set <key> <true|false>"),
                sib(ChatFormatting.GRAY, " — Set a debug key to true or false\n\n"),
                sib(ChatFormatting.GOLD, "Available keys:\n"),
                sib(ChatFormatting.YELLOW, "itemDump"),
                sib(ChatFormatting.GRAY, " — When true, pressing numpad + while hovering\n"),
                sib(ChatFormatting.DARK_GRAY, "  an item dumps its full component tree\n"),
                sib(ChatFormatting.GRAY, "Requires: "),
                sib(ChatFormatting.WHITE, "None (public)"));
    }

    /**
     * All three rows carry DARK_GRAY continuations — one, two and one respectively.
     */
    @Test
    void helpDebugTriggerPage_pinsItsFifteenSiblings() {
        assertPage(
                HelpCommands.buildHelpDebugTrigger(),
                sib(ChatFormatting.YELLOW, "/wv debug trigger <action>\n"),
                sib(ChatFormatting.GRAY, "Run a one-shot debug action.\n\n"),
                sib(ChatFormatting.GOLD, "Actions:\n"),
                sib(ChatFormatting.YELLOW, "/wv debug trigger charDump\n"),
                sib(ChatFormatting.GRAY, "  Render PUA characters U+E001–U+E040 in the resource\n"),
                sib(
                        ChatFormatting.DARK_GRAY,
                        "  pack's chat/prefix font as badge-style sequences.\n"),
                sib(ChatFormatting.YELLOW, "/wv debug trigger forceChecks\n"),
                sib(ChatFormatting.GRAY, "  Force re-check of guild membership, rank, and staff\n"),
                sib(
                        ChatFormatting.DARK_GRAY,
                        "  status. Runs /gu stats and /gu rank via Wynntils'\n"),
                sib(ChatFormatting.DARK_GRAY, "  command queue and reports state to chat.\n"),
                sib(ChatFormatting.YELLOW, "/wv debug trigger tabDump\n"),
                sib(ChatFormatting.GRAY, "  Dump the raw tab list to chat, split into labelled\n"),
                sib(ChatFormatting.DARK_GRAY, "  columns, then show parsed guild members.\n"),
                sib(ChatFormatting.GRAY, "Requires: "),
                sib(ChatFormatting.WHITE, "None (public)"));
    }
}
