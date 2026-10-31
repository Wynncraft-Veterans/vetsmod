package org.wynnvets.retro.rules;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the rule classes do with a row's pattern. */
final class RowMatches {

    private RowMatches() {}

    /**
     * {@code replacement} with the groups of {@code matched}, which just matched in full. The match
     * is run again first, because an earlier substitution moved the matcher's append position past
     * it.
     */
    static String substitute(Matcher matched, String replacement) {
        matched.reset();
        matched.matches();
        StringBuilder out = new StringBuilder();
        matched.appendReplacement(out, replacement);
        matched.appendTail(out);
        return out.toString();
    }

    /** Whether any pattern matches the whole of {@code id}. */
    static boolean matchesAny(List<Pattern> patterns, String id) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(id).matches()) return true;
        }
        return false;
    }
}
