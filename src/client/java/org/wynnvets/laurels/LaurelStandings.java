package org.wynnvets.laurels;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One generation of the server's laurel standings, deeply immutable so it can be published
 * through a volatile field and read from the render thread.
 *
 * <p>The constructor copies the map, each player's list and the set, so nothing the caller still
 * holds can change the record afterwards. Two generations are told apart by identity, not by
 * {@code equals}: {@link LaurelBadges} rebuilds its lookup when the reference changes.</p>
 *
 * @param byPlayer each player's laurels, in sheet-column order
 * @param collect the metrics the server still wants a game client to collect in-game; empty when
 *     it has them all
 */
public record LaurelStandings(Map<UUID, List<Laurel>> byPlayer, Set<LaurelMetric> collect) {

    /** Copies all three collections. */
    public LaurelStandings {
        Map<UUID, List<Laurel>> copy = new HashMap<>();
        byPlayer.forEach((uuid, laurels) -> copy.put(uuid, List.copyOf(laurels)));
        byPlayer = Map.copyOf(copy);
        collect = Set.copyOf(collect);
    }
}
