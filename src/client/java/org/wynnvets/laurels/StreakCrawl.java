package org.wynnvets.laurels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.distribute.MembersGui;
import org.wynnvets.distribute.opener.GuildManageOpener;
import org.wynnvets.distribute.walker.MembersListWalker;
import org.wynnvets.distribute.walker.MembersListWalker.MemberEntry;
import org.wynnvets.util.Json;

/**
 * <b>Temporary.</b> The in-game stand-in for the objective streak, until the Wynncraft API's
 * {@code weekly.streak} is filled in: it reads each member's {@code - Streak: N} lore line from
 * the Members menu with {@link MembersListWalker} and uploads the lot as a {@code streak}
 * {@code laurels_set} frame. It is the one step of {@link LaurelCommands}' {@code update}, and
 * the server stops asking for it ({@code collect}) once the API serves streaks. See
 * {@code vetsmod_laurels.md}, "Retiring the in-game fallback", for what to delete then.
 *
 * <p>The upload names members by their tile name ({@code legacyName}), which the server matches
 * against its member list. A tile carries no UUID that this client reads. The walker keeps only
 * the first of two tiles whose names collide, so one of the two members' streaks can be lost;
 * the server's wholesale replace then leaves that member without a streak laurel until the next
 * crawl.</p>
 */
final class StreakCrawl {

    /** The lore line a Members tile carries, e.g. {@code "- Streak: 76"}, already stripped of
     *  formatting by the walker. */
    private static final Pattern STREAK_LINE = Pattern.compile("^- Streak: (\\d+)$");

    /** How many unmatched names {@link #okLines} lists before it counts the rest. */
    private static final int UNRESOLVED_SHOWN = 10;

    private StreakCrawl() {}

    /**
     * Walks the Members menu, uploads the streaks it read, and then calls {@code next}. A walk
     * that loses its menu or stalls stops the run through the walker and never calls back, so
     * {@code next} does not run. An empty read uploads nothing: the server replaces its stored
     * streaks wholesale, and an empty list would wipe them.
     */
    static void run(Runnable next) {
        ChatUtils.sendLocalMessage(
                LaurelCommands.line(
                        ChatFormatting.GRAY, "Reading objective streaks from the Members menu..."));
        MembersListWalker.armWalk(
                "the streak walk timed out",
                members -> {
                    MembersGui.closeIfOpen();
                    JsonArray entries = streakEntries(members);
                    if (entries.isEmpty()) {
                        ChatUtils.sendLocalMessage(
                                LaurelCommands.line(
                                        ChatFormatting.YELLOW,
                                        "No objective streaks found in the Members menu; nothing"
                                                + " uploaded."));
                    } else {
                        LaurelCommands.upload(
                                LaurelCommands.streakFields(entries), StreakCrawl::okLines);
                    }
                    next.run();
                });
        GuildManageOpener.openManageMembers();
    }

    /**
     * The streak on a tile's lore.
     *
     * @param loreLines the tile's lore, one string per line
     * @return the number on the first {@code - Streak: N} line, or empty when there is none or
     *     it does not fit an {@code int}
     */
    // Package-private for unit tests. See StreakCrawlTest.
    static OptionalInt parseStreak(List<String> loreLines) {
        for (String line : loreLines) {
            Matcher matcher = STREAK_LINE.matcher(line);
            if (matcher.matches()) {
                try {
                    return OptionalInt.of(Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException e) {
                    return OptionalInt.empty();
                }
            }
        }
        return OptionalInt.empty();
    }

    /**
     * The {@code entries} of a {@code streak} frame: one {@code {name, streak}} per tile that
     * shows a streak, in menu order. A tile without one (a fresh recruit's) is left out.
     */
    // Package-private for unit tests. See StreakCrawlTest.
    static JsonArray streakEntries(List<MemberEntry> members) {
        JsonArray entries = new JsonArray();
        for (MemberEntry member : members) {
            OptionalInt streak = parseStreak(member.loreLines());
            if (streak.isEmpty()) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("name", member.legacyName());
            entry.addProperty("streak", streak.getAsInt());
            entries.add(entry);
        }
        return entries;
    }

    /**
     * The lines for an ok ack to a {@code streak} frame: how many members it matched, the names
     * it did not (the first {@value #UNRESOLVED_SHOWN}), and a gray note when the server did not
     * store the upload ({@code replaced: false}) or stored it without showing it
     * ({@code active: false}, the API serving streaks now).
     */
    // Package-private for unit tests. See StreakCrawlTest.
    static List<Component> okLines(JsonObject ack) {
        List<Component> lines = new ArrayList<>();
        lines.add(
                LaurelCommands.line(
                        ChatFormatting.GREEN,
                        "Read objective streaks: "
                                + Json.optInt(ack, "accepted", 0)
                                + " matched to members."));
        JsonElement unresolved = ack.get("unresolved");
        if (unresolved != null
                && unresolved.isJsonArray()
                && !unresolved.getAsJsonArray().isEmpty()) {
            lines.add(
                    LaurelCommands.line(
                            ChatFormatting.YELLOW, unresolvedText(unresolved.getAsJsonArray())));
        }
        if (isFalse(ack, "replaced")) {
            lines.add(
                    LaurelCommands.line(
                            ChatFormatting.GRAY,
                            "Too few members matched, so the server kept its previous streaks."));
        }
        if (isFalse(ack, "active")) {
            lines.add(
                    LaurelCommands.line(
                            ChatFormatting.GRAY,
                            "The server reads streaks from the Wynncraft API now, so this upload"
                                    + " is stored but not shown."));
        }
        return lines;
    }

    private static String unresolvedText(JsonArray unresolved) {
        List<String> shown = new ArrayList<>();
        for (JsonElement name : unresolved) {
            if (shown.size() == UNRESOLVED_SHOWN) break;
            shown.add(name.getAsString());
        }
        String text = "Not matched to a member: " + String.join(", ", shown);
        int more = unresolved.size() - shown.size();
        return more > 0 ? text + " and " + more + " more." : text + ".";
    }

    private static boolean isFalse(JsonObject ack, String key) {
        JsonElement flag = ack.get(key);
        return flag != null
                && flag.isJsonPrimitive()
                && flag.getAsJsonPrimitive().isBoolean()
                && !flag.getAsBoolean();
    }
}
