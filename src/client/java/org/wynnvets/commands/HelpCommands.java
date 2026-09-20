package org.wynnvets.commands;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.guild.GuildStateManager;

/**
 * Handlers for the {@code /wv help} subcommand tree.
 *
 * <p>Each page is a pair: a {@code buildX} method that returns the page as a
 * {@link MutableComponent}, and the {@code helpX} handler that sends it.  The
 * pair exists so the page bodies can be consolidated without changing what is
 * emitted — a returned component can be compared against its predecessor, one
 * handed straight to a send cannot.  All methods are package-private so they
 * can be referenced from {@link CommandRegistry} without being part of the
 * public API.</p>
 */
final class HelpCommands {

    private HelpCommands() {}

    // ----- page primitives -----
    //
    // A page is a title or opening row, then rows, then — on ten of the twelve
    // — the Requires: footer. A row is a head — YELLOW, plus a GOLD run for its
    // arguments if it takes any — followed by an ordered list of description
    // fragments: not a (command, description) pair, because the
    // fragments differ in both count and colour. Every trailing newline lives
    // in the literal it belongs to, including the doubled ones that close a
    // section and the head newline that decides whether a row renders inline
    // or with its description on the next line.

    /** One styled run of a row's description. */
    private record Frag(ChatFormatting style, String text) {}

    private static Frag gray(String text) {
        return new Frag(ChatFormatting.GRAY, text);
    }

    /**
     * A clarification, dimmer than the description it follows.
     *
     * <p>The colour change marks a change of thought, not a line wrap. The GRAY
     * fragments say what the command does; the DARK_GRAY ones qualify it — how
     * it is invoked, what it runs underneath, what the output looks like. A
     * sentence never crosses the boundary, so a description too long for one
     * chat line wraps into a second {@link #gray} fragment rather than falling
     * into this one halfway through.</p>
     */
    private static Frag dim(String text) {
        return new Frag(ChatFormatting.DARK_GRAY, text);
    }

    /** The GOLD, BOLD banner an index page opens with. */
    private static void title(MutableComponent msg, String text) {
        msg.append(Component.literal(text).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
    }

    /** A GOLD section heading inside a page. */
    private static void heading(MutableComponent msg, String text) {
        msg.append(Component.literal(text).withStyle(ChatFormatting.GOLD));
    }

    /** A styled run that belongs to no row. */
    private static void line(MutableComponent msg, ChatFormatting style, String text) {
        msg.append(Component.literal(text).withStyle(style));
    }

    /**
     * A YELLOW head followed by its description fragments, in order.
     *
     * <p>A head that takes arguments splits into two runs: the command itself in
     * YELLOW, and everything from its first argument token onward in GOLD, so a
     * reader can see at a glance where the literal ends and what they have to
     * supply. Literal subcommands are not arguments and stay YELLOW —
     * {@code /wv debug trigger charDump} is one run, {@code /wv debug set <key>}
     * is two.</p>
     */
    private static void row(MutableComponent msg, String head, Frag... description) {
        int arguments = argumentStart(head);
        if (arguments < 0) {
            msg.append(Component.literal(head).withStyle(ChatFormatting.YELLOW));
        } else {
            msg.append(
                    Component.literal(head.substring(0, arguments))
                            .withStyle(ChatFormatting.YELLOW));
            msg.append(Component.literal(head.substring(arguments)).withStyle(ChatFormatting.GOLD));
        }
        for (Frag fragment : description) {
            msg.append(Component.literal(fragment.text()).withStyle(fragment.style()));
        }
    }

    /**
     * Where a head's arguments begin, or {@code -1} if it takes none.
     *
     * <p>An argument token opens with {@code <} (required) or {@code [}
     * (optional), and the space before it belongs to the command half so the
     * GOLD run starts on the bracket. The two forms are searched independently
     * because either can come first: {@code /wv config [<key> [<value>]]} opens
     * on the square bracket, {@code /wv debug set <key>} on the angle.</p>
     */
    private static int argumentStart(String head) {
        int angle = head.indexOf(" <");
        int square = head.indexOf(" [");
        if (angle < 0 && square < 0) return -1;
        if (angle < 0) return square + 1;
        if (square < 0) return angle + 1;
        return Math.min(angle, square) + 1;
    }

    /**
     * How far the viewer's claim on a page's requirement has been established.
     *
     * <p>The {@code Requires:} footer answers <em>can I run this?</em>, so its
     * colour tracks the viewer rather than the command. The distinction between
     * {@link #GRANTED} and {@link #CONFIRMED} is the one that matters in practice:
     * much of the mod runs on a local signal — a cached {@code /gu rank}, a stored
     * unlock key — while the commands that dispatch real actions gate on what the
     * server said at WS auth time. A green footer means <em>this should work</em>;
     * an aqua one means <em>the server agrees</em>.</p>
     */
    enum Access {
        /** The viewer does not meet the requirement. */
        DENIED(ChatFormatting.RED),
        /** Met on the local signal alone — no server-confirmed session behind it. */
        GRANTED(ChatFormatting.GREEN),
        /** Met, and confirmed by the server on this session's auth ack. */
        CONFIRMED(ChatFormatting.AQUA),
        /** There is no requirement to meet. */
        PUBLIC(ChatFormatting.WHITE);

        private final ChatFormatting colour;

        Access(ChatFormatting colour) {
            this.colour = colour;
        }
    }

    /**
     * Resolves a page's two signals into one {@link Access}.
     *
     * <p>{@code confirmed} wins outright rather than being intersected with
     * {@code met}: the local signal can be stale or unset where the server's is
     * not — a rank cache that has not refreshed this session is the everyday case
     * — and a page has no business reporting DENIED on a permission the server has
     * already granted.</p>
     */
    static Access access(boolean met, boolean confirmed) {
        if (confirmed) return Access.CONFIRMED;
        return met ? Access.GRANTED : Access.DENIED;
    }

    /**
     * The {@link Access} for a gate whose only server-side confirmation is that
     * the session is authenticated at all.
     *
     * <p>Five of the six gated pages are this shape. Their requirement — vet,
     * unlocked, Returners — is decided locally from a cache the mod fills on world
     * join, and the only thing the server independently vouches for is that this
     * session presented a key and was acked. {@code /wv check} is the exception
     * and resolves its own pair.</p>
     */
    private static Access sessionBacked(boolean met) {
        return access(met, met && GuildStateManager.isAuthenticatedThisSession());
    }

    /** {@code areFeaturesEnabled()} — the Returners gate, named for the two pages sharing it. */
    private static boolean returners() {
        return GuildStateManager.areFeaturesEnabled();
    }

    /** The {@code Requires:} footer that closes ten of the twelve pages. */
    private static void requires(MutableComponent msg, Access access, String requirementLabel) {
        msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
        msg.append(Component.literal(requirementLabel).withStyle(access.colour));
    }

    static int help(CommandContext<FabricClientCommandSource> ctx) {
        ChatUtils.sendLocalMessageNewBlock(
                buildHelp(
                        GuildStateManager.isEligibleForEnrichment(),
                        GuildStateManager.isUnlocked(),
                        GuildStateManager.areFeaturesEnabled(),
                        GuildStateManager.isStaff()));
        return 1;
    }

    /**
     * Builds the bare {@code /wv help} index page.
     *
     * <p>The four gates arrive as parameters rather than being read from
     * {@link GuildStateManager} here: the rows they guard are interleaved with
     * the unconditional ones, so the page is only enumerable when the gates are
     * inputs.  Reading them once, together, at the call site also stops the
     * page straddling a {@code GuildChecker} cache expiry mid-build.</p>
     */
    static MutableComponent buildHelp(
            boolean vet, boolean unlocked, boolean featuresEnabled, boolean staff) {
        MutableComponent msg = Component.empty();
        title(msg, "Commands\n");
        line(
                msg,
                ChatFormatting.GRAY,
                "Use /wv help <command> for details on a specific command.\n\n");
        row(msg, "/wv help", gray(" — Show this help message\n"));
        row(msg, "/wv anni", gray(" — Show annihilation timer\n"));
        row(msg, "/wv config [<key> [<value>]]", gray(" — View or change mod settings\n"));
        if (vet) {
            row(msg, "/wv motd", gray(" — Show the message of the day\n"));
        }
        if (unlocked) {
            row(msg, "/wv list", gray(" — Show online members and VetsMod status\n"));
            row(msg, "/wv staff", gray(" — Show online staff members\n"));
        }
        if (featuresEnabled) {
            row(msg, "/wv return", gray(" — Show info about this week's event\n"));
            row(
                    msg,
                    "/wv line <church|scrap|bat|hegea|lighthouse>",
                    gray(" — Toggle gxp boundary lines\n"));
        }
        if (staff) {
            row(msg, "/wv check <player>", gray(" — Look up a player's eligibility\n"));
        }
        row(msg, "/wv debug", gray(" — Diagnostics & debug tools"));
        return msg;
    }

    static int helpConfig(CommandContext<FabricClientCommandSource> ctx) {
        ChatUtils.sendLocalMessageNewBlock(buildHelpConfig());
        return 1;
    }

    static MutableComponent buildHelpConfig() {
        MutableComponent msg = Component.empty();
        title(msg, "Config Options\n");
        line(msg, ChatFormatting.GRAY, "Toggle with: /wv config <key> <true|false>\n\n");
        row(
                msg,
                "legacyItemHighlighting\n",
                gray("  Show legacy item highlighting in tooltips and inventory\n"));
        row(msg, "printMOTD\n", gray("  Auto-print the message of the day on world join\n"));
        row(msg, "printANNI\n", gray("  Auto-print the annihilation timer on world join\n"));
        row(
                msg,
                "printBridgeMessages\n",
                gray("  Show bridge (guild chat relay) messages in chat\n"));
        row(
                msg,
                "showSupporterGlints\n",
                gray("  Show supporter animated gradient glints on nametags\n"));
        row(
                msg,
                "colorBlindMode\n",
                gray(
                        "  Use a higher-contrast colour pair for supporter glints "
                                + "so the shimmer is visible to red/green colour-blind users\n"));
        row(
                msg,
                "handleSpoilers\n",
                gray("  Render ||spoiler|| markers as hoverable spoiler labels\n"));
        row(
                msg,
                "moreReliableGuildCheck\n",
                gray("  Run /gu stats on world join to detect guild membership,"),
                gray(" instead of relying solely on Wynntils (which can stay null)"));
        return msg;
    }

    /**
     * The shape shared by the seven {@code /wv help <command>} detail pages: a
     * usage line, a line of prose, and the {@code Requires:} footer.
     *
     * <p>The trailing newlines stay in {@code usage} and {@code prose} rather
     * than being supplied here. They are what separates one line from the next,
     * they are not uniform across the twelve pages, and a template that owned
     * them would quietly be a different template for any page that needed
     * otherwise.</p>
     */
    private static MutableComponent detailPage(
            String usage, String prose, Access access, String requirementLabel) {
        MutableComponent msg = Component.empty();
        row(msg, usage, gray(prose));
        requires(msg, access, requirementLabel);
        return msg;
    }

    static int helpCheck(CommandContext<FabricClientCommandSource> ctx) {
        ChatUtils.sendLocalMessageNewBlock(
                buildHelpCheck(
                        access(GuildStateManager.isStaff(), GuildStateManager.isConfirmedStaff())));
        return 1;
    }

    /**
     * The one page whose CONFIRMED signal is a permission of its own rather than
     * the session's: {@code isStaff()} is the cached {@code /gu rank} answer and
     * {@code isConfirmedStaff()} is the roster answer the server returned on the
     * auth ack. Both entry points gate on the latter, so a GRANTED footer here
     * means the rank looks right and the command will still refuse.
     *
     * <p>That gap is filed as {@code help-advertises-wv-check-on-a-weaker-gate}
     * and is not fixed here — the index row still advertises on {@code isStaff()}.
     * This footer only reports both signals where it used to report neither.</p>
     */
    static MutableComponent buildHelpCheck(Access access) {
        return detailPage(
                "/wv check <player>\n",
                "Look up a player's guild membership and unlock status.\n",
                access,
                "Staff");
    }

    static int helpReturn(CommandContext<FabricClientCommandSource> ctx) {
        ChatUtils.sendLocalMessageNewBlock(buildHelpReturn(sessionBacked(returners())));
        return 1;
    }

    static MutableComponent buildHelpReturn(Access access) {
        return detailPage(
                "/wv return\n",
                "Display information about this week's scheduled event, "
                        + "as fetched from the guild-announcements channel.\n",
                access,
                "Returners guild member");
    }

    static int helpStaff(CommandContext<FabricClientCommandSource> ctx) {
        ChatUtils.sendLocalMessageNewBlock(
                buildHelpStaff(sessionBacked(GuildStateManager.isUnlocked())));
        return 1;
    }

    static MutableComponent buildHelpStaff(Access access) {
        return detailPage(
                "/wv staff\n",
                "Show a list of currently online staff members.\n",
                access,
                "Unlocked");
    }

    static int helpList(CommandContext<FabricClientCommandSource> ctx) {
        ChatUtils.sendLocalMessageNewBlock(
                buildHelpList(sessionBacked(GuildStateManager.isUnlocked())));
        return 1;
    }

    static MutableComponent buildHelpList(Access access) {
        return detailPage(
                "/wv list\n",
                "Show online Returners members, grouped by VetsMod usage, "
                        + "honourary, and waitlist status.\n",
                access,
                "Veteran (Returners, waitlist, or honourary)");
    }

    static int helpMotd(CommandContext<FabricClientCommandSource> ctx) {
        ChatUtils.sendLocalMessageNewBlock(
                buildHelpMotd(sessionBacked(GuildStateManager.isEligibleForEnrichment())));
        return 1;
    }

    static MutableComponent buildHelpMotd(Access access) {
        return detailPage(
                "/wv motd\n",
                "Show the guild message of the day. Also available as "
                        + "a standalone /motd command.\n",
                access,
                "Veteran (Returners, waitlist, or honourary)");
    }

    static int helpAnni(CommandContext<FabricClientCommandSource> ctx) {
        ChatUtils.sendLocalMessageNewBlock(buildHelpAnni());
        return 1;
    }

    static MutableComponent buildHelpAnni() {
        return detailPage(
                "/wv anni\n",
                "Show how long until the next annihilation event, "
                        + "if one has been announced.\n",
                Access.PUBLIC,
                "None (public)");
    }

    static int helpLine(CommandContext<FabricClientCommandSource> ctx) {
        ChatUtils.sendLocalMessageNewBlock(buildHelpLine(sessionBacked(returners())));
        return 1;
    }

    static MutableComponent buildHelpLine(Access access) {
        return detailPage(
                "/wv line <church|scrap|bat|hegea|lighthouse>\n",
                "Toggle the rendering of territory boundary lines for the "
                        + "specified territory. Use \"church\" (Witness Church - Forest of Eyes), \"scrap\" (Scrapyard - Corkus Sea Cove), "
                        + "\"bat\" (Batcave - Royal Barracks), \"hegea\" (Training Grounds - Fort Hegea), or \"lighthouse\" (Lighthouse - Contested District) "
                        + "to pick which boundaries to show.\n",
                access,
                "Returners guild member");
    }

    static int helpDebug(CommandContext<FabricClientCommandSource> ctx) {
        ChatUtils.sendLocalMessageNewBlock(buildHelpDebug());
        return 1;
    }

    static MutableComponent buildHelpDebug() {
        MutableComponent msg = Component.empty();
        row(msg, "/wv debug\n", gray("Run diagnostics and dump mod state to chat and log.\n\n"));
        heading(msg, "Subcommands:\n");
        row(msg, "/wv debug <boolean>", gray(" — Toggle debug logging\n"));
        row(msg, "/wv debug set <key> <value>", gray(" — Manage debug config keys\n"));
        row(msg, "/wv debug trigger <action>", gray(" — Run a one-shot debug action\n"));
        row(
                msg,
                "/wv debug tree <subsystem>",
                gray(" — Open a subsystem-specific debug tree (e.g. anni)\n\n"));
        line(msg, ChatFormatting.DARK_GRAY, "Use /wv help debug <set|trigger> for details.\n");
        requires(msg, Access.PUBLIC, "None (public)");
        return msg;
    }

    static int helpDebugSet(CommandContext<FabricClientCommandSource> ctx) {
        ChatUtils.sendLocalMessageNewBlock(buildHelpDebugSet());
        return 1;
    }

    static MutableComponent buildHelpDebugSet() {
        MutableComponent msg = Component.empty();
        row(
                msg,
                "/wv debug set [<key> [<value>]]\n",
                gray("Manage debug-only configuration keys.\n\n"));
        heading(msg, "Usage:\n");
        row(msg, "/wv debug set", gray(" — List all debug config keys and their values\n"));
        row(msg, "/wv debug set <key>", gray(" — Show the current value of a key\n"));
        row(
                msg,
                "/wv debug set <key> <true|false>",
                gray(" — Set a debug key to true or false\n\n"));
        heading(msg, "Available keys:\n");
        row(
                msg,
                "itemDump",
                gray(" — Dump an item's full component tree\n"),
                dim("  When on, press numpad + while hovering it\n"));
        requires(msg, Access.PUBLIC, "None (public)");
        return msg;
    }

    static int helpDebugTrigger(CommandContext<FabricClientCommandSource> ctx) {
        ChatUtils.sendLocalMessageNewBlock(buildHelpDebugTrigger());
        return 1;
    }

    static MutableComponent buildHelpDebugTrigger() {
        MutableComponent msg = Component.empty();
        row(msg, "/wv debug trigger <action>\n", gray("Run a one-shot debug action.\n\n"));
        heading(msg, "Actions:\n");
        row(
                msg,
                "/wv debug trigger charDump\n",
                gray("  Render PUA characters U+E001–U+E040 in chat.\n"),
                dim("  Drawn as badge-style sequences in the resource\n"),
                dim("  pack's chat/prefix font.\n"));
        row(
                msg,
                "/wv debug trigger forceChecks\n",
                gray("  Force a re-check of guild membership, rank,\n"),
                gray("  and staff status.\n"),
                dim("  Runs /gu stats and /gu rank via Wynntils'\n"),
                dim("  command queue, then reports state to chat.\n"));
        row(
                msg,
                "/wv debug trigger tabDump\n",
                gray("  Dump the raw tab list to chat.\n"),
                dim("  Split into labelled columns, then shows the\n"),
                dim("  parsed guild members.\n"));
        requires(msg, Access.PUBLIC, "None (public)");
        return msg;
    }
}
