package org.wynnvets.laurels;

/**
 * One of the four vets metrics a laurel is awarded for, with the name the server calls it and the
 * column of {@code laurels.png} that draws it.
 *
 * <p>The column is a field of its own rather than {@link #ordinal()}, so reordering the constants
 * cannot silently re-map the sheet; the sheet is vetsmod's own asset and this enum is the one
 * place that says which wreath is which. The wire names are the server's
 * ({@code GET /v1/outbound/laurels}, and for {@code xp} and {@code graids} the path segment of
 * {@code /v1/outbound/leaderboard/<metric>} that {@code /wv list xp|graids} fetches), spelled out
 * so a rename on either side is a deliberate edit.
 * See {@code vetsmod_laurels.md}.</p>
 */
public enum LaurelMetric {
    /** Cumulative guild XP. */
    XP("xp", 0),
    /** Guild raids completed for the guild. */
    GRAIDS("graids", 1),
    /** The weekly objective streak. */
    STREAK("streak", 2),
    /** The weekly return, set by hand. */
    RETURN("return", 3);

    private final String wireName;
    private final int column;

    LaurelMetric(String wireName, int column) {
        this.wireName = wireName;
        this.column = column;
    }

    /**
     * The metric's name on the wire.
     *
     * @return the name the server uses for it, lowercase
     */
    public String wireName() {
        return wireName;
    }

    /**
     * The column of {@code laurels.png} that draws this metric, counted from {@code 0}.
     *
     * @return the zero-based sheet column
     */
    public int column() {
        return column;
    }

    /**
     * Looks a metric up by its wire name. The match is exact; the server always writes lowercase.
     *
     * @param wireName the name from the server, which may be {@code null}
     * @return the metric, or {@code null} for {@code null} or a name this client does not know
     */
    public static LaurelMetric fromWire(String wireName) {
        for (LaurelMetric metric : values()) {
            if (metric.wireName.equals(wireName)) {
                return metric;
            }
        }
        return null;
    }
}
