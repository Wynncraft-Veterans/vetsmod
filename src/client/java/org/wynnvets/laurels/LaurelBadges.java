package org.wynnvets.laurels;

import com.wynntils.services.leaderboard.type.LeaderboardBadge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.wynnvets.fetcher.polling.LaurelPoller;
import org.wynnvets.logging.VetsLogger;

/**
 * Turns the vets laurels into Wynntils {@link LeaderboardBadge}s and tells the draw hook how to
 * read them back. The only class that touches Wynntils' badge types, and the one place the
 * encoding below is written; {@link org.wynnvets.mixin.client.LaurelBarMixin LaurelBarMixin} is
 * its only caller.
 *
 * <p><b>The encoding.</b> A {@code LeaderboardBadge} is a bare {@code (uOffset, vOffset)} into
 * Wynntils' own sheet and carries no texture. Ours are marked by a negative {@code uOffset},
 * {@code -(1 + column)}, which no Wynntils badge can have, and the row goes in {@code vOffset}
 * as Wynntils does. {@link #isLaurel(float)} and {@link #sheetU(float)} decode it at draw time.
 * See {@code vetsmod_laurels.md}.</p>
 *
 * <p><b>Render thread only.</b> The lookup is rebuilt lazily when
 * {@link LaurelPoller#standings()} returns a new reference, so no field here is shared with the
 * poller thread, and none is volatile.</p>
 *
 * <p><b>The half-applied guard.</b> The mixin has two hooks, and if only the list hook applies,
 * our badges reach Wynntils' own draw call with negative offsets and render as garbage. Every
 * list that carries one of ours sets {@code awaitingDraw}; the draw hook clears it through
 * {@link #onBadgeDrawn()}. Finding it still set on the next list means the draw hook never ran,
 * so the laurels switch themselves off for the session and the list hook hands Wynntils' list
 * back untouched. A {@link LinkageError} while building the lookup, which is a Wynntils change
 * to {@code LeaderboardBadge}, switches them off the same way.</p>
 */
public final class LaurelBadges {
    /** Our sheet, 4 columns by 3 rows of {@code LeaderboardBadge.WIDTH} by {@code HEIGHT}. */
    public static final Identifier TEXTURE =
            Identifier.parse("vetsmod:textures/nametag/laurels.png");

    /** The sheet's width in pixels, which the draw hook passes in place of Wynntils' own. */
    public static final int TEXTURE_WIDTH = 76;

    /** The sheet's height in pixels. */
    public static final int TEXTURE_HEIGHT = 54;

    private static LaurelStandings builtFrom;
    private static Map<UUID, List<LeaderboardBadge>> badges = Map.of();
    private static boolean awaitingDraw;
    private static boolean disabled;

    private LaurelBadges() {}

    /**
     * Adds a player's laurels after the badges Wynntils lays out for them. Ours rotate with
     * theirs once the player holds more than Wynntils' {@code badgeCount}.
     *
     * @param wynntils the list Wynntils got for the player, which is never modified
     * @param uuid the player, which may be {@code null}
     * @return {@code wynntils} itself when there is nothing to add, our own list when it was
     *     empty, and a new list of theirs then ours otherwise
     */
    public static List<LeaderboardBadge> withLaurels(List<LeaderboardBadge> wynntils, UUID uuid) {
        if (disabled || uuid == null) {
            return wynntils;
        }
        if (awaitingDraw) {
            disable("the draw hook never ran after the badge list hook");
            return wynntils;
        }
        LaurelStandings standings = LaurelPoller.standings();
        if (standings != builtFrom && !rebuild(standings)) {
            return wynntils;
        }
        List<LeaderboardBadge> ours = badges.get(uuid);
        if (ours == null) {
            return wynntils;
        }
        awaitingDraw = true;
        if (wynntils.isEmpty()) {
            return ours;
        }
        List<LeaderboardBadge> merged = new ArrayList<>(wynntils);
        merged.addAll(ours);
        return merged;
    }

    /** Called by the draw hook before every badge Wynntils draws, ours or theirs. */
    public static void onBadgeDrawn() {
        awaitingDraw = false;
    }

    /**
     * Tells whether a badge's {@code uOffset} marks it as one of ours.
     *
     * @param uOffset the offset Wynntils passes to its draw call
     * @return {@code true} for a negative offset, which Wynntils never produces
     */
    public static boolean isLaurel(float uOffset) {
        return uOffset < 0;
    }

    /**
     * Decodes our badge's {@code uOffset} into a pixel offset in {@link #TEXTURE}.
     *
     * @param uOffset an offset for which {@link #isLaurel(float)} is {@code true}
     * @return the left edge of the badge's column
     */
    public static float sheetU(float uOffset) {
        return (-uOffset - 1) * LeaderboardBadge.WIDTH;
    }

    private static boolean rebuild(LaurelStandings standings) {
        try {
            Map<UUID, List<LeaderboardBadge>> built = new HashMap<>();
            if (standings != null) {
                standings
                        .byPlayer()
                        .forEach(
                                (uuid, laurels) ->
                                        built.put(
                                                uuid,
                                                laurels.stream()
                                                        .map(LaurelBadges::toBadge)
                                                        .toList()));
            }
            badges = Map.copyOf(built);
            builtFrom = standings;
            return true;
        } catch (LinkageError e) {
            disable("Wynntils' LeaderboardBadge changed: " + e);
            return false;
        }
    }

    private static LeaderboardBadge toBadge(Laurel laurel) {
        return new LeaderboardBadge(
                -(1 + laurel.metric().column()), laurel.row() * LeaderboardBadge.HEIGHT);
    }

    private static void disable(String reason) {
        disabled = true;
        VetsLogger.warn("Vets laurels are switched off for this session: {}", reason);
    }
}
