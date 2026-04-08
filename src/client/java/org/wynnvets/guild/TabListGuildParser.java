package org.wynnvets.guild;

import com.wynntils.core.text.StyledText;
import com.wynntils.utils.mc.McUtils;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.wynnvets.logging.VetsLogger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the Wynncraft tab list to extract online guild members.
 *
 * <p>Wynncraft populates the tab list with 80 fake {@link PlayerInfo}
 * entries arranged in 4 columns of 20 when sorted alphabetically by
 * profile name:
 * <ol start="0">
 *   <li>Friends (indices 0–19)</li>
 *   <li>Guild island members (indices 20–39)</li>
 *   <li>Island Info (indices 40–59)</li>
 *   <li>Guild online (indices 60–79)</li>
 * </ol>
 *
 * <p>Column 3 ("Guild") lists up to 19 online guild members (index 60 is
 * the header). Each entry's display name typically follows the format
 * {@code [SERVER] Username} (e.g. {@code [NA58] Coronaus}).
 *
 * <p>When the guild has more than ~19 online members, the tab list is
 * truncated. Callers should fall back to the Wynncraft API for the
 * complete list in that case.</p>
 */
public final class TabListGuildParser {

    /** Matches {@code [SERVER] Username} where SERVER is like NA58, EU1, MEDIA, DEV, etc. */
    private static final Pattern SERVER_USERNAME_PATTERN =
            Pattern.compile("\\[([A-Za-z]+\\d*)]\\s+(.+)");

    /** Same comparator Wynncraft / Wynntils uses for tab list ordering. */
    private static final Comparator<PlayerInfo> TAB_SORT =
            Comparator.comparing(p -> p.getProfile().name(), String::compareToIgnoreCase);

    /** First index of the Guild column (0-based, after sorting). */
    private static final int GUILD_COL_START = 60;
    /** Last index (exclusive) of the Guild column. */
    private static final int GUILD_COL_END = 80;

    private TabListGuildParser() {}

    /**
     * A single online guild member parsed from the tab list.
     *
     * @param server   the world identifier, e.g. "NA58"
     * @param username the player name
     */
    public record GuildEntry(String server, String username) {}

    /**
     * Reads the current tab list and extracts online guild members.
     *
     * @return a list of parsed guild entries, possibly empty if the
     *         player is not on a Wynncraft world or the tab list is
     *         unavailable
     */
    public static List<GuildEntry> parseOnlineGuildMembers() {
        List<GuildEntry> result = new ArrayList<>();

        try {
            if (McUtils.player() == null || McUtils.player().connection == null) {
                return result;
            }

            PlayerTabOverlay tabOverlay = McUtils.mc().gui.getTabList();
            List<PlayerInfo> sorted = McUtils.player().connection
                    .getListedOnlinePlayers().stream()
                    .sorted(TAB_SORT)
                    .toList();

            if (sorted.size() < GUILD_COL_END) {
                VetsLogger.debug("Tab list has only {} entries, expected >= {}",
                        sorted.size(), GUILD_COL_END);
                return result;
            }

            // Index 60 is the column header ("Guild"); entries 61–79 are members.
            for (int i = GUILD_COL_START + 1; i < GUILD_COL_END; i++) {
                PlayerInfo info = sorted.get(i);
                String displayText = StyledText.fromComponent(
                        tabOverlay.getNameForDisplay(info)).getStringWithoutFormatting();

                if (displayText == null || displayText.isBlank()) {
                    continue;
                }

                Matcher m = SERVER_USERNAME_PATTERN.matcher(displayText.trim());
                if (m.matches()) {
                    result.add(new GuildEntry(m.group(1), m.group(2).trim()));
                }
            }
        } catch (Exception e) {
            VetsLogger.debug("Failed to parse tab list guild column: {}", e.getMessage());
        }

        return result;
    }

    /**
     * Returns all 80 tab list entries as display-name strings, preserving
     * the sorted order. Useful for debugging the tab list layout.
     *
     * @return list of plain-text display names, or empty if unavailable
     */
    public static List<String> dumpAllEntries() {
        List<String> result = new ArrayList<>();

        try {
            if (McUtils.player() == null || McUtils.player().connection == null) {
                return result;
            }

            PlayerTabOverlay tabOverlay = McUtils.mc().gui.getTabList();
            List<PlayerInfo> sorted = McUtils.player().connection
                    .getListedOnlinePlayers().stream()
                    .sorted(TAB_SORT)
                    .toList();

            for (int i = 0; i < sorted.size(); i++) {
                PlayerInfo info = sorted.get(i);
                String displayText = StyledText.fromComponent(
                        tabOverlay.getNameForDisplay(info)).getStringWithoutFormatting();
                result.add("[" + i + "] " + (displayText == null ? "<null>" : displayText));
            }
        } catch (Exception e) {
            VetsLogger.debug("Failed to dump tab list: {}", e.getMessage());
        }

        return result;
    }
}
